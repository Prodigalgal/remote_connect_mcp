package com.prodigalgal.remoteconnectmcp.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional WebSocket wake-up channel. It carries only a bounded "wake" hint;
 * task data, authentication refresh and ordering remain on the existing
 * HTTPS poll endpoint. A failed or lost WebSocket can therefore never make
 * the Agent offline.
 */
final class AgentWakeClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AgentWakeClient.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_RECONNECT_DELAY = Duration.ofMinutes(2);

    private final AgentConfig config;
    private final AtomicReference<AgentIdentity> identity;
    private final Runnable wake;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private volatile Thread loop;

    private AgentWakeClient(AgentConfig config, AgentIdentity identity, Runnable wake, HttpClient http) {
        this.config = config;
        this.identity = new AtomicReference<>(identity);
        this.wake = wake;
        this.http = http;
    }

    static AgentWakeClient startIfEnabled(AgentConfig config, AgentIdentity identity, Runnable wake) {
        var mode = System.getenv().getOrDefault("REMOTE_CONNECT_MCP_AGENT_WAKE_TRANSPORT", "poll")
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!"websocket".equals(mode) && !"ws".equals(mode)) {
            return null;
        }
        var client = new AgentWakeClient(config, identity, wake,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        client.start();
        return client;
    }

    static AgentWakeClient forTest(AgentConfig config, AgentIdentity identity, Runnable wake, HttpClient http) {
        return new AgentWakeClient(config, identity, wake, http);
    }

    void start() {
        if (loop != null) return;
        loop = Thread.ofVirtual().name("rcm-agent-wake").start(this::runLoop);
    }

    void updateIdentity(AgentIdentity value) {
        if (value == null) return;
        identity.set(value);
        closeSocket();
    }

    private void runLoop() {
        var delay = config.pollInterval();
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            var latch = new CountDownLatch(1);
            try {
                var current = identity.get();
                var future = http.newWebSocketBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .header("Authorization", "Bearer " + current.token())
                        .header("X-Machine-ID", current.machineId())
                        .buildAsync(websocketUri(config.centerUrl()), new Listener(latch));
                var connected = future.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                socket.set(connected);
                delay = config.pollInterval();
                latch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                if (!closed.get()) {
                    LOG.log(Level.FINE, "Agent WebSocket wake channel unavailable; HTTPS long-poll remains active", exception);
                }
                delay = nextDelay(delay);
            } finally {
                socket.set(null);
            }
            if (!closed.get()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static Duration nextDelay(Duration current) {
        var millis = Math.min(MAX_RECONNECT_DELAY.toMillis(),
                Math.max(250L, Math.max(1L, current.toMillis()) * 2L));
        return Duration.ofMillis(millis);
    }

    private void closeSocket() {
        var current = socket.getAndSet(null);
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
            } catch (RuntimeException ignored) {
                current.abort();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeSocket();
        var current = loop;
        if (current != null) current.interrupt();
    }

    static URI websocketUri(URI centerUrl) {
        if (centerUrl == null || centerUrl.getHost() == null) {
            throw new IllegalArgumentException("centerUrl is required");
        }
        var scheme = "https".equalsIgnoreCase(centerUrl.getScheme()) ? "wss" : "ws";
        try {
            return new URI(scheme, centerUrl.getUserInfo(), centerUrl.getHost(), centerUrl.getPort(),
                    "/agent/v1/ws", null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("centerUrl cannot be converted to WebSocket URI", exception);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final CountDownLatch disconnected;

        private Listener(CountDownLatch disconnected) {
            this.disconnected = disconnected;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            if (last && data.length() <= 4096 && data.toString().contains("\"type\":\"wake\"")) {
                try {
                    wake.run();
                } catch (RuntimeException exception) {
                    LOG.log(Level.FINE, "Agent wake callback failed", exception);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected.countDown();
            if (!closed.get()) LOG.log(Level.FINE, "Agent WebSocket wake channel closed", error);
        }
    }
}
