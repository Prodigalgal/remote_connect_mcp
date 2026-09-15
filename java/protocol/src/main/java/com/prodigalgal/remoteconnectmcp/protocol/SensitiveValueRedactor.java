package com.prodigalgal.remoteconnectmcp.protocol;

import java.util.regex.Pattern;

/**
 * Small, dependency-free redactor for values which may cross a task or
 * diagnostic boundary. It replaces credential-shaped values while preserving
 * the surrounding message so an operator can still understand a failure.
 */
public final class SensitiveValueRedactor {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie|token|password|passwd|secret|api[_-]?key|private[_-]?key|credential)\\b\\s*[\\\"']?\\s*(?:[:=]|=>)\\s*[\\\"']?)(?!Bearer\\b)([^\\\"'\\s,;}]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=-]+)");
    /* PEM labels are extensible (OpenSSH, RSA, EC, ...). Match the marker
       lines separately so an unusual label cannot leave the body in a log. */
    private static final Pattern PEM_BEGIN = Pattern.compile(
            "-----BEGIN[^\\r\\n]*PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEM_END = Pattern.compile(
            "-----END[^\\r\\n]*PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);

    private SensitiveValueRedactor() {
    }

    /** Return the input with likely credential values replaced by a marker. */
    public static String redact(String value) {
        if (value == null || value.isBlank()) return value;
        var result = redactPemBlocks(value);
        // Redact the bearer value before the generic assignment rule.  An
        // Authorization header often starts with "Bearer"; doing the generic
        // replacement first would hide only that word and leave the token.
        result = BEARER.matcher(result).replaceAll("$1[redacted]");
        return ASSIGNMENT.matcher(result).replaceAll("$1[redacted]");
    }

    /** Replace complete PEM private-key blocks; truncate an unterminated one. */
    private static String redactPemBlocks(String value) {
        var begin = PEM_BEGIN.matcher(value);
        var output = new StringBuilder(value.length());
        var cursor = 0;
        while (begin.find()) {
            var end = PEM_END.matcher(value);
            end.region(begin.end(), value.length());
            output.append(value, cursor, begin.start()).append("[private-key redacted]");
            if (!end.find()) {
                return output.toString();
            }
            cursor = end.end();
            begin.region(cursor, value.length());
        }
        return output.append(value, cursor, value.length()).toString();
    }
}
