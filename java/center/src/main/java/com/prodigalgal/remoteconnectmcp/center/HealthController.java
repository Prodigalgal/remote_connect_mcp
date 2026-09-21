package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final String version;
    private final String persistenceMode;
    private final boolean requireDurableStorage;
    private final JdbcTemplate jdbc;
    private final ArtifactStore artifactStore;
    private final ArtifactTransferService transfers;
    private final CenterAsyncExecutor async;
    private final CenterOAuthConfig oauth;

    @org.springframework.beans.factory.annotation.Autowired
    public HealthController(@Value("${rcm.version:dev}") String version,
                            @Value("${rcm.persistence.mode:memory}") String persistenceMode,
                            @Value("${rcm.persistence.require-durable:false}") boolean requireDurableStorage,
                            ObjectProvider<JdbcTemplate> jdbcProvider,
                            ObjectProvider<ArtifactStore> artifactProvider,
                            ObjectProvider<ArtifactTransferService> transferProvider,
                            CenterAsyncExecutor async,
                            CenterOAuthConfig oauth) {
        this.version = version;
        this.persistenceMode = persistenceMode;
        this.requireDurableStorage = requireDurableStorage;
        this.jdbc = jdbcProvider.getIfAvailable();
        this.artifactStore = artifactProvider.getIfAvailable();
        this.transfers = transferProvider == null ? null : transferProvider.getIfAvailable();
        this.async = async;
        this.oauth = oauth;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "center", "version", version, "time", Instant.now().toString());
    }

    @GetMapping("/readyz")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> ready() {
        return async.submit(this::readySync)
                .exceptionally(failure -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "not_ready", "reason", "readiness check failed")));
    }

    private ResponseEntity<Map<String, Object>> readySync() {
        if (requireDurableStorage && !"postgres".equalsIgnoreCase(persistenceMode)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "not_ready", "reason", "durable PostgreSQL persistence is required"));
        }
        if ("postgres".equalsIgnoreCase(persistenceMode)) {
            if (jdbc == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "not_ready", "reason", "postgres datasource is unavailable"));
            }
            if (artifactStore == null || "memory-test".equalsIgnoreCase(artifactStore.backend())) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "not_ready", "reason", "durable artifact storage is unavailable"));
            }
            try {
                // The core table is created by the Liquibase migration Job. A
                // successful SELECT therefore proves both connectivity and
                // that the application is not routing before schema install.
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM rcm_agent)", Boolean.class);
            } catch (DataAccessException exception) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "not_ready", "reason", "database migration is not ready"));
            }
        }
        if (oauth.enabled() && !oauth.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "not_ready", "reason",
                            "OAuth is enabled but issuer/resource must be matching HTTPS public URLs"));
        }
        if (transfers != null) {
            var artifactFailure = transfers.readinessFailure(requireDurableStorage || "postgres".equalsIgnoreCase(persistenceMode));
            if (artifactFailure != null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "not_ready", "reason", artifactFailure));
            }
        }
        return ResponseEntity.ok(Map.of("status", "ready", "migrationStage", "java", "persistence", persistenceMode,
                "oauth", oauth.enabled() ? "configured" : "disabled"));
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("service", "center", "version", version, "implementation", "java");
    }

}
