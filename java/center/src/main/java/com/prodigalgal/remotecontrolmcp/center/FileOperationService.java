package com.prodigalgal.remotecontrolmcp.center;

import com.prodigalgal.remotecontrolmcp.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Shared MCP/Console adapter onto the existing fenced task lifecycle. */
final class FileOperationService {
    static TaskView create(AgentRegistry agents, TaskService tasks, TaskOrigin origin,
                           String machineId, FileRequest request, String cwd, String retryKey) {
        request.validate();
        var machine = agents.findMachine(machineId, Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
        if (!machine.capabilities().contains("files")) throw new IllegalArgumentException("Agent upgrade required for native file management");
        if (!machine.online()) throw new IllegalArgumentException("machine is offline");
        var json = new String(JsonCodec.write(request), StandardCharsets.UTF_8);
        var task = new TaskCommand("", TaskKind.FILES, "files", json,
                cwd == null || cwd.isBlank() ? machine.defaultCwd() : cwd, Map.of(), 300, null, Instant.now());
        var risk = request.readOnly() ? "low" : "high";
        return tasks.create(new CreateTaskRequest(machineId, task, retryKey == null || retryKey.isBlank() ? UUID.randomUUID().toString() : retryKey,
                null, "", risk, false, origin), origin.isConfigured() ? "admin" : "mcp", origin);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> result(TaskService tasks, TaskOrigin origin, TaskView task) {
        if (!"files".equals(task.kind()) || !"application/vnd.rcm.files+json".equals(task.artifactMime())) return null;
        var artifact = tasks.readArtifact(origin, task.id());
        if (artifact.isEmpty()) return null;
        var data = artifact.get().data();
        if (data.length > 96 * 1024) throw new IllegalArgumentException("file result exceeds its response limit");
        return JsonCodec.read(data, Map.class);
    }
}
