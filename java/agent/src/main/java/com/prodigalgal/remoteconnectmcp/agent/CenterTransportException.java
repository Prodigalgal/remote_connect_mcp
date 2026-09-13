package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;

/** HTTP failure that preserves the status code needed by reconnect policy. */
public final class CenterTransportException extends IOException {
    private final int statusCode;

    public CenterTransportException(String message, int statusCode) {
        super(message + " (HTTP " + statusCode + ")");
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
