package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Admin request for an isolated Agent-local Git worktree. */
public record ProjectWorktreeRequest(
        String ref,
        @JsonProperty("idempotency_key") String idempotencyKey) {
}
