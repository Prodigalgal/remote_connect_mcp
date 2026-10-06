package com.prodigalgal.remotecontrolmcp.desktop;

import com.prodigalgal.remotecontrolmcp.protocol.AgentUserContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Resolve the interactive user's desktop once at companion startup. */
final class DesktopUserContext {
    static AgentUserContext current() {
        var user = System.getProperty("user.name", "");
        var desktop = "";
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process process = null;
            try {
                var systemRoot = System.getenv("SystemRoot");
                if (systemRoot != null && !systemRoot.isBlank()) {
                    var executable = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
                    process = new ProcessBuilder(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                            "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); [Environment]::GetFolderPath('Desktop')")
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                        try (var output = process.getInputStream()) {
                            var bytes = output.readNBytes(4097);
                            if (bytes.length <= 4096) desktop = new String(bytes, StandardCharsets.UTF_8).trim();
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // An unavailable path is absent, never guessed from SYSTEM.
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        } else {
            var candidate = Path.of(System.getProperty("user.home", "."), "Desktop");
            if (Files.isDirectory(candidate)) desktop = candidate.toString();
        }
        try {
            return new AgentUserContext("", "", user, desktop);
        } catch (IllegalArgumentException invalid) {
            return new AgentUserContext("", "", "", "");
        }
    }
}
