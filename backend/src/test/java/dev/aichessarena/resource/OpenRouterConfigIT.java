package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.service.Pkce;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(OpenRouterConfigIT.Profile.class)
@QuarkusTestResource(value = OpenRouterConfigIT.Provider.class, restrictToAnnotatedClass = true)
class OpenRouterConfigIT {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("openrouter.api-key", "", "instance.allowed-hosts", "arena.lan");
        }
    }

    public static class Provider implements QuarkusTestResourceLifecycleManager {
        HttpServer server;
        Path directory;
        @Override
        public Map<String, String> start() {
            try {
                directory = Files.createTempDirectory("arena-connect-it-");
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/key", request -> {
                    byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
                    request.sendResponseHeaders(200, body.length);
                    request.getResponseBody().write(body);
                    request.close();
                });
                server.createContext("/auth/keys", request -> {
                    byte[] body = "{\"key\":\"fixture-oauth-key-5678\",\"user_id\":\"never-store\"}".getBytes(StandardCharsets.UTF_8);
                    request.sendResponseHeaders(200, body.length);
                    request.getResponseBody().write(body);
                    request.close();
                });
                server.start();
                return Map.of("openrouter.base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "instance.secrets-dir", directory.toString());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot start provider fixture", e);
            }
        }
        @Override
        public void stop() {
            if (server != null) server.stop(0);
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                        try { Files.deleteIfExists(file); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        }
    }

    @Test
    void noKeyStartupLanClaimManualOauthTestDeleteAndNoKeyCreation() throws Exception {
        String host = "localhost:" + RestAssured.port;
        String origin = "http://" + host;
        var status = given().header("Host", host).get("/api/config/openrouter").then().statusCode(200)
                .header("Cache-Control", "no-store").body("key.source", equalTo("none"))
                .body("canManage", equalTo(false)).extract().response();
        String browser = status.getCookie("arena_browser");
        String setCookie = status.header("Set-Cookie");
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body("{\"key\":\"fixture-manual-1234\"}")
                .post("/api/config/openrouter/key").then().statusCode(403);
        Path directory = Path.of(ConfigProvider.getConfig().getValue("instance.secrets-dir", String.class));
        String token = Files.readString(directory.resolve("setup_token"));
        given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body(Map.of("token", token)).post("/api/config/openrouter/claim")
                .then().statusCode(200).body("canManage", equalTo(true));
        given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body(Map.of("token", token)).post("/api/config/openrouter/claim")
                .then().statusCode(403);
        String response = given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body(Map.of("key", "fixture-manual-1234"))
                .post("/api/config/openrouter/key").then().statusCode(200).body("key.source", equalTo("ui"))
                .body("key.maskedSuffix", equalTo("••••1234")).extract().asString();
        assertFalse(response.contains("fixture-manual-1234"));
        given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body("{}").post("/api/config/openrouter/test")
                .then().statusCode(200).body("key.validation", equalTo("valid"));
        String authUrl = given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body("{}").post("/api/config/openrouter/connect")
                .then().statusCode(200).extract().path("authUrl");
        String callback = URLDecoder.decode(URI.create(authUrl).getRawQuery().split("&")[0].substring(13), StandardCharsets.UTF_8);
        assertTrue(callback.startsWith(origin + "/connect/callback?state="));
        String state = URI.create(callback).getQuery().substring(6);
        given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .contentType("application/json").body(Map.of("code", "fake-code", "state", state))
                .post("/api/config/openrouter/complete").then().statusCode(200).body("key.maskedSuffix", equalTo("••••5678"));
        given().header("Host", host).header("Origin", origin).cookie("arena_browser", browser)
                .delete("/api/config/openrouter/key").then().statusCode(200).body("key.source", equalTo("none"));
        given().contentType("application/json").body("{\"name\":\"No credential\"}").post("/api/tournaments")
                .then().statusCode(409).body("error", containsString("Connect OpenRouter"));
    }

    @Test
    void malformedWriteOnlyInputGetsASafePrivateErrorBeforeResourceInvocation() {
        String secret = "fixtureMalformedPrivateInput";
        String response = given().contentType("application/json").body("{\"key\":" + secret + "}")
                .post("/api/config/openrouter/key").then().statusCode(400)
                .header("Cache-Control", "no-store").extract().asString();
        assertFalse(response.contains(secret));
        assertTrue(response.contains("Invalid OpenRouter configuration request"));
    }

    @Test
    void endpointsRejectForeignHostAndOriginAndUnclaimedCookie() {
        given().header("Host", "evil.example").get("/api/config/openrouter").then().statusCode(421);
        given().header("Origin", "https://evil.example").contentType("application/json").body("{}")
                .post("/api/config/openrouter/connect").then().statusCode(403);
        given().contentType("application/json").body("{}").cookie("arena_browser", Pkce.random())
                .post("/api/config/openrouter/connect").then().statusCode(403);
    }
}
