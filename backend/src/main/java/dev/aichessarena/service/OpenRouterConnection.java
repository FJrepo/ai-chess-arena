package dev.aichessarena.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Bounded, single-use, browser-bound PKCE. Provider payloads and exceptions never leave this class. */
@ApplicationScoped
public class OpenRouterConnection {
    private final OpenRouterKeyStore keys;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Pending> pending = new HashMap<>();
    Clock clock = Clock.systemUTC();

    @Inject
    public OpenRouterConnection(OpenRouterKeyStore keys, ObjectMapper mapper,
                                @ConfigProperty(name = "openrouter.base-url") String baseUrl) {
        this.keys = keys;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    public synchronized String start(String browser, String origin) {
        keys.requireMutable();
        Instant now = clock.instant();
        pending.values().removeIf(p -> !p.expires.isAfter(now));
        if (pending.size() >= 256) throw new WebApplicationException("Too many pending connections; try again later", 429);
        String state = Pkce.random();
        String verifier = Pkce.random();
        pending.put(state, new Pending(verifier, Pkce.challenge(browser), origin, now.plusSeconds(600)));
        String callback = origin + "/connect/callback?state=" + state;
        return "https://openrouter.ai/auth?callback_url=" + URLEncoder.encode(callback, StandardCharsets.UTF_8)
                + "&code_challenge=" + Pkce.challenge(verifier) + "&code_challenge_method=S256";
    }

    public void complete(String browser, String origin, String state, String code) {
        keys.requireMutable();
        Pending attempt;
        synchronized (this) {
            attempt = state == null ? null : pending.remove(state);
        }
        if (attempt == null || !attempt.expires.isAfter(clock.instant())
                || !attempt.browserHash.equals(Pkce.challenge(browser)) || !attempt.origin.equals(origin)) {
            throw new WebApplicationException("Connection expired or does not belong to this browser; connect again", 400);
        }
        if (code == null || code.isBlank() || code.length() > 4096) {
            throw new IllegalArgumentException("Authorization code is required");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("code", code, "code_verifier", attempt.verifier,
                    "code_challenge_method", "S256"));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/auth/keys"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new WebApplicationException("OpenRouter authorization exchange failed; connect again", 502);
            JsonNode result = mapper.readTree(response.body());
            String key = result == null || !result.path("key").isTextual() ? null : result.path("key").asText();
            manual(key);
        } catch (WebApplicationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new WebApplicationException("Cannot reach OpenRouter; connect again", 502);
        }
    }

    public void manual(String key) {
        keys.requireMutable();
        if (key == null || key.length() <= 4 || key.length() > 4096 || !key.matches("[!-~]+")) {
            throw new IllegalArgumentException("Enter a non-empty OpenRouter key without whitespace");
        }
        String result = validate(key);
        if (!result.equals("valid")) {
            throw new WebApplicationException(result.equals("invalid") ? "OpenRouter rejected this key" : "Cannot validate key with OpenRouter; retry later",
                    result.equals("invalid") ? 400 : 502);
        }
        keys.save(key);
    }

    public OpenRouterKeyStore.Status test() {
        String key = keys.key();
        if (!key.isBlank()) keys.checked(key, validate(key));
        return keys.status();
    }

    private String validate(String key) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/key"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + key)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) return "invalid";
            if (response.statusCode() != 200) return "unavailable";
            JsonNode body = mapper.readTree(response.body());
            return body != null && body.path("data").isObject() && !body.hasNonNull("error") ? "valid" : "invalid";
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "unavailable";
        }
    }

    private static final class Pending {
        final String verifier;
        final String browserHash;
        final String origin;
        final Instant expires;

        Pending(String verifier, String browserHash, String origin, Instant expires) {
            this.verifier = verifier;
            this.browserHash = browserHash;
            this.origin = origin;
            this.expires = expires;
        }
    }
}
