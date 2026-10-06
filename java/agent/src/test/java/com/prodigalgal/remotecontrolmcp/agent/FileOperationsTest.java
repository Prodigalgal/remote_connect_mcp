package com.prodigalgal.remotecontrolmcp.agent;

import com.prodigalgal.remotecontrolmcp.protocol.FileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class FileOperationsTest {
    @TempDir Path root;
    private Map<String, Object> run(Map<String, Object> args) throws Exception {
        return new FileOperations(root, Duration.ofSeconds(10), null).execute(FileRequest.from(args));
    }

    @Test void chineseNamesRoundTripThroughWriteReadZipAndExtraction() throws Exception {
        var folder = Files.createDirectory(root.resolve("爬虫"));
        var source = folder.resolve("大乐透 表.txt");
        run(Map.of("operation", "write", "path", source.toString(), "content", "第一行\n中文😀完整"));
        assertEquals("第一行\n中文😀完整", run(Map.of("operation", "read", "path", source.toString())).get("text"));
        var archive = root.resolve("回传.zip");
        run(Map.of("operation", "archive", "path", folder.toString(), "destination_path", archive.toString()));
        var output = root.resolve("解压目录");
        run(Map.of("operation", "extract", "path", archive.toString(), "destination_path", output.toString()));
        assertEquals(Files.readString(source), Files.readString(output.resolve("爬虫/大乐透 表.txt")));
    }

    @Test void directoryPaginationIsStableAndSearchIsBounded() throws Exception {
        for (int i = 0; i < 9; i++) Files.writeString(root.resolve("文件" + i + ".txt"), "ok");
        var first = run(Map.of("operation", "list", "path", root.toString(), "limit", 3));
        var second = run(Map.of("operation", "list", "path", root.toString(), "limit", 3, "offset", first.get("next_offset")));
        assertEquals(true, first.get("has_more"));
        assertNotEquals(((List<?>)first.get("entries")).getFirst(), ((List<?>)second.get("entries")).getFirst());
        var bounded = run(Map.of("operation", "search", "path", root.toString(), "pattern", "*.txt", "max_entries", 3));
        assertEquals(true, bounded.get("scan_truncated"));
        assertTrue(((List<?>)bounded.get("entries")).size() <= 3);
    }

    @Test void textPagingPreservesUtf8Characters() throws Exception {
        var file = root.resolve("中文.txt");
        var content = "测试😀".repeat(100);
        Files.writeString(file, content);
        long offset = 0; var text = new StringBuilder();
        while (offset < Files.size(file)) {
            var page = run(Map.of("operation", "read", "path", file.toString(), "limit", 256, "offset", offset, "encoding", "UTF-8"));
            text.append(page.get("text"));
            var next = ((Number)page.get("next_cursor")).longValue(); assertTrue(next > offset); offset = next;
        }
        assertEquals(content, text.toString());
    }

    @Test void legacyWindowsEncodingCanBeReadAndWrittenExplicitly() throws Exception {
        var file = root.resolve("旧编码.txt");
        run(Map.of("operation", "write", "path", file.toString(), "content", "中文日志正常", "encoding", "GB18030"));
        assertEquals("中文日志正常", run(Map.of("operation", "read", "path", file.toString(), "encoding", "auto")).get("text"));
    }

    @Test void editingPreservesUtf16AndUtf8ByteOrderMarks() throws Exception {
        for (var encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE")) {
            var file = root.resolve(encoding + ".txt");
            run(Map.of("operation", "write", "path", file.toString(), "content", "中文😀", "encoding", encoding, "bom", true));
            var read = run(Map.of("operation", "read", "path", file.toString()));
            assertEquals(encoding, read.get("encoding")); assertEquals(true, read.get("bom")); assertEquals("中文😀", read.get("text"));
            run(Map.of("operation", "write", "path", file.toString(), "content", "编辑完成", "encoding", read.get("encoding"), "bom", read.get("bom"), "overwrite", true, "expected_sha256", read.get("sha256")));
            var updated = run(Map.of("operation", "read", "path", file.toString()));
            assertEquals(encoding, updated.get("encoding")); assertEquals("编辑完成", updated.get("text"));
        }
    }

    @Test void escapedTextResponseBudgetNeverCutsCharactersOrLosesBytes() throws Exception {
        var file = root.resolve("escaped.txt"); var content = "\t中文😀\"\\\n".repeat(7000);
        Files.writeString(file, content); long offset = 0; var text = new StringBuilder();
        while (offset < Files.size(file)) {
            var page = run(Map.of("operation", "read", "path", file.toString(), "limit", 65536, "offset", offset, "encoding", "UTF-8"));
            text.append(page.get("text"));
            assertTrue(com.prodigalgal.remotecontrolmcp.protocol.JsonCodec.write(page).length <= FileOperations.MAX_RESULT_BYTES);
            var next = ((Number)page.get("next_cursor")).longValue(); assertTrue(next > offset); offset = next;
        }
        assertEquals(content, text.toString());
    }

    @Test void overwriteAndVersionConflictsDoNotReplaceExistingContent() throws Exception {
        var file = Files.writeString(root.resolve("编辑.txt"), "old");
        var digest = run(Map.of("operation", "read", "path", file.toString())).get("sha256");
        assertThrows(FileAlreadyExistsException.class, () -> run(Map.of("operation", "write", "path", file.toString(), "content", "new")));
        Files.writeString(file, "changed elsewhere");
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "write", "path", file.toString(), "content", "new", "overwrite", true, "expected_sha256", digest)));
        assertEquals("changed elsewhere", Files.readString(file));
    }

    @Test void directoryCopyMoveAndExplicitRecursiveDeleteWork() throws Exception {
        var source = Files.createDirectory(root.resolve("源目录")); Files.writeString(source.resolve("文件.txt"), "content");
        var copy = root.resolve("复制目录");
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "copy", "path", source.toString(), "destination_path", copy.toString())));
        run(Map.of("operation", "copy", "path", source.toString(), "destination_path", copy.toString(), "recursive", true));
        var moved = root.resolve("改名目录"); run(Map.of("operation", "move", "path", copy.toString(), "destination_path", moved.toString()));
        assertThrows(DirectoryNotEmptyException.class, () -> run(Map.of("operation", "delete", "path", moved.toString())));
        run(Map.of("operation", "delete", "path", moved.toString(), "recursive", true));
        assertFalse(Files.exists(moved)); assertTrue(Files.exists(source.resolve("文件.txt")));
    }

    @Test void failedCopyDoesNotPublishPartialDestinationOrLeaveStage() throws Exception {
        var source = Files.write(root.resolve("large.bin"), new byte[4096]);
        var target = root.resolve("copy.bin");
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "copy", "path", source.toString(), "destination_path", target.toString(), "max_bytes", 1024)));
        assertFalse(Files.exists(target));
        try (var files = Files.list(root)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".rcm-copy-"))); }
    }

    @Test void zipTraversalAndExpansionLimitsAreRejectedWithoutPublishing() throws Exception {
        var archive = root.resolve("bad.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) { zip.putNextEntry(new ZipEntry("../outside.txt")); zip.write("bad".getBytes()); zip.closeEntry(); }
        var target = root.resolve("output");
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "extract", "path", archive.toString(), "destination_path", target.toString())));
        assertFalse(Files.exists(target)); assertFalse(Files.exists(root.resolve("outside.txt")));
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) { zip.putNextEntry(new ZipEntry("bomb.txt")); zip.write(new byte[4096]); zip.closeEntry(); }
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "extract", "path", archive.toString(), "destination_path", target.toString(), "max_bytes", 1024)));
        assertFalse(Files.exists(target));
    }

    @Test void cancellationStopsBeforeMutationAndIrrelevantFieldsAreRejected() throws Exception {
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> run(Map.of("operation", "mkdir", "path", root.resolve("canceled").toString()))); }
        finally { Thread.interrupted(); }
        assertFalse(Files.exists(root.resolve("canceled")));
        assertThrows(IllegalArgumentException.class, () -> FileRequest.from(Map.of("operation", "stat", "path", root.toString(), "overwrite", true)));
    }

    @Test void directoryAliasesCannotCreateAnArchiveInsideTheSource() throws Exception {
        var source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("data.txt"), "data");
        var alias = root.resolve("alias"); Files.createSymbolicLink(alias, source);
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "archive", "path", source.toString(), "destination_path", alias.resolve("nested.zip").toString())));
        assertFalse(Files.exists(source.resolve("nested.zip")));
    }

    @Test void malformedTruncatedAndDuplicateZipContainersCannotPublishResults() throws Exception {
        var source = root.resolve("bad.zip"); var target = root.resolve("output");
        Files.writeString(source, "This is an ordinary file, not a ZIP container");
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "extract", "path", source.toString(), "destination_path", target.toString())));
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) { zip.putNextEntry(new ZipEntry("data.txt")); zip.write("data".getBytes()); zip.closeEntry(); }
        var complete = Files.readAllBytes(source); Files.write(source, Arrays.copyOf(complete, complete.length - 22));
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "extract", "path", source.toString(), "destination_path", target.toString())));
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (var name : List.of("folder/data.txt", "folder/./data.txt")) { zip.putNextEntry(new ZipEntry(name)); zip.write("data".getBytes()); zip.closeEntry(); }
        }
        assertThrows(java.io.IOException.class, () -> run(Map.of("operation", "extract", "path", source.toString(), "destination_path", target.toString())));
        assertFalse(Files.exists(target));
        try (var paths = Files.list(root)) { assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".rcm-extract-"))); }
    }
}
