package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.prodigalgal.remotecontrolmcp.protocol.UpgradeComponentPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseManifestServiceTest {
    @Test
    void missingOrPartiallyInvalidManifestCannotBeMistakenForCoreOnly() {
        var version = "v2.0.0";
        var valid = new UpgradeComponentPlan("browser-agent", version, "linux", "amd64",
                "https://example.test/browser.zip", "a".repeat(64), 123L, "drain-and-restart");
        var invalid = new UpgradeComponentPlan("desktop-companion", version, "linux", "amd64",
                "http://example.test/desktop.zip", "b".repeat(64), 123L, "drain-and-restart");

        assertNull(ReleaseManifestService.normalizeComponents(null, version));
        assertNull(ReleaseManifestService.normalizeComponents(
                new ReleaseManifestService.ManifestWire(version, List.of(valid, invalid)), version));
        assertEquals(List.of(), ReleaseManifestService.normalizeComponents(
                new ReleaseManifestService.ManifestWire(version, List.of()), version));
        assertEquals(List.of(valid), ReleaseManifestService.normalizeComponents(
                new ReleaseManifestService.ManifestWire(version, List.of(valid)), version));
    }
}
