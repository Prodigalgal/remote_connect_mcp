package com.prodigalgal.remoteconnectmcp.protocol;

public record OutputRequest(Long offset, String data) {
    public OutputRequest {
        offset = offset == null ? 0L : offset;
    }
}
