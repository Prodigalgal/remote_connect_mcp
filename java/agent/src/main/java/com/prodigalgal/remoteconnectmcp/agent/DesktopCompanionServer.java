package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * User-session desktop companion. It binds only to loopback, authenticates
 * every request with a local token, and never talks to Center or registers a
 * second Agent identity.
 */
public final class DesktopCompanionServer {
    private static final Logger LOG = Logger.getLogger(DesktopCompanionServer.class.getName());
    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024;
    private static final int DEFAULT_MAX_CONNECTIONS = 4;
    private static final int MAX_ALLOWED_CONNECTIONS = 16;
    private static final int DEFAULT_MAX_LAUNCHED_PROCESSES = 16;
    private static final int MAX_ALLOWED_LAUNCHED_PROCESSES = 64;
    private static final String TOKEN_FILE = "desktop-companion.token";
    private static final String POLICY_FILE = "desktop-companion-policy.json";
    private static final String COMPANION_DIR = "desktop";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path stateDir;
    private final String token;
    private final int requestedPort;
    private final int maxConnections;
    private final int maxLaunchedProcesses;
    private final Policy policy;
    private final Semaphore connectionSlots;
    private final Semaphore launchSlots;
    private final ConcurrentMap<Long, ProcessHandle> launchedProcesses = new ConcurrentHashMap<>();

    DesktopCompanionServer(Path stateDir, String token, int requestedPort) {
        this(stateDir, token, requestedPort, DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_LAUNCHED_PROCESSES);
    }

    DesktopCompanionServer(Path stateDir, String token, int requestedPort,
                           int maxConnections, int maxLaunchedProcesses) {
        this(stateDir, token, requestedPort, maxConnections, maxLaunchedProcesses,
                new Policy(ScopeMode.UNRESTRICTED, null));
    }

    DesktopCompanionServer(Path stateDir, String token, int requestedPort,
                           int maxConnections, int maxLaunchedProcesses, Policy policy) {
        this.stateDir = stateDir.toAbsolutePath().normalize();
        this.token = normalizeToken(token);
        this.requestedPort = requestedPort;
        this.maxConnections = bounded(maxConnections, 1, MAX_ALLOWED_CONNECTIONS, "desktop companion max connections");
        this.maxLaunchedProcesses = bounded(maxLaunchedProcesses, 1, MAX_ALLOWED_LAUNCHED_PROCESSES,
                "desktop companion max launched processes");
        this.connectionSlots = new Semaphore(this.maxConnections);
        this.launchSlots = new Semaphore(this.maxLaunchedProcesses);
        this.policy = policy == null ? new Policy(ScopeMode.UNRESTRICTED, null) : policy;
    }

    public static void run(Path stateDir) throws IOException {
        var token = System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_COMPANION_TOKEN");
        if (token == null || token.isBlank()) token = loadOrCreateToken(stateDir);
        var port = parsePort(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_COMPANION_PORT"));
        var maxConnections = parseBounded(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_CONNECTIONS"),
                DEFAULT_MAX_CONNECTIONS, 1, MAX_ALLOWED_CONNECTIONS, "REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_CONNECTIONS");
        var maxLaunchedProcesses = parseBounded(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES"),
                DEFAULT_MAX_LAUNCHED_PROCESSES, 1, MAX_ALLOWED_LAUNCHED_PROCESSES,
                "REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES");
        token = normalizeToken(token);
        new DesktopCompanionServer(stateDir, token, port, maxConnections, maxLaunchedProcesses,
                loadPolicy(stateDir)).serve();
    }

    /** Publish the machine-level desktop scope for the user-session process. */
    public static void writePolicy(Path stateDir, ScopeMode scopeMode, String workspaceRoot) throws IOException {
        var directory = stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR);
        Files.createDirectories(directory);
        var target = directory.resolve(POLICY_FILE).normalize();
        if (!directory.equals(target.getParent())) throw new IOException("desktop companion policy path escapes state directory");
        var mode = scopeMode == null ? ScopeMode.WORKSPACE : scopeMode;
        var root = workspaceRoot == null || workspaceRoot.isBlank() ? null : workspaceRoot.trim();
        if (root != null && (root.length() > 4096 || root.indexOf('\u0000') >= 0
                || root.indexOf('\r') >= 0 || root.indexOf('\n') >= 0
                || root.chars().anyMatch(Character::isISOControl))) {
            throw new IOException("desktop companion policy root is invalid");
        }
        if (mode.bounded() && (root == null || root.isBlank())) {
            throw new IOException("bounded desktop companion policy requires a workspace root");
        }
        var policy = new Policy(mode, root);
        var temporary = Files.createTempFile(directory, "desktop-policy-", ".tmp");
        try {
            Files.write(temporary, JsonCodec.write(policy));
            restrictOwner(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwner(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void serve() throws IOException {
        Files.createDirectories(stateDir);
        var companionDir = stateDir.resolve(COMPANION_DIR);
        Files.createDirectories(companionDir);
        try (var lock = AgentLock.acquire(companionDir, "desktop-companion.lock");
             var server = new ServerSocket(requestedPort, 32, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var endpoint = companionDir.resolve("desktop-companion.json");
            writeEndpoint(endpoint, server.getLocalPort(), token);
            var cleanup = new Thread(() -> {
                terminateLaunchedProcesses();
                try { Files.deleteIfExists(endpoint); } catch (IOException ignored) { }
            }, "rcm-desktop-companion-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            LOG.info(() -> "desktop companion listening on loopback port " + server.getLocalPort());
            while (!Thread.currentThread().isInterrupted()) {
                var client = server.accept();
                if (!tryDispatch(client, workers)) {
                    rejectBusy(client);
                }
            }
        } finally {
            terminateLaunchedProcesses();
            Files.deleteIfExists(companionDir.resolve("desktop-companion.json"));
        }
    }

    /**
     * A companion restart must not leave GUI applications orphaned forever.
     * Normal application exits release their launch permits through
     * {@code ProcessHandle.onExit}; this path is only for explicit shutdown
     * or a crashed service wrapper and therefore walks the bounded registry
     * once instead of running a background poller.
     */
    private void terminateLaunchedProcesses() {
        for (var handle : List.copyOf(launchedProcesses.values())) {
            try {
                handle.descendants().forEach(child -> {
                    try { child.destroy(); } catch (RuntimeException ignored) { }
                    if (child.isAlive()) {
                        try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                    }
                });
                handle.destroy();
                if (handle.isAlive()) handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // The process may have exited between snapshot and cleanup.
            }
            launchedProcesses.remove(handle.pid(), handle);
        }
    }

    private boolean tryDispatch(Socket client, ExecutorService workers) {
        // A virtual-thread-per-task executor is cheap, but accepting without a
        // permit would still allow an unbounded queue of sockets.  Acquire
        // before submitting so the number of active desktop calls is fixed.
        if (!connectionSlots.tryAcquire()) return false;
        try {
            workers.execute(() -> {
                try {
                    handle(client);
                } finally {
                    connectionSlots.release();
                }
            });
            return true;
        } catch (RuntimeException exception) {
            connectionSlots.release();
            try { client.close(); } catch (IOException ignored) { }
            return true;
        }
    }

    private static void rejectBusy(Socket client) {
        try (client;
             var writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
            write(writer, new DesktopCompanionClient.Response(false, null, null, null,
                    "desktop companion is busy; retry after an active request completes"));
        } catch (Exception ignored) {
            // The caller may have already disconnected; no retry loop here.
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
        validateScope(request);
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
        if (!launchSlots.tryAcquire()) {
            throw new IOException("desktop launch limit reached (" + maxLaunchedProcesses + ")");
        }
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
        try {
            var process = builder.start();
            var handle = process.toHandle();
            launchedProcesses.put(handle.pid(), handle);
            handle.onExit().thenRun(() -> {
                if (launchedProcesses.remove(handle.pid(), handle)) launchSlots.release();
            });
            return new DesktopCompanionClient.Response(true, "launched process " + process.pid(), null, null, null);
        } catch (Exception exception) {
            launchSlots.release();
            if (exception instanceof IOException io) throw io;
            throw new IOException("could not launch desktop process", exception);
        }
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

    /** Re-check the Center contract before executing any user-session action. */
    private void validateScope(DesktopCompanionClient.Request request) throws IOException {
        var machineMode = policy.scopeMode() == null ? ScopeMode.WORKSPACE : policy.scopeMode();
        var requestedMode = request.scopeMode() == null || request.scopeMode().isBlank()
                ? machineMode : ScopeMode.fromWireValue(request.scopeMode());
        if (machineMode.bounded() && !requestedMode.bounded()) {
            throw new IOException("desktop request exceeds the machine workspace policy");
        }
        if (request.contractExpiresAt() != null && !Instant.now().isBefore(request.contractExpiresAt())) {
            throw new IOException("desktop execution contract has expired");
        }
        if (!requestedMode.bounded() && !machineMode.bounded()) return;
        var machineRoot = policy.workspaceRoot();
        if (machineRoot == null || machineRoot.isBlank()) {
            throw new IOException("desktop workspace policy has no root");
        }
        var machineReal = resolveThroughExistingParents(Path.of(machineRoot));
        var contractRoot = request.scopeRoot() == null || request.scopeRoot().isBlank()
                ? machineReal : resolveThroughExistingParents(Path.of(request.scopeRoot()));
        if (!within(machineReal, contractRoot)) {
            throw new IOException("desktop contract root is outside the machine workspace");
        }
        var requestedCwd = request.cwd() == null || request.cwd().isBlank()
                ? contractRoot : resolveThroughExistingParents(Path.of(request.cwd()));
        if (!within(contractRoot, requestedCwd)) {
            throw new IOException("desktop cwd is outside the execution contract scope");
        }
    }

    private static Path resolveThroughExistingParents(Path candidate) throws IOException {
        var missing = new java.util.ArrayDeque<Path>();
        var existing = candidate.toAbsolutePath().normalize();
        while (!Files.exists(existing)) {
            var name = existing.getFileName();
            if (name == null) throw new IOException("desktop cwd has no existing parent");
            missing.addFirst(name);
            existing = existing.getParent();
            if (existing == null) throw new IOException("desktop cwd has no existing parent");
        }
        var resolved = existing.toRealPath();
        for (var name : missing) resolved = resolved.resolve(name);
        return resolved.normalize();
    }

    private static boolean within(Path root, Path candidate) {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var normalizedCandidate = candidate.toAbsolutePath().normalize();
        return normalizedCandidate.equals(normalizedRoot) || normalizedCandidate.startsWith(normalizedRoot);
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
            if (!value.isBlank()) {
                var normalized = normalizeToken(value);
                restrictOwner(file);
                return normalized;
            }
        }
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value, StandardCharsets.UTF_8);
        restrictOwner(file);
        return value;
    }

    private static void writeEndpoint(Path target, int port, String token) throws IOException {
        var temp = Files.createTempFile(target.getParent(), "desktop-companion-", ".tmp");
        try {
            Files.write(temp, JsonCodec.write(new DesktopCompanionClient.Endpoint(port, token)));
            restrictOwner(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwner(target);
        } finally { Files.deleteIfExists(temp); }
    }

    /**
     * Load the policy published by the command Agent.  The companion is a
     * user-session helper. New Agent installations publish a valid policy
     * before the companion is used. A missing or malformed policy fails closed
     * to the Agent state directory rather than widening a user-session process
     * to whole-host access. The constructor-only legacy policy remains
     * available for direct protocol tests, but the production environment
     * loader never treats absent policy as unrestricted authority.
     */
    private static Policy loadPolicy(Path stateDir) {
        var file = stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR).resolve(POLICY_FILE);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return new Policy(ScopeMode.PATH, stateDir.toAbsolutePath().normalize().toString());
            }
            if (Files.size(file) > 4096) throw new IOException("desktop companion policy is too large");
            var parsed = JsonCodec.read(Files.readAllBytes(file), Policy.class);
            if (parsed == null || parsed.scopeMode() == null) {
                throw new IOException("desktop companion policy has no scope mode");
            }
            var root = parsed.workspaceRoot();
            if (root != null && (root.isBlank() || root.indexOf('\u0000') >= 0
                    || root.indexOf('\r') >= 0 || root.indexOf('\n') >= 0
                    || root.length() > 4096)) {
                throw new IOException("desktop companion policy root is invalid");
            }
            return new Policy(parsed.scopeMode(), root == null ? null : root.trim());
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "could not load desktop companion scope policy; using a fail-closed local policy", failure);
            return new Policy(ScopeMode.PATH, stateDir.toAbsolutePath().normalize().toString());
        }
    }

    /** Keep local IPC material private where the host filesystem supports it. */
    private static void restrictOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some mounted filesystems do not expose POSIX modes;
            // installers apply an ACL there instead.
        }
    }

    private static String normalizeToken(String value) {
        if (value == null) throw new IllegalArgumentException("desktop companion token is required");
        var normalized = value.trim();
        if (normalized.length() < 32 || normalized.length() > 256
                || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("desktop companion token must be 32-256 single-line characters");
        }
        return normalized;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean sensitiveEnvironment(String key) {
        var upper = key.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN") || upper.contains("PASSWORD") || upper.contains("PASSWD")
                || upper.contains("SECRET") || upper.contains("COOKIE") || upper.contains("AUTHORIZATION")
                || upper.contains("API_KEY") || upper.contains("PRIVATE_KEY") || upper.contains("CREDENTIAL");
    }

    private static String compactError(String error) {
        var value = error == null || error.isBlank() ? "desktop companion request failed"
                : SensitiveValueRedactor.redact(error.trim());
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

    private static int parseBounded(String value, int fallback, int minimum, int maximum, String name) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return bounded(Integer.parseInt(value.trim()), minimum, maximum, name);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " is outside the allowed range");
        return value;
    }

    private record Policy(ScopeMode scopeMode, String workspaceRoot) {
    }
}
