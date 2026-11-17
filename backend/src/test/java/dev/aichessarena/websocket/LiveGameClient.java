package dev.aichessarena.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A real subscribed network client for integration tests, with an explicit subscription barrier. */
public final class LiveGameClient implements AutoCloseable, WebSocket.Listener {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();
    private WebSocket connection;

    public LiveGameClient(URI endpoint, UUID gameId, GameWebSocket server) throws Exception {
        try {
            URI uri = URI.create(endpoint.toString().replaceFirst("^http", "ws"));
            connection = http.newWebSocketBuilder().buildAsync(uri, this).get(10, TimeUnit.SECONDS);
            connection.sendText("{\"type\":\"subscribe\",\"gameId\":\"%s\"}".formatted(gameId), true)
                    .get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (server.subscriberCount(gameId) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, server.subscriberCount(gameId), "live client subscribed before game starts");
        } catch (Exception | AssertionError e) {
            close();
            throw e;
        }
    }

    @Override
    public void onOpen(WebSocket socket) {
        socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            messages.add(partial.toString());
            partial.setLength(0);
        }
        socket.request(1);
        return null;
    }

    public JsonNode awaitType(String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(message, "live client received " + type);
            JsonNode node = mapper.readTree(message);
            if (type.equals(node.path("type").asText())) return node;
        }
        throw new AssertionError("live client did not receive " + type);
    }

    @Override
    public void close() {
        if (connection != null) connection.abort();
        http.close();
    }
}
