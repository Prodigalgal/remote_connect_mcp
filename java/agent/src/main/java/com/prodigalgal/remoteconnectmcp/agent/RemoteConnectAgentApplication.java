package com.prodigalgal.remoteconnectmcp.agent;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Executable entrypoint for configuration checks, one-shot registration and runtime mode. */
public final class RemoteConnectAgentApplication {
    private static final Logger LOG = Logger.getLogger(RemoteConnectAgentApplication.class.getName());

    private RemoteConnectAgentApplication() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--apply-update".equals(args[0])) {
            System.exit(AgentUpgradeHelper.run(args[1]));
            return;
        }
        if ((args.length == 1 || args.length == 2) && "--desktop-companion".equals(args[0])) {
            try {
                var defaultStateDir = System.getenv().getOrDefault("REMOTE_CONNECT_MCP_AGENT_STATE_DIR",
                        System.getProperty("os.name", "").toLowerCase().contains("win")
                                ? Path.of(System.getenv().getOrDefault("ProgramData", "."), "RemoteConnectMCPAgent").toString()
                                : "/var/lib/remote-connect-mcp-agent");
                var stateDir = args.length == 2 ? Path.of(args[1]) : Path.of(defaultStateDir);
                DesktopCompanionServer.run(stateDir);
                return;
            } catch (Exception exception) {
                LOG.log(Level.SEVERE, "desktop companion failed", exception);
                System.exit(1);
            }
        }
        if (args.length == 1 && "--check-config".equals(args[0])) {
            var config = AgentConfig.fromEnvironment();
            var metadata = config.metadata();
            LOG.info(() -> "java agent configuration valid: name=" + metadata.name() + ", hostId=" + metadata.hostId()
                    + ", os=" + metadata.os() + ", arch=" + metadata.arch() + ", capabilities=" + metadata.capabilities()
                    + ", maxConcurrency=" + config.maxConcurrency() + ", maxOutputBytes=" + config.maxOutputBytes()
                    + ", maxAggregateOutputBytes=" + config.maxAggregateOutputBytes());
            return;
        }

        if (args.length == 1 && "--register-once".equals(args[0])) {
            try {
                var config = AgentConfig.fromEnvironment();
                if (config.enrollmentToken() == null || config.enrollmentToken().isBlank()) {
                    throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN is required for --register-once");
                }
                var response = new AgentTransportClient(config.centerUrl()).register(config);
                new AgentIdentityStore(config.stateDir()).save(new AgentIdentity(response.machineId(), response.token()));
                LOG.info(() -> "java agent registration succeeded: machineId=" + response.machineId());
                return;
            } catch (Exception exception) {
                LOG.log(Level.SEVERE, "java agent registration failed", exception);
                System.exit(1);
            }
        }

        // Service managers invoke the native binary without arguments in the
        // shipped Windows/Linux unit files. Treat that form as the durable
        // runtime entrypoint; explicit --run remains useful for diagnostics.
        if (args.length == 0 || (args.length == 1 && "--run".equals(args[0]))) {
            try {
                var config = AgentConfig.fromEnvironment();
                LOG.info(() -> "java agent heartbeat runtime starting: name=" + config.name() + ", center=" + config.centerUrl());
                new AgentRuntime(config, new AgentTransportClient(config.centerUrl())).run();
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOG.info("java agent heartbeat runtime stopped");
                return;
            } catch (Exception exception) {
                LOG.log(Level.SEVERE, "java agent heartbeat runtime failed", exception);
                System.exit(1);
            }
        }

        LOG.log(Level.WARNING, "usage: java -jar remote-connect-mcp-agent.jar --check-config|--register-once|--run|--desktop-companion [state-dir]|--apply-update <helper-config>");
    }
}
