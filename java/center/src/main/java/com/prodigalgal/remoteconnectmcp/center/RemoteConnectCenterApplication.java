package com.prodigalgal.remoteconnectmcp.center;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import java.nio.file.Path;

@SpringBootApplication
@ImportRuntimeHints(RemoteConnectCenterRuntimeHints.class)
public class RemoteConnectCenterApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--migrate".equals(args[0])) {
            System.exit(runMigration());
            return;
        }
        if (args.length == 2 && "--import-go".equals(args[0])) {
            System.exit(GoStateImportApplication.run(Path.of(args[1])));
            return;
        }
        var application = new SpringApplication(RemoteConnectCenterApplication.class);
        application.addInitializers(new DatabaseRuntimeInitializer());
        application.run(args);
    }

    /**
     * Run the schema migration without creating a Spring application context.
     * The normal Center context includes Servlet/WebSocket infrastructure which
     * is not valid in a one-shot Native Image process.  Keeping this path
     * explicit also makes the Kubernetes migration Job independent of the HTTP
     * server lifecycle.
     */
    private static int runMigration() {
        var mode = setting("RCM_CENTER_PERSISTENCE_MODE", "").trim();
        if (!"postgres".equalsIgnoreCase(mode)) {
            System.err.println("Liquibase migration requires RCM_CENTER_PERSISTENCE_MODE=postgres");
            return 2;
        }
        try {
            var config = new HikariConfig();
            config.setJdbcUrl(requiredSetting("RCM_CENTER_DATABASE_URL"));
            config.setUsername(requiredSetting("RCM_CENTER_DATABASE_USERNAME"));
            config.setPassword(setting("RCM_CENTER_DATABASE_PASSWORD", ""));
            config.setPoolName("rcm-migration");
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(5000);
            config.setInitializationFailTimeout(10000);
            try (var dataSource = new HikariDataSource(config)) {
                var liquibase = new SpringLiquibase();
                liquibase.setDataSource(dataSource);
                liquibase.setResourceLoader(new DefaultResourceLoader(RemoteConnectCenterApplication.class.getClassLoader()));
                liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
                liquibase.setContexts("postgres");
                liquibase.setShouldRun(Boolean.parseBoolean(setting("RCM_CENTER_LIQUIBASE_ENABLED", "true")));
                liquibase.afterPropertiesSet();
                return 0;
            }
        } catch (Exception exception) {
            var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            System.err.println("Liquibase migration failed: " + redact(message));
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
        if (property != null && !property.isBlank()) {
            return property;
        }
        var environment = System.getenv(key);
        return environment == null ? defaultValue : environment;
    }

    private static String redact(String message) {
        return message.replaceAll("(?i)(password|passwd|token|secret)=([^,;\\s]+)", "$1=<redacted>");
    }
}
