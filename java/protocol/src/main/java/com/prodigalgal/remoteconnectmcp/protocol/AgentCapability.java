package com.prodigalgal.remoteconnectmcp.protocol;

public enum AgentCapability {
    COMMAND("command"),
    DURABLE_TASKS("durable_tasks"),
    DESKTOP("desktop"),
    BROWSER("browser"),
    FILE_TRANSFER("file_transfer");

    private final String wireValue;

    AgentCapability(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
