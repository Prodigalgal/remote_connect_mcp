package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/** Parse the complete changelog in CI even when no PostgreSQL is available locally. */
class LiquibaseChangelogTest {
    @Test
    void masterChangelogParses() {
        assertDoesNotThrow(() -> {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                    new OfflineConnection("offline:postgresql", new ClassLoaderResourceAccessor()));
            var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database);
            liquibase.validate();
        });
    }
}
