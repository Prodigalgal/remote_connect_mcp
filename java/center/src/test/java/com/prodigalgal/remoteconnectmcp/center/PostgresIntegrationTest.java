package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL/Liquibase contract test. It is enabled only when the CI
 * (or a developer) explicitly provides RCM_TEST_POSTGRES_URL; ordinary local
 * JVM tests remain database-free and do not silently claim PostgreSQL
 * coverage.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RCM_TEST_POSTGRES_URL", matches = "\\S+")
class PostgresIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private String agentId;

    @BeforeAll
    void migrateSchema() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(required("RCM_TEST_POSTGRES_URL"));
        config.setUsername(System.getenv().getOrDefault("RCM_TEST_POSTGRES_USERNAME", "postgres"));
        config.setPassword(System.getenv().getOrDefault("RCM_TEST_POSTGRES_PASSWORD", "postgres"));
        config.setPoolName("rcm-postgres-integration");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(10_000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("postgres");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
    }

    @AfterAll
    void cleanUp() {
        if (jdbc != null && agentId != null) {
            jdbc.update("DELETE FROM rcm_agent WHERE agent_id = ?", agentId);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void liquibaseSchemaSupportsRegistrationTaskLeaseOutputAndArtifactRoundTrip() throws Exception {
        agentId = "machine_it_" + UUID.randomUUID().toString().replace("-", "");
        var registry = AgentRegistry.forTest("integration-enrollment", jdbc);
        var request = new RegisterRequest(
                "postgres-it-agent-" + agentId,
                "postgres-it-host",
                "postgres-it-host",
                "linux",
                "amd64",
                "integration",
                "/tmp",
                ScopeMode.UNRESTRICTED,
                null,
                List.of("command", "durable_tasks"));
        var registration = registry.register(request, "integration-enrollment");
        agentId = registration.machineId();
        assertNotNull(jdbc.queryForObject("SELECT agent_id FROM rcm_agent WHERE agent_id = ?", String.class, agentId));

        // A new Center facade must authenticate and read the same durable
        // identity immediately after a process restart; no in-memory registry
        // cache may be required for daily Agent traffic.
        var restartedRegistry = AgentRegistry.forTest("integration-enrollment", jdbc);
        assertTrue(restartedRegistry.findMachine(agentId, Instant.now()).isPresent());
        assertTrue(restartedRegistry.acceptsAgent(agentId, registration.token()));

        var runtime = new AgentRuntimeDescriptor(1, 7, 2, 1,
                32L * 1024 * 1024, 96L * 1024 * 1024, 12, 900, 0, 0, false, true);
        var heartbeat = new AgentMetadata(request.name(), request.hostId(),
                "postgres-it-host", "linux", "amd64", "integration-2", "/tmp",
                ScopeMode.UNRESTRICTED, null, List.of("command", "browser"), runtime);
        registry.poll(agentId, registration.token(), new PollRequest(List.of(), 1,
                List.of("command", "browser"), heartbeat));
        var persistedRuntime = restartedRegistry.findMachine(agentId, Instant.now()).orElseThrow().runtime();
        assertEquals(7, persistedRuntime.configGeneration());
        assertEquals(2, persistedRuntime.maxConcurrency());
        assertTrue(persistedRuntime.browserAdapterConfigured());

        // Project/worktree rows use the same Agent-local task contract.  The
        // Center never opens the repository; completion of the generated Git
        // task is the only transition that makes a worktree selectable as a
        // task cwd.
        var projectTasks = new TaskService(registry, jdbc, transactions);
        var projectService = new ProjectService(registry, projectTasks, jdbc, transactions);
        var project = projectService.register(new ProjectRegistrationRequest(agentId, "integration-project", "/tmp/rcm-it-project", null, "main"));
        var worktree = projectService.createWorktree(project.id(), new ProjectWorktreeRequest("feature/integration", "project-worktree-1"));
        assertNotNull(worktree.taskId());
        var worktreeTask = projectTasks.poll(agentId, new PollRequest(List.of(), 1, List.of("command"))).task();
        assertEquals(worktree.taskId(), worktreeTask.id());
        assertEquals("project", worktreeTask.contract().scopeMode().wireValue());
        assertEquals(project.id(), worktreeTask.contract().projectId());
        assertEquals(project.rootPath(), worktreeTask.contract().scopeRoot());
        projectTasks.updateState(agentId, worktreeTask.id(), new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
        projectTasks.updateState(agentId, worktreeTask.id(), new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
        var readyWorktree = projectService.find(project.id()).worktrees().stream()
                .filter(value -> value.id().equals(worktree.id())).findFirst().orElseThrow();
        assertEquals("ready", readyWorktree.status());
        assertEquals(readyWorktree.path(), projectService.resolveCwd(agentId, project.id(), worktree.id(), null));

        var store = new JdbcTaskStore(jdbc, transactions);
        var taskId = "task_it_" + UUID.randomUUID().toString().replace("-", "");
        var command = new TaskCommand(taskId, TaskKind.COMMAND, "command", "printf integration", "/tmp",
                Map.of("RCM_IT", "1"), 0, null, Instant.now());
        var first = store.create(taskId, agentId, command, "integration-key", command.createdAt());
        var replay = store.create("task_it_replay", agentId, command, "integration-key", command.createdAt());
        assertEquals(first.id(), replay.id(), "idempotent retry must return the committed task");

        var poll = store.poll(agentId, new PollRequest(List.of(), 1, List.of("command"),
                new AgentMetadata(request.name(), request.hostId(), request.hostname(), request.os(), request.arch(),
                        request.version(), request.defaultCwd(), request.scopeMode(), request.workspaceRoot(), request.capabilities())));
        assertNotNull(poll.task());
        assertEquals(taskId, poll.task().id());
        assertEquals(1, store.find(taskId).orElseThrow().attempt(), "first lease claim must be attempt 1");
        store.updateState(agentId, taskId, new TaskUpdateRequest("running", null, null, Instant.now(), null, false));

        var firstOutput = "line-1\n".getBytes(StandardCharsets.UTF_8);
        var secondOutput = "line-2\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(firstOutput.length, store.appendOutput(agentId, taskId, 0, firstOutput).nextOffset());
        assertEquals(firstOutput.length + secondOutput.length,
                store.appendOutput(agentId, taskId, firstOutput.length, secondOutput).nextOffset());
        // Replaying an already confirmed chunk is accepted without appending
        // it twice, which is the key disconnect/retry invariant.
        assertEquals(firstOutput.length + secondOutput.length,
                store.appendOutput(agentId, taskId, 0, concat(firstOutput, secondOutput)).nextOffset());
        var output = store.readOutput(taskId, 0, 64 * 1024);
        assertArrayEquals(concat(firstOutput, secondOutput), output.data());
        assertEquals(firstOutput.length + secondOutput.length, output.nextCursor());

        var artifact = "artifact-data".getBytes(StandardCharsets.UTF_8);
        var digest = sha256(artifact);
        assertEquals(artifact.length, store.appendArtifact(agentId, taskId, "text/plain", digest, artifact).bytes());
        assertEquals("filesystem", jdbc.queryForObject("SELECT storage_backend FROM rcm_task_artifact WHERE task_id = ?", String.class, taskId));
        assertNull(jdbc.queryForObject("SELECT artifact_data FROM rcm_task_artifact WHERE task_id = ?", byte[].class, taskId),
                "new artifacts must not be written into PostgreSQL bytea");
        assertTrue(store.readArtifact(taskId).isPresent());
        store.updateState(agentId, taskId, new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
        assertEquals(TaskStatus.COMPLETED, store.find(taskId).orElseThrow().status());
        assertEquals(0, store.find(taskId).orElseThrow().exitCode());

        // A transport retry can race with the original request.  The unique
        // (agent_id, idempotency_key) constraint plus the adapter's duplicate
        // recovery must return one committed task to every caller.
        var concurrentCommand = new TaskCommand("concurrent-command", TaskKind.COMMAND, "command", "printf concurrent", "/tmp",
                Map.of(), 30, null, Instant.now());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                start.await();
                return store.create("task_it_race_" + index, agentId, concurrentCommand, "concurrent-key", concurrentCommand.createdAt());
            })).toList();
            start.countDown();
            var ids = new HashSet<String>();
            for (var future : futures) ids.add(future.get().id());
            assertEquals(1, ids.size(), "concurrent idempotency requests must converge on one task");
            // The race task is only a uniqueness probe. Remove it from the
            // queue so the following lease-recovery assertions select their
            // own task deterministically.
            var raceTaskId = ids.iterator().next();
            store.cancel(raceTaskId);
            var canceledRace = store.find(raceTaskId).orElseThrow();
            store.updateState(agentId, raceTaskId,
                    new TaskUpdateRequest("canceled", null, null, null, Instant.now(), false),
                    canceledRace.attempt());
        }

        // Two independent Center facades must be able to claim different
        // queued rows at the same time.  The row lock/SKIP LOCKED contract is
        // what permits multiple Center replicas without double dispatch.
        var claimIds = IntStream.range(0, 2).mapToObj(index -> {
            // Explicit path roots produce two independent execution lanes;
            // using the implicit host lane here would correctly serialize the
            // claims and make the SKIP LOCKED assertion nondeterministic.
            var claimCommand = new TaskCommand("", TaskKind.COMMAND, "command", "printf claim-" + index, "/tmp/claim-" + index,
                    Map.of(), 30, null, Instant.now());
            return projectTasks.create(new CreateTaskRequest(agentId, claimCommand, "claim-key-" + index,
                    "", "", ScopeMode.PATH, "/tmp/claim-" + index,
                    "claim-session-" + index, "low", false)).id();
        }).toList();
        var claimStart = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = claimIds.stream().map(ignored -> executor.submit(() -> {
                claimStart.await();
                return new JdbcTaskStore(jdbc, transactions).poll(agentId,
                        new PollRequest(List.of(), 1, List.of("command"))).task();
            })).toList();
            claimStart.countDown();
            var claimed = new HashSet<String>();
            for (var future : futures) {
                var claimedTask = future.get();
                assertNotNull(claimedTask, "each concurrent Center poll must claim a task");
                claimed.add(claimedTask.id());
            }
            assertEquals(2, claimed.size(), "concurrent polls must not double-dispatch one task");
            // Release both claimed rows before the following lease-recovery
            // assertions.  Leaving dispatching rows alive would make this
            // test depend on an Agent callback that it does not run.
            for (var claimedId : claimed) {
                var claimedTask = store.find(claimedId).orElseThrow();
                store.updateState(agentId, claimedId,
                        new TaskUpdateRequest("running", null, null, Instant.now(), null, false),
                        claimedTask.attempt());
                store.updateState(agentId, claimedId,
                        new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false),
                        claimedTask.attempt());
            }
        }

        // An Agent that disappears after a lease is claimed must not leave a
        // task permanently dispatching. A subsequent poll requeues and claims
        // it, while a fresh adapter instance can still read the same state.
        var leaseTaskId = "task_it_lease_" + UUID.randomUUID().toString().replace("-", "");
        var leaseCommand = new TaskCommand(leaseTaskId, TaskKind.COMMAND, "command", "printf lease", "/tmp",
                Map.of(), 30, null, Instant.now());
        store.create(leaseTaskId, agentId, leaseCommand, "lease-key", leaseCommand.createdAt());
        assertEquals(leaseTaskId, store.poll(agentId, new PollRequest(List.of(), 1, List.of("command"))).task().id());
        jdbc.update("UPDATE rcm_task SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE task_id = ?", leaseTaskId);
        var reclaimed = store.poll(agentId, new PollRequest(List.of(), 1, List.of("command")));
        assertEquals(leaseTaskId, reclaimed.task().id(), "expired dispatch lease must be reclaimed");
        assertEquals(2, store.find(leaseTaskId).orElseThrow().attempt(), "reclaimed lease must increment attempt");
        var restartedStore = new JdbcTaskStore(jdbc, transactions);
        assertEquals(TaskStatus.DISPATCHING, restartedStore.find(leaseTaskId).orElseThrow().status());
        // The task is intentionally left dispatching for the restart read
        // above, then finalized so the next durable-lease scenario can use
        // the same host lane without an Agent callback.
        var reclaimedLease = store.find(leaseTaskId).orElseThrow();
        store.updateState(agentId, leaseTaskId,
                new TaskUpdateRequest("running", null, null, Instant.now(), null, false),
                reclaimedLease.attempt());
        store.updateState(agentId, leaseTaskId,
                new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false),
                reclaimedLease.attempt());

        // A no-timeout process recovered by the same Agent advertises its
        // durable task ID and renews the expired lease without dispatching a
        // second process. Timed tasks cannot be safely replayed and therefore
        // become an explicit failure after their lease expires.
        var durableLeaseId = "task_it_durable_" + UUID.randomUUID().toString().replace("-", "");
        var durableLeaseCommand = new TaskCommand(durableLeaseId, TaskKind.COMMAND, "command", "printf durable", "/tmp",
                Map.of(), 0, null, Instant.now());
        store.create(durableLeaseId, agentId, durableLeaseCommand, "durable-lease-key", durableLeaseCommand.createdAt());
        assertEquals(durableLeaseId, store.poll(agentId, new PollRequest(List.of(), 1, List.of("command"))).task().id());
        store.updateState(agentId, durableLeaseId, new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
        jdbc.update("UPDATE rcm_task SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE task_id = ?", durableLeaseId);
        var durableReconnect = store.poll(agentId, new PollRequest(List.of(durableLeaseId), 1, List.of("command")));
        assertNull(durableReconnect.task());
        assertEquals(TaskStatus.RUNNING, store.find(durableLeaseId).orElseThrow().status());
        var renewedDurable = store.find(durableLeaseId).orElseThrow();
        store.updateState(agentId, durableLeaseId,
                new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false),
                renewedDurable.attempt());

        var timedLeaseId = "task_it_timed_" + UUID.randomUUID().toString().replace("-", "");
        var timedLeaseCommand = new TaskCommand(timedLeaseId, TaskKind.COMMAND, "command", "printf timed", "/tmp",
                Map.of(), 30, null, Instant.now());
        store.create(timedLeaseId, agentId, timedLeaseCommand, "timed-lease-key", timedLeaseCommand.createdAt());
        assertEquals(timedLeaseId, store.poll(agentId, new PollRequest(List.of(durableLeaseId), 1, List.of("command"))).task().id());
        store.updateState(agentId, timedLeaseId, new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
        jdbc.update("UPDATE rcm_task SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE task_id = ?", timedLeaseId);
        store.poll(agentId, new PollRequest(List.of(durableLeaseId), 1, List.of("command")));
        assertEquals(TaskStatus.FAILED, store.find(timedLeaseId).orElseThrow().status());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the PostgreSQL integration test");
        }
        return value.trim();
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
