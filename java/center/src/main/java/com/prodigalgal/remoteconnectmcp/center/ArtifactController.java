package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
                                                          @RequestParam long expires,
                                                          @RequestParam String principal,
                                                          @RequestParam(defaultValue = "") String connection,
                                                          @RequestParam(defaultValue = "download") String purpose,
                                                          @RequestParam String signature) {
        var artifact = transfers.openPublic(artifactId, expires, principal, connection, purpose, signature);
        StreamingResponseBody body = output -> {
            try (var input = artifact.body()) {
                input.transferTo(output);
            } catch (IOException exception) {
                throw exception;
            }
        };
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.mimeType() == null || artifact.mimeType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : artifact.mimeType()));
        headers.setContentLength(artifact.bytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(artifact.fileName()).build());
        headers.set("X-RCM-Artifact-SHA256", artifact.sha256());
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
