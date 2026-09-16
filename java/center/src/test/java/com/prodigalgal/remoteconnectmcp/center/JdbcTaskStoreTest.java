package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcTaskStoreTest {
    @Test
    void taskProjectionIncludesExitCodeForRestartedTaskReads() {
        assertTrue(JdbcTaskStore.SELECT_TASK_META.contains("t.exit_code"));
    }

    @Test
    void createBindsEveryTaskColumnAndInitialOutputRow() {
        var jdbc = new RecordingJdbcTemplate();
        var transaction = new InlineTransactionTemplate();
        var store = new JdbcTaskStore(jdbc, transaction);
        var command = new TaskCommand("ignored", TaskKind.COMMAND, "command", "echo ok", "/tmp", java.util.Map.of(), 0, null, Instant.now());

        var view = store.create("task_1", "machine_1", command, null, command.createdAt());

        assertEquals("task_1", view.id());
        assertEquals(2, jdbc.calls.size());
        assertEquals(22, jdbc.calls.get(0).arguments.length,
                "rcm_task now binds principal/connection/lane ownership plus the existing task columns");
        assertTrue(jdbc.calls.get(0).sql.contains("INSERT INTO rcm_task"));
        assertTrue(jdbc.calls.get(1).sql.contains("rcm_task_output"));
    }

    private static final class InlineTransactionTemplate extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public int update(String sql, Object... arguments) {
            calls.add(new Call(sql, arguments));
            return 1;
        }
    }

    private record Call(String sql, Object[] arguments) {
    }
}
