package com.prodigalgal.remoteconnectmcp.center;

import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Supplier;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers the PostgreSQL adapter from the runtime environment.
 *
 * <p>This initializer runs after the environment has been prepared but before
 * the application context creates any services. Unlike a Spring conditional,
 * it is not evaluated during Native Image AOT analysis, so a single native
 * binary can still support both memory protocol smoke tests and production
 * PostgreSQL deployments.</p>
 */
public final class DatabaseRuntimeInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        if (!"postgres".equalsIgnoreCase(setting(environment,
                "RCM_CENTER_PERSISTENCE_MODE", "rcm.persistence.mode", "memory"))) {
            return;
        }

        var registry = (BeanDefinitionRegistry) context.getBeanFactory();
        var beanFactory = context.getBeanFactory();
        var url = requiredSetting(environment, "RCM_CENTER_DATABASE_URL", "rcm.database.url");
        var username = requiredSetting(environment, "RCM_CENTER_DATABASE_USERNAME", null);
        var password = setting(environment, "RCM_CENTER_DATABASE_PASSWORD", null, "");
        var liquibaseEnabled = Boolean.parseBoolean(setting(environment,
                "RCM_CENTER_LIQUIBASE_ENABLED", null, "true"));

        register(registry, "dataSource", HikariDataSource.class,
                () -> DatabaseConfiguration.dataSource(url, username, password), "close");
        register(registry, "jdbcTemplate", JdbcTemplate.class,
                () -> DatabaseConfiguration.jdbcTemplate(beanFactory.getBean(DataSource.class)), null);
        register(registry, "transactionManager", JdbcTransactionManager.class,
                () -> DatabaseConfiguration.transactionManager(beanFactory.getBean(DataSource.class)), null);
        register(registry, "transactionTemplate", TransactionTemplate.class,
                () -> DatabaseConfiguration.transactionTemplate(beanFactory.getBean(JdbcTransactionManager.class)), null);
        register(registry, "liquibase", SpringLiquibase.class,
                () -> DatabaseConfiguration.liquibase(beanFactory.getBean(DataSource.class), liquibaseEnabled), null);
    }

    private static <T> void register(BeanDefinitionRegistry registry, String name, Class<T> type,
                                     Supplier<T> supplier, String destroyMethod) {
        if (registry.containsBeanDefinition(name)) return;
        var definition = BeanDefinitionBuilder.rootBeanDefinition(type).getBeanDefinition();
        definition.setInstanceSupplier(supplier);
        if (destroyMethod != null) definition.setDestroyMethodName(destroyMethod);
        registry.registerBeanDefinition(name, definition);
    }

    private static String requiredSetting(Environment environment, String envKey, String propertyKey) {
        var value = setting(environment, envKey, propertyKey, "").trim();
        if (value.isEmpty()) throw new IllegalStateException(envKey + " is required in postgres mode");
        return value;
    }

    private static String setting(Environment environment, String envKey, String propertyKey, String defaultValue) {
        var value = environment.getProperty(envKey);
        if (value == null && propertyKey != null) value = environment.getProperty(propertyKey);
        if (value == null) value = System.getProperty(envKey);
        if (value == null) value = System.getenv(envKey);
        return value == null ? defaultValue : value;
    }
}
