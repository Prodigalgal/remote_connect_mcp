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
}
