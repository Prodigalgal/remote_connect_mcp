package com.prodigalgal.remoteconnectmcp.protocol;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded transport capability vocabulary shared by Agent and Center.
 * Business payloads never depend on one of these values; they only describe
 * how the same task contract reached the peer.  Unknown values are ignored so
 * an older Agent can safely talk to a newer Center.
 */
public final class TransportNegotiation {
    public static final String HEADER_CAPABILITIES = "X-RCM-Transport-Capabilities";
    public static final String HEADER_PREFERRED = "X-RCM-Transport-Preferred";
    public static final String HEADER_SELECTED = "X-RCM-Transport-Selected";

    public static final String HTTPS = "https";
    public static final String WEBSOCKET = "websocket";
    public static final String QUIC = "quic";

    /** Current reliable server path; QUIC is not advertised until a provider exists. */
    public static final String SERVER_CAPABILITIES = HTTPS + "," + WEBSOCKET;
    public static final String AGENT_CAPABILITIES = HTTPS + "," + WEBSOCKET;

    private static final Set<String> KNOWN = Set.of(HTTPS, WEBSOCKET, QUIC);
    private static final int MAX_HEADER_LENGTH = 128;
    private static final int MAX_CAPABILITIES = 8;

    private TransportNegotiation() {
    }

    /** Normalize a comma-separated header without echoing untrusted text. */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > MAX_HEADER_LENGTH) throw new IllegalArgumentException("transport capability header is too large");
        var values = new LinkedHashSet<String>();
        for (var raw : value.split(",")) {
            var candidate = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (candidate.isBlank()) continue;
            if (candidate.length() > 32 || !KNOWN.contains(candidate)) continue;
            values.add(candidate);
            if (values.size() >= MAX_CAPABILITIES) break;
        }
        return String.join(",", values);
    }

    /**
     * Select one server-supported capability from the client's offer.  The
     * current HTTP endpoint always has HTTPS fallback, even for an empty or
     * malformed offer.  QUIC remains opt-in and cannot be selected by default.
     */
    public static String select(String offered, String preferred, Set<String> serverSupported) {
        var supported = serverSupported == null ? Set.<String>of() : serverSupported;
        var offer = normalize(offered);
        var preference = normalize(preferred);
        for (var candidate : preference.isBlank() ? new String[0] : preference.split(",")) {
            if (offer.contains(candidate) && supported.contains(candidate)) return candidate;
        }
        for (var candidate : offer.isBlank() ? new String[0] : offer.split(",")) {
            if (supported.contains(candidate)) return candidate;
        }
        return supported.contains(HTTPS) ? HTTPS : "";
    }

    /** Return a canonical set for tests and server-side comparisons. */
    public static Set<String> asSet(String value) {
        var normalized = normalize(value);
        if (normalized.isBlank()) return Set.of();
        return Arrays.stream(normalized.split(",")).collect(Collectors.toUnmodifiableSet());
    }
}
