package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeComponentPlan;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Reads the immutable component manifest published beside a GitHub release.
 * The manifest is an optional additive contract: legacy releases continue to
 * upgrade command-agent through the existing platform artifact only.
 */
@Service
public final class ReleaseManifestService {
    private static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern VERSION = Pattern.compile("v[0-9A-Za-z][0-9A-Za-z._+-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final Pattern COMPONENT = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);
    private final UpgradeConfig config;
    private final Map<String, CachedManifest> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ReleaseManifestService(UpgradeConfig config) {
        this.config = config;
    }

    /** Return component plans for one platform; no manifest means legacy mode. */
    public List<UpgradeComponentPlan> components(String version, String os, String arch) {
        var normalizedVersion = version == null ? "" : version.trim();
        var platform = (os == null ? "" : os.trim().toLowerCase(java.util.Locale.ROOT)) + "/"
                + (arch == null ? "" : arch.trim().toLowerCase(java.util.Locale.ROOT));
        if (!VERSION.matcher(normalizedVersion).matches()) return List.of();
        if (!("linux/amd64".equals(platform) || "linux/arm64".equals(platform) || "windows/amd64".equals(platform))) {
            return List.of();
        }
        var manifest = fetchCached(normalizedVersion);
        if (manifest == null || manifest.components() == null) return List.of();
        return manifest.components().stream()
                .filter(plan -> plan != null && platform.equals(plan.os() + "/" + plan.arch()))
                .toList();
    }

    /**
     * Return a compact platform-keyed manifest for the Console selector.  The
     * manifest is fetched once per request and filtered through the same
     * validation path used by upgrade campaign creation; callers never get an
     * untrusted GitHub JSON object directly.
     */
    public Map<String, List<UpgradeComponentPlan>> allComponents(String version) {
        var normalizedVersion = version == null ? "" : version.trim();
        if (!VERSION.matcher(normalizedVersion).matches()) return Map.of();
        var manifest = fetchCached(normalizedVersion);
        if (manifest == null || manifest.components() == null) return Map.of();
        var result = new LinkedHashMap<String, List<UpgradeComponentPlan>>();
        for (var plan : manifest.components()) {
            if (plan == null) continue;
            var platform = plan.os() + "/" + plan.arch();
            if (!("linux/amd64".equals(platform) || "linux/arm64".equals(platform)
                    || "windows/amd64".equals(platform))) continue;
            result.computeIfAbsent(platform, ignored -> new java.util.ArrayList<>()).add(plan);
        }
        var immutable = new LinkedHashMap<String, List<UpgradeComponentPlan>>();
        result.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private Manifest fetch(String version) {
        if (config.releaseBaseUrl().isBlank()) return null;
        var tag = config.releaseTagPrefix() + version;
        var url = config.releaseBaseUrl() + "/" + tag + "/remote-connect-mcp-manifest-" + version + ".json";
        try {
            var uri = URI.create(url);
            var request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "remote-connect-mcp-center")
                    .GET().build();
            var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response;
            try {
                response = future.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                return null;
            } catch (java.util.concurrent.ExecutionException exception) {
                return null;
            }
            if (response.statusCode() != 200 || !"https".equalsIgnoreCase(response.uri().getScheme())
                    || !uri.getHost().equalsIgnoreCase(response.uri().getHost())
                    || response.body() == null || response.body().length > MAX_MANIFEST_BYTES) return null;
            var raw = JsonCodec.read(response.body(), ManifestWire.class);
            return normalize(raw, version);
        } catch (IOException | RuntimeException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Manifest fetchCached(String version) {
        var now = java.time.Instant.now();
        var cached = cache.get(version);
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.manifest();
        var fetched = fetch(version);
        var value = fetched == null ? new Manifest(version, List.of()) : fetched;
        if (cache.size() >= 16) cache.clear();
        cache.put(version, new CachedManifest(value, now.plus(CACHE_TTL)));
        return value;
    }

    private static Manifest normalize(ManifestWire raw, String requestedVersion) {
        if (raw == null || raw.components() == null || !requestedVersion.equals(raw.version())) {
            return new Manifest(raw == null ? "" : raw.version(), List.of());
        }
        var result = new java.util.ArrayList<UpgradeComponentPlan>();
        for (var plan : raw.components()) {
            if (plan == null || plan.component() == null || !COMPONENT.matcher(plan.component()).matches()) continue;
            if (plan.version() == null || plan.version().isBlank() || !VERSION.matcher(plan.version()).matches()) continue;
            if (!("linux".equals(plan.os()) || "windows".equals(plan.os()))) continue;
            if (!("amd64".equals(plan.arch()) || "arm64".equals(plan.arch()))) continue;
            if (plan.url() == null || plan.url().isBlank() || !SHA256.matcher(plan.sha256()).matches()) continue;
            try {
                var url = URI.create(plan.url());
                if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                        || url.getUserInfo() != null || url.getFragment() != null) continue;
                result.add(plan);
            } catch (IllegalArgumentException ignored) {
                // Ignore one malformed optional component; command-agent can
                // still upgrade and the operator sees the missing component.
            }
        }
        return new Manifest(raw.version() == null ? "" : raw.version(), List.copyOf(result));
    }

    record ManifestWire(String version, List<UpgradeComponentPlan> components) { }
    private record Manifest(String version, List<UpgradeComponentPlan> components) { }
    private record CachedManifest(Manifest manifest, java.time.Instant expiresAt) { }
}
