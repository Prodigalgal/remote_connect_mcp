package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Runs Liquibase as a one-shot migration Job without starting an HTTP server. */
public final class LiquibaseMigrationApplication {
    private LiquibaseMigrationApplication() {
    }

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(DatabaseConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("rcm.persistence.mode=postgres", "RCM_CENTER_LIQUIBASE_ENABLED=true")
                .run(args)) {
            // Context creation runs Liquibase and fails the process on a
            // validation/update error; closing it releases the pool cleanly.
        }
    }
}
