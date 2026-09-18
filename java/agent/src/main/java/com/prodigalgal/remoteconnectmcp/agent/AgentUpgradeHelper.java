package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Detached, bounded updater transaction used by the Native Image Agent bundle. */
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
            var configFile = Path.of(configPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(configFile) > 64 * 1024) {
                throw new IOException("upgrade helper config is not a regular bounded file");
            }
            config = JsonCodec.read(Files.readAllBytes(configFile), Config.class);
            validate(config);
            waitForParent(config.parentPid());
            stopService(config.serviceName());
            applyArchive(config);
            startRuntime(config);
            var componentFailures = new ArrayList<String>();
            var componentStatuses = new java.util.LinkedHashMap<String, String>();
            for (var component : config.components()) {
                try {
                    if (!"manual".equalsIgnoreCase(component.restartPolicy())) {
                        stopService(component.serviceName());
                    }
                    applyComponentArchive(component);
                    if (!"manual".equalsIgnoreCase(component.restartPolicy())) {
                        startComponent(component);
                    }
                    cleanupComponent(component);
                    componentStatuses.put(component.component(), "completed");
                } catch (Exception componentFailure) {
                    var message = component.component() + ": " + compactError(componentFailure.getMessage());
                    componentFailures.add(message);
                    componentStatuses.put(component.component(), "failed");
                    try {
                        rollbackComponent(component);
                        if (!"manual".equalsIgnoreCase(component.restartPolicy())) startComponent(component);
                    } catch (Exception ignored) {
                        // Keep the original component failure; the next
                        // campaign retry will inspect the component marker.
                    }
                }
            }
            cleanupAfterSuccess(config);
            if (componentFailures.isEmpty()) {
                writeResult(Path.of(config.stateDir()), new Result(config.campaignId(), "completed", null, Instant.now(), config.attempt(), componentStatuses));
                return 0;
            }
            writeResult(Path.of(config.stateDir()), new Result(config.campaignId(), "failed",
                    String.join("; ", componentFailures), Instant.now(), config.attempt(), componentStatuses));
            return 1;
        } catch (Exception exception) {
            var message = compactError(exception.getMessage());
            try {
                if (config != null) {
                    rollback(config);
                    startRuntime(config);
                    writeResult(Path.of(config.stateDir()), new Result(config.campaignId(), "failed", message, Instant.now(), config.attempt()));
                }
            } catch (Exception ignored) {
                // Preserve the original failure for the next Agent heartbeat.
            }
            return 1;
        }
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
        if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("upgrade archive or target file is missing");
        var stateDir = Path.of(config.stateDir()).toAbsolutePath().normalize();
        Files.createDirectories(stateDir.resolve("upgrades"));
        var extraction = Files.createTempDirectory(stateDir.resolve("upgrades"), "agent-archive-");
        var names = new java.util.ArrayList<String>();
        long total = 0;
        try (var input = new java.util.zip.ZipInputStream(Files.newInputStream(staged, LinkOption.NOFOLLOW_LINKS))) {
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
            // Persist the installed filename so the component service always
            // points at the canonical bundle layout.
            manifestLines.add((Files.exists(current, LinkOption.NOFOLLOW_LINKS) ? "1" : "0") + "\t" + installedName);
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
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    move(current, backup);
                }
                move(extraction.resolve(name), current);
                // Zip entries do not reliably carry POSIX mode bits.  The
                // extracted Native Image executable otherwise lands as 0600
                // under the Agent's restrictive umask and systemd reports
                // 203/EXEC after the service restart.  Match the installer
                // bundle permissions for every swapped runtime file.
                setBundlePermissions(current);
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

    /** Apply one companion bundle without touching command-agent state. */
    private static void applyComponentArchive(ComponentConfig component) throws IOException {
        var staged = Path.of(component.staged()).toAbsolutePath().normalize();
        var target = Path.of(component.target()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("component archive or target file is missing");
        }
        var stateDir = Path.of(component.stateDir()).toAbsolutePath().normalize();
        Files.createDirectories(stateDir.resolve("upgrades"));
        var extraction = Files.createTempDirectory(stateDir.resolve("upgrades"), "component-archive-");
        var names = new ArrayList<String>();
        long total = 0;
        try (var input = new java.util.zip.ZipInputStream(Files.newInputStream(staged, LinkOption.NOFOLLOW_LINKS))) {
            var buffer = new byte[64 * 1024];
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                var name = entry.getName();
                if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                        || !isComponentBundleFile(name, target.getFileName().toString(), component.component())) {
                    throw new IOException("component archive contains an unsafe file name");
                }
                if (names.stream().anyMatch(value -> value.equalsIgnoreCase(name))) {
                    throw new IOException("component archive contains duplicate files");
                }
                var destination = extraction.resolve(name).normalize();
                if (!destination.getParent().equals(extraction)) throw new IOException("component archive escapes staging");
                long written = 0;
                try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        written += read;
                        total += read;
                        if (written > MAX_ARCHIVE_ENTRY_BYTES || total > MAX_ARCHIVE_TOTAL_BYTES) {
                            throw new IOException("component archive contains an oversized file");
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
            throw new IOException("component archive extraction failed", exception);
        }
        var executableEntries = names.stream().filter(value -> isComponentExecutableFile(value, target.getFileName().toString(), component.component())).toList();
        if (executableEntries.size() != 1) {
            deleteTree(extraction);
            throw new IOException("component archive must contain exactly one target executable");
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        var manifest = stateDir.resolve("component-upgrade-files-" + safeComponent(component.component()) + ".txt");
        var targetDir = target.getParent();
        var lines = new ArrayList<String>();
        for (var name : names) {
            var installedName = installedComponentFileName(name, target.getFileName().toString(), component.component());
            var current = targetDir.resolve(installedName).normalize();
            lines.add((Files.exists(current, LinkOption.NOFOLLOW_LINKS) ? "1" : "0") + "\t" + installedName);
        }
        Files.write(manifest, lines, StandardCharsets.US_ASCII, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            for (var name : names) {
                var installedName = installedComponentFileName(name, target.getFileName().toString(), component.component());
                var current = targetDir.resolve(installedName).normalize();
                var backup = current.resolveSibling(current.getFileName() + ".previous");
                Files.deleteIfExists(backup);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) move(current, backup);
                move(extraction.resolve(name), current);
                setBundlePermissions(current);
            }
            writeComponentVersion(component);
            Files.deleteIfExists(staged);
            deleteTree(extraction);
        } catch (Exception exception) {
            rollbackComponentArchive(component);
            deleteTree(extraction);
            if (exception instanceof IOException io) throw io;
            throw new IOException("component archive replacement failed", exception);
        }
    }

    private static void writeVersion(Config config) throws IOException {
        var stateDir = Path.of(config.stateDir()).toAbsolutePath().normalize();
        Files.createDirectories(stateDir);
        var versionFile = stateDir.resolve("agent-version");
        var previousVersionFile = stateDir.resolve("agent-version.previous");
        if (Files.isRegularFile(versionFile, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(versionFile, previousVersionFile, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(previousVersionFile);
        }
        var versionTemp = versionFile.resolveSibling(".agent-version-" + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(versionTemp, config.version() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        move(versionTemp, versionFile);
    }

    private static void writeComponentVersion(ComponentConfig component) throws IOException {
        var stateDir = Path.of(component.stateDir()).toAbsolutePath().normalize();
        Files.createDirectories(stateDir);
        var versionFile = stateDir.resolve(safeComponent(component.component()) + "-version");
        var previous = stateDir.resolve(safeComponent(component.component()) + "-version.previous");
        if (Files.isRegularFile(versionFile, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(versionFile, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        var temp = versionFile.resolveSibling("." + versionFile.getFileName() + "-" + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(temp, component.version() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        move(temp, versionFile);
    }

    private static void cleanupAfterSuccess(Config config) {
        try {
            var target = Path.of(config.target()).toAbsolutePath().normalize();
            var manifest = Path.of(config.stateDir()).toAbsolutePath().normalize()
                    .resolve("upgrade-files-" + safeComponent(config.campaignId()) + ".txt");
            if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
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
            Files.deleteIfExists(Path.of(config.stateDir()).resolve("agent-version.previous"));
        } catch (Exception ignored) {
            // Cleanup is best effort; the current binary is already running.
        }
    }

    private static void rollback(Config config) {
        try {
            rollbackArchive(Path.of(config.target()).toAbsolutePath().normalize(),
                    Path.of(config.stateDir()).toAbsolutePath().normalize()
                            .resolve("upgrade-files-" + safeComponent(config.campaignId()) + ".txt"));
            restorePreviousVersion(Path.of(config.stateDir()).toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
    }

    private static void rollbackComponent(ComponentConfig component) {
        try {
            var manifest = Path.of(component.stateDir()).toAbsolutePath().normalize()
                    .resolve("component-upgrade-files-" + safeComponent(component.component()) + ".txt");
            var target = Path.of(component.target()).toAbsolutePath().normalize();
            rollbackComponentArchive(target, manifest);
            var stateDir = Path.of(component.stateDir()).toAbsolutePath().normalize();
            var version = stateDir.resolve(safeComponent(component.component()) + "-version");
            var previous = stateDir.resolve(safeComponent(component.component()) + "-version.previous");
            if (Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS)) move(previous, version);
        } catch (Exception ignored) {
        }
    }

    private static void cleanupComponent(ComponentConfig component) {
        try {
            var targetDir = Path.of(component.target()).toAbsolutePath().normalize().getParent();
            var manifest = Path.of(component.stateDir()).toAbsolutePath().normalize()
                    .resolve("component-upgrade-files-" + safeComponent(component.component()) + ".txt");
            if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                for (var raw : Files.readAllLines(manifest, StandardCharsets.US_ASCII)) {
                    var fields = raw.split("\t", 2);
                    var name = (fields.length == 2 ? fields[1] : fields[0]).trim();
                    if (name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                        Files.deleteIfExists(targetDir.resolve(name + ".previous").normalize());
                    }
                }
                Files.deleteIfExists(manifest);
            }
            Files.deleteIfExists(Path.of(component.stateDir()).toAbsolutePath().normalize()
                    .resolve(safeComponent(component.component()) + "-version.previous"));
        } catch (Exception ignored) {
        }
    }

    private static void restorePreviousVersion(Path stateDir) throws IOException {
        var versionFile = stateDir.resolve("agent-version");
        var previousVersionFile = stateDir.resolve("agent-version.previous");
        if (Files.isRegularFile(previousVersionFile, LinkOption.NOFOLLOW_LINKS)) move(previousVersionFile, versionFile);
        else Files.deleteIfExists(versionFile);
    }

    private static void rollbackArchive(Path target, Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return;
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
            if (Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(current);
                move(backup, current);
            } else if (!existed) {
                Files.deleteIfExists(current);
            }
        }
        Files.deleteIfExists(manifest);
    }

    private static void rollbackComponentArchive(ComponentConfig component) throws IOException {
        var target = Path.of(component.target()).toAbsolutePath().normalize();
        var manifest = Path.of(component.stateDir()).toAbsolutePath().normalize()
                .resolve("component-upgrade-files-" + safeComponent(component.component()) + ".txt");
        rollbackComponentArchive(target, manifest);
    }

    private static void rollbackComponentArchive(Path target, Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return;
        var targetDir = target.getParent();
        for (var raw : Files.readAllLines(manifest, StandardCharsets.US_ASCII)) {
            var fields = raw.split("\t", 2);
            var existed = fields.length == 2 && "1".equals(fields[0]);
            var name = (fields.length == 2 ? fields[1] : fields[0]).trim();
            if (name.isBlank() || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) continue;
            var current = targetDir.resolve(name).normalize();
            if (!current.getParent().equals(targetDir)) continue;
            var backup = current.resolveSibling(current.getFileName() + ".previous");
            if (Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
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

    private static boolean isComponentBundleFile(String name, String executableName, String component) {
        if (isComponentExecutableFile(name, executableName, component)) return true;
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so")
                || lower.matches(".*\\.so\\.[0-9]+(?:\\.[0-9]+)*")
                || lower.endsWith(".dylib") || lower.endsWith(".node")
                || lower.endsWith(".json") || lower.endsWith(".js")
                || lower.endsWith(".mjs") || lower.endsWith(".cjs");
    }

    private static boolean isComponentExecutableFile(String name, String executableName, String component) {
        if (isExecutableBundleFile(name, executableName)) return true;
        var normalized = component == null ? "" : component.replace('-', '_').toLowerCase(Locale.ROOT);
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("rcm-" + normalized + (isWindows() ? ".exe" : ""))
                || lower.equals(normalized + (isWindows() ? ".exe" : ""));
    }

    private static boolean isExecutableBundleFile(String name, String targetName) {
        return name.equalsIgnoreCase(targetName);
    }

    private static String installedFileName(String archiveName, String targetName) {
        return archiveName;
    }

    private static String installedComponentFileName(String archiveName, String targetName, String component) {
        return isComponentExecutableFile(archiveName, targetName, component) ? targetName : archiveName;
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
        // systemctl command itself returns.  systemd has no --wait option for
        // stop, so wait for ActiveState=inactive before replacing the bundle.
        if (isWindows()) {
            // GraalVM Native Image binaries are console executables and are
            // supervised by the canonical Task Scheduler task.
            if (windowsTaskExists(service)) {
                // The helper itself is launched by the Agent task.  Calling
                // `schtasks /End` here could terminate this detached helper
                // together with the task action.  waitForParent() above has
                // already observed the Agent exit, so only wait for the
                // PowerShell launcher/task action to drain naturally.
                waitWindowsTaskStopped(service);
            } else {
                throw new IOException("Windows Agent task is missing: " + service);
            }
        } else {
            runServiceCommand(List.of("systemctl", "stop", service), false);
            waitUnixServiceState(service, "inactive");
        }
    }

    private static void startRuntime(Config config) throws IOException, InterruptedException {
        var service = config.serviceName();
        if (service != null && !service.isBlank()) {
            if (isWindows()) {
                if (windowsTaskExists(service)) {
                    runServiceCommand(List.of("schtasks.exe", "/Run", "/TN", service), true);
                    waitWindowsTaskRunning(service);
                } else {
                    throw new IOException("Windows Agent task is missing: " + service);
                }
            } else {
                runServiceCommand(List.of("systemctl", "start", service), true);
                waitUnixServiceState(service, "active");
            }
            return;
        }
        // A manually launched `--run` Agent has no service manager to restart
        // it. Replace-and-relaunch keeps the same identity and state directory
        // while still allowing the helper to exit independently.
        var target = Path.of(config.target()).toAbsolutePath().normalize();
        var command = new java.util.ArrayList<String>();
        command.add(target.toString());
        command.add("--run");
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void startComponent(ComponentConfig component) throws IOException, InterruptedException {
        var service = component.serviceName();
        if (service == null || service.isBlank()) return;
        if (isWindows()) {
            if (windowsTaskExists(service)) {
                runServiceCommand(List.of("schtasks.exe", "/Run", "/TN", service), true);
                waitWindowsTaskRunning(service);
            } else {
                throw new IOException("Windows component task is missing: " + service);
            }
        } else {
            runServiceCommand(List.of("systemctl", "start", service), true);
            waitUnixServiceState(service, "active");
        }
    }

    private static void runServiceCommand(List<String> command, boolean mustSucceed) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("service command timed out");
        }
        if (mustSucceed && process.exitValue() != 0) throw new IOException("service command failed with code " + process.exitValue());
    }

    private static boolean windowsTaskExists(String task) throws IOException, InterruptedException {
        var process = new ProcessBuilder("schtasks.exe", "/Query", "/TN", task)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return false;
        }
        return process.exitValue() == 0;
    }

    private static boolean windowsTaskRunning(String task) throws IOException, InterruptedException {
        var process = new ProcessBuilder("schtasks.exe", "/Query", "/TN", task, "/XML")
                .redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return false;
        }
        var output = decodeWindowsOutput(process.getInputStream().readAllBytes());
        return process.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("<state>running</state>");
    }

    private static String decodeWindowsOutput(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 2 && ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe
                || ((bytes[1] & 0xff) == 0 && bytes[0] != 0))) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void waitWindowsTaskStopped(String task) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (true) {
            if (!windowsTaskExists(task) || !windowsTaskRunning(task)) return;
            if (System.nanoTime() >= deadline) throw new IOException("task " + task + " did not become stopped");
            Thread.sleep(250L);
        }
    }

    private static void waitWindowsTaskRunning(String task) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (true) {
            if (windowsTaskRunning(task)) return;
            if (System.nanoTime() >= deadline) throw new IOException("task " + task + " did not become running");
            Thread.sleep(250L);
        }
    }

    private static void waitUnixServiceState(String service, String expected) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (true) {
            var process = new ProcessBuilder("systemctl", "is-active", service).redirectErrorStream(true).start();
            var finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            } else {
                var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (expected.equals(output)) return;
            }
            if (System.nanoTime() >= deadline) throw new IOException("service " + service + " did not become " + expected);
            Thread.sleep(250L);
        }
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

    private static void setBundlePermissions(Path path) throws IOException {
        if (isWindows()) return;
        Files.setPosixFilePermissions(path, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
    }

    private static void validate(Config config) throws IOException {
        if (config == null || config.campaignId() == null || config.campaignId().isBlank()) throw new IOException("invalid upgrade helper config");
        if (config.staged() == null || config.target() == null || config.stateDir() == null) throw new IOException("upgrade helper paths are missing");
        if (!config.staged().toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IOException("upgrade helper accepts only Native Image ZIP bundles");
        if (config.target().toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IOException("upgrade helper requires a Native Image executable target");
        if (config.parentPid() <= 0) throw new IOException("upgrade helper parent pid is invalid");
        for (var component : config.components()) {
            if (component == null || component.component() == null || !component.component().matches("[a-z][a-z0-9-]{1,63}")) {
                throw new IOException("invalid component upgrade name");
            }
            if (component.staged() == null || component.target() == null || component.stateDir() == null) {
                throw new IOException("component upgrade paths are missing");
            }
            if (!component.staged().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw new IOException("component upgrade accepts only Native Image ZIP bundles");
            }
            if (component.target().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IOException("component upgrade requires a Native Image executable target");
            }
            // A target may be temporarily absent on a host that has not
            // enabled this companion yet.  Treat it as a component-level
            // failure after command-agent is healthy, never as a reason to
            // roll back or strand command-agent.
        }
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "Agent upgrade failed" : value.trim();
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }

    record Config(String campaignId, String version, String staged, String target, String stateDir,
                  String serviceName, long parentPid, Integer attempt,
                  List<ComponentConfig> components) {
        Config {
            components = components == null ? List.of() : List.copyOf(components);
        }

        Config(String campaignId, String version, String staged, String target, String stateDir,
               String serviceName, long parentPid, Integer attempt) {
            this(campaignId, version, staged, target, stateDir, serviceName, parentPid, attempt, List.of());
        }

        Config(String campaignId, String version, String staged, String target, String stateDir,
               String serviceName, long parentPid) {
            this(campaignId, version, staged, target, stateDir, serviceName, parentPid, null, List.of());
        }
    }

    record ComponentConfig(String component, String version, String staged, String target, String stateDir,
                           String serviceName, String restartPolicy) {
        ComponentConfig {
            component = component == null ? "" : component.trim();
            version = version == null ? "" : version.trim();
            staged = staged == null ? "" : staged.trim();
            target = target == null ? "" : target.trim();
            stateDir = stateDir == null ? "" : stateDir.trim();
            serviceName = serviceName == null ? "" : serviceName.trim();
            restartPolicy = restartPolicy == null || restartPolicy.isBlank() ? "drain-and-restart" : restartPolicy.trim();
        }
    }

    record Result(String campaignId, String status, String error, Instant updatedAt, Integer attempt,
                  java.util.Map<String, String> componentStatuses) {
        Result {
            componentStatuses = componentStatuses == null ? java.util.Map.of() : java.util.Map.copyOf(componentStatuses);
        }

        Result(String campaignId, String status, String error, Instant updatedAt, Integer attempt) {
            this(campaignId, status, error, updatedAt, attempt, java.util.Map.of());
        }

        Result(String campaignId, String status, String error, Instant updatedAt) {
            this(campaignId, status, error, updatedAt, null, java.util.Map.of());
        }
    }
}
