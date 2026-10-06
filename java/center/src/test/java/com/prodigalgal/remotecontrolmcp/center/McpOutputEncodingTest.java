package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.*;
import com.prodigalgal.remotecontrolmcp.protocol.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpOutputEncodingTest {
    @Test void commandFastResultAndTaskReadNormalizeGbkWithoutRerunningTheCommand() {
        var fixture = fixture();
        var bytes = "中文结果\n".getBytes(Charset.forName("GBK"));
        fixture.tasks.appendOutput(fixture.machineId, fixture.task.id(), 0, bytes);
        fixture.tasks.updateState(fixture.machineId, fixture.task.id(),
                new TaskUpdateRequest("completed", 0, null, null, null, false));
        var view = new TaskView(fixture.tasks.find(fixture.task.id()).orElseThrow());
        var immediate = McpConfiguration.immediateTaskResult(fixture.tasks, TaskOrigin.configured(), view);
        assertEquals("中文结果\n", output(immediate).get("text"));
        assertEquals("UTF-8", output(immediate).get("encoding"));
        var explicit = read(fixture, Map.of("task_id", fixture.task.id(), "source_encoding", "GBK"));
        assertEquals("中文结果\n", output(explicit).get("text"));
        assertEquals((long) bytes.length, output(explicit).get("next_cursor"));
        assertEquals("GBK", output(explicit).get("source_encoding"));
        assertEquals(1, fixture.tasks.totalCount());
        assertArrayEquals(bytes, fixture.tasks.readOutput(fixture.task.id(), 0, 64).data());
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(McpConfiguration.modelOutputSchema("task_read"),
                (Map<String, Object>) explicit.structuredContent()).valid());
    }

    @Test void utf16AndLegacySourceOverridesReturnTheSameUtf8Text() {
        for (var encoding : List.of("UTF-16LE", "UTF-32BE", "windows-1252", "ISO-2022-JP")) {
            var fixture = fixture();
            var text = encoding.equals("windows-1252") ? "café €" : "日本語";
            fixture.tasks.appendOutput(fixture.machineId, fixture.task.id(), 0, text.getBytes(Charset.forName(encoding)));
            fixture.tasks.updateState(fixture.machineId, fixture.task.id(),
                    new TaskUpdateRequest("completed", 0, null, null, null, false));
            var result = read(fixture, Map.of("task_id", fixture.task.id(), "source_encoding", encoding));
            assertEquals(text, output(result).get("text"));
            assertEquals("UTF-8", output(result).get("encoding"));
        }
    }

    @Test void partialUtf8IsPendingInsteadOfCorruptedAndACompletedInvalidByteIsVisible() {
        var fixture = fixture();
        var bytes = "中".getBytes(StandardCharsets.UTF_8);
        fixture.tasks.appendOutput(fixture.machineId, fixture.task.id(), 0, new byte[] {bytes[0]});
        var partial = read(fixture, Map.of("task_id", fixture.task.id(), "source_encoding", "UTF-8"));
        assertEquals("", output(partial).get("text"));
        assertEquals(1, output(partial).get("pending_bytes"));
        assertEquals(0L, output(partial).get("next_cursor"));
        fixture.tasks.updateState(fixture.machineId, fixture.task.id(),
                new TaskUpdateRequest("completed", 0, null, null, null, false));
        var invalid = read(fixture, Map.of("task_id", fixture.task.id(), "source_encoding", "UTF-8"));
        assertEquals("\ufffd", output(invalid).get("text"));
        assertEquals(true, output(invalid).get("decoding_error"));
    }

    @Test void unsupportedCharsetIsRejectedBeforeCreatingOrWaitingForATask() {
        var fixture = fixture();
        var command = McpConfiguration.commandModel(fixture.registry, fixture.tasks, new McpAccessService(),
                TaskOrigin.configured(), new McpSchema.CallToolRequest("command", Map.of("machine_id", fixture.machineId,
                        "command", "ignored", "source_encoding", "bogus-charset"), Map.of()));
        assertTrue(Boolean.TRUE.equals(command.isError()));
        assertEquals(1, fixture.tasks.totalCount());
        assertTrue(Boolean.TRUE.equals(read(fixture, Map.of("task_id", fixture.task.id(),
                "source_encoding", "bogus-charset", "wait_ms", 20000)).isError()));
        assertFalse(McpJsonDefaults.getSchemaValidator().validate(McpConfiguration.taskReadModelSchema(),
                Map.of("task_id", fixture.task.id(), "include_output", false, "source_encoding", "GBK")).valid());
    }

    private static Map<?, ?> output(McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), result.content().toString());
        return (Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("output");
    }
    private static McpSchema.CallToolResult read(Fixture fixture, Map<String, Object> args) {
        return McpConfiguration.taskReadModel(fixture.tasks, new McpAccessService(), TaskOrigin.configured(),
                new McpSchema.CallToolRequest("task_read", args, Map.of()));
    }
    private static Fixture fixture() {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(new RegisterRequest("encoding-agent", "host", "host", "windows", "amd64",
                "test", "C:/", List.of("command")), "enroll");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(machine.machineId(),
                new TaskCommand("", null, null, "read-only", "C:/", Map.of(), 30, null, null), "test-encoding"));
        tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        tasks.updateState(machine.machineId(), task.id(), new TaskUpdateRequest("running", null, null, null, null, false));
        return new Fixture(registry, tasks, machine.machineId(), task);
    }
    private record Fixture(AgentRegistry registry, TaskService tasks, String machineId, TaskView task) {}
}
