package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * User-session desktop companion. It binds only to loopback, authenticates
 * every request with a local token, and never talks to Center or registers a
 * second Agent identity.
 */
final class DesktopCompanionServer {
    private static final Logger LOG = Logger.getLogger(DesktopCompanionServer.class.getName());
    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024;
    private static final String TOKEN_FILE = "desktop-companion.token";
    private static final String COMPANION_DIR = "desktop";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path stateDir;
    private final String token;
    private final int requestedPort;

    DesktopCompanionServer(Path stateDir, String token, int requestedPort) {
        this.stateDir = stateDir.toAbsolutePath().normalize();
        this.token = token;
        this.requestedPort = requestedPort;
    }

    static void run(Path stateDir) throws IOException {
        var token = System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_COMPANION_TOKEN");
        if (token == null || token.isBlank()) token = loadOrCreateToken(stateDir);
        var port = parsePort(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_COMPANION_PORT"));
        new DesktopCompanionServer(stateDir, token.trim(), port).serve();
    }

    private void serve() throws IOException {
        Files.createDirectories(stateDir);
        var companionDir = stateDir.resolve(COMPANION_DIR);
        Files.createDirectories(companionDir);
        try (var server = new ServerSocket(requestedPort, 32, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var endpoint = companionDir.resolve("desktop-companion.json");
            writeEndpoint(endpoint, server.getLocalPort(), token);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(endpoint); } catch (IOException ignored) { }
            }, "rcm-desktop-companion-cleanup"));
            LOG.info(() -> "desktop companion listening on loopback port " + server.getLocalPort());
            while (!Thread.currentThread().isInterrupted()) {
                var client = server.accept();
                workers.execute(() -> handle(client));
            }
        } finally {
            Files.deleteIfExists(companionDir.resolve("desktop-companion.json"));
        }
    }

    private void handle(Socket socket) {
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(300_000);
            var line = reader.readLine();
            if (line == null || line.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                write(writer, new DesktopCompanionClient.Response(false, null, null, null, "request too large"));
                return;
            }
            var request = JsonCodec.read(line.getBytes(StandardCharsets.UTF_8), DesktopCompanionClient.Request.class);
            if (!constantTimeEquals(token, request.token())) {
                write(writer, new DesktopCompanionClient.Response(false, null, null, null, "invalid companion token"));
                return;
            }
            var response = execute(request);
            write(writer, response);
        } catch (Exception exception) {
            LOG.log(Level.FINE, "desktop companion request failed", exception);
            try {
                var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                write(writer, new DesktopCompanionClient.Response(false, null, null, null, compactError(exception.getMessage())));
            } catch (Exception ignored) { }
        }
    }

    private DesktopCompanionClient.Response execute(DesktopCompanionClient.Request request) throws Exception {
        var operation = request.operation() == null ? "" : request.operation().trim().toLowerCase(Locale.ROOT);
        validate(request, operation);
        return switch (operation) {
            case "screenshot" -> screenshot(request);
            case "screens" -> screens();
            case "launch" -> launch(request);
            case "click" -> click(request);
            case "drag" -> drag(request);
            case "key" -> key(request);
            case "type" -> type(request);
            case "clipboard_read" -> clipboardRead();
            case "clipboard_write" -> clipboardWrite(request);
            case "focus" -> focus(request);
            default -> throw new IllegalArgumentException("unsupported desktop operation: " + operation);
        };
    }

    private DesktopCompanionClient.Response screenshot(DesktopCompanionClient.Request request) throws Exception {
        ensureDisplay();
        var bounds = virtualBounds(request.screen());
        var image = new Robot().createScreenCapture(bounds);
        var output = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder is unavailable");
        var data = output.toByteArray();
        if (data.length == 0 || data.length > MAX_SCREENSHOT_BYTES) throw new IOException("screenshot exceeds 8 MiB");
        return new DesktopCompanionClient.Response(true, "screenshot captured (" + data.length + " bytes)",
                "image/png", Base64.getEncoder().encodeToString(data), null);
    }

    private DesktopCompanionClient.Response screens() {
        ensureDisplay();
        var values = new ArrayList<Map<String, Object>>();
        var devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (var index = 0; index < devices.length; index++) {
            var bounds = devices[index].getDefaultConfiguration().getBounds();
            values.add(Map.of("index", index, "id", devices[index].getIDstring(), "x", bounds.x,
                    "y", bounds.y, "width", bounds.width, "height", bounds.height));
        }
        try {
            return new DesktopCompanionClient.Response(true,
                    new String(JsonCodec.write(values), StandardCharsets.UTF_8),
                    "application/json", null, null);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("could not encode screen metadata", exception);
        }
    }

    private DesktopCompanionClient.Response launch(DesktopCompanionClient.Request request) throws IOException {
        var command = new ArrayList<String>();
        command.add(request.executable());
        command.addAll(request.args() == null ? List.of() : request.args());
        var builder = new ProcessBuilder(command);
        if (request.cwd() != null && !request.cwd().isBlank()) {
            var cwd = Path.of(request.cwd()).toAbsolutePath().normalize();
            if (!Files.isDirectory(cwd)) throw new IOException("desktop cwd is not a directory");
            builder.directory(cwd.toFile());
        }
        builder.environment().keySet().removeIf(DesktopCompanionServer::sensitiveEnvironment);
        var process = builder.start();
        return new DesktopCompanionClient.Response(true, "launched process " + process.pid(), null, null, null);
    }

    private DesktopCompanionClient.Response click(DesktopCompanionClient.Request request) throws AWTException {
        ensureDisplay();
        var robot = new Robot();
        robot.mouseMove(request.x(), request.y());
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        return new DesktopCompanionClient.Response(true, "clicked " + request.x() + "," + request.y(), null, null, null);
    }

    private DesktopCompanionClient.Response drag(DesktopCompanionClient.Request request) throws AWTException, InterruptedException {
        ensureDisplay();
        var robot = new Robot();
        var duration = request.durationMs() == null ? 300 : Math.min(10_000, Math.max(0, request.durationMs()));
        robot.mouseMove(request.x(), request.y());
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            var steps = Math.max(1, duration / 15);
            for (var step = 1; step <= steps; step++) {
                var fraction = (double) step / steps;
                var x = (int) Math.round(request.x() + (request.x2() - request.x()) * fraction);
                var y = (int) Math.round(request.y() + (request.y2() - request.y()) * fraction);
                robot.mouseMove(x, y);
                if (duration > 0) Thread.sleep(Math.max(1, duration / steps));
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        return new DesktopCompanionClient.Response(true,
                "dragged " + request.x() + "," + request.y() + " to " + request.x2() + "," + request.y2(),
                null, null, null);
    }

    private DesktopCompanionClient.Response key(DesktopCompanionClient.Request request) throws AWTException {
        ensureDisplay();
        var robot = new Robot();
        var parts = request.key().split("\\+");
        var main = parts[parts.length - 1].trim();
        var modifiers = new ArrayList<Integer>();
        for (var index = 0; index < parts.length - 1; index++) {
            var code = modifierCode(parts[index]);
            if (code == null) throw new IllegalArgumentException("unsupported key modifier: " + parts[index]);
            modifiers.add(code);
        }
        var code = keyCode(main);
        modifiers.forEach(robot::keyPress);
        robot.keyPress(code);
        robot.keyRelease(code);
        for (var index = modifiers.size() - 1; index >= 0; index--) robot.keyRelease(modifiers.get(index));
        return new DesktopCompanionClient.Response(true, "pressed " + request.key(), null, null, null);
    }

    private DesktopCompanionClient.Response type(DesktopCompanionClient.Request request) throws AWTException {
        ensureDisplay();
        var robot = new Robot();
        for (var character : request.text().toCharArray()) {
            typeCharacter(robot, character);
        }
        return new DesktopCompanionClient.Response(true, "typed " + request.text().length() + " characters", null, null, null);
    }

    private DesktopCompanionClient.Response clipboardRead() throws IOException, UnsupportedFlavorException {
        ensureDisplay();
        var value = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        if (value == null) value = "";
        if (value.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IOException("clipboard text exceeds 64 KiB");
        }
        return new DesktopCompanionClient.Response(true, value, "text/plain; charset=utf-8", null, null);
    }

    private DesktopCompanionClient.Response clipboardWrite(DesktopCompanionClient.Request request) {
        ensureDisplay();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(request.text()), null);
        return new DesktopCompanionClient.Response(true, "clipboard updated (" + request.text().length() + " characters)", null, null, null);
    }

    private DesktopCompanionClient.Response focus(DesktopCompanionClient.Request request) throws IOException, InterruptedException {
        if (!isWindows()) throw new IOException("window focus is currently supported only on Windows");
        var script = "$ErrorActionPreference='Stop'; Add-Type @'\nusing System; using System.Runtime.InteropServices; public static class RcmWindow { [DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr h); [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h, int n); }\n'@; $needle=$env:RCM_DESKTOP_WINDOW_TITLE; $p=Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle.Contains($needle) } | Select-Object -First 1; if($null -eq $p){ exit 2 }; [RcmWindow]::ShowWindow($p.MainWindowHandle,9) | Out-Null; if(-not [RcmWindow]::SetForegroundWindow($p.MainWindowHandle)){ exit 3 }";
        var builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", script).redirectErrorStream(true);
        builder.environment().keySet().removeIf(DesktopCompanionServer::sensitiveEnvironment);
        builder.environment().put("RCM_DESKTOP_WINDOW_TITLE", request.windowTitle());
        var process = builder.start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("window focus timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("window was not found or could not be focused");
        }
        return new DesktopCompanionClient.Response(true, "focused window: " + request.windowTitle(), null, null, null);
    }

    private static void typeCharacter(Robot robot, char character) {
        if (character == '\n' || character == '\r') { press(robot, KeyEvent.VK_ENTER, false); return; }
        if (character == '\t') { press(robot, KeyEvent.VK_TAB, false); return; }
        if (character == ' ') { press(robot, KeyEvent.VK_SPACE, false); return; }
        var code = KeyEvent.getExtendedKeyCodeForChar(character);
        if (code == KeyEvent.VK_UNDEFINED) throw new IllegalArgumentException("unsupported character in type request");
        var shifted = Character.isUpperCase(character);
        press(robot, code, shifted);
    }

    private static void press(Robot robot, int code, boolean shift) {
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(code);
        robot.keyRelease(code);
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
    }

    private static Integer modifierCode(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL;
            case "ALT" -> KeyEvent.VK_ALT;
            case "SHIFT" -> KeyEvent.VK_SHIFT;
            case "WIN", "META" -> KeyEvent.VK_META;
            default -> null;
        };
    }

    private static int keyCode(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ENTER", "RETURN" -> KeyEvent.VK_ENTER;
            case "TAB" -> KeyEvent.VK_TAB;
            case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
            case "DELETE", "DEL" -> KeyEvent.VK_DELETE;
            case "UP" -> KeyEvent.VK_UP;
            case "DOWN" -> KeyEvent.VK_DOWN;
            case "LEFT" -> KeyEvent.VK_LEFT;
            case "RIGHT" -> KeyEvent.VK_RIGHT;
            case "HOME" -> KeyEvent.VK_HOME;
            case "END" -> KeyEvent.VK_END;
            case "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL;
            case "ALT" -> KeyEvent.VK_ALT;
            case "SHIFT" -> KeyEvent.VK_SHIFT;
            case "WIN", "META" -> KeyEvent.VK_META;
            default -> {
                if (normalized.length() == 1) {
                    var code = KeyEvent.getExtendedKeyCodeForChar(normalized.charAt(0));
                    if (code != KeyEvent.VK_UNDEFINED) yield code;
                }
                yield switch (normalized) {
                    case "F1" -> KeyEvent.VK_F1;
                    case "F2" -> KeyEvent.VK_F2;
                    case "F3" -> KeyEvent.VK_F3;
                    case "F4" -> KeyEvent.VK_F4;
                    case "F5" -> KeyEvent.VK_F5;
                    case "F6" -> KeyEvent.VK_F6;
                    case "F7" -> KeyEvent.VK_F7;
                    case "F8" -> KeyEvent.VK_F8;
                    case "F9" -> KeyEvent.VK_F9;
                    case "F10" -> KeyEvent.VK_F10;
                    case "F11" -> KeyEvent.VK_F11;
                    case "F12" -> KeyEvent.VK_F12;
                    default -> throw new IllegalArgumentException("unsupported key: " + value);
                };
            }
        };
    }

    private static void validate(DesktopCompanionClient.Request request, String operation) {
        if (request.operation() == null || request.operation().isBlank()) throw new IllegalArgumentException("desktop operation is required");
        if (operation.equals("launch") && (request.executable() == null || request.executable().isBlank())) throw new IllegalArgumentException("launch executable is required");
        if (operation.equals("click") && (request.x() == null || request.y() == null)) throw new IllegalArgumentException("click requires x/y");
        if (operation.equals("drag") && (request.x() == null || request.y() == null || request.x2() == null || request.y2() == null)) throw new IllegalArgumentException("drag requires x/y/x2/y2");
        if (operation.equals("key") && (request.key() == null || request.key().isBlank())) throw new IllegalArgumentException("key requires key name");
        if (operation.equals("type") && (request.text() == null || request.text().isEmpty() || request.text().length() > 16384)) throw new IllegalArgumentException("type requires text up to 16384 characters");
        if (operation.equals("clipboard_write") && (request.text() == null || request.text().length() > 65536)) throw new IllegalArgumentException("clipboard_write requires text up to 65536 characters");
        if (operation.equals("focus") && (request.windowTitle() == null || request.windowTitle().isBlank() || request.windowTitle().length() > 512
                || request.windowTitle().indexOf('\r') >= 0 || request.windowTitle().indexOf('\n') >= 0)) throw new IllegalArgumentException("focus requires a valid single-line window_title");
        if (request.x() != null && (request.x() < -100000 || request.x() > 100000)) throw new IllegalArgumentException("x is outside the allowed range");
        if (request.y() != null && (request.y() < -100000 || request.y() > 100000)) throw new IllegalArgumentException("y is outside the allowed range");
        if (request.x2() != null && (request.x2() < -100000 || request.x2() > 100000)) throw new IllegalArgumentException("x2 is outside the allowed range");
        if (request.y2() != null && (request.y2() < -100000 || request.y2() > 100000)) throw new IllegalArgumentException("y2 is outside the allowed range");
        if (request.screen() != null && (request.screen() < 0 || request.screen() > 32)) throw new IllegalArgumentException("screen must be between 0 and 32");
        if (request.durationMs() != null && (request.durationMs() < 0 || request.durationMs() > 10000)) throw new IllegalArgumentException("duration_ms must be between 0 and 10000");
        if (request.args() != null && request.args().size() > 128) throw new IllegalArgumentException("too many launch arguments");
        if (!operation.equals("launch") && request.args() != null && !request.args().isEmpty()) throw new IllegalArgumentException("args are allowed only for launch");
        if (!operation.equals("launch") && request.executable() != null && !request.executable().isBlank()) throw new IllegalArgumentException("executable is allowed only for launch");
    }

    private static void ensureDisplay() {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("user session has no desktop display");
    }

    private static Rectangle virtualBounds(Integer screen) {
        var devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (screen != null) {
            if (screen < 0 || screen >= devices.length) throw new IllegalArgumentException("screen index is not available");
            var bounds = devices[screen].getDefaultConfiguration().getBounds();
            if (bounds.width <= 0 || bounds.height <= 0) throw new IllegalStateException("selected screen has no bounds");
            return bounds;
        }
        var result = new Rectangle();
        for (GraphicsDevice device : devices) {
            for (GraphicsConfiguration configuration : device.getConfigurations()) result = result.union(configuration.getBounds());
        }
        if (result.width <= 0 || result.height <= 0) throw new IllegalStateException("no desktop display bounds");
        return result;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String loadOrCreateToken(Path stateDir) throws IOException {
        var file = stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR).resolve(TOKEN_FILE);
        if (Files.isRegularFile(file)) {
            var value = Files.readString(file).trim();
            if (!value.isBlank()) return value;
        }
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value, StandardCharsets.UTF_8);
        return value;
    }

    private static void writeEndpoint(Path target, int port, String token) throws IOException {
        var temp = Files.createTempFile(target.getParent(), "desktop-companion-", ".tmp");
        try {
            Files.write(temp, JsonCodec.write(new DesktopCompanionClient.Endpoint(port, token)));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean sensitiveEnvironment(String key) {
        var upper = key.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN") || upper.contains("PASSWORD") || upper.contains("SECRET") || upper.contains("COOKIE");
    }

    private static String compactError(String error) {
        var value = error == null || error.isBlank() ? "desktop companion request failed" : error.trim();
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }

    private static void write(BufferedWriter writer, DesktopCompanionClient.Response response) throws IOException {
        writer.write(new String(JsonCodec.write(response), StandardCharsets.UTF_8));
        writer.newLine();
        writer.flush();
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            var port = Integer.parseInt(value.trim());
            if (port < 0 || port > 65535) throw new IllegalArgumentException("desktop companion port is invalid");
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("desktop companion port is invalid", exception);
        }
    }
}
