package com.prodigalgal.remotecontrolmcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Set;

/** One native filesystem operation. Null options retain bounded defaults. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileRequest(String operation, String path, String destinationPath, String pattern,
                          String content, String encoding, Long offset, Integer limit,
                          Boolean recursive, Boolean overwrite, String expectedSha256, Boolean bom,
                          Integer maxDepth, Integer maxEntries, Long maxBytes) {
    public static final Set<String> READ_ONLY = Set.of("roots", "list", "search", "stat", "read");
    public static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("roots", Set.of("operation")),
            Map.entry("list", Set.of("operation", "path", "offset", "limit", "max_entries")),
            Map.entry("search", Set.of("operation", "path", "pattern", "offset", "limit", "max_depth", "max_entries")),
            Map.entry("stat", Set.of("operation", "path")),
            Map.entry("read", Set.of("operation", "path", "encoding", "offset", "limit")),
            Map.entry("write", Set.of("operation", "path", "content", "encoding", "bom", "overwrite", "expected_sha256")),
            Map.entry("mkdir", Set.of("operation", "path", "recursive")),
            Map.entry("copy", Set.of("operation", "path", "destination_path", "recursive", "overwrite", "max_entries", "max_bytes")),
            Map.entry("move", Set.of("operation", "path", "destination_path", "overwrite")),
            Map.entry("delete", Set.of("operation", "path", "recursive", "max_entries")),
            Map.entry("archive", Set.of("operation", "path", "destination_path", "overwrite", "max_entries", "max_bytes")),
            Map.entry("extract", Set.of("operation", "path", "destination_path", "max_entries", "max_bytes")));

    public static FileRequest from(Map<String, Object> values) {
        if (values == null) throw new IllegalArgumentException("file request is required");
        var request = JsonCodec.read(JsonCodec.write(values), FileRequest.class);
        request.validate();
        var allowed = FIELDS.get(request.operation());
        for (var key : values.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(key + " is not valid for " + request.operation());
        }
        return request;
    }

    public void validate() {
        if (operation == null || !FIELDS.containsKey(operation)) throw new IllegalArgumentException("unsupported file operation: " + operation);
        var values = JsonCodec.read(JsonCodec.write(this), Map.class);
        for (var key : values.keySet()) {
            if (!FIELDS.get(operation).contains(key)) throw new IllegalArgumentException(key + " is not valid for " + operation);
        }
        if (!"roots".equals(operation)) requirePath(path, "path");
        if (Set.of("copy", "move", "archive", "extract").contains(operation)) requirePath(destinationPath, "destination_path");
        if ("write".equals(operation) && (content == null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536))
            throw new IllegalArgumentException("write requires content of at most 65536 UTF-8 bytes");
        if (encoding != null && !Set.of("auto", "UTF-8", "GB18030", "UTF-16LE", "UTF-16BE").contains(encoding))
            throw new IllegalArgumentException("unsupported encoding");
        if ("write".equals(operation) && "auto".equals(encoding)) throw new IllegalArgumentException("write requires an explicit encoding");
        if (Boolean.TRUE.equals(bom) && "GB18030".equals(encoding)) throw new IllegalArgumentException("GB18030 does not use a BOM");
        if (offset != null && (offset < 0 || (Set.of("list", "search").contains(operation) && offset > 100000)))
            throw new IllegalArgumentException("offset is outside the allowed range");
        if (limit != null && (limit < ("read".equals(operation) ? 256 : 1) || limit > ("read".equals(operation) ? 65536 : 100)))
            throw new IllegalArgumentException("limit is outside the allowed range");
        if (maxDepth != null && (maxDepth < 1 || maxDepth > 32)) throw new IllegalArgumentException("max_depth must be between 1 and 32");
        if (maxEntries != null && (maxEntries < 1 || maxEntries > 100000)) throw new IllegalArgumentException("max_entries must be between 1 and 100000");
        if (maxBytes != null && (maxBytes < 1 || maxBytes > 4L * 1024 * 1024 * 1024)) throw new IllegalArgumentException("max_bytes exceeds 4 GiB");
        if (pattern != null && (pattern.isBlank() || pattern.length() > 512 || pattern.indexOf('\u0000') >= 0)) throw new IllegalArgumentException("pattern is invalid");
        if (expectedSha256 != null && !expectedSha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("expected_sha256 is invalid");
    }

    private static void requirePath(String value, String field) {
        if (value == null || value.isBlank() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384
                || value.indexOf('\u0000') >= 0) throw new IllegalArgumentException(field + " is invalid");
    }

    public boolean readOnly() { return READ_ONLY.contains(operation); }
    public boolean recursiveValue() { return Boolean.TRUE.equals(recursive); }
    public boolean overwriteValue() { return Boolean.TRUE.equals(overwrite); }
    public long offsetValue() { return offset == null ? 0 : offset; }
    public int limitValue() { return limit == null ? ("read".equals(operation) ? 16384 : 50) : limit; }
    public int depthValue() { return maxDepth == null ? 8 : maxDepth; }
    public int entriesValue() { return maxEntries == null ? 10000 : maxEntries; }
    public long bytesValue() { return maxBytes == null ? 256L * 1024 * 1024 : maxBytes; }
}
