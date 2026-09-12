package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Prevents a memory-mode instance from becoming ready in a durable deployment. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rcm.persistence.mode=memory",
                "rcm.persistence.require-durable=true"
        })
class HealthControllerTest {
    @Autowired
    private HealthController health;

    @Test
    void memoryModeFailsDurableReadinessGuard() throws Exception {
        var response = health.ready().get(5, TimeUnit.SECONDS);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("durable PostgreSQL persistence is required", response.getBody().get("reason"));
    }
}
