package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Arrays;
import java.nio.file.Path;

@SpringBootApplication
public class RemoteConnectCenterApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--migrate".equals(args[0])) {
            // Native Image only emits an AOT initializer for the configured
            // application main class.  Starting a second, ad-hoc
            // SpringApplicationBuilder here makes the native executable look
            // for a LiquibaseMigrationApplication initializer that does not
            // exist. Reuse this AOT-compiled application and disable the web
            // server; DatabaseConfiguration runs Liquibase during context
            // creation and closing the context exits the one-shot job.
            var migrationArgs = Arrays.copyOfRange(args, 1, args.length);
            var bootArgs = new String[migrationArgs.length + 2];
            bootArgs[0] = "--spring.main.web-application-type=none";
            bootArgs[1] = "--spring.profiles.active=migrate";
            System.arraycopy(migrationArgs, 0, bootArgs, 2, migrationArgs.length);
            try (var context = SpringApplication.run(RemoteConnectCenterApplication.class, bootArgs)) {
                // Liquibase has completed successfully when the context opens.
            }
            return;
        }
        if (args.length == 2 && "--import-go".equals(args[0])) {
            System.exit(GoStateImportApplication.run(Path.of(args[1])));
            return;
        }
        SpringApplication.run(RemoteConnectCenterApplication.class, args);
    }
}
