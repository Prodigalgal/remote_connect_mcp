package com.prodigalgal.remoteconnectmcp.center;

import java.nio.file.Path;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** One-shot command-line entrypoint for importing the legacy Go file store. */
public final class GoStateImportApplication {
    private GoStateImportApplication() {
    }

    public static int run(Path statePath) {
        System.setProperty("rcm.persistence.mode", "postgres");
        System.setProperty("RCM_CENTER_LIQUIBASE_ENABLED", "false");
        // This configuration class lives in the Center package and would be
        // discovered by Spring component scanning during a normal HTTP start.
        // Keep the one-shot importer opt-in so memory-mode Center startup does
        // not require a JdbcTemplate or a PostgreSQL datasource.
        System.setProperty("spring.profiles.active", "go-state-import");
        var application = new SpringApplication(ImportConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = null;
        try {
            context = application.run("--spring.main.web-application-type=none",
                    "--RCM_CENTER_LIQUIBASE_ENABLED=false");
            var summary = context.getBean(GoStateImporter.class).importState(statePath);
            System.out.printf("Imported Go state: machines=%d enrollments=%d tasks=%d output_bytes=%d artifacts=%d%n",
                    summary.machines(), summary.enrollments(), summary.tasks(), summary.outputBytes(), summary.artifacts());
            return 0;
        } catch (Exception exception) {
            System.err.println("Go state import failed: " + (exception.getMessage() == null ? exception : exception.getMessage()));
            return 1;
        } finally {
            if (context != null) context.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("go-state-import")
    @EnableAutoConfiguration
    @Import(DatabaseConfiguration.class)
    static class ImportConfiguration {
        @Bean
        GoStateImporter goStateImporter(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                        org.springframework.transaction.support.TransactionTemplate transactions) {
            return new GoStateImporter(jdbc, transactions);
        }
    }
}
