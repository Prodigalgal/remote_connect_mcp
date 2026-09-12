package com.prodigalgal.remoteconnectmcp.center;

public record OutputPage(byte[] data, long cursor, long nextCursor, boolean more) {
}
