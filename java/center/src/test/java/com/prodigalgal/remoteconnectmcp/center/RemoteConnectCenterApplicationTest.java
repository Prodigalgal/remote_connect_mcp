package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Guards normal HTTP startup from accidentally loading one-shot CLI configs. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rcm.persistence.mode=memory",
                "rcm.version=context-test"
        })
class RemoteConnectCenterApplicationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void memoryModeStartsWithoutImporterDatasource() {
        assertNotNull(context.getBean(RemoteConnectCenterApplication.class));
        assertFalse(context.containsBean("goStateImporter"));
    }
}
