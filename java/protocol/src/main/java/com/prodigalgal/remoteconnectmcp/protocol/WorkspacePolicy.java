package com.prodigalgal.remoteconnectmcp.protocol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Target-platform lexical workspace checks shared by Center and Agent.
 * Center cannot resolve a Windows path on a Linux host and cannot evaluate
 * target symlinks, so the Agent still performs the authoritative local check.
 */
public final class WorkspacePolicy {
    private WorkspacePolicy() {
    }

    /** Validate a requested target cwd against the Agent's advertised policy. */
    public static void validateRemote(ScopeMode mode, String osName, String root,
                                      String defaultCwd, String requested) {
        if (mode == null || !mode.bounded()) return;
        boolean windows = "windows".equalsIgnoreCase(osName);
        String normalizedRoot = normalize(root, windows);
        String normalizedBase = normalize(defaultCwd, windows);
        if (normalizedRoot.isBlank() || !absolute(normalizedRoot, windows)) {
            throw new IllegalArgumentException("workspace root must be an absolute path");
        }
        if (normalizedBase.isBlank() || !absolute(normalizedBase, windows)
                || !within(normalizedRoot, normalizedBase)) {
            throw new IllegalArgumentException("default cwd must be an absolute path inside workspace root");
        }
        String candidate = normalize(requested, windows);
        if (candidate.isBlank()) {
            candidate = normalizedBase;
        } else if (!absolute(candidate, windows)) {
            // A Windows drive-relative path (for example C:tmp) resolves
            // against process state rather than the advertised base.
            if (windows && candidate.length() >= 2 && candidate.charAt(1) == ':') {
                throw new IllegalArgumentException("working directory uses an unsupported drive-relative path");
            }
            candidate = normalize(join(normalizedBase, candidate), windows);
        }
        if (!within(normalizedRoot, candidate)) {
            throw new IllegalArgumentException("working directory is outside workspace root");
        }
    }

    private static String join(String base, String child) {
        if (base.endsWith("/")) return base + child;
        return base + "/" + child;
    }

    private static String normalize(String value, boolean windows) {
        if (value == null) return "";
        String raw = value.trim();
        if (windows) raw = raw.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (raw.isBlank()) return "";

        String prefix = "";
        boolean absolute = raw.startsWith("/");
        if (windows && raw.matches("^[a-z]:$")) {
            raw += "/";
            absolute = true;
        }
        if (windows && raw.matches("^[a-z]:/.*")) {
            prefix = raw.substring(0, 3);
            raw = raw.substring(3);
            absolute = true;
        } else if (raw.startsWith("//")) {
            prefix = "//";
            raw = raw.substring(2);
            absolute = true;
        } else if (raw.startsWith("/")) {
            prefix = "/";
            raw = raw.substring(1);
        }

        Deque<String> parts = new ArrayDeque<>();
        for (String part : raw.split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty() && !"..".equals(parts.peekLast())) {
                    parts.removeLast();
                } else if (!absolute) {
                    parts.addLast(part);
                }
            } else {
                parts.addLast(part);
            }
        }
        String body = String.join("/", parts);
        if (prefix.isEmpty()) return body;
        if (body.isEmpty()) return prefix;
        return prefix + body;
    }

    private static boolean absolute(String value, boolean windows) {
        if (value == null || value.isBlank()) return false;
        if (value.startsWith("/")) return true;
        return windows && value.length() >= 3 && value.charAt(1) == ':' && value.charAt(2) == '/';
    }

    private static boolean within(String root, String candidate) {
        if (root.equals("/")) return candidate.startsWith("/");
        if (root.equals("//")) return candidate.startsWith("//");
        return candidate.equals(root) || candidate.startsWith(root + "/");
    }
}
