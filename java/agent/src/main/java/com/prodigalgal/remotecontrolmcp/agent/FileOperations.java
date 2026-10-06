package com.prodigalgal.remotecontrolmcp.agent;

import com.prodigalgal.remotecontrolmcp.protocol.FileRequest;
import com.prodigalgal.remotecontrolmcp.protocol.JsonCodec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Bounded native filesystem operations; never starts a shell or follows tree symlinks. */
final class FileOperations {
    static final int MAX_RESULT_BYTES = 96 * 1024;
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private final Path cwd;
    private final long deadline;
    private final java.util.function.LongConsumer progress;
    private long visited, bytes;
    private boolean depthLimited;
    private FileRequest request;

    FileOperations(Path cwd, Duration timeout, java.util.function.LongConsumer progress) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.deadline = System.nanoTime() + timeout.toNanos();
        this.progress = progress;
    }

    Map<String, Object> execute(FileRequest request) throws IOException, InterruptedException {
        request.validate();
        this.request = request;
        check();
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", request.operation());
        if ("roots".equals(request.operation())) {
            var roots = new ArrayList<String>();
            FileSystems.getDefault().getRootDirectories().forEach(root -> roots.add(root.toString()));
            result.put("roots", roots);
            result.put("cwd", cwd.toString());
            return result;
        }
        var path = path(request.path());
        result.put("path", path.toString());
        switch (request.operation()) {
            case "list", "search" -> list(path, result);
            case "stat" -> result.put("entry", stat(path));
            case "read" -> read(path, result);
            case "write" -> write(path, result);
            case "mkdir" -> {
                if (request.recursiveValue()) Files.createDirectories(path); else if (!Files.isDirectory(path, NOFOLLOW)) Files.createDirectory(path);
                result.put("entry", stat(path));
            }
            case "copy" -> copy(path, path(request.destinationPath()), result);
            case "move" -> {
                var target = path(request.destinationPath());
                validateTarget(path, target);
                if (Files.isDirectory(path, NOFOLLOW) && Files.exists(target, NOFOLLOW)) throw new FileAlreadyExistsException(target.toString());
                Files.move(path, target, request.overwriteValue() ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0]);
                result.put("entry", stat(target));
            }
            case "delete" -> {
                protectRoot(path);
                if (Files.isDirectory(path, NOFOLLOW) && request.recursiveValue()) {
                    // Preflight limits before deleting any entry. Deletion still
                    // reports partial completion if permissions change mid-run.
                    walk(path, (entry, relative, attrs) -> {});
                    visited = 0;
                    removeTree(path, true);
                } else Files.delete(path);
                result.put("deleted", true);
            }
            case "archive" -> archive(path, path(request.destinationPath()), result);
            case "extract" -> extract(path, path(request.destinationPath()), result);
            default -> throw new IOException("unsupported operation");
        }
        if (JsonCodec.write(result).length > MAX_RESULT_BYTES) throw new IOException("file result exceeds the bounded response size; reduce limit");
        return result;
    }

    private Path path(String raw) throws IOException {
        try {
            var value = Path.of(raw);
            return (value.isAbsolute() ? value : cwd.resolve(value)).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) { throw new IOException("invalid filesystem path", invalid); }
    }

    private Map<String, Object> stat(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", path.getFileName() == null ? path.toString() : path.getFileName().toString());
        entry.put("path", path.toString());
        entry.put("type", attrs.isSymbolicLink() ? "symlink" : attrs.isDirectory() ? "directory" : attrs.isRegularFile() ? "file" : "other");
        entry.put("bytes", attrs.size());
        entry.put("modified_at", attrs.lastModifiedTime().toInstant().toString());
        entry.put("readable", Files.isReadable(path));
        entry.put("writable", Files.isWritable(path));
        if (attrs.isSymbolicLink()) entry.put("link_target", Files.readSymbolicLink(path).toString());
        return entry;
    }

    private void list(Path root, Map<String, Object> result) throws IOException, InterruptedException {
        if (!Files.isDirectory(root, NOFOLLOW)) throw new IOException("path is not a non-symlink directory");
        var entries = new ArrayList<Map<String, Object>>();
        var candidates = new ArrayList<Candidate>();
        var candidateBytes = new long[]{0};
        var searching = "search".equals(request.operation());
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + (request.pattern() == null ? "*" : request.pattern()));
        var scanTruncated = new boolean[]{false};
        if (searching) {
            try {
                walk(root, (path, relative, attrs) -> {
                    if (!path.equals(root) && (matcher.matches(path.getFileName()) || matcher.matches(relative)))
                        addCandidate(candidates, path, attrs.isDirectory(), candidateBytes);
                }, request.depthValue());
            } catch (ScanLimit limit) { scanTruncated[0] = true; }
        } else {
            try (var stream = Files.newDirectoryStream(root)) {
                for (var path : stream) {
                    if (visited >= request.entriesValue()) { scanTruncated[0] = true; break; }
                    tick();
                    try { addCandidate(candidates, path, Files.isDirectory(path, NOFOLLOW), candidateBytes); }
                    catch (ScanLimit limit) { scanTruncated[0] = true; break; }
                }
            }
        }
        candidates.sort(Comparator.comparing((Candidate p) -> !p.directory())
                .thenComparing(p -> p.path().toString().toLowerCase(Locale.ROOT)).thenComparing(p -> p.path().toString()));
        var start = (int) Math.min(request.offsetValue(), candidates.size());
        var next = start;
        var responseBytes = 0;
        while (next < candidates.size() && entries.size() < request.limitValue()) {
            check();
            var entry = stat(candidates.get(next).path());
            var entryBytes = JsonCodec.write(entry).length;
            if (!entries.isEmpty() && responseBytes + entryBytes > 64 * 1024) break;
            entries.add(entry);
            responseBytes += entryBytes;
            next++;
        }
        result.put("entries", entries);
        result.put("offset", request.offsetValue());
        result.put("next_offset", next);
        result.put("has_more", next < candidates.size());
        result.put("scanned", visited);
        result.put("scan_truncated", scanTruncated[0]);
        if (searching) {
            result.put("depth_limited", depthLimited);
            result.put("pattern", request.pattern() == null ? "*" : request.pattern());
            result.put("max_depth", request.depthValue());
        }
    }

    private static void addCandidate(List<Candidate> candidates, Path path, boolean directory, long[] size) throws ScanLimit {
        size[0] += 128L + path.toString().length() * 2L;
        if (size[0] > 8L * 1024 * 1024) throw new ScanLimit();
        candidates.add(new Candidate(path, directory));
    }

    private record Candidate(Path path, boolean directory) { }

    private void read(Path path, Map<String, Object> result) throws IOException {
        regular(path);
        var size = Files.size(path);
        if (request.offsetValue() > size) throw new IOException("offset is beyond end of file");
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, NOFOLLOW))) {
            channel.position(request.offsetValue());
            var buffer = ByteBuffer.allocate((int) Math.min(request.limitValue(), size - request.offsetValue()));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { checkIo(); }
            var raw = Arrays.copyOf(buffer.array(), buffer.position());
            var sample = ByteBuffer.allocate((int) Math.min(size, 4096));
            channel.position(0);
            while (sample.hasRemaining() && channel.read(sample) >= 0) { checkIo(); }
            var first = Arrays.copyOf(sample.array(), sample.position());
            var encoding = request.encoding() == null ? "auto" : request.encoding();
            if ("auto".equals(encoding)) encoding = detectEncoding(first);
            var charset = Charset.forName(encoding);
            var consumed = raw.length;
            var trims = 0;
            String text = "";
            while (consumed > 0) {
                try {
                    text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(raw, 0, consumed)).toString();
                    if (text.indexOf('\u0000') >= 0) throw new IOException("binary content; retrieve it with artifact get");
                    if (JsonCodec.write(text).length <= 64 * 1024) break;
                    consumed /= 2; trims = 0;
                } catch (CharacterCodingException invalid) {
                    if (trims++ >= 3 || request.offsetValue() + consumed == size)
                        throw new IOException("cannot decode text; choose the correct encoding or use artifact get", invalid);
                    consumed--;
                }
            }
            if (raw.length > 0 && consumed == 0) throw new IOException("read page does not contain a complete character");
            if (request.offsetValue() == 0 && text.startsWith("\ufeff")) text = text.substring(1);
            result.put("text", text);
            result.put("encoding", encoding);
            result.put("bom", hasBom(first, encoding));
            result.put("bytes", size);
            result.put("cursor", request.offsetValue());
            result.put("next_cursor", request.offsetValue() + consumed);
            result.put("has_more", request.offsetValue() + consumed < size);
            if (request.offsetValue() == 0 && size <= 1024 * 1024) result.put("sha256", sha256(path));
        }
    }

    private static String detectEncoding(byte[] sample) {
        if (sample.length >= 2 && sample[0] == (byte) 0xff && sample[1] == (byte) 0xfe) return "UTF-16LE";
        if (sample.length >= 2 && sample[0] == (byte) 0xfe && sample[1] == (byte) 0xff) return "UTF-16BE";
        for (int trim = 0; trim <= 3 && trim <= sample.length; trim++) {
            try { StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(sample, 0, sample.length - trim)); return "UTF-8"; }
            catch (CharacterCodingException ignored) { }
        }
        return "GB18030";
    }

    private static boolean hasBom(byte[] sample, String encoding) {
        return switch (encoding) {
            case "UTF-8" -> sample.length >= 3 && sample[0] == (byte) 0xef && sample[1] == (byte) 0xbb && sample[2] == (byte) 0xbf;
            case "UTF-16LE" -> sample.length >= 2 && sample[0] == (byte) 0xff && sample[1] == (byte) 0xfe;
            case "UTF-16BE" -> sample.length >= 2 && sample[0] == (byte) 0xfe && sample[1] == (byte) 0xff;
            default -> false;
        };
    }

    private void write(Path path, Map<String, Object> result) throws IOException, InterruptedException {
        var parent = existingParent(path);
        if (Files.exists(path, NOFOLLOW)) {
            regular(path);
            if (!request.overwriteValue()) throw new FileAlreadyExistsException(path.toString());
        }
        checkExpectedDigest(path);
        var encoding = request.encoding() == null ? "UTF-8" : request.encoding();
        var data = Charset.forName(encoding).newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(request.content()));
        var temporary = Files.createTempFile(parent, ".rcm-write-", ".part");
        try {
            var bom = request.bom() == null ? encoding.startsWith("UTF-16") : request.bom();
            var prefix = !bom ? new byte[0] : switch (encoding) {
                case "UTF-8" -> new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
                case "UTF-16LE" -> new byte[]{(byte) 0xff, (byte) 0xfe};
                case "UTF-16BE" -> new byte[]{(byte) 0xfe, (byte) 0xff};
                default -> new byte[0];
            };
            var content = new byte[prefix.length + data.remaining()];
            System.arraycopy(prefix, 0, content, 0, prefix.length);
            data.get(content, prefix.length, content.length - prefix.length); Files.write(temporary, content);
            check(); checkExpectedDigest(path);
            commit(temporary, path, request.overwriteValue());
        } finally { Files.deleteIfExists(temporary); }
        result.put("entry", stat(path)); result.put("sha256", sha256(path));
    }

    private void checkExpectedDigest(Path path) throws IOException {
        if (request.expectedSha256() != null && (!Files.isRegularFile(path, NOFOLLOW)
                || !request.expectedSha256().equalsIgnoreCase(sha256(path))))
            throw new IOException("FILE_CHANGED: file differs from the version read; refresh before writing");
    }

    private void copy(Path source, Path target, Map<String, Object> result) throws IOException, InterruptedException {
        validateTarget(source, target);
        if (Files.isDirectory(source, NOFOLLOW)) {
            if (!request.recursiveValue()) throw new IOException("directory copy requires recursive=true");
            if (Files.exists(target, NOFOLLOW)) throw new FileAlreadyExistsException(target.toString());
            var stage = Files.createTempDirectory(existingParent(target), ".rcm-copy-");
            try {
                walk(source, (entry, relative, attrs) -> {
                    var output = stage.resolve(relative);
                    if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) throw new IOException("tree contains a symlink or special file");
                    if (attrs.isDirectory()) Files.createDirectories(output); else copyFile(entry, output);
                });
                check(); commit(stage, target, false);
            } finally { cleanup(stage); }
        } else {
            regular(source);
            var stage = Files.createTempFile(existingParent(target), ".rcm-copy-", ".part");
            try { copyFile(source, stage); check(); commit(stage, target, request.overwriteValue()); }
            finally { Files.deleteIfExists(stage); }
        }
        result.put("entry", stat(target)); result.put("bytes_copied", bytes);
    }

    private void copyFile(Path source, Path target) throws IOException {
        regular(source);
        try (var input = Files.newInputStream(source, NOFOLLOW); var output = Files.newOutputStream(target)) { transfer(input, output); }
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source, NOFOLLOW));
    }

    private void archive(Path source, Path target, Map<String, Object> result) throws IOException, InterruptedException {
        validateTarget(source, target);
        var temporary = Files.createTempFile(existingParent(target), ".rcm-archive-", ".part");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                zip.setLevel(1);
                walk(source, (entry, relative, attrs) -> {
                    if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) throw new IOException("archive contains a symlink or special file");
                    var name = source.getFileName().toString() + (relative.toString().isEmpty() ? "" : "/" + relative.toString().replace('\\', '/'));
                    zip.putNextEntry(new ZipEntry(name + (attrs.isDirectory() ? "/" : "")));
                    if (attrs.isRegularFile()) try (var input = Files.newInputStream(entry, NOFOLLOW)) { transfer(input, zip); }
                    zip.closeEntry();
                });
            }
            check(); commit(temporary, target, request.overwriteValue());
        } finally { Files.deleteIfExists(temporary); }
        result.put("entry", stat(target)); result.put("bytes_archived", bytes);
    }

    private void extract(Path source, Path target, Map<String, Object> result) throws IOException, InterruptedException {
        regular(source); validateTarget(source, target);
        if (Files.exists(target, NOFOLLOW)) throw new FileAlreadyExistsException(target.toString());
        if (Files.size(source) > request.bytesValue()) throw new IOException("ZIP input exceeds max_bytes");
        var expectedEntries = zipEntries(source);
        if (expectedEntries > request.entriesValue()) throw new IOException("ZIP exceeds max_entries");
        var stage = Files.createTempDirectory(existingParent(target), ".rcm-extract-");
        // Stream entries instead of allocating a central-directory index for
        // the entire archive before applying entry and expansion limits.
        try (var zip = new ZipInputStream(Files.newInputStream(source, NOFOLLOW), StandardCharsets.UTF_8)) {
            var names = new HashSet<String>();
            long nameBytes = 0; ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                tick();
                var name = entry.getName().replace('\\', '/');
                nameBytes += 128L + name.length() * 2L;
                if (name.getBytes(StandardCharsets.UTF_8).length > 16384 || nameBytes > 8L * 1024 * 1024) throw new IOException("ZIP entry names exceed the bounded path budget");
                if (name.startsWith("/") || name.contains(":") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("unsafe ZIP entry path");
                var output = stage.resolve(name).normalize();
                if (output.equals(stage) || !output.startsWith(stage)) throw new IOException("unsafe ZIP entry path");
                var key = output.toString();
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) key = key.toLowerCase(Locale.ROOT);
                if (!names.add(key)) throw new IOException("duplicate ZIP entry");
                if (entry.isDirectory()) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    try (var out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) { transfer(zip, out); }
                }
                zip.closeEntry();
            }
            if (visited != expectedEntries) throw new IOException("ZIP entry count does not match its directory; archive is incomplete");
            check(); commit(stage, target, false);
        } finally { cleanup(stage); }
        result.put("entry", stat(target)); result.put("bytes_extracted", bytes); result.put("entries", visited);
    }

    // A bounded footer read detects non-ZIP and truncated containers without
    // allocating ZipFile's full central-directory index. Entry CRCs are checked
    // by ZipInputStream and the declared count must match streamed entries.
    private long zipEntries(Path source) throws IOException {
        try (var channel = Files.newByteChannel(source, Set.of(StandardOpenOption.READ, NOFOLLOW))) {
            var length = channel.size();
            if (length < 22) throw new IOException("ZIP end record is missing");
            var tailSize = (int) Math.min(length, 65557);
            var tail = ByteBuffer.allocate(tailSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            channel.position(length - tailSize);
            while (tail.hasRemaining()) { checkIo(); if (channel.read(tail) < 0) throw new IOException("ZIP is truncated"); }
            for (int position = tailSize - 22; position >= 0; position--) {
                if (tail.getInt(position) != 0x06054b50 || position + 22 + Short.toUnsignedInt(tail.getShort(position + 20)) != tailSize) continue;
                if (tail.getShort(position + 4) != 0 || tail.getShort(position + 6) != 0) throw new IOException("multi-volume ZIP is not supported");
                long entries = Short.toUnsignedInt(tail.getShort(position + 10));
                long directorySize = Integer.toUnsignedLong(tail.getInt(position + 12));
                long directoryOffset = Integer.toUnsignedLong(tail.getInt(position + 16));
                var endOffset = length - tailSize + position;
                if (entries == 65535 || directorySize == 0xffffffffL || directoryOffset == 0xffffffffL) {
                    if (position < 20 || tail.getInt(position - 20) != 0x07064b50 || tail.getInt(position - 16) != 0 || tail.getInt(position - 4) != 1)
                        throw new IOException("ZIP64 locator is missing or multi-volume");
                    var zip64Offset = tail.getLong(position - 12);
                    if (zip64Offset < 0 || zip64Offset > endOffset - 76) throw new IOException("invalid ZIP64 end offset");
                    var record = ByteBuffer.allocate(56).order(java.nio.ByteOrder.LITTLE_ENDIAN); channel.position(zip64Offset);
                    while (record.hasRemaining()) { checkIo(); if (channel.read(record) < 0) throw new IOException("ZIP64 is truncated"); }
                    if (record.getInt(0) != 0x06064b50 || record.getLong(4) < 44 || record.getLong(4) != endOffset - 20 - zip64Offset - 12
                            || record.getInt(16) != 0 || record.getInt(20) != 0 || record.getLong(24) != record.getLong(32))
                        throw new IOException("invalid ZIP64 end record");
                    entries = record.getLong(32); directorySize = record.getLong(40); directoryOffset = record.getLong(48); endOffset = zip64Offset;
                } else if (Short.toUnsignedInt(tail.getShort(position + 8)) != entries) throw new IOException("invalid ZIP entry count");
                if (entries < 0 || directoryOffset < 0 || directorySize < 0 || directoryOffset > endOffset || directorySize != endOffset - directoryOffset)
                    throw new IOException("ZIP directory is incomplete");
                return entries;
            }
            throw new IOException("ZIP end record is missing");
        }
    }

    private void validateTarget(Path source, Path target) throws IOException {
        protectRoot(source); protectRoot(target); existingParent(target);
        var realSource = source.getParent().toRealPath().resolve(source.getFileName()).normalize();
        var realTarget = target.getParent().toRealPath().resolve(target.getFileName()).normalize();
        if (realTarget.equals(realSource) || realTarget.startsWith(realSource)) throw new IOException("destination must be outside the source");
        if (Files.isSymbolicLink(target)) throw new IOException("destination is a symlink");
    }

    private static Path existingParent(Path path) throws IOException {
        var parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("destination parent directory does not exist");
        return parent;
    }

    private static void protectRoot(Path path) throws IOException {
        if (path.getParent() == null) throw new IOException("operation on a filesystem root is not allowed");
    }

    private static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW)) throw new IOException("path is not a regular non-symlink file");
    }

    private static void commit(Path stage, Path target, boolean overwrite) throws IOException {
        if (!overwrite) Files.move(stage, target);
        else {
            if (Files.isDirectory(target, NOFOLLOW) || Files.isSymbolicLink(target)) throw new IOException("cannot overwrite a directory or symlink");
            try { Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    private void transfer(InputStream input, OutputStream output) throws IOException {
        var buffer = new byte[64 * 1024]; int length;
        while ((length = input.read(buffer)) >= 0) {
            checkIo(); bytes += length;
            if (bytes > request.bytesValue()) throw new IOException("file operation exceeds max_bytes");
            output.write(buffer, 0, length);
        }
    }

    private void walk(Path root, EntryAction action) throws IOException, InterruptedException { walk(root, action, 256); }

    private void walk(Path root, EntryAction action, int depth) throws IOException, InterruptedException {
        try {
            Files.walkFileTree(root, Set.of(), depth, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { tick(); action.accept(dir, root.relativize(dir), attrs); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    tick();
                    if (attrs.isDirectory()) {
                        if (!"search".equals(request.operation())) throw new IOException("directory depth limit reached; no result was committed");
                        depthLimited = true;
                    }
                    action.accept(file, root.relativize(file), attrs); return FileVisitResult.CONTINUE;
                }
            });
        } catch (InterruptedIOException canceled) { throw new InterruptedException(canceled.getMessage()); }
    }

    private void tick() throws IOException {
        checkIo(); if (++visited > request.entriesValue()) throw new ScanLimit();
        if (visited % 500 == 0 && progress != null) progress.accept(visited);
    }

    private void check() throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("file operation canceled");
        if (System.nanoTime() >= deadline) throw new IOException("file operation timed out");
    }

    private void checkIo() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("file operation canceled");
        if (System.nanoTime() >= deadline) throw new IOException("file operation timed out");
    }

    private void removeTree(Path root, boolean bounded) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { if (bounded) tick(); Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; if (bounded) tick(); Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }

    private void cleanup(Path stage) throws IOException { if (Files.exists(stage, NOFOLLOW)) removeTree(stage, false); }

    private String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path, NOFOLLOW)) {
            var digest = MessageDigest.getInstance("SHA-256"); var buffer = new byte[64 * 1024]; int read;
            while ((read = input.read(buffer)) >= 0) { checkIo(); digest.update(buffer, 0, read); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    @FunctionalInterface private interface EntryAction { void accept(Path entry, Path relative, BasicFileAttributes attrs) throws IOException; }
    private static final class ScanLimit extends IOException { ScanLimit() { super("file operation exceeds max_entries"); } }
}
