package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskOutputTextTest {
    private static TaskOutputText.Page read(byte[] bytes, boolean complete, long cursor, int limit, String encoding) {
        return TaskOutputText.read((offset, maximum) -> {
            assertTrue(maximum > 0 && maximum <= TaskService.MAX_OUTPUT_PAGE);
            var end = (int) Math.min(bytes.length, offset + maximum);
            return new OutputPage(Arrays.copyOfRange(bytes, (int) offset, end), offset, end, end < bytes.length);
        }, bytes.length, complete, cursor, limit, encoding);
    }

    @Test void transcodesSourceCharsetsWithRawCursorsAndTinyUtf8Budgets() {
        for (var encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "GB18030")) {
            var text = "A中文😀完成\n";
            var bytes = text.getBytes(Charset.forName(encoding));
            for (var limit : List.of(1, 2, 3, 4, 5, 7, 64)) {
                var output = new StringBuilder();
                long cursor = 0;
                for (var reads = 0; cursor < bytes.length; reads++) {
                    assertTrue(reads < bytes.length * 2, encoding + " stalled");
                    var page = read(bytes, true, cursor, limit, encoding);
                    assertTrue(page.nextCursor() > cursor, encoding);
                    assertFalse(page.decodingError(), encoding);
                    assertEquals(Arrays.toString(Arrays.copyOfRange(bytes, (int) page.cursor(), (int) page.nextCursor())),
                            Arrays.toString(page.raw()));
                    assertTrue(page.text().getBytes(StandardCharsets.UTF_8).length <= Math.max(4, limit));
                    output.append(page.text());
                    cursor = page.nextCursor();
                    assertEquals(cursor < bytes.length, page.more());
                }
                assertEquals(text, output.toString(), encoding + " / " + limit);
            }
        }
    }

    @Test void supportsLegacySingleByteMultibyteAndStatefulCharsets() {
        var encodings = List.of("windows-1252", "IBM850", "Shift_JIS", "ISO-2022-JP");
        var texts = List.of("café € end", "café ü end", "A日本語完了\n", "A日本語完了\n");
        for (var i = 0; i < encodings.size(); i++) {
            var encoding = encodings.get(i);
            var bytes = texts.get(i).getBytes(Charset.forName(encoding));
            var expected = new String(bytes, Charset.forName(encoding));
            var output = new StringBuilder();
            long cursor = 0;
            for (var reads = 0; cursor < bytes.length; reads++) {
                assertTrue(reads < bytes.length * 2, encoding + " stalled");
                var page = read(bytes, true, cursor, 4, encoding);
                assertTrue(page.nextCursor() > cursor, encoding);
                assertFalse(page.decodingError(), encoding);
                output.append(page.text());
                cursor = page.nextCursor();
            }
            assertEquals(expected, output.toString(), encoding);
        }
    }

    @Test void autoRecognizesBomUtf8AndGbkAndSkipsOnlyInitialBom() {
        for (var encoding : List.of("UTF-8", "GB18030")) {
            var bytes = "中文完成\n".getBytes(Charset.forName(encoding));
            assertEquals("中文完成\n", read(bytes, true, 0, 64, "auto").text());
        }
        for (var encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            var bytes = "\ufeff中文\ufeff结束".getBytes(Charset.forName(encoding));
            var page = read(bytes, true, 0, 64, "auto");
            assertEquals("中文\ufeff结束", page.text(), encoding);
            assertEquals(encoding, page.sourceEncoding());
            assertEquals(bytes.length, page.nextCursor());
        }
    }

    @Test void runningPartialCharactersWaitWithoutReplacementOrCursorLoss() {
        for (var encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030")) {
            var bytes = "中😀".getBytes(Charset.forName(encoding));
            var cursor = 0L;
            var output = new StringBuilder();
            for (var size = 1; size <= bytes.length; size++) {
                var page = read(Arrays.copyOf(bytes, size), false, cursor, 64, encoding);
                assertFalse(page.decodingError(), encoding);
                assertFalse(page.more(), "only a partial character is pending");
                output.append(page.text());
                cursor = page.nextCursor();
                if (cursor < size) assertTrue(page.pendingBytes() > 0);
            }
            assertEquals("中😀", output.toString(), encoding);
            assertEquals(bytes.length, cursor);
        }
        var utf8Bom = "\ufeff中文".getBytes(StandardCharsets.UTF_8);
        for (var size = 1; size <= 3; size++) {
            var page = read(Arrays.copyOf(utf8Bom, size), false, 0, 64, "auto");
            assertEquals("", page.text());
            assertTrue(page.pendingBytes() > 0);
            assertEquals(0, page.nextCursor());
        }
    }

    @Test void completedInvalidBytesAreReportedAndCanBeRereadWithCorrectSource() {
        var bytes = new byte[] {(byte) 0xe9};
        var wrong = read(bytes, true, 0, 64, "UTF-8");
        assertEquals("\ufffd", wrong.text());
        assertTrue(wrong.decodingError());
        assertEquals(1, wrong.nextCursor());
        assertEquals("é", read(bytes, true, 0, 64, "windows-1252").text());
        assertArrayEquals(bytes, wrong.raw());
        var malformed = read(new byte[] {'A', (byte) 0x80, 'B'}, true, 1, 64, "UTF-8");
        assertEquals("\ufffdB", malformed.text());
        assertTrue(malformed.decodingError());
    }

    @Test void tailInsideMultibyteCharactersAndAfterLongAsciiPrefixIsAligned() {
        for (var encoding : List.of("UTF-8", "UTF-16LE", "GB18030")) {
            var bytes = "中文结束\n".getBytes(Charset.forName(encoding));
            assertEquals("文结束\n", read(bytes, true, 1, 64, encoding).text(), encoding);
        }
        var bytes = ("a".repeat(5000) + "中文😀END\n").getBytes(StandardCharsets.UTF_8);
        assertEquals("😀END\n", read(bytes, true, 5004, 64, "auto").text());
    }

    @Test void validatesCharsetAndReadBounds() {
        assertEquals("GBK", TaskOutputText.validateEncoding("gbk"));
        assertThrows(IllegalArgumentException.class, () -> TaskOutputText.validateEncoding("bogus-charset"));
        assertThrows(IllegalArgumentException.class, () -> read(new byte[0], true, 0, 65537, "UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> read(new byte[0], true, 1, 64, "UTF-8"));
    }
}
