package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DesktopProcessBudgetTest {
    @Test
    void closeReapsLaunchedProcessAndReleasesGlobalReservation() throws Exception {
        var global = new AgentProcessBudget(2);
        var budget = new DesktopProcessBudget(2, global);
        var process = budget.start(sleepProcess());

        assertTrue(process.isAlive());
        assertEquals(1, global.usedProcesses());

        budget.close();
        process.onExit().get(5, TimeUnit.SECONDS);

        assertEquals(0, global.usedProcesses());
        assertThrows(IOException.class, () -> budget.start(sleepProcess()));
        budget.close();
    }

    @Test
    void failedStartDoesNotLeakSlotsOrGlobalReservation() {
        var global = new AgentProcessBudget(1);
        var budget = new DesktopProcessBudget(1, global);

        assertThrows(IOException.class, () -> budget.start(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "missing-java").toString())));
        assertEquals(0, global.usedProcesses());
        budget.close();
    }

    private static ProcessBuilder sleepProcess() {
        var executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return new ProcessBuilder(executable.toString(), "-cp", System.getProperty("java.class.path"),
                Sleeper.class.getName(), "30000");
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.parseLong(args[0]));
        }
    }
}
