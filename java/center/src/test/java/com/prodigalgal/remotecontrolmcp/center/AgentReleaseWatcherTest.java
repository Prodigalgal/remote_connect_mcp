package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeComponentPlan;
import org.junit.jupiter.api.Test;

class AgentReleaseWatcherTest {
    @Test
    void currentAgentStillReceivesCompatibleComponentsWithoutInstallProof() {
        var updater = new UpgradeComponentPlan("agent-updater", "v1.0.0+abc", "linux", "amd64",
                "https://example.test/updater.zip", "a".repeat(64), 100L, "manual", "v0.1.36");
        var machine = machine("current", "linux", "amd64", "v0.1.39");
        var desired = Map.of("linux/amd64", List.of(updater));
        assertEquals(List.of("current"), AgentReleaseWatcher.selectTargets(List.of(machine),
                release("v0.1.39", false), Set.of(), desired, Map.of()));
        assertEquals(List.of(), AgentReleaseWatcher.selectTargets(List.of(machine),
                release("v0.1.39", false), Set.of(), desired, Map.of("current", List.of(updater))));
        var old = new UpgradeComponentPlan("agent-updater", "v1.0.0+old", "linux", "amd64",
                "https://example.test/old.zip", "b".repeat(64), 100L, "manual", "v0.1.36");
        assertEquals(List.of("current"), AgentReleaseWatcher.selectTargets(List.of(machine),
                release("v0.1.39", false), Set.of(), desired, Map.of("current", List.of(old))));
    }

    @Test
    void latePublicationOfOldReleaseCannotReplaceDesiredStableVersion() {
        assertEquals("v0.1.40", AgentReleaseWatcher.selectRelease(List.of(
                release("v0.1.38", false), release("v0.1.40", false),
                release("v0.1.39", false), release("v0.2.0-beta.1", true)), false).version());
    }

    @Test
    void excludedMachinesDoNotBlockHealthyTargets() {
        assertEquals(List.of("healthy"), AgentReleaseWatcher.selectTargets(List.of(
                machine("deferred", "windows", "amd64", "v0.1.38"),
                machine("healthy", "linux", "amd64", "v0.1.38")), release("v0.1.39", false),
                java.util.Set.of("deferred")));
    }

    @Test
    void stableReleaseOnlyTargetsSupportedOlderMachines() {
        var release = release("v0.2.0", false);
        var machines = List.of(machine("older", "linux", "amd64", "v0.1.9"),
                machine("current", "windows", "amd64", "v0.2.0"),
                machine("ahead", "linux", "arm64", "v0.3.0"),
                machine("unsupported", "darwin", "arm64", "v0.1.0"));

        assertEquals(List.of("older"), AgentReleaseWatcher.selectTargets(machines, release));
    }

    @Test
    void stagingPrereleaseTargetsDifferentOnlineVersion() {
        var release = release("v0.0.0-main.42", true);
        var machines = List.of(machine("old", "linux", "amd64", "v0.1.9"),
                machine("current", "windows", "amd64", "v0.0.0-main.42"));

        assertEquals(List.of("old"), AgentReleaseWatcher.selectTargets(machines, release));
    }

    @Test
    void offlineTargetsWaitForAReconnectCampaign() {
        var machines = List.of(machine("offline", "linux", "amd64", "v0.1.9", false),
                machine("online-a", "linux", "arm64", "v0.1.9", true),
                machine("online-b", "windows", "amd64", "v0.1.9", true));

        assertEquals(List.of("online-a", "online-b"),
                AgentReleaseWatcher.selectTargets(machines, release("v0.2.0", false)));
    }

    private static ReleaseCatalogService.ReleaseView release(String version, boolean prerelease) {
        return new ReleaseCatalogService.ReleaseView(version, "java-" + version, "Agent", Instant.now(),
                prerelease, List.of(new ReleaseCatalogService.ReleaseAssetView("linux", "amd64", "agent.zip", true, true),
                        new ReleaseCatalogService.ReleaseAssetView("linux", "arm64", "agent.zip", true, true),
                        new ReleaseCatalogService.ReleaseAssetView("windows", "amd64", "agent.zip", true, true)));
    }

    private static MachineView machine(String id, String os, String arch, String version) {
        return machine(id, os, arch, version, true);
    }

    private static MachineView machine(String id, String os, String arch, String version, boolean online) {
        return new MachineView(id, id, id, id, os, arch, version, "/", List.of("command"),
                Instant.now(), Instant.now(), online);
    }
}
