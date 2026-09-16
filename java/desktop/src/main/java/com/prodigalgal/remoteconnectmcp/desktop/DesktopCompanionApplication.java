package com.prodigalgal.remoteconnectmcp.desktop;

import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.nio.file.Path;
import java.util.Locale;

/** Standalone user-session entrypoint for the optional desktop companion. */
public final class DesktopCompanionApplication {
    private DesktopCompanionApplication() {
    }

    public static void main(String[] args) {
        if (!((args.length == 1 || args.length == 2) && "--desktop-companion".equals(args[0]))) {
            System.err.println("usage: rcm-desktop-companion --desktop-companion [state-dir]");
            System.exit(2);
            return;
        }
        var defaultStateDir = System.getenv().getOrDefault("REMOTE_CONNECT_MCP_AGENT_STATE_DIR",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? Path.of(System.getenv().getOrDefault("ProgramData", "."), "RemoteConnectMCPAgent").toString()
                        : "/var/lib/remote-connect-mcp-agent");
        var stateDir = args.length == 2 ? Path.of(args[1]) : Path.of(defaultStateDir);
        try {
            DesktopCompanionServer.run(stateDir);
        } catch (Exception exception) {
            System.err.println("desktop companion failed: " + compactError(exception.getMessage()));
            System.exit(1);
        }
    }

    private static String compactError(String value) {
        var message = value == null || value.isBlank() ? "unknown error" : SensitiveValueRedactor.redact(value.trim());
        return message.length() <= 4096 ? message : message.substring(0, 4096);
    }
}
