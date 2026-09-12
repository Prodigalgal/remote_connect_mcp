package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class GoStateImporterTest {
    @Test
    void importsMachineAndTaskMetadataWithoutLoadingMissingOutput(@TempDir Path stateDir) throws Exception {
        Files.createDirectories(stateDir.resolve("task-output"));
        var hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Files.writeString(stateDir.resolve("state.json"), """
                {"machines":{"machine_1":{"id":"machine_1","name":"node-1","host_id":"host-1","hostname":"node-1","os":"linux","arch":"amd64","version":"v1","default_cwd":"/","capabilities":["command"],"token_hash":"%s","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:01Z","last_seen":"2026-01-01T00:00:02Z"}},"enrollments":{},"tasks":{"task_1":{"id":"task_1","machine_id":"machine_1","kind":"command","required_capability":"command","command":"echo ok","status":"completed","exit_code":0,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:03Z"}}}
                """.formatted(hash));
        var jdbc = new RecordingJdbcTemplate();
        var importer = new GoStateImporter(jdbc, new InlineTransactionTemplate());

        var summary = importer.importState(stateDir);

        assertEquals(1, summary.machines());
        assertEquals(1, summary.tasks());
        assertEquals(0, summary.outputBytes());
        assertTrue(jdbc.sql.stream().anyMatch(sql -> sql.contains("exit_code")));
    }

    private static final class InlineTransactionTemplate extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> sql = new ArrayList<>();

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            return 1;
        }
    }
}
