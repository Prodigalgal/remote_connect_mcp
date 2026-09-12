package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class AgentUpgradeHelperTest {
    @Test
    void windowsArchiveReplacesRuntimeFilesAndWritesVersionMarker() throws Exception {
        assumeTrue(isWindows(), "Windows Native Image bundles are only applied on Windows");
        var root = Files.createTempDirectory("rcm-upgrade-helper-");
        try {
            var targetDir = Files.createDirectories(root.resolve("bin"));
            var stateDir = Files.createDirectories(root.resolve("state"));
            var target = targetDir.resolve("rcm-agent.exe");
            Files.writeString(target, "old-agent", StandardCharsets.UTF_8);
            Files.writeString(targetDir.resolve("java.dll"), "old-java", StandardCharsets.UTF_8);
            var archive = root.resolve("agent.zip");
            writeArchive(archive, "new-agent", "new-java");
            var config = new AgentUpgradeHelper.Config("campaign-1", "v2.0.0", archive.toString(),
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid(), true);

            invoke("applyArchive", config);

            assertEquals("new-agent", Files.readString(target));
            assertEquals("new-java", Files.readString(targetDir.resolve("java.dll")));
            assertEquals("v2.0.0", Files.readString(stateDir.resolve("agent-version")).trim());
            assertTrue(Files.exists(target.resolveSibling("rcm-agent.exe.previous")));
            assertTrue(Files.exists(stateDir.resolve("upgrade-files-campaign-1.txt")));

            invoke("cleanupAfterSuccess", config);
            assertFalse(Files.exists(target.resolveSibling("rcm-agent.exe.previous")));
            assertFalse(Files.exists(targetDir.resolve("java.dll.previous")));
            assertFalse(Files.exists(stateDir.resolve("upgrade-files-campaign-1.txt")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void windowsArchiveRollbackRestoresFilesAndVersion() throws Exception {
        assumeTrue(isWindows(), "Windows Native Image bundles are only applied on Windows");
        var root = Files.createTempDirectory("rcm-upgrade-rollback-");
        try {
            var targetDir = Files.createDirectories(root.resolve("bin"));
            var stateDir = Files.createDirectories(root.resolve("state"));
            var target = targetDir.resolve("rcm-agent.exe");
            Files.writeString(target, "old-agent", StandardCharsets.UTF_8);
            Files.writeString(stateDir.resolve("agent-version"), "v1.0.0\n", StandardCharsets.UTF_8);
            var archive = root.resolve("agent.zip");
            writeArchive(archive, "new-agent", "new-java");
            var config = new AgentUpgradeHelper.Config("campaign-rollback", "v2.0.0", archive.toString(),
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid(), true);

            invoke("applyArchive", config);
            invoke("rollback", config);

            assertEquals("old-agent", Files.readString(target));
            assertEquals("v1.0.0", Files.readString(stateDir.resolve("agent-version")).trim());
            assertFalse(Files.exists(stateDir.resolve("upgrade-files-campaign-rollback.txt")));
        } finally {
            deleteTree(root);
        }
    }

    private static void writeArchive(Path path, String agent, String javaDll) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("rcm-agent.exe"));
            output.write(agent.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("java.dll"));
            output.write(javaDll.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void invoke(String name, AgentUpgradeHelper.Config config) throws Exception {
        Method method = AgentUpgradeHelper.class.getDeclaredMethod(name,
                AgentUpgradeHelper.Config.class);
        method.setAccessible(true);
        try {
            method.invoke(null, config);
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
