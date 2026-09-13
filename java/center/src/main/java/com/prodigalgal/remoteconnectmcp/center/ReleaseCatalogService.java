package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Request-triggered GitHub Release catalog for the admin console.
 *
 * <p>The Center never runs a fixed discovery poller.  A console load or an
 * explicit refresh performs one bounded request and shares the result through
 * a short in-process cache.  If GitHub is temporarily unavailable, the last
 * successful catalog remains usable and is marked stale.</p>
 */
@Service
public final class ReleaseCatalogService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_API_PAGE = 100;
    private static final Pattern JAVA_TAG = Pattern.compile("^java-(v\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)$");
    private static final List<AssetCoordinate> EXPECTED_ASSETS = List.of(
            new AssetCoordinate("linux", "amd64"),
            new AssetCoordinate("linux", "arm64"),
            new AssetCoordinate("windows", "amd64"));

    private final UpgradeConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = Snapshot.empty();

    public ReleaseCatalogService(UpgradeConfig config) {
        this.config = config;
    }

    public CatalogView list(int limit, boolean includePrerelease) {
        var boundedLimit = Math.max(1, Math.min(MAX_API_PAGE, limit));
        var now = Instant.now();
        var current = snapshot;
        if (current.isFresh(now)) return current.view(boundedLimit, includePrerelease);
        synchronized (refreshLock) {
            current = snapshot;
            if (!current.isFresh(now)) {
                try {
                    snapshot = fetch(now);
                } catch (Exception ignored) {
                    // Keep the previous immutable snapshot.  The response
                    // carries a generic warning and never exposes a URL,
                    // token, or upstream exception details.
                    snapshot = current.markStale(now);
                }
            }
            return snapshot.view(boundedLimit, includePrerelease);
        }
    }

    private Snapshot fetch(Instant now) throws IOException, InterruptedException {
        if (config.releasesApiUrl().isBlank()) return Snapshot.unavailable(now);
        var endpoint = config.releasesApiUrl();
        if (!endpoint.contains("?")) endpoint += "?per_page=" + MAX_API_PAGE;
        var configuredUri = URI.create(config.releasesApiUrl());
        var request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "remote-connect-mcp-center")
                .GET()
                .build();
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = future.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IOException("release catalog request timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("release catalog request failed", exception.getCause());
        }
        if (response.statusCode() != 200 || !"https".equalsIgnoreCase(response.uri().getScheme())
                || !configuredUri.getHost().equalsIgnoreCase(response.uri().getHost())) {
            throw new IOException("release catalog response was not usable");
        }
        return new Snapshot(parse(response.body()), now, false, true);
    }

    private static List<ReleaseView> parse(byte[] body) {
        Map[] releases;
        try {
            releases = JsonCodec.read(body, Map[].class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("release catalog JSON is invalid", exception);
        }
        var result = new ArrayList<ReleaseView>();
        for (Map raw : releases) {
            if (raw == null) continue;
            var tag = text(raw.get("tag_name"));
            var matcher = JAVA_TAG.matcher(tag);
            if (!matcher.matches() || bool(raw.get("draft"))) continue;
            var version = matcher.group(1);
            var prerelease = bool(raw.get("prerelease"));
            var name = text(raw.get("name"));
            if (name.isBlank()) name = tag;
            var publishedAt = instant(raw.get("published_at"));
            var names = assetNames(raw.get("assets"));
            var assets = EXPECTED_ASSETS.stream().map(coordinate -> {
                var fileName = "remote-connect-mcp-agent-" + version + "-" + coordinate.os() + "-" + coordinate.arch() + ".zip";
                var checksum = fileName + ".sha256";
                return new ReleaseAssetView(coordinate.os(), coordinate.arch(), fileName,
                        names.contains(fileName), names.contains(checksum));
            }).toList();
            result.add(new ReleaseView(version, tag, safeName(name), publishedAt, prerelease, assets));
        }
        result.sort(Comparator.comparing(ReleaseView::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ReleaseView::version, Comparator.reverseOrder()));
        return List.copyOf(result);
    }

    private static Set<String> assetNames(Object value) {
        var result = new LinkedHashSet<String>();
        if (!(value instanceof Iterable<?> values)) return result;
        for (var entry : values) {
            if (entry instanceof Map<?, ?> map) {
                var name = text(map.get("name"));
                if (!name.isBlank() && name.length() <= 220) result.add(name);
            }
        }
        return result;
    }

    private static String safeName(String value) {
        var normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private static Instant instant(Object value) {
        var text = text(value);
        if (text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record AssetCoordinate(String os, String arch) {
    }

    private record Snapshot(List<ReleaseView> items, Instant refreshedAt, boolean stale, boolean available) {
        static Snapshot empty() {
            return new Snapshot(List.of(), null, true, false);
        }

        static Snapshot unavailable(Instant now) {
            return new Snapshot(List.of(), now, true, false);
        }

        boolean isFresh(Instant now) {
            return available && refreshedAt != null && refreshedAt.plus(CACHE_TTL).isAfter(now);
        }

        Snapshot markStale(Instant now) {
            return new Snapshot(items, refreshedAt, true, available);
        }

        CatalogView view(int limit, boolean includePrerelease) {
            var filtered = items.stream()
                    .filter(item -> includePrerelease || !item.prerelease())
                    .limit(limit)
                    .toList();
            var warning = stale ? (available ? "GitHub Release 暂时不可达，当前显示最近一次成功目录" : "GitHub Release 目录暂不可用") : "";
            return new CatalogView(filtered, refreshedAt, stale, available, warning);
        }
    }

    public record CatalogView(List<ReleaseView> items, Instant refreshedAt, boolean stale,
                              boolean available, String warning) {
    }

    public record ReleaseView(String version, String tag, String name, Instant publishedAt,
                              boolean prerelease, List<ReleaseAssetView> assets) {
    }

    public record ReleaseAssetView(String os, String arch, String fileName,
                                   boolean available, boolean checksumAvailable) {
    }
}
