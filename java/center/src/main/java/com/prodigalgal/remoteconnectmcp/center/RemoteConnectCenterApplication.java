package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Arrays;
import java.nio.file.Path;

@SpringBootApplication
public class RemoteConnectCenterApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--migrate".equals(args[0])) {
            LiquibaseMigrationApplication.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 2 && "--import-go".equals(args[0])) {
            System.exit(GoStateImportApplication.run(Path.of(args[1])));
            return;
        }
        SpringApplication.run(RemoteConnectCenterApplication.class, args);
    }
}
