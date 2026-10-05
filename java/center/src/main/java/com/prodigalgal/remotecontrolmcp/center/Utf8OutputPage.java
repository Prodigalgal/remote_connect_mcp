package com.prodigalgal.remotecontrolmcp.center;

import java.util.Arrays;

/** Preserve UTF-8 characters while keeping byte cursors usable across pages. */
final class Utf8OutputPage {
    private Utf8OutputPage() {}

    static OutputPage align(OutputPage page, int limit) {
        var data = page.data();
        var start = 0;
        while (start < data.length && continuation(data[start])) start++;
        var end = Math.min(data.length, start + limit);
        if (end > start) {
            var lead = end - 1;
            while (lead > start && continuation(data[lead])) lead--;
            if (end - lead < width(data[lead]) && (page.more() || end < data.length)) end = lead;
            // A budget smaller than one character may exceed it by at most
            // three bytes so a complete first character still makes progress.
            if (end == start && start + width(data[start]) <= data.length) end = start + width(data[start]);
        }
        return new OutputPage(Arrays.copyOfRange(data, start, end), page.cursor() + start,
                page.cursor() + end, page.more() || end < data.length);
    }

    private static boolean continuation(byte value) {
        return (value & 0xc0) == 0x80;
    }

    private static int width(byte value) {
        var unsigned = value & 0xff;
        if ((unsigned & 0xe0) == 0xc0) return 2;
        if ((unsigned & 0xf0) == 0xe0) return 3;
        if ((unsigned & 0xf8) == 0xf0) return 4;
        return 1;
    }
}
