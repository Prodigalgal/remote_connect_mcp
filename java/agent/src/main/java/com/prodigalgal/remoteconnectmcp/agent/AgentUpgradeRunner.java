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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
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
    private static final int COPY_BUFFER = 64 * 1024;

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final AgentTransport transport;
    private final UpgradePlan plan;
    private final Runnable requestShutdown;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
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
            var archive = bundleSuffix != null;
            staged = directory.resolve("agent-" + safeVersion + ".new" + (archive ? bundleSuffix : executableSuffix()));
            downloadVerified(plan.url(), plan.sha256(), staged);

            var target = resolveTargetBinary();
            var helperConfig = new AgentUpgradeHelper.Config(
                    plan.campaignId(), plan.version(), staged.toString(), target.toString(),
                    config.stateDir().toAbsolutePath().normalize().toString(), serviceName(),
                    ProcessHandle.current().pid(), archive, plan.attempt());
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
                    new UpgradeStatusRequest(plan.campaignId(), status, error, plan.attempt()));
            return null;
        });
    }

    private void downloadVerified(String rawUrl, String expected, Path destination)
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
            // Windows ACLs are applied by the service installer.
        }
    }

    private Path resolveTargetBinary() throws IOException {
        var configured = System.getenv("REMOTE_CONNECT_MCP_AGENT_BINARY_PATH");
        if (configured == null || configured.isBlank()) {
            throw new IOException("REMOTE_CONNECT_MCP_AGENT_BINARY_PATH is required for self-upgrade");
        }
        var target = Path.of(configured.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) throw new IOException("Agent binary path is not a file: " + target);
        return target;
    }

    private void launchHelper(Path target, Path configPath) throws IOException {
        var command = new java.util.ArrayList<String>();
        if (target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            command.add(System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator +
                    (isWindows() ? "java.exe" : "java"));
            command.add("-jar");
        }
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
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("upgrade URL must use HTTPS");
        if (value.sha256() == null || !value.sha256().trim().matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("upgrade SHA-256 is invalid");
    }

    private static String safeComponent(String value) {
        var result = value == null ? "upgrade" : value.replaceAll("[^A-Za-z0-9._-]", "");
        return result.isBlank() ? "upgrade" : result.substring(0, Math.min(80, result.length()));
    }

    private static String executableSuffix() { return isWindows() ? ".exe" : ""; }
    private static String archiveSuffix(String value) {
        if (value == null) return null;
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        var query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        if (normalized.endsWith(".zip")) return ".zip";
        return null;
    }
    private static String serviceName() { return System.getenv().getOrDefault("REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME", "").trim(); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "Agent upgrade failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
