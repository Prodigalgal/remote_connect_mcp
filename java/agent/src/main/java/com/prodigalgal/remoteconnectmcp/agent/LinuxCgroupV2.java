package com.prodigalgal.remoteconnectmcp.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Optional cgroup v2 attachment for a task process. The service manager still
 * owns the Agent's parent cgroup; this hook lets an operator create a child
 * cgroup with MemoryMax/CPUQuota and have each task inherit the hard ceiling.
 * A configured but unusable path fails the task closed instead of silently
 * pretending that a memory limit was applied.
 */
final class LinuxCgroupV2 {
    private LinuxCgroupV2() {
    }

    static String tryAttach(ProcessHandle process, AgentConfig config) {
        if (config == null || !isLinux()) return null;
        var raw = config.resourceCgroupPath();
        if (raw == null || raw.isBlank()) return null;
        try {
            var directory = Path.of(raw).toAbsolutePath().normalize();
            var procs = directory.resolve("cgroup.procs");
            // cgroup.procs is a pseudo-file on cgroupfs, so
            // isRegularFile() is intentionally not used here.
            if (!Files.isDirectory(directory) || !Files.exists(procs) || !Files.isWritable(procs)) {
                return "configured cgroup-v2 path is unavailable";
            }
            Files.writeString(procs, Long.toString(process.pid()), StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
            return null;
        } catch (Exception failure) {
            return "configured cgroup-v2 attachment failed: " + safeMessage(failure);
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static String safeMessage(Exception failure) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        var clean = message.replaceAll("[\\r\\n]+", " ");
        return clean.length() <= 256 ? clean : clean.substring(0, 256);
    }
}
