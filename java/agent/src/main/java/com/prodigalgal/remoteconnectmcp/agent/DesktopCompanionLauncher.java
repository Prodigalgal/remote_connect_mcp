package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Compatibility launcher for the legacy {@code --desktop-companion} command.
 * The desktop AWT implementation now lives in a separate executable; the
 * command Agent only starts it and never links desktop classes into its
 * Native Image reachability graph.
 */
final class DesktopCompanionLauncher {
    private DesktopCompanionLauncher() {
    }

    static void run(Path stateDir) throws IOException, InterruptedException {
        var process = start(stateDir);
        if (!process.waitFor(365, TimeUnit.DAYS)) {
            process.destroyForcibly();
            throw new IOException("desktop companion did not exit within the supported lifetime");
        }
        if (process.exitValue() != 0) throw new IOException("desktop companion exited with code " + process.exitValue());
    }

    /** Start a companion without blocking the command Agent heartbeat loop. */
    static Process start(Path stateDir) throws IOException {
        var current = currentCommand().orElse(null);
        var configured = System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY");
        var target = configured == null || configured.isBlank()
                ? siblingDesktopBinary(current)
                : Optional.of(Path.of(configured.trim()).toAbsolutePath().normalize());
        if (target.isEmpty() || !Files.isRegularFile(target.get(), LinkOption.NOFOLLOW_LINKS)
                || (!isWindows() && !Files.isExecutable(target.get()))) {
            throw new IOException("desktop companion binary was not found; install rcm-desktop-companion and set REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY");
        }
        if (current != null && target.get().equals(current)) {
            throw new IOException("REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY points to the command Agent itself");
        }
        var command = new ArrayList<String>();
        command.add(target.get().toString());
        command.add("--desktop-companion");
        command.add(stateDir.toAbsolutePath().normalize().toString());
        return new ProcessBuilder(command).inheritIO().start();
    }

    private static Optional<Path> currentCommand() {
        return ProcessHandle.current().info().command()
                .map(value -> Path.of(value).toAbsolutePath().normalize());
    }

    private static Optional<Path> siblingDesktopBinary(Path current) {
        if (current == null || current.getParent() == null) return Optional.empty();
        var name = current.getFileName().toString();
        var lower = name.toLowerCase(Locale.ROOT);
        var replacement = lower.endsWith(".exe") ? "rcm-desktop-companion.exe" : "rcm-desktop-companion";
        var direct = current.resolveSibling(replacement);
        if (Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS)) return Optional.of(direct);
        return Optional.of(current.resolveSibling("desktop").resolve(replacement).normalize());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
