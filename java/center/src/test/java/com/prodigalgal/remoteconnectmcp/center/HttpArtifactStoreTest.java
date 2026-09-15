package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HttpArtifactStoreTest {
    @Test
    void storesReadsAndDeletesThroughTheBoundedObjectGateway() throws Exception {
        var stored = new AtomicReference<byte[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/objects/", exchange -> {
            try (exchange) {
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        stored.set(exchange.getRequestBody().readAllBytes());
                        exchange.sendResponseHeaders(201, -1);
                    }
                    case "GET" -> {
                        var data = stored.get();
                        if (data == null) {
                            exchange.sendResponseHeaders(404, -1);
                        } else {
                            exchange.sendResponseHeaders(200, data.length);
                            exchange.getResponseBody().write(data);
                        }
                    }
                    case "DELETE" -> {
                        stored.set(null);
                        exchange.sendResponseHeaders(204, -1);
                    }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            }
        });
        server.start();
        try {
            var store = new HttpArtifactStore(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "gateway-token", Duration.ofSeconds(5));
            var data = "remote-artifact".getBytes(StandardCharsets.UTF_8);
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            var key = store.put("task-http", digest, data);
            assertEquals("http", store.backend());
            assertArrayEquals(data, store.read(key));
            store.delete(key);
            assertEquals(null, stored.get());
        } finally {
            server.stop(0);
        }
    }
}
