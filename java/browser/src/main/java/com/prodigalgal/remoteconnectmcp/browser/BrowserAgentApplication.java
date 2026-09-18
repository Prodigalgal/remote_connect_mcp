package com.prodigalgal.remoteconnectmcp.browser;

import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Standalone, one-task Browser Agent process.  It deliberately has no Center
 * credentials or MCP surface: command-agent owns the Center identity and
 * starts this bounded child with the local Playwright/Patchright/Comoufox
 * adapter.  Keeping the process separate prevents browser dependencies from
 * inflating or destabilizing the command Native Image.
 */
public final class BrowserAgentApplication {
    private BrowserAgentApplication() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || !"--run-worker".equals(args[0])) {
            System.err.println("usage: rcm-browser-agent --run-worker");
            System.exit(2);
            return;
        }
        var adapter = System.getenv("REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER");
        if (adapter == null || adapter.isBlank()) {
            System.err.println("REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER is required");
            System.exit(2);
            return;
        }
        Process worker = null;
        try {
            var builder = new ProcessBuilder(shell(adapter.trim())).redirectErrorStream(true);
            cleanSensitiveEnvironment(builder.environment());
            worker = builder.start();
            var startedWorker = worker;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> terminate(startedWorker),
                    "rcm-browser-agent-worker-cleanup"));
            try (var output = worker.getInputStream()) {
                output.transferTo(System.out);
                System.out.flush();
            }
            var exitCode = worker.waitFor();
            System.exit(exitCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminate(worker);
            System.exit(130);
        } catch (Exception exception) {
            terminate(worker);
            System.err.println("browser adapter failed: " + compactError(exception.getMessage()));
            System.exit(1);
        }
    }

    private static List<String> shell(String command) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return List.of("/bin/sh", "-lc", command);
    }

    private static void cleanSensitiveEnvironment(java.util.Map<String, String> environment) {
        environment.keySet().removeIf(key -> {
            var upper = key.toUpperCase(Locale.ROOT);
            return upper.contains("TOKEN") || upper.contains("PASSWORD") || upper.contains("PASSWD")
                    || upper.contains("SECRET") || upper.contains("COOKIE") || upper.contains("AUTHORIZATION")
                    || upper.contains("API_KEY") || upper.contains("PRIVATE_KEY") || upper.contains("CREDENTIAL");
        });
    }

    private static void terminate(Process process) {
        if (process == null) return;
        try {
            process.descendants().forEach(child -> {
                child.destroy();
                if (child.isAlive()) child.destroyForcibly();
            });
        } catch (RuntimeException ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) process.destroyForcibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String compactError(String value) {
        var message = value == null || value.isBlank() ? "browser adapter failed" : SensitiveValueRedactor.redact(value.trim());
        return message.length() <= 4096 ? message : message.substring(0, 4096);
    }
}
