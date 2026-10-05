package com.prodigalgal.remotecontrolmcp.center;

public record OutputPage(byte[] data, long cursor, long nextCursor, boolean more) {
}
