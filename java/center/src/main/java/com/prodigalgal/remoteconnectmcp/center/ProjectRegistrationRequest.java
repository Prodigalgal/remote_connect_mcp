package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Admin request for registering an Agent-local repository boundary. */
public record ProjectRegistrationRequest(
        @JsonProperty("machine_id") String machineId,
        String name,
        @JsonProperty("root_path") String rootPath,
        @JsonProperty("repository_path") String repositoryPath,
        @JsonProperty("default_ref") String defaultRef) {
}
