package com.prodigalgal.remoteconnectmcp.center;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runtime PostgreSQL bean factories.
 *
 * <p>The Center selects its persistence mode from environment variables at
 * runtime. Keeping these factories free of Spring conditional annotations is
 * intentional: Native AOT evaluates {@code @ConditionalOnProperty} while the
 * image is built, before the deployment environment is available. The
 * {@link DatabaseRuntimeInitializer} registers these beans only when the
 * running process selected PostgreSQL.</p>
 */
public final class DatabaseConfiguration {
    private DatabaseConfiguration() {
    }

    public static HikariDataSource dataSource(String url, String username, String password) {
        if (url.isBlank() || username.isBlank()) {
            throw new IllegalStateException("RCM_CENTER_DATABASE_URL and RCM_CENTER_DATABASE_USERNAME are required in postgres mode");
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url.trim());
        config.setUsername(username.trim());
        config.setPassword(password);
        config.setPoolName("rcm-center");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(10000);
        return new HikariDataSource(config);
    }

    public static JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    public static JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    public static TransactionTemplate transactionTemplate(JdbcTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    public static SpringLiquibase liquibase(DataSource dataSource, boolean enabled) {
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("postgres");
        liquibase.setShouldRun(enabled);
        liquibase.setResourceLoader(new DefaultResourceLoader(DatabaseConfiguration.class.getClassLoader()));
        return liquibase;
    }
}
