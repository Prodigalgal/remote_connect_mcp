package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/** Keeps the Liquibase changelog available to the direct Native migration path. */
public final class RemoteConnectCenterRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/.*");
    }
}
