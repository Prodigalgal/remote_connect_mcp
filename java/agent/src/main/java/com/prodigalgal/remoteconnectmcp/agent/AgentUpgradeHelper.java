package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Detached, bounded updater transaction used by native/JAR Agent builds. */
final class AgentUpgradeHelper {
    // A Windows Native Image Agent executable is currently about 55 MiB and
    // will grow as capabilities are added. Keep a generous per-file ceiling
    // while bounding the complete uncompressed bundle to avoid zip bombs.
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_ARCHIVE_TOTAL_BYTES = 256L * 1024 * 1024;

    private AgentUpgradeHelper() {
    }

    static int run(String configPath) {
        Config config = null;
        try {
            config = JsonCodec.read(Files.readAllBytes(Path.of(configPath)), Config.class);
            validate(config);
            waitForParent(config.parentPid());
            stopService(config.serviceName());
            apply(config);
            startRuntime(config);
            cleanupAfterSuccess(config);
            writeResult(Path.of(config.stateDir()), new Result(config.campaignId(), "completed", null, Instant.now()));
            return 0;
        } catch (Exception exception) {
            var message = compactError(exception.getMessage());
            try {
                if (config != null) {
                    rollback(config);
                    startRuntime(config);
                    writeResult(Path.of(config.stateDir()), new Result(config.campaignId(), "failed", message, Instant.now()));
                }
            } catch (Exception ignored) {
                // Preserve the original failure for the next Agent heartbeat.
            }
            return 1;
        }
    }

    private static void apply(Config config) throws IOException {
        if (config.archive()) {
            applyArchive(config);
            return;
        }
        var staged = Path.of(config.staged()).toAbsolutePath().normalize();
        var target = Path.of(config.target()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(staged) || !Files.isRegularFile(target)) throw new IOException("upgrade staged or target file is missing");
        var next = target.resolveSibling("." + target.getFileName() + ".next");
        var backup = target.resolveSibling(target.getFileName() + ".previous");
        Files.deleteIfExists(next);
        Files.copy(staged, next, StandardCopyOption.REPLACE_EXISTING);
        move(target, backup);
        try {
            move(next, target);
        } catch (Exception exception) {
            move(backup, target);
            throw exception;
        }
        try {
            Files.setPosixFilePermissions(target, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (Exception ignored) {
        }
        writeVersion(config);
        Files.deleteIfExists(staged);
    }

    /**
     * Apply a platform Agent bundle. Native Image emits runtime DLLs on
     * Windows and shared objects on Linux beside the executable; replacing
     * only the executable would leave an incompatible or missing runtime. The
     * archive is extracted into a private staging folder, then every
     * root-level executable/runtime library is swapped with a rollback marker.
     */
    private static void applyArchive(Config config) throws IOException {
        var staged = Path.of(config.staged()).toAbsolutePath().normalize();
        var target = Path.of(config.target()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(staged) || !Files.isRegularFile(target)) throw new IOException("upgrade archive or target file is missing");
        var stateDir = Path.of(config.stateDir()).toAbsolutePath().normalize();
        Files.createDirectories(stateDir.resolve("upgrades"));
        var extraction = Files.createTempDirectory(stateDir.resolve("upgrades"), "agent-archive-");
        var names = new java.util.ArrayList<String>();
        long total = 0;
        try (var input = new java.util.zip.ZipInputStream(Files.newInputStream(staged))) {
            java.util.zip.ZipEntry entry;
            var buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                var name = entry.getName();
                if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                        || !isAgentBundleFile(name, target.getFileName().toString())) {
                    throw new IOException("Agent archive contains an unsafe file name");
                }
                if (names.stream().anyMatch(value -> value.equalsIgnoreCase(name))) {
                    throw new IOException("Agent archive contains duplicate files");
                }
                var destination = extraction.resolve(name).normalize();
                if (!destination.getParent().equals(extraction)) throw new IOException("Agent archive path escapes staging directory");
                long written = 0;
                try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        written += read;
                        total += read;
                        if (written > MAX_ARCHIVE_ENTRY_BYTES || total > MAX_ARCHIVE_TOTAL_BYTES) {
                            throw new IOException("Agent archive contains an oversized file");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                names.add(name);
                input.closeEntry();
            }
        } catch (Exception exception) {
            deleteTree(extraction);
            if (exception instanceof IOException io) throw io;
            throw new IOException("Agent archive extraction failed", exception);
        }
        var executableName = target.getFileName().toString();
        var executableEntries = names.stream()
                .filter(value -> isExecutableBundleFile(value, executableName))
                .toList();
        if (executableEntries.isEmpty()) {
            deleteTree(extraction);
            throw new IOException("Agent archive does not contain " + executableName + " or its canonical rcm-agent name");
        }
        if (executableEntries.size() > 1) {
            deleteTree(extraction);
            throw new IOException("Agent archive contains multiple Agent executables");
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        var manifest = stateDir.resolve("upgrade-files-" + safeComponent(config.campaignId()) + ".txt");
        var targetDir = target.getParent();
        var manifestLines = new java.util.ArrayList<String>();
        for (var name : names) {
            var installedName = installedFileName(name, executableName);
            var current = targetDir.resolve(installedName).normalize();
            // Persist the installed filename, not the archive filename.  This
            // matters when a Java Native Image archive uses canonical
            // `rcm-agent` but the existing service still points at the legacy
            // `remote-connect-mcp-agent` basename.
            manifestLines.add((Files.exists(current) ? "1" : "0") + "\t" + installedName);
        }
        try {
            Files.write(manifest, manifestLines, StandardCharsets.US_ASCII, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            deleteTree(extraction);
            throw exception;
        }
        try {
            for (var name : names) {
                var installedName = installedFileName(name, executableName);
                var current = targetDir.resolve(installedName).normalize();
                var backup = current.resolveSibling(current.getFileName() + ".previous");
                Files.deleteIfExists(backup);
                if (Files.exists(current)) {
                    move(current, backup);
                }
                move(extraction.resolve(name), current);
            }
            writeVersion(config);
            Files.deleteIfExists(staged);
            deleteTree(extraction);
        } catch (Exception exception) {
            rollbackArchive(target, manifest);
            deleteTree(extraction);
            if (exception instanceof IOException io) throw io;
            throw new IOException("Agent archive replacement failed", exception);
        }
    }

    private static void writeVersion(Config config) throws IOException {
        var stateDir = Path.of(config.stateDir()).toAbsolutePath().normalize();
        Files.createDirectories(stateDir);
        var versionFile = stateDir.resolve("agent-version");
        var previousVersionFile = stateDir.resolve("agent-version.previous");
        if (Files.isRegularFile(versionFile)) {
            Files.copy(versionFile, previousVersionFile, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(previousVersionFile);
        }
        var versionTemp = versionFile.resolveSibling(".agent-version-" + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(versionTemp, config.version() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        move(versionTemp, versionFile);
    }

    private static void cleanupAfterSuccess(Config config) {
        try {
            var target = Path.of(config.target()).toAbsolutePath().normalize();
            if (config.archive()) {
                var manifest = Path.of(config.stateDir()).toAbsolutePath().normalize()
                        .resolve("upgrade-files-" + safeComponent(config.campaignId()) + ".txt");
                if (Files.isRegularFile(manifest)) {
                    var targetDir = target.getParent();
                    for (var raw : Files.readAllLines(manifest, StandardCharsets.US_ASCII)) {
                        var fields = raw.split("\\t", 2);
                        var name = (fields.length == 2 ? fields[1] : fields[0]).trim();
                        if (name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                            Files.deleteIfExists(targetDir.resolve(name + ".previous").normalize());
                        }
                    }
                    Files.deleteIfExists(manifest);
                }
            } else {
                Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".previous"));
            }
            Files.deleteIfExists(Path.of(config.stateDir()).resolve("agent-version.previous"));
        } catch (Exception ignored) {
            // Cleanup is best effort; the current binary is already running.
        }
    }

    private static void rollback(Config config) {
        try {
            if (config.archive()) {
                rollbackArchive(Path.of(config.target()).toAbsolutePath().normalize(),
                        Path.of(config.stateDir()).toAbsolutePath().normalize()
                                .resolve("upgrade-files-" + safeComponent(config.campaignId()) + ".txt"));
                restorePreviousVersion(Path.of(config.stateDir()).toAbsolutePath().normalize());
                return;
            }
            var target = Path.of(config.target()).toAbsolutePath().normalize();
            var backup = target.resolveSibling(target.getFileName() + ".previous");
            if (Files.isRegularFile(backup)) move(backup, target);
            restorePreviousVersion(Path.of(config.stateDir()).toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
    }

    private static void restorePreviousVersion(Path stateDir) throws IOException {
        var versionFile = stateDir.resolve("agent-version");
        var previousVersionFile = stateDir.resolve("agent-version.previous");
        if (Files.isRegularFile(previousVersionFile)) move(previousVersionFile, versionFile);
        else Files.deleteIfExists(versionFile);
    }

    private static void rollbackArchive(Path target, Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) return;
        var targetDir = target.getParent();
        for (var raw : Files.readAllLines(manifest, StandardCharsets.US_ASCII)) {
            var fields = raw.split("\\t", 2);
            var existed = fields.length == 2 && "1".equals(fields[0]);
            var name = (fields.length == 2 ? fields[1] : fields[0]).trim();
            if (name.isBlank()) continue;
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) continue;
            var current = targetDir.resolve(name).normalize();
            if (!current.getParent().equals(targetDir)) continue;
            var backup = current.resolveSibling(current.getFileName() + ".previous");
            if (Files.isRegularFile(backup)) {
                Files.deleteIfExists(current);
                move(backup, current);
            } else if (!existed) {
                Files.deleteIfExists(current);
            }
        }
        Files.deleteIfExists(manifest);
    }

    private static String safeComponent(String value) {
        var result = value == null ? "upgrade" : value.replaceAll("[^A-Za-z0-9._-]", "");
        return result.isBlank() ? "upgrade" : result.substring(0, Math.min(80, result.length()));
    }

    private static boolean isAgentBundleFile(String name, String executableName) {
        if (isExecutableBundleFile(name, executableName)) return true;
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.matches(".*\\.so\\.[0-9]+(?:\\.[0-9]+)*");
    }

    private static boolean isExecutableBundleFile(String name, String targetName) {
        if (name.equalsIgnoreCase(targetName)) return true;
        return isLegacyTargetName(targetName) && name.equalsIgnoreCase(canonicalExecutableName());
    }

    private static String installedFileName(String archiveName, String targetName) {
        if (isLegacyTargetName(targetName) && archiveName.equalsIgnoreCase(canonicalExecutableName())) {
            return targetName;
        }
        return archiveName;
    }

    private static boolean isLegacyTargetName(String targetName) {
        var lower = targetName.toLowerCase(Locale.ROOT);
        return lower.equals("remote-connect-mcp-agent") || lower.equals("remote-connect-mcp-agent.exe");
    }

    private static String canonicalExecutableName() {
        return isWindows() ? "rcm-agent.exe" : "rcm-agent";
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        } catch (IOException ignored) { }
    }

    private static void waitForParent(long pid) throws InterruptedException, IOException {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return;
        var parent = ProcessHandle.of(pid).orElse(null);
        if (parent == null) return;
        try {
            parent.onExit().get(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IOException("previous Agent process did not stop within 45 seconds", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("could not observe previous Agent process", exception);
        }
    }

    private static void stopService(String service) throws IOException, InterruptedException {
        if (service == null || service.isBlank()) return;
        // A long-polling Agent can keep the unit in `deactivating` after the
        // systemctl command itself returns.  Waiting for the stop job avoids
        // replacing the native bundle while the old process still owns files
        // or the listening connection.
        runServiceCommand(isWindows() ? List.of("sc.exe", "stop", service) : List.of("systemctl", "--wait", "stop", service), false);
    }

    private static void startRuntime(Config config) throws IOException, InterruptedException {
        var service = config.serviceName();
        if (service != null && !service.isBlank()) {
            runServiceCommand(isWindows() ? List.of("sc.exe", "start", service) : List.of("systemctl", "--wait", "start", service), true);
            return;
        }
        // A manually launched `--run` Agent has no service manager to restart
        // it. Replace-and-relaunch keeps the same identity and state directory
        // while still allowing the helper to exit independently.
        var target = Path.of(config.target()).toAbsolutePath().normalize();
        var command = new java.util.ArrayList<String>();
        if (target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            command.add(System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator
                    + (isWindows() ? "java.exe" : "java"));
            command.add("-jar");
        }
        command.add(target.toString());
        command.add("--run");
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void runServiceCommand(List<String> command, boolean mustSucceed) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("service command timed out");
        }
        if (mustSucceed && process.exitValue() != 0) throw new IOException("service command failed with code " + process.exitValue());
    }

    private static void writeResult(Path stateDir, Result result) throws IOException {
        Files.createDirectories(stateDir);
        var target = stateDir.resolve("upgrade-result.json");
        var temp = stateDir.resolve(".upgrade-result-" + ProcessHandle.current().pid() + ".tmp");
        Files.write(temp, JsonCodec.write(result), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validate(Config config) throws IOException {
        if (config == null || config.campaignId() == null || config.campaignId().isBlank()) throw new IOException("invalid upgrade helper config");
        if (config.staged() == null || config.target() == null || config.stateDir() == null) throw new IOException("upgrade helper paths are missing");
        if (config.parentPid() <= 0) throw new IOException("upgrade helper parent pid is invalid");
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "Agent upgrade failed" : value.trim();
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }

    record Config(String campaignId, String version, String staged, String target, String stateDir,
                  String serviceName, long parentPid, boolean archive) {
        Config(String campaignId, String version, String staged, String target, String stateDir,
               String serviceName, long parentPid) {
            this(campaignId, version, staged, target, stateDir, serviceName, parentPid, false);
        }
    }

    record Result(String campaignId, String status, String error, Instant updatedAt) {
    }
}
