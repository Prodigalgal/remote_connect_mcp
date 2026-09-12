package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Shared cwd resolution for command, desktop and browser execution. */
final class AgentPaths {
    private AgentPaths() {
    }

    static Path resolveCwd(AgentConfig config, String requested) throws IOException {
        var raw = requested == null || requested.isBlank() ? config.defaultCwd() : requested.trim();
        final Path input;
        try {
            input = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IOException("cwd is not a valid path", exception);
        }
        var candidate = input.isAbsolute() ? input : Path.of(config.defaultCwd()).resolve(input);
        candidate = candidate.toAbsolutePath().normalize();
        if (config.scopeMode() == ScopeMode.WORKSPACE) {
            var root = Path.of(config.workspaceRoot()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IOException("configured workspace root is not a directory: " + root);
            }
            var rootReal = root.toRealPath();
            candidate = resolveThroughExistingParents(candidate);
            if (!candidate.startsWith(rootReal)) {
                throw new IOException("cwd is outside the configured workspace");
            }
        }
        if (!Files.isDirectory(candidate)) {
            throw new IOException("cwd is not a directory: " + candidate);
        }
        return candidate;
    }

    /** Resolves symlinks/junctions in the existing prefix of a path. */
    private static Path resolveThroughExistingParents(Path candidate) throws IOException {
        var missing = new ArrayDeque<Path>();
        var existing = candidate;
        while (!Files.exists(existing)) {
            var name = existing.getFileName();
            if (name == null) {
                throw new IOException("cwd has no existing parent");
            }
            missing.addFirst(name);
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("cwd has no existing parent");
            }
        }
        var resolved = existing.toRealPath();
        for (var name : missing) {
            resolved = resolved.resolve(name);
        }
        return resolved.normalize();
    }
}
