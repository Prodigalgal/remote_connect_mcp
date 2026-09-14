package com.prodigalgal.remoteconnectmcp.protocol;

import java.util.regex.Pattern;

/**
 * Small, dependency-free redactor for values which may cross a task or
 * diagnostic boundary. It replaces credential-shaped values while preserving
 * the surrounding message so an operator can still understand a failure.
 */
public final class SensitiveValueRedactor {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie|token|password|passwd|secret|api[_-]?key|private[_-]?key|credential)\\b\\s*(?:[:=]|=>)\\s*[\\\"']?)(?!Bearer\\b)([^\\\"'\\s,;}]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=-]+)");
    /*
     * Keep the label deliberately broad.  PEM labels are extensible (for
     * example, OPENSSH PRIVATE KEY and RSA PRIVATE KEY), and a restrictive
     * token-by-token expression can leave the body in diagnostics when a new
     * label is introduced.  The line boundaries prevent this from spanning
     * into an unrelated header while the lazy body stops at the first end
     * marker.
     */
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN[^\\r\\n]*PRIVATE KEY-----[\\s\\S]*?-----END[^\\r\\n]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE);

    private SensitiveValueRedactor() {
    }

    /** Return the input with likely credential values replaced by a marker. */
    public static String redact(String value) {
        if (value == null || value.isBlank()) return value;
        var result = PEM.matcher(value).replaceAll("[private-key redacted]");
        // Redact the bearer value before the generic assignment rule.  An
        // Authorization header often starts with "Bearer"; doing the generic
        // replacement first would hide only that word and leave the token.
        result = BEARER.matcher(result).replaceAll("$1[redacted]");
        return ASSIGNMENT.matcher(result).replaceAll("$1[redacted]");
    }
}
