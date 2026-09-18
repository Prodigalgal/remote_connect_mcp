package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Small, Center-issued description of one binary file transfer.
 *
 * <p>The action contains references and target paths only. File bytes never
 * travel in a task JSON payload; the Agent obtains or uploads them through
 * the authenticated transfer stream.</p>
 */
public record FileTransferAction(
        String direction,
        @JsonProperty("transfer_id") String transferId,
        @JsonProperty("artifact_id") String artifactId,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("destination_path") String destinationPath,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("expected_bytes") long expectedBytes,
        @JsonProperty("expected_sha256") String expectedSha256,
        boolean overwrite) {

    public static final String WEB_TO_AGENT = "web_to_agent";
    public static final String AGENT_TO_WEB = "agent_to_web";

    public FileTransferAction {
        direction = direction == null ? "" : direction.trim().toLowerCase(java.util.Locale.ROOT);
        transferId = transferId == null ? "" : transferId.trim();
        artifactId = artifactId == null ? "" : artifactId.trim();
        sourcePath = sourcePath == null ? "" : sourcePath.trim();
        destinationPath = destinationPath == null ? "" : destinationPath.trim();
        fileName = fileName == null ? "" : fileName.trim();
        mimeType = mimeType == null ? "" : mimeType.trim().toLowerCase(java.util.Locale.ROOT);
        expectedSha256 = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (expectedBytes < 0) throw new IllegalArgumentException("expectedBytes must be non-negative");
    }

    public boolean webToAgent() {
        return WEB_TO_AGENT.equals(direction);
    }

    public boolean agentToWeb() {
        return AGENT_TO_WEB.equals(direction);
    }

    /**
     * The destination path is the complete target file path.  {@code file_name}
     * is metadata shown to a user and is never appended to this path; keeping
     * that distinction avoids directory/file ambiguity during retries.
     */
    public String targetPath() {
        return destinationPath;
    }

    /** A stable display name, falling back to the final path segment. */
    public String displayName() {
        if (!fileName.isBlank()) return fileName;
        var value = agentToWeb() ? sourcePath : destinationPath;
        var slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }
}
