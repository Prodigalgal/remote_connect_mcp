package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterRequest;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterResponse;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import com.prodigalgal.remotecontrolmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeComponentPlan;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeStatusRequest;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpgradeServiceTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void automaticComponentProofUsesReportedCompletionInsteadOfJustAgentVersion() {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(registration("current", "v2.0.0"), "enroll");
        var upgrades = new UpgradeService(registry, new TaskService(registry), new UpgradeConfig(true, ""));
        var updater = new UpgradeComponentPlan("agent-updater", "v1.0.0+abc", "linux", "amd64",
                "https://example.test/updater.zip", SHA, 100L, "manual");
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(machine.machineId()),
                Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent.zip", SHA)),
                false, Map.of("linux/amd64", List.of(updater))));
        assertTrue(upgrades.completedComponents("v2.0.0").isEmpty());
        var offered = upgrades.offer(machine.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertNotNull(offered);
        upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(),
                UpgradeService.COMPLETED, null, offered.attempt(), Map.of("agent-updater", "already-current")));
        assertEquals(List.of(updater), upgrades.completedComponents("v2.0.0").get(machine.machineId()));
    }

    @Test
    void companionBundlesAreOfferedOnlyToMachinesThatUseThem() {
        var registry = AgentRegistry.forTest("enroll");
        var commandOnly = registry.register(registration("command-only", "v1.0.0"), "enroll");
        var full = registry.register(new RegisterRequest("full", "full", "full", "linux", "amd64",
                "v1.0.0", "/", List.of("command", "durable_tasks", "desktop", "browser")), "enroll");
        var upgrades = new UpgradeService(registry, new TaskService(registry), new UpgradeConfig(true, ""));
        var desktop = new UpgradeComponentPlan("desktop-companion", "v2.0.0", "linux", "amd64",
                "https://example.test/desktop.zip", SHA, 100L, "drain-and-restart");
        var browser = new UpgradeComponentPlan("browser-agent", "v2.0.0", "linux", "amd64",
                "https://example.test/browser.zip", SHA, 100L, "drain-and-restart");
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(commandOnly.machineId(), full.machineId()),
                Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent.zip", SHA)),
                false, Map.of("linux/amd64", List.of(desktop, browser))));

        var poll = new PollRequest(List.of(), 1, List.of("command"));
        var commandPlan = upgrades.offer(commandOnly.machineId(), poll);
        assertNotNull(commandPlan);
        assertTrue(commandPlan.components().isEmpty());
        heartbeat(registry, commandOnly, "command-only", "v2.0.0");
        upgrades.updateStatus(commandOnly.machineId(), new UpgradeStatusRequest(
                campaign.id(), UpgradeService.COMPLETED, null, commandPlan.attempt(), Map.of()));
        var fullPlan = upgrades.offer(full.machineId(), poll);
        assertNotNull(fullPlan);
        assertEquals(List.of(desktop, browser), fullPlan.components());
    }

    @Test
    void updaterBundleIsWithheldFromAgentsBeforeTheBridgeVersion() {
        var updater = new UpgradeComponentPlan("agent-updater", "v1.0.0", "linux", "amd64",
                "https://example.test/updater.zip", SHA, 100L, "manual", "v0.1.36");
        var old = new MachineView("old", "old", "old", "old", "linux", "amd64", "v0.1.35",
                "/", List.of("command"), Instant.EPOCH, Instant.EPOCH, true);
        var bridge = new MachineView("bridge", "bridge", "bridge", "bridge", "linux", "amd64", "v0.1.36",
                "/", List.of("command"), Instant.EPOCH, Instant.EPOCH, true);
        var prerelease = new MachineView("prerelease", "prerelease", "prerelease", "prerelease", "linux", "amd64", "v0.1.36-rc.1",
                "/", List.of("command"), Instant.EPOCH, Instant.EPOCH, true);
        var plans = Map.of("linux/amd64", List.of(updater));

        assertTrue(UpgradeService.componentsForMachine(plans, old).isEmpty());
        assertEquals(List.of(updater), UpgradeService.componentsForMachine(plans, bridge));
        assertTrue(UpgradeService.componentsForMachine(plans, prerelease).isEmpty());
    }

    @Test
    void companionBundlesAreWithheldUntilTheAgentLifecycleFixIsInstalled() {
        var desktop = new UpgradeComponentPlan("desktop-companion", "v0.1.36", "windows", "amd64",
                "https://example.test/desktop.zip", SHA, 100L, "drain-and-restart", "v0.1.36");
        var browser = new UpgradeComponentPlan("browser-agent", "v0.1.36", "windows", "amd64",
                "https://example.test/browser.zip", SHA, 100L, "drain-and-restart", "v0.1.36");
        var old = new MachineView("old", "old", "old", "old", "windows", "amd64", "v0.1.35",
                "/", List.of("command", "desktop", "browser"), Instant.EPOCH, Instant.EPOCH, true);
        var upgraded = new MachineView("upgraded", "upgraded", "upgraded", "upgraded", "windows", "amd64", "v0.1.36",
                "/", List.of("command", "desktop", "browser"), Instant.EPOCH, Instant.EPOCH, true);
        var plans = Map.of("windows/amd64", List.of(desktop, browser));

        assertTrue(UpgradeService.componentsForMachine(plans, old).isEmpty());
        assertEquals(List.of(desktop, browser), UpgradeService.componentsForMachine(plans, upgraded));
    }

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

        var acknowledged = upgrades.updateStatus(first.machineId(), new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null, 1, Map.of()));
        assertEquals(UpgradeService.VERIFYING, acknowledged.targets().getFirst().status());
        assertNull(upgrades.offer(second.machineId(), request), "a helper receipt alone must not promote the next batch");
        heartbeat(registry, first, "one", "v2.0.0");
        upgrades.reconcileActive(Instant.now());
        var afterFirst = upgrades.list(0, 10).getFirst();
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

        assertEquals(2, campaign.targets().size(), "all registrations must remain durable campaign targets");
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
                new UpgradeStatusRequest(campaign.id(), UpgradeService.FAILED, "SHA-256 mismatch", 1, Map.of())).status());
        assertNull(upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))));
        assertEquals(UpgradeService.RUNNING, upgrades.control(campaign.id(), "resume").status());
        assertNotNull(upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))));
    }

    @Test
    void retryTargetRequeuesOnlyTheFailedMachine() {
        var registry = AgentRegistry.forTest("enroll");
        var first = registry.register(registration("one", "v1.0.0"), "enroll");
        var second = registry.register(registration("two", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(first.machineId(), second.machineId()), Map.of("linux/amd64",
                        new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        var plan = upgrades.offer(first.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertNotNull(plan);
        var failed = upgrades.updateStatus(first.machineId(), new UpgradeStatusRequest(
                campaign.id(), UpgradeService.FAILED, "temporary token=should-not-be-visible", plan.attempt(), Map.of()));
        assertEquals(UpgradeService.PAUSED, failed.status());
        assertEquals("temporary token=[redacted]", failed.targets().stream()
                .filter(target -> target.machineId().equals(first.machineId())).findFirst().orElseThrow().error());
        var retried = upgrades.retryTarget(campaign.id(), first.machineId());
        assertEquals(UpgradeService.RUNNING, retried.status());
        assertEquals(UpgradeService.PENDING, retried.targets().stream()
                .filter(target -> target.machineId().equals(first.machineId())).findFirst().orElseThrow().status());
        assertEquals(UpgradeService.PENDING, retried.targets().stream()
                .filter(target -> target.machineId().equals(second.machineId())).findFirst().orElseThrow().status());
        assertEquals(1, retried.targets().stream()
                .filter(target -> target.machineId().equals(first.machineId())).findFirst().orElseThrow().attempts());
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
                new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null, 1, Map.of()));

        assertEquals(UpgradeService.CANCELED, late.status());
        assertEquals(UpgradeService.OFFERED, late.targets().getFirst().status());
    }

    @Test
    void ignoresStatusWithADifferentOfferAttempt() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(registration.machineId()), Map.of("linux/amd64",
                        new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        var first = upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertNotNull(first);

        // A report carrying a different attempt is stale. It must not turn the
        // still-offered target into a completed target.
        var stale = upgrades.updateStatus(registration.machineId(),
                new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null, first.attempt() + 1, Map.of()));

        assertEquals(UpgradeService.OFFERED, stale.targets().getFirst().status());
    }

    @Test
    void expiredDownloadSettlesAsFailureWithoutLaunchingASecondHelper() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var upgrades = new UpgradeService(registry, new TaskService(registry), new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(registration.machineId()), Map.of("linux/amd64",
                        new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        var plan = upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        upgrades.updateStatus(registration.machineId(), new UpgradeStatusRequest(
                campaign.id(), UpgradeService.DOWNLOADING, null, plan.attempt(), Map.of()));

        upgrades.reconcileActive(Instant.now().plusSeconds(21 * 60));
        var settled = upgrades.list(0, 10).getFirst();
        assertEquals(UpgradeService.PAUSED, settled.status());
        assertEquals(UpgradeService.FAILED, settled.targets().getFirst().status());
        assertTrue(settled.targets().getFirst().error().contains("lease expired"));
        assertNull(upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))));

        heartbeat(registry, registration, "one", "v2.0.0");
        var late = upgrades.updateStatus(registration.machineId(), new UpgradeStatusRequest(
                campaign.id(), UpgradeService.COMPLETED, null, plan.attempt(), Map.of()));
        assertEquals(UpgradeService.COMPLETED, late.status());
    }

    @Test
    void ignoresOutOfOrderStatusAfterCompletion() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(registration("one", "v1.0.0"), "enroll");
        var tasks = new TaskService(registry);
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(registration.machineId()), Map.of("linux/amd64",
                        new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        var plan = upgrades.offer(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertNotNull(plan);
        heartbeat(registry, registration, "one", "v2.0.0");
        assertEquals(UpgradeService.COMPLETED, upgrades.updateStatus(registration.machineId(),
                new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null, plan.attempt(), Map.of())).status());
        var late = upgrades.updateStatus(registration.machineId(),
                new UpgradeStatusRequest(campaign.id(), UpgradeService.DOWNLOADING, null, plan.attempt(), Map.of()));
        assertEquals(UpgradeService.COMPLETED, late.targets().getFirst().status());
        assertEquals(UpgradeService.COMPLETED, upgrades.updateStatus(registration.machineId(),
                new UpgradeStatusRequest(campaign.id(), UpgradeService.COMPLETED, null, plan.attempt(), Map.of())).status());
    }

    @Test
    void temporaryDownloadFailureWaitsBeforeRetryAndInstallationFailureDoesNotRetry() {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(registration("one", "v1.0.0"), "enroll");
        var upgrades = new UpgradeService(registry, new TaskService(registry), new UpgradeConfig(true, ""));
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(machine.machineId()), Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA))));
        var poll = new PollRequest(List.of(), 1, List.of("command"));
        var plan = upgrades.offer(machine.machineId(), poll);
        var failedAt = Instant.now();
        var waiting = upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(),
                UpgradeService.FAILED, "download HTTP connect timed out", plan.attempt(), Map.of()));
        assertEquals(UpgradeService.RUNNING, waiting.status());
        assertEquals(UpgradeService.RETRYING, waiting.targets().getFirst().status());
        assertTrue(waiting.targets().getFirst().retryAt().isAfter(failedAt.plusSeconds(29)));
        assertNull(upgrades.offer(machine.machineId(), poll));
        upgrades.reconcileActive(waiting.targets().getFirst().retryAt().plusMillis(1));
        var retry = upgrades.offer(machine.machineId(), poll);
        assertEquals(2, retry.attempt());
        upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(), UpgradeService.INSTALLING, null, retry.attempt(), Map.of()));
        var installationFailure = upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(),
                UpgradeService.FAILED, "network down", retry.attempt(), Map.of()));
        assertEquals(UpgradeService.PAUSED, installationFailure.status());
        assertEquals(UpgradeService.FAILED, installationFailure.targets().getFirst().status());
        assertNull(installationFailure.targets().getFirst().retryAt());
        assertEquals(java.util.Set.of(machine.machineId()), upgrades.failedMachines("v2.0.0"));
        assertTrue(upgrades.failedMachines("v3.0.0").isEmpty());
    }

    @Test
    void incompleteComponentProofCannotCompleteAnOtherwiseCurrentAgent() {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(registration("one", "v2.0.0"), "enroll");
        var upgrades = new UpgradeService(registry, new TaskService(registry), new UpgradeConfig(true, ""));
        var updater = new UpgradeComponentPlan("agent-updater", "v1.0.0", "linux", "amd64",
                "https://example.test/updater.zip", SHA, 100L, "manual");
        var campaign = upgrades.create(new CreateUpgradeCampaignRequest("v2.0.0", 1, 1,
                List.of(machine.machineId()), Map.of("linux/amd64", new UpgradeArtifact("linux", "amd64", "https://example.test/agent", SHA)),
                true, Map.of("linux/amd64", List.of(updater))));
        var plan = upgrades.offer(machine.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        var waiting = upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(),
                UpgradeService.COMPLETED, null, plan.attempt(), Map.of()));
        assertEquals(UpgradeService.VERIFYING, waiting.targets().getFirst().status());
        assertEquals(Boolean.FALSE, waiting.summary().get("fully_updated"));
        var duplicate = upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(),
                UpgradeService.COMPLETED, null, plan.attempt(), Map.of()));
        assertEquals(waiting.targets().getFirst().leaseUntil(), duplicate.targets().getFirst().leaseUntil(),
                "duplicate receipts must not extend the verification deadline");
        var completed = upgrades.updateStatus(machine.machineId(), new UpgradeStatusRequest(campaign.id(),
                UpgradeService.COMPLETED, null, plan.attempt(), Map.of("agent-updater", "completed")));
        assertEquals(UpgradeService.COMPLETED, completed.status());
        assertEquals(Boolean.TRUE, completed.summary().get("fully_updated"));
    }

    @Test
    void digestAndAuthenticationFailuresNeverBecomeAutomaticNetworkRetries() {
        assertFalse(UpgradeService.retryableDownloadError("SHA-256 mismatch after timeout"));
        assertFalse(UpgradeService.retryableDownloadError("HTTP 403 temporarily unavailable"));
        assertFalse(UpgradeService.retryableDownloadError("permission denied"));
        assertTrue(UpgradeService.retryableDownloadError("HTTP 503"));
        assertTrue(UpgradeService.retryableDownloadError("connection reset"));
    }

    private static void heartbeat(AgentRegistry registry, RegisterResponse agent, String name, String version) {
        registry.poll(agent.machineId(), agent.token(), new PollRequest(List.of(), 1, List.of("command"), registration(name, version).metadata()));
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
        return new RegisterRequest(name, name, name, "linux", "amd64", version, "/", List.of("command", "durable_tasks"));
    }
}
