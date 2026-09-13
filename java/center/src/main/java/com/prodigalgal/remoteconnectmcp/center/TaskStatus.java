package com.prodigalgal.remoteconnectmcp.center;

/** Stable task state values shared by MCP, Center and Agent transports. */
public final class TaskStatus {
    public static final String QUEUED = "queued";
    public static final String DISPATCHING = "dispatching";
    public static final String RUNNING = "running";
    public static final String CANCEL_REQUESTED = "cancel_requested";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELED = "canceled";

    private TaskStatus() {
    }

    public static boolean terminal(String value) {
        return COMPLETED.equals(value) || FAILED.equals(value) || CANCELED.equals(value);
    }
}
