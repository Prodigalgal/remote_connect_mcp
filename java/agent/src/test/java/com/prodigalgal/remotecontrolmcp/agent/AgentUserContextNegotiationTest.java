package com.prodigalgal.remotecontrolmcp.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.TransportNegotiation;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentUserContextNegotiationTest {
    @Test
    void olderCenterRegistrationUpgradeAndRollbackKeepHeartbeatsReadable(@TempDir Path stateDir) throws Exception {
        var acceptsContext = new AtomicBoolean(false);
        var bodies = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/v1/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            var rejected = !acceptsContext.get() && body.contains("\"user_context\"");
            var register = exchange.getRequestURI().getPath().endsWith("/register");
            var response = (rejected ? "{}" : register ? "{\"machine_id\":\"machine\",\"token\":\"test-token\"}"
                    : "{\"cancel_task_ids\":[]}").getBytes(StandardCharsets.UTF_8);
            if (acceptsContext.get()) exchange.getResponseHeaders().set(TransportNegotiation.HEADER_USER_CONTEXT,
                    TransportNegotiation.USER_CONTEXT_VERSION);
            exchange.sendResponseHeaders(rejected ? 400 : register ? 201 : 200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var config = new AgentConfig(endpoint, "test-enrollment", "agent", "host", stateDir.toString(),
                    List.of("command"), false, stateDir, Duration.ofSeconds(5), 1);
            var client = new AgentTransportClient(endpoint, http, Duration.ofSeconds(5), 1L);
            assertNotNull(config.metadata().runtime().userContext());
            client.register(config);
            assertFalse(bodies.getLast().contains("\"user_context\""));
            var poll = new PollRequest(List.of(), 1, List.of("command"), config.metadata());
            client.poll("machine", "test-token", poll);
            assertFalse(bodies.getLast().contains("\"user_context\""));
            acceptsContext.set(true);
            client.poll("machine", "test-token", poll);
            assertFalse(bodies.getLast().contains("\"user_context\""));
            client.poll("machine", "test-token", poll);
            assertTrue(bodies.getLast().contains("\"user_context\""));
            acceptsContext.set(false);
            assertEquals(400, assertThrows(CenterTransportException.class,
                    () -> client.poll("machine", "test-token", poll)).statusCode());
            client.poll("machine", "test-token", poll);
            assertFalse(bodies.getLast().contains("\"user_context\""));
        } finally {
            server.stop(0);
        }
    }
}
