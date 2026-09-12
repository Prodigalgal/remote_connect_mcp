package com.prodigalgal.remoteconnectmcp.protocol;

/** A verified release artifact advertised for one OS/architecture pair. */
public record UpgradeArtifact(
        String os,
        String arch,
        String url,
        String sha256) {
}
