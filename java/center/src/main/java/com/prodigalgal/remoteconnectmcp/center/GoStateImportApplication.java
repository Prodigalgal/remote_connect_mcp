package com.prodigalgal.remoteconnectmcp.center;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-shot command-line entrypoint for importing the legacy Go file store. */
public final class GoStateImportApplication {
    private GoStateImportApplication() {
    }

    public static int run(Path statePath) {
        try {
            if (!"postgres".equalsIgnoreCase(setting("RCM_CENTER_PERSISTENCE_MODE", ""))) {
                throw new IllegalStateException("Go state import requires RCM_CENTER_PERSISTENCE_MODE=postgres");
            }
            var config = new HikariConfig();
            config.setJdbcUrl(requiredSetting("RCM_CENTER_DATABASE_URL"));
            config.setUsername(requiredSetting("RCM_CENTER_DATABASE_USERNAME"));
            config.setPassword(setting("RCM_CENTER_DATABASE_PASSWORD", ""));
            config.setPoolName("rcm-go-import");
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(5000);
            config.setInitializationFailTimeout(10000);
            try (var dataSource = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(dataSource);
                var transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
                var summary = new GoStateImporter(jdbc, transactions).importState(statePath);
                System.out.printf("Imported Go state: machines=%d enrollments=%d tasks=%d output_bytes=%d artifacts=%d%n",
                        summary.machines(), summary.enrollments(), summary.tasks(), summary.outputBytes(), summary.artifacts());
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("Go state import failed: " + (exception.getMessage() == null ? exception : exception.getMessage()));
            return 1;
        }
    }

    private static String requiredSetting(String key) {
        var value = setting(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(key + " is required");
        }
        return value;
    }

    private static String setting(String key, String defaultValue) {
        var property = System.getProperty(key);
        if (property != null && !property.isBlank()) return property;
        var environment = System.getenv(key);
        return environment == null ? defaultValue : environment;
    }
}
