package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Short-lived signed artifact URL used by ChatGPT/React file renderers. */
@RestController
public final class ArtifactController {
    private final ArtifactTransferService transfers;

    public ArtifactController(ArtifactTransferService transfers) {
        this.transfers = transfers;
    }

    @GetMapping("/artifacts/{artifactId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String artifactId,
                                                          @RequestParam(required = false) Long expires,
                                                          @RequestParam(defaultValue = "") String principal,
                                                          @RequestParam(defaultValue = "") String connection,
                                                          @RequestParam(defaultValue = "") String session,
                                                          @RequestParam(defaultValue = "download") String purpose,
                                                          @RequestParam(defaultValue = "") String signature,
                                                          @RequestParam(defaultValue = "") String token,
                                                          @org.springframework.web.bind.annotation.RequestHeader(value = "Range", required = false) String rangeHeader) {
        var artifact = token.isBlank()
                ? transfers.openPublic(artifactId, expires == null ? 0L : expires, principal, connection, session, purpose, signature)
                : transfers.openPublic(token);
        var range = parseRange(rangeHeader, artifact.bytes());
        if (range.invalid()) {
            var headers = new HttpHeaders();
            headers.set("Accept-Ranges", "bytes");
            headers.set("Content-Range", "bytes */" + artifact.bytes());
            headers.setCacheControl("private, no-store");
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers).build();
        }
        StreamingResponseBody body = output -> {
            try (var input = artifact.body()) {
                if (range.start() > 0) skipFully(input, range.start());
                copyExactly(input, output, range.length());
            } catch (IOException exception) {
                throw exception;
            }
        };
        var headers = new HttpHeaders();
        headers.setContentType(safeMediaType(artifact.mimeType()));
        // A 206 response must advertise the selected slice, not the full
        // artifact.  Browsers and resumable clients use this value to decide
        // whether the range is complete; advertising the full size makes
        // them wait for bytes that this response intentionally does not send.
        headers.setContentLength(range.length());
        var disposition = "preview".equalsIgnoreCase(purpose)
                ? ContentDisposition.inline().filename(artifact.fileName()).build()
                : ContentDisposition.attachment().filename(artifact.fileName()).build();
        headers.setContentDisposition(disposition);
        headers.set("X-RCM-Artifact-SHA256", artifact.sha256());
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Accept-Ranges", "bytes");
        if (range.partial()) {
            headers.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + artifact.bytes());
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(body);
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static Range parseRange(String value, long total) {
        if (value == null || value.isBlank()) return new Range(0, Math.max(0, total - 1), Math.max(0, total), false, false);
        if (total <= 0 || !value.trim().toLowerCase(java.util.Locale.ROOT).startsWith("bytes=")) {
            return new Range(0, -1, 0, false, true);
        }
        var raw = value.trim().substring("bytes=".length());
        // A single range keeps the response streamable and avoids multipart
        // framing/content-type ambiguity in browser and MCP viewers.
        if (raw.indexOf(',') >= 0) return new Range(0, -1, 0, false, true);
        var parts = raw.split("-", -1);
        if (parts.length != 2) return new Range(0, -1, 0, false, true);
        try {
            long start;
            long end;
            if (parts[0].isBlank()) {
                var suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) return new Range(0, -1, 0, false, true);
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isBlank() ? total - 1 : Long.parseLong(parts[1]);
                if (start < 0 || start >= total || end < start) return new Range(0, -1, 0, false, true);
                end = Math.min(end, total - 1);
            }
            return new Range(start, end, end - start + 1, start != 0 || end != total - 1, false);
        } catch (NumberFormatException ignored) {
            return new Range(0, -1, 0, false, true);
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        var remaining = bytes;
        while (remaining > 0) {
            var skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() < 0) {
                throw new IOException("artifact ended before requested range");
            } else {
                remaining--;
            }
        }
    }

    private static void copyExactly(InputStream input, java.io.OutputStream output, long bytes) throws IOException {
        var remaining = bytes;
        var buffer = new byte[64 * 1024];
        while (remaining > 0) {
            var read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("artifact ended before requested range");
            if (read == 0) continue;
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private record Range(long start, long end, long length, boolean partial, boolean invalid) { }

    private static MediaType safeMediaType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
