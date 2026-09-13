package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PollResponse(
        TaskCommand task,
        @JsonProperty("cancel_task_ids") List<String> cancelTaskIds,
        UpgradePlan upgrade,
        AgentConfigUpdate config) {

    /** Compatibility constructor for the pre-hot-reload wire shape. */
    public PollResponse(TaskCommand task, List<String> cancelTaskIds, UpgradePlan upgrade) {
        this(task, cancelTaskIds, upgrade, null);
    }

    public PollResponse {
        cancelTaskIds = cancelTaskIds == null ? List.of() : List.copyOf(cancelTaskIds);
    }

    public static PollResponse empty() {
        return new PollResponse(null, List.of(), null, null);
    }
}
