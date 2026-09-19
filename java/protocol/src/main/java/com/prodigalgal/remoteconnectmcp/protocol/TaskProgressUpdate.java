package com.prodigalgal.remoteconnectmcp.protocol;

/**
 * Bounded, model-friendly progress snapshot for a durable task.
 *
 * <p>Progress is advisory task metadata.  It never changes the task state
 * machine and it is always fenced by the same dispatch attempt as output and
 * terminal state updates.</p>
 */
public record TaskProgressUpdate(
        String phase,
        Integer percent,
        String message,
        Long current,
        Long total,
        String unit) {

    public TaskProgressUpdate {
        phase = normalize(phase, "phase", 64);
        message = normalize(message, "message", 1024);
        unit = normalize(unit, "unit", 32);
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("progress percent must be between 0 and 100");
        }
        if (current != null && current < 0) {
            throw new IllegalArgumentException("progress current must be non-negative");
        }
        if (total != null && total < 0) {
            throw new IllegalArgumentException("progress total must be non-negative");
        }
        if (current != null && total != null && total > 0 && current > total) {
            throw new IllegalArgumentException("progress current must not exceed total");
        }
        if (phase == null && percent == null && message == null && current == null && total == null && unit == null) {
            throw new IllegalArgumentException("at least one progress field is required");
        }
    }

    private static String normalize(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > maxLength || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("progress " + field + " is invalid");
        }
        return normalized;
    }
}
