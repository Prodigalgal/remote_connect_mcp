package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorkspacePolicyTest {
    @Test
    void validatesLinuxRelativeAndRejectsTraversal() {
        assertDoesNotThrow(() -> WorkspacePolicy.validateRemote(ScopeMode.WORKSPACE, "linux",
                "/srv/project", "/srv/project/src", "lib"));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePolicy.validateRemote(ScopeMode.WORKSPACE,
                "linux", "/srv/project", "/srv/project/src", "../../etc"));
    }

    @Test
    void validatesWindowsSeparatorsAndDriveRules() {
        assertDoesNotThrow(() -> WorkspacePolicy.validateRemote(ScopeMode.WORKSPACE, "windows",
                "C:\\Work\\Project", "C:\\Work\\Project\\src", "..\\tests"));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePolicy.validateRemote(ScopeMode.WORKSPACE,
                "windows", "C:\\Work\\Project", "C:\\Work\\Project", "D:\\tmp"));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePolicy.validateRemote(ScopeMode.WORKSPACE,
                "windows", "C:\\Work\\Project", "C:\\Work\\Project", "C:tmp"));
    }

    @Test
    void unrestrictedModePreservesCompatibility() {
        assertDoesNotThrow(() -> WorkspacePolicy.validateRemote(ScopeMode.UNRESTRICTED, "linux",
                null, null, "/outside"));
    }
}
