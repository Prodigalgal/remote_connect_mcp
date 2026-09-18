package com.prodigalgal.remoteconnectmcp.agent;

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
        if (args.length == 1 && "--check-config".equals(args[0])) {
            var config = AgentConfig.fromEnvironment();
            var metadata = config.metadata();
            LOG.info(() -> "java agent configuration valid: name=" + metadata.name() + ", hostId=" + metadata.hostId()
                    + ", os=" + metadata.os() + ", arch=" + metadata.arch() + ", capabilities=" + metadata.capabilities()
                    + ", maxConcurrency=" + config.maxConcurrency() + ", maxBrowserWorkers=" + config.maxBrowserWorkers()
                    + ", maxOutputBytes=" + config.maxOutputBytes()
                    + ", maxAggregateOutputBytes=" + config.maxAggregateOutputBytes());
            return;
        }

        if (args.length == 1 && "--register-once".equals(args[0])) {
            try {
                var config = AgentConfig.fromEnvironment();
                if (config.enrollmentToken() == null || config.enrollmentToken().isBlank()) {
                    throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN is required for --register-once");
                }
                var response = new AgentTransportClient(config.centerUrl(), config.longPollSeconds(), config.transferTimeout(), config.transferStallTimeout()).register(config);
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
                new AgentRuntime(config, new AgentTransportClient(config.centerUrl(), config.longPollSeconds(), config.transferTimeout(), config.transferStallTimeout())).run();
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

        LOG.log(Level.WARNING, "usage: rcm-agent --check-config|--register-once|--run|--apply-update <helper-config>");
    }
}
