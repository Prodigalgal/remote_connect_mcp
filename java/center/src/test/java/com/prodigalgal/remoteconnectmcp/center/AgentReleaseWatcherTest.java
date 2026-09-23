package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentReleaseWatcherTest {
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
    void stagingPrereleaseTargetsDifferentVersionIncludingOfflineMachine() {
        var release = release("v0.0.0-main.42", true);
        var machines = List.of(machine("old", "linux", "amd64", "v0.1.9"),
                machine("current", "windows", "amd64", "v0.0.0-main.42"));

        assertEquals(List.of("old"), AgentReleaseWatcher.selectTargets(machines, release));
    }

    @Test
    void offlineTargetsFollowOnlineBatches() {
        var machines = List.of(machine("offline", "linux", "amd64", "v0.1.9", false),
                machine("online-a", "linux", "arm64", "v0.1.9", true),
                machine("online-b", "windows", "amd64", "v0.1.9", true));

        assertEquals(List.of("online-a", "online-b", "offline"),
                AgentReleaseWatcher.selectTargets(machines, release("v0.2.0", false)));
    }

    private static ReleaseCatalogService.ReleaseView release(String version, boolean prerelease) {
        return new ReleaseCatalogService.ReleaseView(version, "java-" + version, "Agent", Instant.now(),
                prerelease, List.of(new ReleaseCatalogService.ReleaseAssetView("linux", "amd64", "agent.zip", true, true),
                        new ReleaseCatalogService.ReleaseAssetView("linux", "arm64", "agent.zip", true, true),
                        new ReleaseCatalogService.ReleaseAssetView("windows", "amd64", "agent.zip", true, true)));
    }

    private static MachineView machine(String id, String os, String arch, String version) {
        return machine(id, os, arch, version, false);
    }

    private static MachineView machine(String id, String os, String arch, String version, boolean online) {
        return new MachineView(id, id, id, id, os, arch, version, "/", List.of("command"),
                Instant.now(), Instant.now(), online);
    }
}
