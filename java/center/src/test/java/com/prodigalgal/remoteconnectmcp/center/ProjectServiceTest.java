package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectServiceTest {
    @Test
    void registersProjectCreatesIsolatedWorktreeAndResolvesOnlyAfterTaskCompletes() {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("builder", "host-a", "host-a", "linux", "amd64",
                "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command")), "enrollment");
        var tasks = new TaskService(registry);
        var projects = new ProjectService(registry, tasks);

        var project = projects.register(new ProjectRegistrationRequest(registration.machineId(), "demo", "/srv/demo", null, "main"));
        assertEquals("/srv/demo", project.rootPath());
        assertEquals("/srv/demo", project.repositoryPath());

        var worktree = projects.createWorktree(project.id(), new ProjectWorktreeRequest("feature/one", "create-1"));
        assertTrue(worktree.path().startsWith("/srv/demo/.rcm-worktrees/worktree_"));
        assertEquals("queued", worktree.status());
        assertTrue(worktree.taskId() != null && !worktree.taskId().isBlank());
        assertThrows(IllegalArgumentException.class,
                () -> projects.resolveCwd(registration.machineId(), project.id(), worktree.id(), null));

        var dispatched = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        tasks.updateState(registration.machineId(), dispatched.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));
        tasks.updateState(registration.machineId(), dispatched.id(),
                new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false));

        var ready = projects.find(project.id()).worktrees().stream()
                .filter(value -> value.id().equals(worktree.id())).findFirst().orElseThrow();
        assertEquals("ready", ready.status());
        assertEquals(ready.path(), projects.resolveCwd(registration.machineId(), project.id(), worktree.id(), null));
        assertEquals(ready.path() + "/src", projects.resolveCwd(registration.machineId(), project.id(), worktree.id(), "src"));
        assertThrows(IllegalArgumentException.class,
                () -> projects.resolveCwd(registration.machineId(), project.id(), worktree.id(), "../../etc"));
    }

    @Test
    void worktreeCreateIsIdempotentAndProjectBoundaryRejectsUnsafePaths() {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("builder", "host-a", "host-a", "linux", "amd64",
                "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command")), "enrollment");
        var tasks = new TaskService(registry);
        var projects = new ProjectService(registry, tasks);
        var project = projects.register(new ProjectRegistrationRequest(registration.machineId(), "demo", "/srv/demo", null, "HEAD"));
        var first = projects.createWorktree(project.id(), new ProjectWorktreeRequest("HEAD", "same"));
        var second = projects.createWorktree(project.id(), new ProjectWorktreeRequest("HEAD", "same"));
        assertEquals(first.id(), second.id());
        assertFalse(first.path().contains(".."));

        assertThrows(IllegalArgumentException.class, () -> projects.register(
                new ProjectRegistrationRequest(registration.machineId(), "bad", "/srv/demo;rm", null, "HEAD")));
        assertThrows(IllegalArgumentException.class, () -> projects.register(
                new ProjectRegistrationRequest(registration.machineId(), "outside", "/srv/demo", "/tmp/repo", "HEAD")));
    }

    @Test
    void gitOperationsAreScopedAndMutationsRequireIdempotency() {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("builder", "host-a", "host-a", "linux", "amd64",
                "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command")), "enrollment");
        var tasks = new TaskService(registry);
        var projects = new ProjectService(registry, tasks);
        var project = projects.register(new ProjectRegistrationRequest(registration.machineId(), "demo", "/srv/demo", null, "main"));

        var status = projects.gitOperation(project.id(), "status", new ProjectGitOperationRequest("", "", "", "", ""));
        assertTrue(status.command().command().contains("git -C '/srv/demo' status"));
        assertEquals(ScopeMode.PROJECT, status.command().contract().scopeMode());
        assertThrows(IllegalArgumentException.class, () -> projects.gitOperation(project.id(), "commit",
                new ProjectGitOperationRequest("", "", "message", "", "")));

        var request = new ProjectGitOperationRequest("", "", "message", "", "commit-1");
        var first = projects.gitOperation(project.id(), "commit", request);
        var retry = projects.gitOperation(project.id(), "commit", request);
        assertEquals(first.id(), retry.id(), "commit retries must be idempotent");
        var abort = projects.gitOperation(project.id(), "merge_abort",
                new ProjectGitOperationRequest("", "", "", "", "abort-1"));
        assertTrue(abort.command().command().contains("git -C '/srv/demo' merge --abort"));
        assertThrows(IllegalArgumentException.class, () -> projects.gitOperation(project.id(), "merge",
                new ProjectGitOperationRequest("", "feature/../main", "", "", "merge-1")));
    }

    @Test
    void windowsGitPathsRejectDelayedExpansionBeforeQueueing() {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("builder-win", "host-win", "host-win", "windows", "amd64",
                "dev", "C:\\", ScopeMode.UNRESTRICTED, null, List.of("command")), "enrollment");
        var tasks = new TaskService(registry);
        var projects = new ProjectService(registry, tasks);
        var project = projects.register(new ProjectRegistrationRequest(registration.machineId(), "demo", "C:\\workspace\\demo!", null, "main"));

        assertThrows(IllegalArgumentException.class,
                () -> projects.gitOperation(project.id(), "status", new ProjectGitOperationRequest("", "", "", "", "")));
        assertTrue(tasks.list(0, 10).isEmpty(), "unsafe Windows path must not enqueue a Git task");
    }

    @Test
    void projectRemovalRequiresInactiveTasksAndExplicitWorktreeCleanup() {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("builder", "host-a", "host-a", "linux", "amd64",
                "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command")), "enrollment");
        var tasks = new TaskService(registry);
        var projects = new ProjectService(registry, tasks);
        var project = projects.register(new ProjectRegistrationRequest(registration.machineId(), "demo", "/srv/demo", null, "main"));
        var worktree = projects.createWorktree(project.id(), new ProjectWorktreeRequest("feature/remove", "remove-setup"));
        assertThrows(IllegalArgumentException.class, () -> projects.remove(project.id()));

        var createTask = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        tasks.updateState(registration.machineId(), createTask.id(), new TaskUpdateRequest("running", null, null, null, null, false));
        tasks.updateState(registration.machineId(), createTask.id(), new TaskUpdateRequest("completed", 0, null, null, null, false));
        var ready = projects.find(project.id()).worktrees().stream().filter(value -> value.id().equals(worktree.id())).findFirst().orElseThrow();
        assertEquals("ready", ready.status());
        assertThrows(IllegalArgumentException.class, () -> projects.remove(project.id()));

        var remove = projects.removeWorktree(project.id(), worktree.id(), "remove-worktree");
        var removeTask = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        tasks.updateState(registration.machineId(), removeTask.id(), new TaskUpdateRequest("running", null, null, null, null, false));
        tasks.updateState(registration.machineId(), removeTask.id(), new TaskUpdateRequest("completed", 0, null, null, null, false));
        assertEquals("removed", projects.find(project.id()).worktrees().stream()
                .filter(value -> value.id().equals(remove.id())).findFirst().orElseThrow().status());
        assertEquals(project.id(), projects.remove(project.id()).id());
        assertThrows(IllegalArgumentException.class, () -> projects.find(project.id()));
    }
}
