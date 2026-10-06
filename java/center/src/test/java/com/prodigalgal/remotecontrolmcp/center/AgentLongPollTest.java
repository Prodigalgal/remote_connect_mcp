package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.prodigalgal.remotecontrolmcp.protocol.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class AgentLongPollTest {
    @Test
    void finishedTaskWakesAFullCapacityPollWithoutOverDispatching() throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var machine = registry.register(new RegisterRequest("poll-agent", "poll-host", "poll-host", "linux", "amd64",
                "dev", "/tmp", List.of("command")), "enrollment");
        try (var async = new CenterAsyncExecutor(); var wakes = spy(new AgentWakeRegistry(null))) {
            var beans = new StaticListableBeanFactory();
            beans.addBean("wakes", wakes);
            var tasks = new TaskService(registry, beans.getBeanProvider(JdbcTemplate.class),
                    beans.getBeanProvider(TransactionTemplate.class), beans.getBeanProvider(AgentWakeRegistry.class),
                    beans.getBeanProvider(TaskChangeRegistry.class), beans.getBeanProvider(ArtifactStore.class),
                    beans.getBeanProvider(AuditService.class), beans.getBeanProvider(ExecutionSessionService.class),
                    beans.getBeanProvider(McpQuotaService.class));
            var command = new TaskCommand("", TaskKind.COMMAND, "command", "echo short", "/tmp", Map.of(), 30, null, Instant.now());
            var first = tasks.create(new CreateTaskRequest(machine.machineId(), command, "first"));
            var leased = tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
            tasks.updateState(machine.machineId(), first.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), leased.attempt());
            var second = tasks.create(new CreateTaskRequest(machine.machineId(), command, "second"));
            var controller = new AgentController(registry, tasks, mock(UpgradeService.class),
                    mock(AgentConfigurationService.class), async, null, beans.getBeanProvider(AgentWakeRegistry.class));
            var waiting = new CountDownLatch(1);
            doAnswer(call -> { waiting.countDown(); return call.callRealMethod(); }).when(wakes)
                    .awaitChange(eq(machine.machineId()), anyLong(), anyLong());
            var full = new PollRequest(List.of(first.id()), 0, List.of("command"));
            var pending = controller.poll("Bearer " + machine.token(), machine.machineId(), null, null, full, 25000);
            assertTrue(waiting.await(3, TimeUnit.SECONDS), "poll did not enter event wait");
            tasks.updateState(machine.machineId(), first.id(), new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false), leased.attempt());
            var refreshed = (PollResponse) pending.get(3, TimeUnit.SECONDS).getBody();
            assertNotNull(refreshed);
            assertNull(refreshed.task(), "stale zero-slot request must not claim another task");
            assertEquals(TaskStatus.QUEUED, tasks.find(second.id()).orElseThrow().status());
            var next = (PollResponse) controller.poll("Bearer " + machine.token(), machine.machineId(), null, null,
                    new PollRequest(List.of(), 1, List.of("command")), 0).get(3, TimeUnit.SECONDS).getBody();
            assertEquals(second.id(), next.task().id());
        }
    }
}
