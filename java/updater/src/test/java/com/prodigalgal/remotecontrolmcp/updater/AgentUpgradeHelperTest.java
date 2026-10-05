package com.prodigalgal.remotecontrolmcp.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.prodigalgal.remotecontrolmcp.protocol.JsonCodec;
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
    void preservesAlreadyCurrentComponentStatusInTerminalResult() throws Exception {
        var root = Files.createTempDirectory("rcm-upgrade-current-component-");
        try {
            var stateDir = Files.createDirectories(root.resolve("state"));
            var target = Files.createDirectories(root.resolve("agent")).resolve(isWindows() ? "rcm-agent.exe" : "rcm-agent");
            Files.writeString(target, "unchanged-agent", StandardCharsets.UTF_8);
            var config = new AgentUpgradeHelper.Config("campaign-current-component", "v1.0.0", "",
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid(), 1,
                    java.util.List.of(), java.util.Map.of("agent-updater", "already-current"));
            var configFile = root.resolve("helper.json");
            Files.write(configFile, JsonCodec.write(config));

            assertEquals(0, AgentUpgradeHelper.run(configFile.toString()));

            var result = JsonCodec.read(Files.readAllBytes(stateDir.resolve("upgrade-result.json")),
                    AgentUpgradeHelper.Result.class);
            assertEquals("completed", result.status());
            assertEquals("already-current", result.componentStatuses().get("agent-updater"));
            assertEquals("unchanged-agent", Files.readString(target));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void componentOnlyCampaignLeavesCommandAgentInPlace() throws Exception {
        var root = Files.createTempDirectory("rcm-upgrade-component-only-");
        try {
            var agentDir = Files.createDirectories(root.resolve("agent"));
            var updaterDir = Files.createDirectories(root.resolve("updater"));
            var stateDir = Files.createDirectories(root.resolve("state"));
            var agent = agentDir.resolve(isWindows() ? "rcm-agent.exe" : "rcm-agent");
            var updater = updaterDir.resolve(isWindows() ? "rcm-updater.exe" : "rcm-updater");
            Files.writeString(agent, "unchanged-agent", StandardCharsets.UTF_8);
            Files.writeString(updater, "old-updater", StandardCharsets.UTF_8);

            var archive = Files.createDirectories(stateDir.resolve("upgrades")).resolve("updater.zip");
            try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry(updater.getFileName().toString()));
                output.write("new-updater".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            var component = new AgentUpgradeHelper.ComponentConfig("agent-updater", "v1.0.0+abc",
                    archive.toString(), updater.toString(), stateDir.toString(), "", "manual");
            var config = new AgentUpgradeHelper.Config("campaign-component-only", "v1.0.0", "",
                    agent.toString(), stateDir.toString(), "", ProcessHandle.current().pid(), 1,
                    java.util.List.of(component));
            var configFile = root.resolve("helper.json");
            Files.write(configFile, JsonCodec.write(config));

            assertEquals(0, AgentUpgradeHelper.run(configFile.toString()));
            assertEquals("unchanged-agent", Files.readString(agent));
            assertEquals("new-updater", Files.readString(updater));
            assertFalse(Files.exists(archive));
            assertFalse(Files.exists(stateDir.resolve("agent-version")));
            assertEquals("v1.0.0+abc", Files.readString(stateDir.resolve("agent-updater-version")).trim());
            var result = JsonCodec.read(Files.readAllBytes(stateDir.resolve("upgrade-result.json")),
                    AgentUpgradeHelper.Result.class);
            assertEquals("completed", result.status());
            assertEquals("completed", result.componentStatuses().get("agent-updater"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void componentOnlyCampaignInstallsWhenTargetDoesNotExistYet() throws Exception {
        var root = Files.createTempDirectory("rcm-upgrade-first-component-install-");
        try {
            var agentDir = Files.createDirectories(root.resolve("agent"));
            var stateDir = Files.createDirectories(root.resolve("state"));
            var agent = agentDir.resolve(isWindows() ? "rcm-agent.exe" : "rcm-agent");
            var updater = root.resolve("install").resolve("updater")
                    .resolve(isWindows() ? "rcm-updater.exe" : "rcm-updater");
            Files.writeString(agent, "unchanged-agent", StandardCharsets.UTF_8);

            var archive = Files.createDirectories(stateDir.resolve("upgrades")).resolve("updater.zip");
            try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry(updater.getFileName().toString()));
                output.write("first-updater-install".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            var component = new AgentUpgradeHelper.ComponentConfig("agent-updater", "v1.0.0+abc",
                    archive.toString(), updater.toString(), stateDir.toString(), "", "manual");
            var config = new AgentUpgradeHelper.Config("campaign-first-component-install", "v1.0.0", "",
                    agent.toString(), stateDir.toString(), "", ProcessHandle.current().pid(), 1,
                    java.util.List.of(component));
            var configFile = root.resolve("helper.json");
            Files.write(configFile, JsonCodec.write(config));

            assertFalse(Files.exists(updater));
            assertEquals(0, AgentUpgradeHelper.run(configFile.toString()));
            assertEquals("first-updater-install", Files.readString(updater));
            assertEquals("unchanged-agent", Files.readString(agent));
            assertEquals("v1.0.0+abc", Files.readString(stateDir.resolve("agent-updater-version")).trim());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void windowsBundlePermissionsResetToTheTargetDirectoryAcl() throws Exception {
        assumeTrue(isWindows(), "Windows Native Image bundles use inherited ACLs");
        var root = Files.createTempDirectory("rcm-upgrade-windows-acl-");
        try {
            var target = Files.writeString(root.resolve("rcm-updater.exe"), "updater", StandardCharsets.UTF_8);
            Method method = AgentUpgradeHelper.class.getDeclaredMethod("setBundlePermissions", Path.class);
            method.setAccessible(true);
            try {
                method.invoke(null, target);
            } catch (InvocationTargetException exception) {
                var cause = exception.getCause();
                if (cause instanceof Exception checked) throw checked;
                if (cause instanceof Error error) throw error;
                throw exception;
            }
            var systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            var process = new ProcessBuilder(Path.of(systemRoot, "System32", "icacls.exe").toString(),
                    target.toString()).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            assertTrue(output.contains("(I)"), "installed component files must inherit the target directory ACL: " + output);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void replacesTheDeclaredExecutableWithoutRenamingIt() throws Exception {
        var root = Files.createTempDirectory("rcm-upgrade-exact-name-");
        try {
            var targetDir = Files.createDirectories(root.resolve("bin"));
            var stateDir = Files.createDirectories(root.resolve("state"));
            var targetName = isWindows() ? "rcm-agent.exe" : "rcm-agent";
            var target = targetDir.resolve(targetName);
            Files.writeString(target, "old-agent", StandardCharsets.UTF_8);
            var archive = root.resolve("agent.zip");
            writeArchive(archive, targetName, "new-agent", "java.dll", "new-java");
            var config = new AgentUpgradeHelper.Config("campaign-exact-name", "v2.0.0", archive.toString(),
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid());

            invoke("applyArchive", config);

            assertEquals("new-agent", Files.readString(target));
            assertEquals("new-java", Files.readString(targetDir.resolve("java.dll")));
            if (!isWindows()) {
                assertTrue(Files.isExecutable(target), "the declared executable must remain executable after archive replacement");
                assertTrue(Files.isExecutable(targetDir.resolve("java.dll")), "runtime library permissions must match the installed bundle");
            }

            invoke("rollback", config);
            assertEquals("old-agent", Files.readString(target));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void mapsCanonicalBundleExecutableToAConfiguredTargetName() throws Exception {
        var root = Files.createTempDirectory("rcm-upgrade-canonical-name-");
        try {
            var targetDir = Files.createDirectories(root.resolve("bin"));
            var stateDir = Files.createDirectories(root.resolve("state"));
            var targetName = isWindows() ? "agent-custom.exe" : "agent-custom";
            var canonicalName = isWindows() ? "rcm-agent.exe" : "rcm-agent";
            var target = targetDir.resolve(targetName);
            Files.writeString(target, "old-agent", StandardCharsets.UTF_8);
            var archive = root.resolve("agent.zip");
            writeArchive(archive, canonicalName, "new-agent", "java.dll", "new-java");
            var config = new AgentUpgradeHelper.Config("campaign-canonical-name", "v2.0.0", archive.toString(),
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid());

            invoke("applyArchive", config);

            assertEquals("new-agent", Files.readString(target));
            assertEquals("new-java", Files.readString(targetDir.resolve("java.dll")));
            assertFalse(Files.exists(targetDir.resolve(canonicalName)),
                    "the canonical archive name must not leave a second executable beside the configured target");
        } finally {
            deleteTree(root);
        }
    }

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
            writeArchive(archive, "rcm-agent.exe", "new-agent", "java.dll", "new-java");
            var config = new AgentUpgradeHelper.Config("campaign-1", "v2.0.0", archive.toString(),
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid());

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
            writeArchive(archive, "rcm-agent.exe", "new-agent", "java.dll", "new-java");
            var config = new AgentUpgradeHelper.Config("campaign-rollback", "v2.0.0", archive.toString(),
                    target.toString(), stateDir.toString(), "", ProcessHandle.current().pid());

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
        writeArchive(path, "rcm-agent.exe", agent, "java.dll", javaDll);
    }

    private static void writeArchive(Path path, String agentName, String agent, String javaDllName, String javaDll) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry(agentName));
            output.write(agent.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry(javaDllName));
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
