package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpgradeServiceTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void offersCanaryThenAdvancesBatchAfterSuccessfulAgentReport() {
        var registry = AgentRegistry.forTest("enroll");
        var first = registry.register(registration("one", "v1.0.0"), "enroll");
        var second = registry.register(registration("two", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var artifacts = Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA));

        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(first.machineId(), second.machineId()), artifacts));
        assertEquals(UpgradeService.RUNNING, campaign.status());

        var request = new PollRequest(List.of(), 1, List.of("command"));
        var firstPlan = upgrades.offer(first.machineId(), request);
        assertNotNull(firstPlan);
        assertNull(upgrades.offer(second.machineId(), request), "second target waits for canary completion");

        var afterFirst = upgrades.updateStatus(first.machineId(), new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null));
        assertEquals(UpgradeService.RUNNING, afterFirst.status());
        assertNotNull(upgrades.offer(second.machineId(), request));
    }

    @Test
    void fleetCampaignRetainsAllRegisteredMachinesWhenMachineListIsOmitted() {
        var registry = AgentRegistry.forTest("enroll");
        registry.register(registration("one", "v1.0.0"), "enroll");
        registry.register(registration("two", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));

        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(), Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));

        assertEquals(2, campaign.targets().size(), "offline registrations must remain durable campaign targets");
    }

    @Test
    void failurePausesCampaignAndResumeRequeuesOnlyFailedTarget() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(registration.machineId()), Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertEquals(UpgradeService.PAUSED, upgrades.updateStatus(registration.machineId(),
                new UpgradeStatusRequest(campaign.id(), UpgradeService.FAILED, "network down")).status());
        assertNull(upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))));
        assertEquals(UpgradeService.RUNNING, upgrades.control(campaign.id(), "resume").status());
        assertNotNull(upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))));
    }

    @Test
    void refusesToUpgradeWhileNonDurableTaskIsAttached() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", null, "command", "echo hi", "/", Map.of(), 30, null, null), ""));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1, List.of(registration.machineId()),
                Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));

        assertNull(upgrades.offer(registration.machineId(), new PollRequest(List.of(task.id()), 1, List.of("command"))));
    }

    @Test
    void ignoresLateAgentStatusAfterCampaignCancellation() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(registration.machineId()), Map.of("linux/amd64",
                        new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertEquals(UpgradeService.CANCELED, upgrades.control(campaign.id(), "cancel").status());

        var late = upgrades.updateStatus(registration.machineId(),
                new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null));

        assertEquals(UpgradeService.CANCELED, late.status());
        assertEquals(UpgradeService.OFFERED, late.targets().getFirst().status());
    }

    @Test
    void rejectsNonHttpsOrBadDigestArtifactsBeforeCreatingCampaign() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));

        assertThrows(IllegalArgumentException.class, () -> upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(registration.machineId()), Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "http://example.test/agent", SHA)))));
        assertEquals(0, upgrades.list(0, 20).size());
    }

    private static RegisterRequest registration(String name, String version) {
        return new RegisterRequest(name, name, name, "linux", "amd64", version, "/", ScopeMode.UNRESTRICTED, null, List.of("command", "durable_tasks"));
    }
}
