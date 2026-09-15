package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/** Atomic, restart-safe storage for the Center-issued Agent identity. */
public final class AgentIdentityStore {
    private static final String FILE_NAME = "identity.json";
    private final Path stateDir;

    public AgentIdentityStore(Path stateDir) {
        this.stateDir = stateDir.toAbsolutePath().normalize();
    }

    public Optional<AgentIdentity> load() throws IOException {
        var file = identityFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            var identity = JsonCodec.read(Files.readAllBytes(file), AgentIdentity.class);
            return Optional.of(identity);
        } catch (RuntimeException exception) {
            throw new IOException("invalid agent identity file: " + file, exception);
        }
    }

    public void save(AgentIdentity identity) throws IOException {
        if (identity == null) {
            throw new IllegalArgumentException("agent identity is required");
        }
        Files.createDirectories(stateDir);
        var temp = stateDir.resolve("." + FILE_NAME + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, JsonCodec.write(identity), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictToOwner(temp);
            try {
                Files.move(temp, identityFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, identityFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(identityFile());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void delete() throws IOException {
        Files.deleteIfExists(identityFile());
    }

    Path identityFile() {
        return stateDir.resolve(FILE_NAME);
    }

    private static void restrictToOwner(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs are applied by the installer; POSIX hosts get
            // an explicit 0600 file when the filesystem supports it.
        }
    }
}
