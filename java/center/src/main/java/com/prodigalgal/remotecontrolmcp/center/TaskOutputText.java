package com.prodigalgal.remotecontrolmcp.center;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Decode retained source bytes without changing their durable cursors. */
final class TaskOutputText {
    private static final int BUFFER_SIZE = 32 * 1024;
    private TaskOutputText() {}

    @FunctionalInterface
    interface Reader { OutputPage read(long cursor, int limit); }

    record Page(String text, long cursor, long nextCursor, boolean more,
                String sourceEncoding, boolean decodingError, int pendingBytes, byte[] raw) {}

    static String validateEncoding(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) return "auto";
        if (value.length() > 64) throw new IllegalArgumentException("source_encoding is too long");
        try { return Charset.forName(value.trim()).name(); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unsupported source_encoding; use auto or an installed charset name");
        }
    }

    static Page read(Reader reader, long size, boolean complete, long cursor, int limit, String requested) {
        var encoding = validateEncoding(requested);
        if (cursor < 0 || cursor > size) throw new IllegalArgumentException("cursor is outside retained output");
        if (limit < 1 || limit > TaskService.MAX_OUTPUT_PAGE)
            throw new IllegalArgumentException("limit must be between 1 and " + TaskService.MAX_OUTPUT_PAGE);
        var head = reader.read(0, (int) Math.max(1, Math.min(size, 4096))).data();
        if ("auto".equals(encoding)) {
            if (!complete && head.length < 4 && possibleBom(head))
                return new Page("", cursor, cursor, false, "auto", false, head.length, new byte[0]);
            encoding = detect(head);
            // An ASCII prefix cannot identify the encoding of later text.
            if (ascii(head) && cursor < size) {
                var sampleStart = Math.max(0, cursor - 3);
                var sample = reader.read(sampleStart, (int) Math.min(4096, size - sampleStart)).data();
                if (sampleStart > 0) {
                    var leading = 0;
                    while (leading < Math.min(3, sample.length) && (sample[leading] & 0xc0) == 0x80) leading++;
                    sample = java.util.Arrays.copyOfRange(sample, leading, sample.length);
                }
                if (!utf8(sample)) encoding = "GB18030";
            }
        }
        if ("UTF-16".equals(encoding)) encoding = bom(head).equals("UTF-16LE") ? "UTF-16LE" : "UTF-16BE";
        if ("UTF-32".equals(encoding)) encoding = bom(head).equals("UTF-32LE") ? "UTF-32LE" : "UTF-32BE";
        var charset = Charset.forName(encoding);
        var start = cursor;
        var fast = false;
        if (encoding.equals("UTF-8")) {
            fast = true;
            start = utf8Start(reader, cursor, size);
        } else if (encoding.startsWith("UTF-16")) {
            fast = true;
            start = Math.min(size, (cursor + 1) / 2 * 2);
            var data = reader.read(start, (int) Math.max(1, Math.min(2, size - start))).data();
            if (cursor > 0 && data.length == 2) {
                var unit = encoding.endsWith("LE") ? (data[0] & 255) | (data[1] & 255) << 8
                        : (data[0] & 255) << 8 | (data[1] & 255);
                if (unit >= 0xdc00 && unit <= 0xdfff && start >= 2) {
                    var previous = reader.read(start - 2, 2).data();
                    var high = encoding.endsWith("LE") ? (previous[0] & 255) | (previous[1] & 255) << 8
                            : (previous[0] & 255) << 8 | (previous[1] & 255);
                    if (high >= 0xd800 && high <= 0xdbff) start += 2;
                }
            }
        } else if (encoding.startsWith("UTF-32")) {
            fast = true;
            start = Math.min(size, (cursor + 3) / 4 * 4);
        } else if (charset.canEncode() && charset.newEncoder().maxBytesPerChar() <= 1) {
            fast = true;
        }
        var bomBytes = bomLength(head, encoding);
        if (fast) start = Math.max(start, Math.min(size, bomBytes));
        var stream = new Stream(reader, charset, fast ? start : 0);
        if (!fast && cursor > 0) {
            // Legacy multibyte and stateful charsets need decoder state from
            // byte zero. Replay bounded buffers, never materialize the log.
            stream.skipPrefix(cursor);
            if (stream.pending() > 0) stream.next(size, complete);
            start = Math.max(cursor, stream.position());
        }
        var end = Math.min(size, start + Math.max(16, limit));
        var text = new StringBuilder();
        var utf8Bytes = 0;
        var next = start;
        var decodingError = false;
        var exhausted = false;
        while (true) {
            var before = stream.position();
            var piece = stream.next(end, complete && end == size);
            if (piece == null) {
                next = Math.max(next, stream.position());
                exhausted = true;
                break;
            }
            if (before == 0 && piece.text().equals("\ufeff") && bomBytes > 0) {
                next = stream.position();
                start = next;
                continue;
            }
            var width = utf8Length(piece.text());
            if (utf8Bytes > 0 && utf8Bytes + width > limit) {
                next = before;
                break;
            }
            text.append(piece.text());
            utf8Bytes += width;
            decodingError |= piece.error();
            next = stream.position();
            if (utf8Bytes >= limit) break;
        }
        var more = next < size && (!exhausted || end < size);
        var pending = exhausted && end == size && !complete ? stream.pending() : 0;
        // raw and text have the same consumed source range, but only text is
        // presented. Raw bytes remain available for a different source choice.
        var raw = next > start ? reader.read(start, (int) (next - start)).data() : new byte[0];
        return new Page(text.toString(), start, next, more, encoding, decodingError, pending, raw);
    }

    private static int utf8Length(String text) {
        var first = text.charAt(0);
        return text.length() == 2 && Character.isSurrogatePair(first, text.charAt(1)) ? 4
                : first < 0x80 ? 1 : first < 0x800 ? 2 : 3;
    }

    private static long utf8Start(Reader reader, long cursor, long size) {
        if (cursor == 0 || cursor == size) return cursor;
        var offset = Math.max(0, cursor - 3);
        var data = reader.read(offset, (int) Math.min(7, size - offset)).data();
        var index = (int) (cursor - offset);
        if (index >= data.length || (data[index] & 0xc0) != 0x80) return cursor;
        var lead = index;
        while (lead > 0 && (data[lead] & 0xc0) == 0x80) lead--;
        var first = data[lead] & 255;
        var width = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3
                : first >= 0xf0 && first <= 0xf4 ? 4 : 1;
        if (width <= index - lead) return cursor;
        var candidate = java.util.Arrays.copyOfRange(data, lead, Math.min(data.length, lead + width));
        return utf8(candidate) ? Math.min(size, offset + lead + width) : cursor;
    }

    private static String detect(byte[] data) {
        var marked = bom(data);
        if (!marked.isEmpty()) return marked;
        if (data.length >= 8) {
            var evenZeros = 0; var oddZeros = 0;
            for (var i = 0; i < Math.min(data.length, 64); i++) {
                if (data[i] == 0) { if (i % 2 == 0) evenZeros++; else oddZeros++; }
            }
            if (oddZeros >= 4 && evenZeros == 0) return "UTF-16LE";
            if (evenZeros >= 4 && oddZeros == 0) return "UTF-16BE";
        }
        return utf8(data) ? "UTF-8" : "GB18030";
    }

    private static boolean ascii(byte[] data) {
        for (var value : data) if (value < 0 || value == 0) return false;
        return true;
    }

    private static boolean utf8(byte[] data) {
        var result = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data),
                CharBuffer.allocate(data.length + 1), false);
        return !result.isError();
    }

    private static boolean possibleBom(byte[] data) {
        if (data.length == 0) return false;
        var first = data[0] & 255;
        return first == 0xff || first == 0xfe || first == 0xef || first == 0;
    }

    private static String bom(byte[] data) {
        if (data.length >= 4) {
            if (data[0] == -1 && data[1] == -2 && data[2] == 0 && data[3] == 0) return "UTF-32LE";
            if (data[0] == 0 && data[1] == 0 && data[2] == -2 && data[3] == -1) return "UTF-32BE";
        }
        if (data.length >= 3 && data[0] == -17 && data[1] == -69 && data[2] == -65) return "UTF-8";
        if (data.length >= 2 && data[0] == -1 && data[1] == -2) return "UTF-16LE";
        if (data.length >= 2 && data[0] == -2 && data[1] == -1) return "UTF-16BE";
        return "";
    }

    private static int bomLength(byte[] data, String encoding) {
        return !bom(data).equals(encoding) ? 0 : encoding.equals("UTF-8") ? 3
                : encoding.startsWith("UTF-32") ? 4 : 2;
    }

    private record Piece(String text, boolean error) {}

    private static final class Stream {
        private final Reader reader;
        private final java.nio.charset.CharsetDecoder decoder;
        private final ByteBuffer input = ByteBuffer.allocate(BUFFER_SIZE + 32);
        private final CharBuffer character = CharBuffer.allocate(2);
        private long loaded;
        private boolean finished;

        Stream(Reader reader, Charset charset, long start) {
            this.reader = reader;
            this.decoder = charset.newDecoder();
            this.loaded = start;
            input.limit(0);
        }

        long position() { return loaded - input.remaining(); }
        int pending() { return input.remaining(); }

        private boolean load(long end) {
            if (loaded >= end) return false;
            input.compact();
            var length = (int) Math.min(Math.min(BUFFER_SIZE, input.remaining()), end - loaded);
            var data = reader.read(loaded, length).data();
            input.put(data).flip();
            loaded += data.length;
            return data.length > 0;
        }

        void skipPrefix(long end) {
            var discard = CharBuffer.allocate(1024);
            while (true) {
                discard.clear();
                var result = decoder.decode(input, discard, false);
                if (result.isError()) { input.position(input.position() + result.length()); continue; }
                if (result.isOverflow()) continue;
                if (!load(end)) return;
            }
        }

        Piece next(long end, boolean complete) {
            if (finished) return null;
            while (true) {
                character.clear().limit(1);
                var result = decoder.decode(input, character, complete && loaded == end);
                if (result.isOverflow() && character.position() == 0) {
                    character.limit(2);
                    result = decoder.decode(input, character, complete && loaded == end);
                }
                if (character.position() > 0) {
                    character.flip();
                    return new Piece(character.toString(), false);
                }
                if (result.isError()) {
                    input.position(input.position() + result.length());
                    return new Piece("\ufffd", true);
                }
                if (complete && loaded == end && result.isUnderflow()) {
                    character.clear();
                    decoder.flush(character);
                    finished = true;
                    character.flip();
                    return character.hasRemaining() ? new Piece(character.toString(), false) : null;
                }
                if (!load(end)) return null;
            }
        }
    }
}
