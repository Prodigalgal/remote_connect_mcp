package com.prodigalgal.remotecontrolmcp.agent;

import com.prodigalgal.remotecontrolmcp.protocol.AgentMetadata;
import com.prodigalgal.remotecontrolmcp.protocol.JsonCodec;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Executable entrypoint for configuration checks, one-shot registration and runtime mode. */
public final class RemoteControlAgentApplication {
    private static final Logger LOG = Logger.getLogger(RemoteControlAgentApplication.class.getName());

    private RemoteControlAgentApplication() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--apply-update".equals(args[0])) {
            System.exit(AgentUpgradeHelper.run(args[1]));
            return;
        }
        if (args.length == 1 && "--check-config".equals(args[0])) {
            var config = AgentConfig.fromEnvironment();
            var metadata = config.metadata();
            // Exercise the same nested records used by heartbeat requests in
            // the actual Native binary, before a release can be published.
            var decoded = JsonCodec.read(JsonCodec.write(metadata), AgentMetadata.class);
            if (!metadata.equals(decoded)) {
                throw new IllegalStateException("Agent metadata changed during protocol round-trip");
            }
            var fileRequest = com.prodigalgal.remotecontrolmcp.protocol.FileRequest.from(java.util.Map.of(
                    "operation", "write", "path", "中文文件.txt", "content", "中文😀", "encoding", "UTF-16LE", "bom", true));
            if (!fileRequest.equals(JsonCodec.read(JsonCodec.write(fileRequest), com.prodigalgal.remotecontrolmcp.protocol.FileRequest.class))) {
                throw new IllegalStateException("Native file request changed during protocol round-trip");
            }
            // Catch missing legacy charsets in the Native binary before rollout.
            var legacyCharset = java.nio.charset.Charset.forName("GB18030");
            if (!"中文".equals(new String("中文".getBytes(legacyCharset), legacyCharset))) throw new IllegalStateException("Native GB18030 codec unavailable");
            // JSON and text codecs can work while the Native Image still has
            // an ASCII filename encoding inherited from its build container.
            var fileName = "中文😀文件.txt";
            if (!fileName.equals(java.nio.file.Path.of(fileName).getFileName().toString())) {
                throw new IllegalStateException("Native Unicode filesystem path changed during round-trip");
            }
            LOG.info(() -> "java agent configuration valid: name=" + metadata.name() + ", hostId=" + metadata.hostId()
                    + ", os=" + metadata.os() + ", arch=" + metadata.arch() + ", capabilities=" + metadata.capabilities()
                    + ", maxConcurrency=" + config.maxConcurrency() + ", maxBrowserWorkers=" + config.maxBrowserWorkers()
                    + ", maxOutputBytes=" + config.maxOutputBytes()
                    + ", maxAggregateOutputBytes=" + config.maxAggregateOutputBytes()
                    + ", filenameEncoding=" + System.getProperty("sun.jnu.encoding"));
            return;
        }

        if (args.length == 1 && "--register-once".equals(args[0])) {
            try {
                var config = AgentConfig.fromEnvironment();
                if (config.enrollmentToken() == null || config.enrollmentToken().isBlank()) {
                    throw new IllegalArgumentException("REMOTE_CONTROL_MCP_AGENT_ENROLLMENT_TOKEN is required for --register-once");
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
