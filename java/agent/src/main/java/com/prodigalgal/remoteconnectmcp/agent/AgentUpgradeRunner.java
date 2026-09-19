package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradePlan;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stages a Center-offered Agent binary and launches a detached helper.  The
 * helper performs the stop/replace/start transaction after this process exits;
 * the Agent itself never overwrites its currently executing file.
 */
final class AgentUpgradeRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(AgentUpgradeRunner.class.getName());
    // Native Image bundles are currently below 100 MiB, but the executable
    // and runtime DLL set must have room to grow without making a valid
    // release impossible to install. The helper applies a separate 256 MiB
    // uncompressed bundle ceiling.
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(15);
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final Duration DOWNLOAD_RETRY_DELAY = Duration.ofSeconds(2);
    private static final int COPY_BUFFER = 64 * 1024;

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final AgentTransport transport;
    private final UpgradePlan plan;
    private final Runnable requestShutdown;
    private final HttpClient http = HttpClient.newBuilder()
            // GitHub release redirects intermittently terminate HTTP/2 streams
            // on long Native Image ZIP downloads.  Keep the control plane
            // websocket/HTTPS paths unchanged, but use a deterministic HTTP/1.1
            // connection for the bounded upgrade artifact fetch.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    AgentUpgradeRunner(AgentConfig config, AgentIdentity identity, AgentTransport transport,
                       UpgradePlan plan, Runnable requestShutdown) {
        this.config = config;
        this.identity = identity;
        this.transport = transport;
        this.plan = plan;
        this.requestShutdown = requestShutdown == null ? () -> { } : requestShutdown;
    }

    @Override
    public void run() {
        Path staged = null;
        try {
            validatePlan(plan);
            report("downloading", null);
            var directory = config.stateDir().toAbsolutePath().normalize().resolve("upgrades");
            Files.createDirectories(directory);
            var safeVersion = safeComponent(plan.version());
            var bundleSuffix = archiveSuffix(plan.url());
            if (bundleSuffix == null) throw new IOException("Agent upgrade URL must reference a Native Image ZIP bundle");
            staged = directory.resolve("agent-" + safeVersion + ".new" + bundleSuffix);
            downloadVerified(plan.url(), plan.sha256(), staged);

            var componentConfigs = new ArrayList<AgentUpgradeHelper.ComponentConfig>();
            for (var component : plan.components()) {
                var componentTarget = resolveComponentTarget(component.component());
                var componentSuffix = archiveSuffix(component.url());
                if (componentSuffix == null) throw new IOException("component upgrade URL must reference a Native Image ZIP bundle");
                var componentStaged = directory.resolve(safeComponent(component.component()) + "-" + safeComponent(component.version())
                        + ".new" + componentSuffix);
                downloadVerified(component.url(), component.sha256(), componentStaged);
                componentConfigs.add(new AgentUpgradeHelper.ComponentConfig(component.component(), component.version(),
                        componentStaged.toString(), componentTarget.toString(), config.stateDir().toAbsolutePath().normalize().toString(),
                        componentServiceName(component.component()), component.restartPolicy()));
            }

            var target = resolveTargetBinary();
            var helperConfig = new AgentUpgradeHelper.Config(
                    plan.campaignId(), plan.version(), staged.toString(), target.toString(),
                    config.stateDir().toAbsolutePath().normalize().toString(), serviceName(),
                    ProcessHandle.current().pid(), plan.attempt(), componentConfigs);
            var configPath = directory.resolve("helper-" + safeVersion + ".json");
            writeConfig(configPath, helperConfig);
            report("installing", null);
            launchHelper(target, configPath);
            LOG.info(() -> "Agent upgrade helper launched for " + plan.version());
            requestShutdown.run();
        } catch (Exception exception) {
            var message = compactError(exception.getMessage());
            LOG.log(Level.WARNING, "Agent upgrade failed: " + message, exception);
            try {
                report("failed", message);
            } catch (Exception reportFailure) {
                LOG.log(Level.WARNING, "could not report Agent upgrade failure", reportFailure);
            }
        }
    }

    private void report(String status, String error) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "upgrade status " + plan.campaignId(), () -> {
            transport.reportUpgrade(identity.machineId(), identity.token(),
                    new UpgradeStatusRequest(plan.campaignId(), status, error, plan.attempt(), java.util.Map.of()));
            return null;
        });
    }

    private void downloadVerified(String rawUrl, String expected, Path destination)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                downloadVerifiedOnce(rawUrl, expected, destination);
                return;
            } catch (IOException exception) {
                last = exception;
                if (attempt == MAX_DOWNLOAD_ATTEMPTS || !isRetryableDownloadFailure(exception)) throw exception;
                LOG.log(Level.INFO, "retrying Agent upgrade download ({0}/{1}): {2}",
                        new Object[]{attempt + 1, MAX_DOWNLOAD_ATTEMPTS, compactError(exception.getMessage())});
                Thread.sleep(DOWNLOAD_RETRY_DELAY.toMillis());
            }
        }
        throw last == null ? new IOException("Agent upgrade download failed") : last;
    }

    private void downloadVerifiedOnce(String rawUrl, String expected, Path destination)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(rawUrl.trim()))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "remote-connect-mcp-agent-updater")
                .GET().build();
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = future.get(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IOException("Agent upgrade download timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Agent upgrade download failed", cause == null ? exception : cause);
        }
        if (response.statusCode() != 200) {
            try (var body = response.body()) { body.transferTo(OutputStream.nullOutputStream()); }
            throw new IOException("Agent upgrade endpoint returned HTTP " + response.statusCode());
        }
        if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
            try (var body = response.body()) { body.transferTo(OutputStream.nullOutputStream()); }
            throw new IOException("Agent upgrade redirected to a non-HTTPS URL");
        }

        var temporary = destination.resolveSibling("." + destination.getFileName() + ".download");
        Files.deleteIfExists(temporary);
        var digest = sha256Digest();
        long written = 0;
        try (var input = response.body(); var output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                written += read;
                if (written > MAX_ARTIFACT_BYTES) throw new IOException("Agent upgrade exceeds 256 MiB");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            output.flush();
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            if (exception instanceof IOException io) throw io;
            throw new IOException("Agent upgrade download failed", exception);
        }
        var actual = HexFormat.of().formatHex(digest.digest());
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(temporary);
            throw new IOException("Agent upgrade SHA-256 mismatch");
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.setPosixFilePermissions(destination, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (Exception ignored) {
            // Windows ACLs are applied by the Agent installer/startup task.
        }
    }

    private Path resolveTargetBinary() throws IOException {
        var configured = System.getenv("REMOTE_CONNECT_MCP_AGENT_BINARY_PATH");
        if (configured == null || configured.isBlank()) {
            throw new IOException("REMOTE_CONNECT_MCP_AGENT_BINARY_PATH is required for self-upgrade");
        }
        var target = Path.of(configured.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Agent binary path is not a file: " + target);
        if (target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Agent self-upgrade requires the installed Native Image executable");
        }
        return target;
    }

    private void launchHelper(Path target, Path configPath) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add(target.toString());
        command.add("--apply-update");
        command.add(configPath.toString());
        new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void writeConfig(Path target, AgentUpgradeHelper.Config value) throws IOException {
        var temp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.write(temp, JsonCodec.write(value), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validatePlan(UpgradePlan value) {
        if (value == null || value.campaignId() == null || value.campaignId().isBlank()) throw new IllegalArgumentException("upgrade campaign_id is required");
        if (value.version() == null || value.version().isBlank() || value.version().length() > 128) throw new IllegalArgumentException("upgrade version is invalid");
        var uri = URI.create(value.url() == null ? "" : value.url().trim());
        if (value.url() == null || value.url().isBlank() || value.url().length() > 4096
                || archiveSuffix(value.url()) == null
                || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("upgrade URL must be an HTTPS Native Image ZIP bundle");
        }
        if (value.sha256() == null || !value.sha256().trim().matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("upgrade SHA-256 is invalid");
        var names = new java.util.HashSet<String>();
        for (var component : value.components()) {
            if (component == null || component.component().isBlank() || !names.add(component.component())) {
                throw new IllegalArgumentException("upgrade component is duplicated or missing");
            }
            var componentUri = URI.create(component.url() == null ? "" : component.url().trim());
            if (component.url().isBlank() || component.url().length() > 4096
                    || archiveSuffix(component.url()) == null
                    || !"https".equalsIgnoreCase(componentUri.getScheme())
                    || componentUri.getHost() == null || componentUri.getUserInfo() != null
                    || componentUri.getFragment() != null
                    || !component.sha256().matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("upgrade component artifact is invalid");
            }
            var policy = component.restartPolicy() == null ? "" : component.restartPolicy().trim().toLowerCase(Locale.ROOT);
            if (!("drain-and-restart".equals(policy) || "restart".equals(policy) || "manual".equals(policy))) {
                throw new IllegalArgumentException("upgrade component restart policy is invalid");
            }
        }
    }

    private static boolean isRetryableDownloadFailure(IOException exception) {
        var message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("eof") || message.contains("reset") || message.contains("timed out")
                || message.contains("connect") || message.contains("closed") || message.contains("no bytes");
    }

    private Path resolveComponentTarget(String component) throws IOException {
        var key = component == null ? "" : component.trim().toLowerCase(Locale.ROOT);
        var env = switch (key) {
            case "desktop-companion", "desktop" -> "REMOTE_CONNECT_MCP_DESKTOP_BINARY_PATH";
            case "browser-agent", "browser" -> "REMOTE_CONNECT_MCP_BROWSER_BINARY_PATH";
            default -> "REMOTE_CONNECT_MCP_" + key.replace('-', '_').toUpperCase(Locale.ROOT) + "_BINARY_PATH";
        };
        var configured = System.getenv(env);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }

        // The installer keeps all component bundles beside the command-agent.
        // Resolve that immutable layout when an older installation did not
        // persist the optional component path variable.  This is the canonical
        // layout, not a second download or a legacy component name.
        var commandPath = System.getenv("REMOTE_CONNECT_MCP_AGENT_BINARY_PATH");
        if (commandPath != null && !commandPath.isBlank()) {
            var root = Path.of(commandPath.trim()).toAbsolutePath().normalize().getParent();
            if (root != null) {
                var executable = isWindows()
                        ? ("desktop-companion".equals(key) || "desktop".equals(key)
                        ? "rcm-desktop-companion.exe" : "browser-agent".equals(key) || "browser".equals(key)
                        ? "rcm-browser-agent.exe" : null)
                        : ("desktop-companion".equals(key) || "desktop".equals(key)
                        ? "rcm-desktop-companion" : "browser-agent".equals(key) || "browser".equals(key)
                        ? "rcm-browser-agent" : null);
                if (executable != null) return root.resolve("desktop".equals(key) || "desktop-companion".equals(key)
                        ? "desktop" : "browser").resolve(executable).normalize();
            }
        }
        throw new IOException(env + " is required for component upgrade");
    }

    private static String componentServiceName(String component) {
        var key = component == null ? "" : component.trim().toLowerCase(Locale.ROOT);
        var env = switch (key) {
            case "desktop-companion", "desktop" -> "REMOTE_CONNECT_MCP_DESKTOP_SERVICE_NAME";
            case "browser-agent", "browser" -> "REMOTE_CONNECT_MCP_BROWSER_SERVICE_NAME";
            default -> "REMOTE_CONNECT_MCP_" + key.replace('-', '_').toUpperCase(Locale.ROOT) + "_SERVICE_NAME";
        };
        return System.getenv().getOrDefault(env, "").trim();
    }

    private static String safeComponent(String value) {
        var result = value == null ? "upgrade" : value.replaceAll("[^A-Za-z0-9._-]", "");
        return result.isBlank() ? "upgrade" : result.substring(0, Math.min(80, result.length()));
    }

    private static String archiveSuffix(String value) {
        if (value == null) return null;
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        var query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        if (normalized.endsWith(".zip")) return ".zip";
        return null;
    }
    private static String serviceName() { return System.getenv().getOrDefault("REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME", "").trim(); }
    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "Agent upgrade failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
