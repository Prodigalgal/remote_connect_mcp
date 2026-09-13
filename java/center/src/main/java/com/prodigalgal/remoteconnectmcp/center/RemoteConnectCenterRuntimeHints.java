package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;

/** Keeps the Liquibase changelog available to the direct Native migration path. */
public final class RemoteConnectCenterRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/.*");
        // Jackson 3 introspects record components through Class#getRecordComponents
        // at runtime. Native Image needs the accessors/constructors declared
        // explicitly; otherwise /api/v1/admin/releases fails while serializing
        // the catalog even though the GitHub fetch itself succeeded.
        var recordMembers = new MemberCategory[]{
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS};
        hints.reflection().registerType(ReleaseCatalogService.CatalogView.class, recordMembers);
        hints.reflection().registerType(ReleaseCatalogService.ReleaseView.class, recordMembers);
        hints.reflection().registerType(ReleaseCatalogService.ReleaseAssetView.class, recordMembers);
        // MCP's JSON error mapper inspects Throwable#getCause when a client
        // sends a malformed session/request.  Register the base Throwable
        // methods so Native Image returns a real JSON-RPC error instead of a
        // secondary MissingReflectionRegistrationError/HTTP 500.
        hints.reflection().registerType(Throwable.class, MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
