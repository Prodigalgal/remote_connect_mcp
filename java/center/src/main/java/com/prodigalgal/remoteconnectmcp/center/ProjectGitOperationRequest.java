package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicit, audited Git operation requested for a registered project. */
public record ProjectGitOperationRequest(
        @JsonProperty("worktree_id") String worktreeId,
        String ref,
        String message,
        String mode,
        @JsonProperty("idempotency_key") String idempotencyKey) {
}
