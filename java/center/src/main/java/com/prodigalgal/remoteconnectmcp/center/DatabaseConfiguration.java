package com.prodigalgal.remoteconnectmcp.center;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL is opt-in during the migration. The default memory mode keeps the
 * scaffold and protocol tests runnable without a local database.
 */
@Configuration
@ConditionalOnProperty(name = "rcm.persistence.mode", havingValue = "postgres")
public class DatabaseConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(
            @Value("${RCM_CENTER_DATABASE_URL:}") String url,
            @Value("${RCM_CENTER_DATABASE_USERNAME:}") String username,
            @Value("${RCM_CENTER_DATABASE_PASSWORD:}") String password) {
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

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(JdbcTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public SpringLiquibase liquibase(DataSource dataSource,
                                     @Value("${RCM_CENTER_LIQUIBASE_ENABLED:true}") boolean enabled) {
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("postgres");
        liquibase.setShouldRun(enabled);
        return liquibase;
    }
}
