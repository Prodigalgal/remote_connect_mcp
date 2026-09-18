package com.prodigalgal.remoteconnectmcp.desktop;

import com.prodigalgal.remoteconnectmcp.protocol.DesktopCompanionProtocol;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.awt.AWTException;
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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
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
    private static final int MAX_WINDOW_METADATA_BYTES = 64 * 1024;
    private static final String TOKEN_FILE = DesktopCompanionProtocol.TOKEN_FILE;
    private static final String COMPANION_DIR = DesktopCompanionProtocol.COMPANION_DIR;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path stateDir;
    private final String token;
    private final int requestedPort;
    private final int maxConnections;
    private final int maxLaunchedProcesses;
    private final java.time.Duration launchLifetime;
    private final DesktopCompanionProtocol.Policy policy;
    private final Semaphore connectionSlots;
    private final Semaphore launchSlots;
    /** One input/screen controller at a time per logged-in OS desktop. */
    private final Semaphore desktopLease = new Semaphore(1);
    private final ConcurrentMap<Long, ProcessHandle> launchedProcesses = new ConcurrentHashMap<>();
    /** Serialize GUI mutations within one user/conversation session. */
    private final ConcurrentMap<String, SessionGuard> sessionLocks = new ConcurrentHashMap<>();

    DesktopCompanionServer(Path stateDir, String token, int requestedPort) {
        this(stateDir, token, requestedPort, DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_LAUNCHED_PROCESSES);
    }

    DesktopCompanionServer(Path stateDir, String token, int requestedPort,
                           int maxConnections, int maxLaunchedProcesses) {
        this(stateDir, token, requestedPort, maxConnections, maxLaunchedProcesses,
                new DesktopCompanionProtocol.Policy(ScopeMode.UNRESTRICTED, null));
    }

    DesktopCompanionServer(Path stateDir, String token, int requestedPort,
                           int maxConnections, int maxLaunchedProcesses, DesktopCompanionProtocol.Policy policy) {
        this.stateDir = stateDir.toAbsolutePath().normalize();
        this.token = normalizeToken(token);
        this.requestedPort = requestedPort;
        this.maxConnections = bounded(maxConnections, 1, MAX_ALLOWED_CONNECTIONS, "desktop companion max connections");
        this.maxLaunchedProcesses = bounded(maxLaunchedProcesses, 1, MAX_ALLOWED_LAUNCHED_PROCESSES,
                "desktop companion max launched processes");
        this.launchLifetime = configuredDuration("REMOTE_CONNECT_MCP_AGENT_DESKTOP_PROCESS_TTL_SECONDS",
                java.time.Duration.ofHours(8), java.time.Duration.ofMinutes(1), java.time.Duration.ofDays(30));
        this.connectionSlots = new Semaphore(this.maxConnections);
        this.launchSlots = new Semaphore(this.maxLaunchedProcesses);
        this.policy = policy == null ? new DesktopCompanionProtocol.Policy(ScopeMode.UNRESTRICTED, null) : policy;
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

    private void serve() throws IOException {
        Files.createDirectories(stateDir);
        var companionDir = stateDir.resolve(COMPANION_DIR);
        Files.createDirectories(companionDir);
        try (var lock = DesktopCompanionLock.acquire(companionDir, "desktop-companion.lock");
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
            write(writer, new DesktopCompanionProtocol.Response(false, null, null, null,
                    "desktop companion is busy; retry after an active request completes"));
        } catch (Exception ignored) {
            // The caller may have already disconnected; no retry loop here.
        }
    }

    private void handle(Socket socket) {
        // Keep the response writer alive while handling a request.  The old
        // try-with-resources closed the socket before its catch block ran, so
        // any AWT/IPC validation error surfaced to the command Agent merely
        // as "desktop companion closed the connection" and discarded the
        // actionable root cause.  Returning a bounded error also lets the
        // caller distinguish a missing display from a stale endpoint without
        // adding a retry loop or leaking a stack trace into task output.
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(300_000);
            try {
                var line = reader.readLine();
                if (line == null || line.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                    write(writer, new DesktopCompanionProtocol.Response(false, null, null, null, "request too large"));
                    return;
                }
                var request = JsonCodec.read(line.getBytes(StandardCharsets.UTF_8), DesktopCompanionProtocol.Request.class);
                if (!constantTimeEquals(token, request.token())) {
                    write(writer, new DesktopCompanionProtocol.Response(false, null, null, null, "invalid companion token"));
                    return;
                }
                write(writer, execute(request));
            } catch (Throwable failure) {
                // AWT reports display/toolkit initialization failures as
                // java.awt.AWTError (an Error, not an Exception).  Surface
                // those failures through the bounded protocol response so
                // the command Agent can explain a missing/stale GUI session.
                // Preserve VM-fatal errors: catching an OutOfMemoryError or
                // ThreadDeath would leave the companion in an unsafe state.
                if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
                LOG.log(Level.WARNING, "desktop companion request failed", failure);
                write(writer, new DesktopCompanionProtocol.Response(false, null, null, null,
                        compactError(failure.getMessage() == null ? failure.toString() : failure.getMessage())));
            }
        } catch (Exception exception) {
            LOG.log(Level.FINE, "desktop companion connection failed", exception);
        }
    }

    private DesktopCompanionProtocol.Response execute(DesktopCompanionProtocol.Request request) throws Exception {
        var operation = request.operation() == null ? "" : request.operation().trim().toLowerCase(Locale.ROOT);
        validate(request, operation);
        validateScope(request);
        var sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "legacy" : request.sessionId().trim();
        if (sessionId.length() > 256 || sessionId.indexOf('\u0000') >= 0
                || sessionId.indexOf('\r') >= 0 || sessionId.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("desktop session id is invalid");
        }
        var sessionGuard = sessionLocks.compute(sessionId, (ignored, current) -> {
            var guard = current == null ? new SessionGuard() : current;
            guard.references.incrementAndGet();
            return guard;
        });
        var sessionLock = sessionGuard.semaphore;
        var sessionAcquired = false;
        var desktopAcquired = false;
        try {
            if (!sessionLock.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("desktop session is busy; retry after the active action completes");
            }
            sessionAcquired = true;
            if (!desktopLease.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("desktop session is controlled by another task; retry after it completes");
            }
            desktopAcquired = true;
            return switch (operation) {
            case "screenshot" -> screenshot(request);
            case "screens" -> screens();
            case "windows" -> windows();
            case "launch" -> launch(request);
            case "click" -> click(request);
            case "double_click" -> click(request, InputEvent.BUTTON1_DOWN_MASK, 2);
            case "right_click" -> click(request, InputEvent.BUTTON3_DOWN_MASK, 1);
            case "move" -> move(request);
            case "screenshot_region" -> screenshotRegion(request);
            case "drag" -> drag(request);
            case "key" -> key(request);
            case "type" -> type(request);
            case "clipboard_read" -> clipboardRead();
            case "clipboard_write" -> clipboardWrite(request);
            case "focus" -> focus(request);
            default -> throw new IllegalArgumentException("unsupported desktop operation: " + operation);
            };
        } finally {
            if (desktopAcquired) desktopLease.release();
            if (sessionAcquired) sessionLock.release();
            if (sessionGuard.references.decrementAndGet() == 0 && sessionLock.availablePermits() > 0) {
                // Removal must be serialized with a new request acquiring the
                // same guard.  A plain remove() can evict the guard after a
                // concurrent caller increments its reference count, allowing
                // the next caller to create a second semaphore for one GUI
                // session.
                sessionLocks.computeIfPresent(sessionId, (ignored, current) ->
                        current == sessionGuard && current.references.get() == 0
                                && current.semaphore.availablePermits() > 0 ? null : current);
            }
        }
    }

    private DesktopCompanionProtocol.Response screenshot(DesktopCompanionProtocol.Request request) throws Exception {
        try {
            ensureDisplay();
            var bounds = virtualBounds(request.screen());
            return encodeScreenshot(new Robot().createScreenCapture(bounds), "screenshot captured");
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
            // Wayland compositors commonly expose a display while denying AWT
            // capture.  Prefer a native compositor helper when available;
            // this keeps the companion cross-platform without linking a
            // heavyweight desktop automation library into the Agent.
            var nativeCapture = nativeScreenshot(request.screen());
            if (nativeCapture != null) return new DesktopCompanionProtocol.Response(true,
                    "screenshot captured by native display helper (" + nativeCapture.length + " bytes)",
                    "image/png", Base64.getEncoder().encodeToString(nativeCapture), null);
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException(failure.getMessage() == null ? "desktop capture failed" : failure.getMessage(), failure);
        }
    }

    private DesktopCompanionProtocol.Response screenshotRegion(DesktopCompanionProtocol.Request request) throws Exception {
        try {
            ensureDisplay();
            var bounds = new Rectangle(request.x(), request.y(), request.x2() - request.x(), request.y2() - request.y());
            return encodeScreenshot(new Robot().createScreenCapture(bounds), "region screenshot captured");
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
            var nativeCapture = nativeScreenshotRegion(request.x(), request.y(),
                    request.x2() - request.x(), request.y2() - request.y());
            if (nativeCapture != null) return new DesktopCompanionProtocol.Response(true,
                    "region screenshot captured by native display helper (" + nativeCapture.length + " bytes)",
                    "image/png", Base64.getEncoder().encodeToString(nativeCapture), null);
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException(failure.getMessage() == null ? "region desktop capture failed" : failure.getMessage(), failure);
        }
    }

    private DesktopCompanionProtocol.Response encodeScreenshot(BufferedImage image, String description) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder is unavailable");
        var data = output.toByteArray();
        if (data.length == 0 || data.length > MAX_SCREENSHOT_BYTES) throw new IOException("screenshot exceeds 8 MiB");
        return new DesktopCompanionProtocol.Response(true, description + " (" + data.length + " bytes)",
                "image/png", Base64.getEncoder().encodeToString(data), null);
    }

    private DesktopCompanionProtocol.Response screens() {
        ensureDisplay();
        var values = new ArrayList<Map<String, Object>>();
        var devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (var index = 0; index < devices.length; index++) {
            var bounds = devices[index].getDefaultConfiguration().getBounds();
            values.add(Map.of("index", index, "id", devices[index].getIDstring(), "x", bounds.x,
                    "y", bounds.y, "width", bounds.width, "height", bounds.height));
        }
        try {
            return new DesktopCompanionProtocol.Response(true,
                    new String(JsonCodec.write(values), StandardCharsets.UTF_8),
                    "application/json", null, null);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("could not encode screen metadata", exception);
        }
    }

    private DesktopCompanionProtocol.Response launch(DesktopCompanionProtocol.Request request) throws IOException {
        if (!launchSlots.tryAcquire()) {
            throw new IOException("desktop launch limit reached (" + maxLaunchedProcesses + ")");
        }
        try {
            var command = new ArrayList<String>();
            command.add(request.executable());
            command.addAll(request.args() == null ? List.of() : request.args());
            var builder = new ProcessBuilder(command);
            if (request.cwd() != null && !request.cwd().isBlank()) {
                // Resolve the existing prefix once more immediately before
                // ProcessBuilder uses it.  This closes the common symlink/
                // junction swap between contract validation and launch while
                // keeping unrestricted host operations available.
                var cwd = resolveThroughExistingParents(Path.of(request.cwd()));
                if (!Files.isDirectory(cwd, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("desktop cwd is not a directory");
                }
                builder.directory(cwd.toFile());
            }
            builder.environment().keySet().removeIf(DesktopCompanionServer::sensitiveEnvironment);
            var process = builder.start();
            var handle = process.toHandle();
            launchedProcesses.put(handle.pid(), handle);
            handle.onExit().thenRun(() -> {
                if (launchedProcesses.remove(handle.pid(), handle)) launchSlots.release();
            });
            // A GUI process that never exits must not consume a permit
            // forever. This is a one-shot deadline tied to this launch, not a
            // polling watchdog; normal exits remove the handle first.
            java.util.concurrent.CompletableFuture.delayedExecutor(
                    launchLifetime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
                if (handle.isAlive()) {
                    terminateProcessTree(handle);
                }
            });
            return new DesktopCompanionProtocol.Response(true, "launched process " + process.pid(), null, null, null);
        } catch (Exception exception) {
            launchSlots.release();
            if (exception instanceof IOException io) throw io;
            throw new IOException("could not launch desktop process", exception);
        }
    }

    private DesktopCompanionProtocol.Response click(DesktopCompanionProtocol.Request request) throws AWTException {
        return click(request, InputEvent.BUTTON1_DOWN_MASK, 1);
    }

    private DesktopCompanionProtocol.Response click(DesktopCompanionProtocol.Request request, int button, int count) throws AWTException {
        ensureDisplay();
        var robot = new Robot();
        robot.mouseMove(request.x(), request.y());
        for (var index = 0; index < count; index++) {
            robot.mousePress(button);
            robot.mouseRelease(button);
            if (count > 1 && index + 1 < count) robot.delay(60);
        }
        var label = button == InputEvent.BUTTON3_DOWN_MASK ? "right-clicked" : count > 1 ? "double-clicked" : "clicked";
        return new DesktopCompanionProtocol.Response(true, label + " " + request.x() + "," + request.y(), null, null, null);
    }

    private DesktopCompanionProtocol.Response move(DesktopCompanionProtocol.Request request) throws AWTException {
        ensureDisplay();
        new Robot().mouseMove(request.x(), request.y());
        return new DesktopCompanionProtocol.Response(true, "moved pointer to " + request.x() + "," + request.y(), null, null, null);
    }

    private DesktopCompanionProtocol.Response drag(DesktopCompanionProtocol.Request request) throws AWTException, InterruptedException {
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
        return new DesktopCompanionProtocol.Response(true,
                "dragged " + request.x() + "," + request.y() + " to " + request.x2() + "," + request.y2(),
                null, null, null);
    }

    private DesktopCompanionProtocol.Response key(DesktopCompanionProtocol.Request request) throws AWTException {
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
        return new DesktopCompanionProtocol.Response(true, "pressed " + request.key(), null, null, null);
    }

    private DesktopCompanionProtocol.Response type(DesktopCompanionProtocol.Request request) throws AWTException {
        ensureDisplay();
        var robot = new Robot();
        for (var character : request.text().toCharArray()) {
            typeCharacter(robot, character);
        }
        return new DesktopCompanionProtocol.Response(true, "typed " + request.text().length() + " characters", null, null, null);
    }

    private DesktopCompanionProtocol.Response clipboardRead() throws IOException, UnsupportedFlavorException {
        ensureDisplay();
        var value = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        if (value == null) value = "";
        if (value.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IOException("clipboard text exceeds 64 KiB");
        }
        return new DesktopCompanionProtocol.Response(true, value, "text/plain; charset=utf-8", null, null);
    }

    private DesktopCompanionProtocol.Response clipboardWrite(DesktopCompanionProtocol.Request request) {
        ensureDisplay();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(request.text()), null);
        return new DesktopCompanionProtocol.Response(true, "clipboard updated (" + request.text().length() + " characters)", null, null, null);
    }

    private DesktopCompanionProtocol.Response focus(DesktopCompanionProtocol.Request request) throws IOException, InterruptedException {
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
        return new DesktopCompanionProtocol.Response(true, "focused window: " + request.windowTitle(), null, null, null);
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

    private static void validate(DesktopCompanionProtocol.Request request, String operation) {
        if (request.operation() == null || request.operation().isBlank()) throw new IllegalArgumentException("desktop operation is required");
        if (operation.equals("launch") && (request.executable() == null || request.executable().isBlank())) throw new IllegalArgumentException("launch executable is required");
        if ((operation.equals("click") || operation.equals("double_click") || operation.equals("right_click") || operation.equals("move"))
                && (request.x() == null || request.y() == null)) throw new IllegalArgumentException(operation + " requires x/y");
        if (operation.equals("drag") && (request.x() == null || request.y() == null || request.x2() == null || request.y2() == null)) throw new IllegalArgumentException("drag requires x/y/x2/y2");
        if (operation.equals("screenshot_region") && (request.x() == null || request.y() == null
                || request.x2() == null || request.y2() == null || request.x2() <= request.x() || request.y2() <= request.y()
                || ((long) request.x2() - request.x()) > 16000 || ((long) request.y2() - request.y()) > 16000)) {
            throw new IllegalArgumentException("screenshot_region requires ordered x/y/x2/y2 within a 16000x16000 region");
        }
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
    private void validateScope(DesktopCompanionProtocol.Request request) throws IOException {
        validateScope(policy, request);
    }

    /**
     * Validate a task contract against the machine policy.  An unrestricted
     * command Agent may still receive a narrower per-task contract; the
     * companion must enforce that contract even though its machine policy has
     * no fixed workspace root.  The previous implementation treated that
     * valid combination as if the machine root were missing and rejected all
     * companion input actions on unrestricted Agents.
     */
    static void validateScope(DesktopCompanionProtocol.Policy policy, DesktopCompanionProtocol.Request request) throws IOException {
        if (request == null) throw new IOException("desktop request is missing");
        var effectivePolicy = policy == null ? new DesktopCompanionProtocol.Policy(ScopeMode.WORKSPACE, null) : policy;
        var machineMode = effectivePolicy.scopeMode() == null ? ScopeMode.WORKSPACE : effectivePolicy.scopeMode();
        var requestedMode = request.scopeMode() == null || request.scopeMode().isBlank()
                ? machineMode : ScopeMode.fromWireValue(request.scopeMode());
        if (machineMode.bounded() && !requestedMode.bounded()) {
            throw new IOException("desktop request exceeds the machine workspace policy");
        }
        if (request.contractExpiresAt() != null && !Instant.now().isBefore(request.contractExpiresAt())) {
            throw new IOException("desktop execution contract has expired");
        }
        if (!requestedMode.bounded()) {
            if (request.scopeRoot() != null && !request.scopeRoot().isBlank()) {
                throw new IOException("unrestricted desktop request cannot carry scope_root");
            }
            return;
        }
        Path machineReal = null;
        if (machineMode.bounded()) {
            var machineRoot = effectivePolicy.workspaceRoot();
            if (machineRoot == null || machineRoot.isBlank()) {
                throw new IOException("desktop workspace policy has no root");
            }
            machineReal = resolveThroughExistingParents(Path.of(machineRoot));
        }
        var contractRoot = request.scopeRoot() == null || request.scopeRoot().isBlank()
                ? machineReal : resolveThroughExistingParents(Path.of(request.scopeRoot()));
        if (contractRoot == null) {
            throw new IOException("bounded desktop request requires scope_root");
        }
        if (machineReal != null && !within(machineReal, contractRoot)) {
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

    private static byte[] nativeScreenshot(Integer screen) {
        // grim exposes a compositor-wide capture but not a stable screen
        // index. Never return a misleading full desktop image for an
        // explicitly selected monitor.
        if (screen != null) return null;
        var wayland = System.getenv("WAYLAND_DISPLAY");
        var candidates = new ArrayList<List<String>>();
        if (wayland != null && !wayland.isBlank()) candidates.add(List.of("grim", "-"));
        candidates.add(List.of("gnome-screenshot", "-f", "-"));
        candidates.add(List.of("scrot", "-"));
        for (var command : candidates) {
            var bytes = nativePng(command);
            if (bytes != null) return bytes;
        }
        return null;
    }

    /** Native Wayland region capture for hosts where AWT cannot access the display. */
    private static byte[] nativeScreenshotRegion(int x, int y, int width, int height) {
        var wayland = System.getenv("WAYLAND_DISPLAY");
        if (wayland == null || wayland.isBlank() || width <= 0 || height <= 0) return null;
        return nativePng(List.of("grim", "-g", x + "," + y + " " + width + "x" + height, "-"));
    }

    private static byte[] nativePng(List<String> command) {
        Process process = null;
        try {
            // stdout is the PNG byte stream; merge stderr would corrupt the
            // image when a helper emits a warning before its payload.
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            var bytes = readProcessOutput(process, MAX_SCREENSHOT_BYTES + 1, 10);
            if (bytes == null) {
                process.destroyForcibly();
                return null;
            }
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || bytes.length < 8 || bytes.length > MAX_SCREENSHOT_BYTES
                    || bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4e || bytes[3] != 0x47) {
                return null;
            }
            return bytes;
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    /**
     * Enumerate visible top-level windows with a small, fixed OS helper. AWT
     * only knows about windows owned by the companion JVM, so using the
     * platform tools here is necessary for focusing an application launched
     * by another process. The commands are constants; no request value is
     * interpolated into a shell command.
     */
    private DesktopCompanionProtocol.Response windows() throws IOException {
        var commands = new ArrayList<List<String>>();
        if (isWindows()) {
            var script = ""
                    + "$ErrorActionPreference='Stop'; "
                    + "$items=@(Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle } "
                    + "| Select-Object Id,ProcessName,MainWindowTitle,MainWindowHandle); "
                    + "if ($null -eq $items) { '[]' } else { @($items) | ConvertTo-Json -Compress }";
            commands.add(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script));
            commands.add(List.of("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script));
        } else {
            commands.add(List.of("wmctrl", "-l", "-x", "-p", "-G"));
            commands.add(List.of("xdotool", "search", "--onlyvisible", "--name", ".", "getwindowname"));
        }
        for (var command : commands) {
            var output = runWindowEnumerator(command);
            if (output != null) {
                return new DesktopCompanionProtocol.Response(true, output,
                        isWindows() ? "application/json" : "text/plain", null, null);
            }
        }
        throw new IOException(isWindows()
                ? "Windows window enumeration helper is unavailable"
                : "window enumeration requires wmctrl or xdotool");
    }

    private static String runWindowEnumerator(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var bytes = readProcessOutput(process, MAX_WINDOW_METADATA_BYTES + 1, 5);
            if (bytes == null) {
                process.destroyForcibly();
                return null;
            }
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || bytes.length > MAX_WINDOW_METADATA_BYTES) return null;
            var output = new String(bytes, StandardCharsets.UTF_8).trim();
            return output.isBlank() ? "[]" : output;
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    /**
     * Read helper output without allowing a child that keeps stdout open to
     * block the request thread forever.  The read itself runs on one virtual
     * thread and is canceled by closing the pipe when the bounded wait expires;
     * no recurring watchdog or polling loop is introduced.
     */
    private static byte[] readProcessOutput(Process process, int maxBytes, long timeoutSeconds) {
        if (process == null || maxBytes < 1 || timeoutSeconds < 1) return null;
        var result = new CompletableFuture<byte[]>();
        var reader = Thread.startVirtualThread(() -> {
            try {
                result.complete(process.getInputStream().readNBytes(maxBytes));
            } catch (IOException exception) {
                result.completeExceptionally(exception);
            }
        });
        try {
            return result.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException exception) {
            return null;
        } finally {
            if (!result.isDone()) {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                reader.interrupt();
            }
        }
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

    private static final class SessionGuard {
        private final Semaphore semaphore = new Semaphore(1);
        private final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger();
    }

    private static String loadOrCreateToken(Path stateDir) throws IOException {
        var file = stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR).resolve(TOKEN_FILE);
        if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            String value;
            try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    StandardOpenOption.READ)) {
                var bytes = input.readNBytes(4097);
                if (bytes.length > 4096) throw new IOException("desktop companion token file is too large");
                value = new String(bytes, StandardCharsets.UTF_8).trim();
            }
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
            Files.write(temp, JsonCodec.write(new DesktopCompanionProtocol.Endpoint(port, token)));
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
    private static DesktopCompanionProtocol.Policy loadPolicy(Path stateDir) {
        try {
            return DesktopCompanionProtocol.readPolicy(stateDir);
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "could not load desktop companion scope policy; using a fail-closed local policy", failure);
            return new DesktopCompanionProtocol.Policy(ScopeMode.PATH, stateDir.toAbsolutePath().normalize().toString());
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

    private static void write(BufferedWriter writer, DesktopCompanionProtocol.Response response) throws IOException {
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

    private static void terminateProcessTree(ProcessHandle handle) {
        if (handle == null) return;
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
            // Process exit races are harmless; the onExit callback releases
            // the launch slot when the root eventually disappears.
        }
    }

    private static java.time.Duration configuredDuration(String key, java.time.Duration fallback,
                                                         java.time.Duration minimum, java.time.Duration maximum) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            var seconds = Long.parseLong(value.trim());
            var duration = java.time.Duration.ofSeconds(seconds);
            if (duration.compareTo(minimum) < 0 || duration.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(key + " is outside the allowed range");
            }
            return duration;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer number of seconds", exception);
        }
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " is outside the allowed range");
        return value;
    }

}
