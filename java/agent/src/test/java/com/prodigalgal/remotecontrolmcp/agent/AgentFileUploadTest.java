package com.prodigalgal.remotecontrolmcp.agent;

import com.prodigalgal.remotecontrolmcp.protocol.FileTransferResponse;
import com.prodigalgal.remotecontrolmcp.protocol.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentFileUploadTest {
    @Test void unicodeNameUploadsWithoutPuttingUnicodeInHttpHeaders(@TempDir Path root) throws Exception {
        var source = Files.writeString(root.resolve("大乐透 中文报告.xlsx"), "test payload 中文");
        var bytes = Files.readAllBytes(source);
        var sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var uploaded = new AtomicReference<byte[]>(); var filenameHeader = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/v1/transfers/", exchange -> {
            try {
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("X-RCM-Resume-Offset", "0");
                    exchange.getResponseHeaders().set("X-RCM-Transfer-Status", "receiving");
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    filenameHeader.set(exchange.getRequestHeaders().getFirst("X-RCM-File-Name"));
                    uploaded.set(exchange.getRequestBody().readAllBytes());
                    var response = JsonCodec.write(new FileTransferResponse("transfer-test", "artifact-test", "delivered", bytes.length, sha, null));
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            } finally { exchange.close(); }
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var client = new AgentTransportClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, Duration.ofSeconds(5), 1L);
            var result = client.uploadTransfer("machine", "test-auth", "transfer-test", source, source.getFileName().toString(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length, sha, 1);
            assertEquals("delivered", result.status()); assertArrayEquals(bytes, uploaded.get());
            assertNull(filenameHeader.get(), "filename belongs in transfer JSON, not an HTTP header");
        } finally { server.stop(0); }
    }
}
