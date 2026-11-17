package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import jakarta.ws.rs.WebApplicationException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRouterConnectionTest {
    @TempDir Path directory;
    HttpServer server;
    OpenRouterKeyStore keys;
    OpenRouterConnection connection;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String providerKey = "test-provider-key";
    int exchangeStatus = 200;
    int validationStatus = 200;
    String validationBody = "{\"data\":{}}";
    AtomicReference<String> exchange = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    String browser = Pkce.random();
    String origin = "http://localhost:54321";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/keys", request -> {
            exchange.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"key\":\"" + providerKey + "\",\"user_id\":\"discard-me\"}").getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(exchangeStatus, body.length);
            request.getResponseBody().write(body);
            request.close();
        });
        server.createContext("/key", request -> {
            authorization.set(request.getRequestHeaders().getFirst("Authorization"));
            byte[] body = validationBody.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(validationStatus, body.length);
            request.getResponseBody().write(body);
            request.close();
        });
        server.start();
        keys = OpenRouterKeyStoreFixture.keys(null, directory);
        connection = new OpenRouterConnection(keys, mapper, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void pkceMatchesRfc7636AndHasCryptographicVerifier() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertTrue(Pkce.random().matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(Pkce.random(), Pkce.random());
    }

    @Test
    void exchangesThenValidatesAndPublishesPrivateKeyWithoutUserId() throws Exception {
        String authUrl = connection.start(browser, origin);
        String state = state(authUrl);
        assertTrue(authUrl.startsWith("https://openrouter.ai/auth?"));
        connection.complete(browser, origin, state, "fake-code");
        var request = mapper.readTree(exchange.get());
        assertEquals("S256", request.path("code_challenge_method").asText());
        assertEquals("fake-code", request.path("code").asText());
        assertTrue(authUrl.contains(Pkce.challenge(request.path("code_verifier").asText())));
        assertEquals("Bearer " + providerKey, authorization.get());
        assertEquals(providerKey, keys.key());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("openrouter_key"))));
        assertEquals("valid", keys.status().validation());
        assertEquals("••••-key", keys.status().maskedSuffix());
        assertNotNull(keys.status().lastValidatedAt());
        String response = mapper.writeValueAsString(keys.status());
        assertFalse(response.contains(providerKey));
        assertFalse(response.contains("discard-me"));
        assertEquals(List.of("openrouter_key"), Files.list(directory).map(p -> p.getFileName().toString()).toList());
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, origin, state, "fake-code"));
    }

    @Test
    void stateExpiresAtTenMinutes() {
        String state = state(connection.start(browser, origin));
        connection.clock = Clock.offset(connection.clock, Duration.ofMinutes(10));
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, origin, state, "fake-code"));
        assertNull(exchange.get());
    }

    @Test
    void wrongCookieConsumesStateAndCannotReplay() {
        String state = state(connection.start(browser, origin));
        assertThrows(WebApplicationException.class, () -> connection.complete(Pkce.random(), origin, state, "fake-code"));
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, origin, state, "fake-code"));
        assertNull(exchange.get());
    }

    @Test
    void wrongOriginAndUnknownStateCannotExchange() {
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, origin, Pkce.random(), "fake-code"));
        String state = state(connection.start(browser, origin));
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, "http://localhost:1", state, "fake-code"));
        assertNull(exchange.get());
    }

    @Test
    void exchangeErrorsAreSanitizedAndSingleUse() {
        exchangeStatus = 400;
        String state = state(connection.start(browser, origin));
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> connection.complete(browser, origin, state, "fake-code"));
        assertEquals(502, error.getResponse().getStatus());
        assertFalse(error.getMessage().contains(providerKey));
        assertTrue(keys.key().isBlank());
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, origin, state, "fake-code"));
    }

    @Test
    void invalidValidationKeepsPreviousKeyAndRejectsOauthAndPaste() {
        connection.manual(providerKey);
        providerKey = "test-replacement-key";
        validationStatus = 401;
        String state = state(connection.start(browser, origin));
        assertThrows(WebApplicationException.class, () -> connection.complete(browser, origin, state, "fake-code"));
        assertThrows(WebApplicationException.class, () -> connection.manual(providerKey));
        assertEquals("test-provider-key", keys.key());
    }

    @Test
    void malformedValidationAndProviderOutageDoNotSave() {
        validationBody = "{}";
        assertThrows(WebApplicationException.class, () -> connection.manual(providerKey));
        validationStatus = 500;
        assertThrows(WebApplicationException.class, () -> connection.manual(providerKey));
        assertTrue(keys.key().isBlank());
    }

    @Test
    void networkErrorsDoNotSaveOrLeakAndCannotReplay() {
        String state = state(connection.start(browser, origin));
        server.stop(0);
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> connection.complete(browser, origin, state, "fake-code"));
        assertEquals(502, error.getResponse().getStatus());
        assertFalse(error.getMessage().contains(providerKey));
        assertThrows(WebApplicationException.class, () -> connection.manual(providerKey));
        assertTrue(keys.key().isBlank());
    }

    @Test
    void invalidInputAndPendingStateLimitFailClosed() {
        for (String value : List.of("", "1234", "key with spaces", "key\nheader")) {
            assertThrows(IllegalArgumentException.class, () -> connection.manual(value));
        }
        for (int i = 0; i < 256; i++) connection.start(browser, origin);
        assertEquals(429, assertThrows(WebApplicationException.class, () -> connection.start(browser, origin)).getResponse().getStatus());
    }

    @Test
    void envAndFilePrecedenceAreDynamicAndImmutable() throws Exception {
        Map<String, String> environment = new HashMap<>();
        keys = OpenRouterKeyStoreFixture.keys(null, directory, environment);
        keys.save(providerKey);
        Path operatorFile = directory.resolve("operator");
        Files.writeString(operatorFile, "  test-operator-key\n");
        environment.put("OPENROUTER_API_KEY_FILE", operatorFile.toString());
        keys = OpenRouterKeyStoreFixture.keys(null, directory, environment);
        assertEquals("file", keys.source());
        assertEquals("test-operator-key", keys.key());
        assertEquals(409, assertThrows(WebApplicationException.class, keys::delete).getResponse().getStatus());
        assertThrows(WebApplicationException.class, () -> keys.save("test-overwrite-key"));
        keys.checked(keys.key(), "valid");
        assertNotNull(keys.status().lastValidatedAt());
        Files.writeString(operatorFile, "test-rotated-key");
        assertEquals("test-rotated-key", keys.key());
        assertEquals("unknown", keys.status().validation());
        assertNull(keys.status().lastValidatedAt());
        environment.put("OPENROUTER_API_KEY", "test-env-key");
        keys = OpenRouterKeyStoreFixture.keys(null, directory, environment);
        assertEquals("env", keys.source());
        assertEquals("test-env-key", keys.key());
        assertNull(keys.status().lastValidatedAt());
        environment.put("OPENROUTER_API_KEY", "");
        environment.remove("OPENROUTER_API_KEY_FILE");
        keys = OpenRouterKeyStoreFixture.keys(null, directory, environment);
        assertEquals(providerKey, keys.key());
        keys.delete();
        assertEquals("none", keys.source());
        assertNull(keys.status().maskedSuffix());
    }

    @Test
    void shortConfiguredCredentialIsNeverReturnedAndEmptyFileFailsClosed() throws Exception {
        var shortKey = OpenRouterKeyStoreFixture.keys("1234", directory);
        assertNull(shortKey.status().maskedSuffix());
        Path file = directory.resolve("empty");
        Files.writeString(file, "\n");
        var fileKeys = OpenRouterKeyStoreFixture.keys(null, directory, Map.of("OPENROUTER_API_KEY_FILE", file.toString()));
        assertThrows(IllegalStateException.class, fileKeys::key);
    }

    @Test
    void nextChatRequestUsesRotatedKeyAndRedactsProviderEchoInPayloadAndLogs() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        StringBuilder logs = new StringBuilder();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(OpenRouterService.class.getName());
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logs.append(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            server.createContext("/chat/completions", request -> {
                sent.set(request.getRequestHeaders().getFirst("Authorization"));
                String key = sent.get().substring(7);
                byte[] body = ("{\"error\":{\"message\":\"" + key + "\"}}").getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(401, body.length);
                request.getResponseBody().write(body);
                request.close();
            });
            var router = new OpenRouterService();
            router.keyStore = keys;
            router.objectMapper = mapper;
            router.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            connection.manual(providerKey);
            var first = router.chat("fake/model", List.of(new OpenRouterService.ChatMsg("user", "move")));
            assertEquals("Bearer " + providerKey, sent.get());
            assertTrue(first.isProviderFailure());
            assertFalse(first.rawResponse().contains(providerKey));
            assertFalse(first.errorMessage().contains(providerKey));
            connection.manual("test-next-key");
            router.chat("fake/model", List.of(new OpenRouterService.ChatMsg("user", "move")));
            assertEquals("Bearer test-next-key", sent.get());
            assertFalse(logs.toString().contains(providerKey));
            assertFalse(logs.toString().contains("test-next-key"));
            keys.delete();
            assertTrue(router.chat("fake/model", List.of()).isProviderFailure());
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static String state(String authUrl) {
        String callback = URLDecoder.decode(URI.create(authUrl).getRawQuery().split("&")[0].substring("callback_url=".length()), StandardCharsets.UTF_8);
        return URI.create(callback).getQuery().substring("state=".length());
    }
}
