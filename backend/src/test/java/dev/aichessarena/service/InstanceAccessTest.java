package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceAccessTest {
    @TempDir Path directory;
    URI local = URI.create("http://localhost:54321/api/config/openrouter");
    URI lan = URI.create("http://arena.lan:54321/api/config/openrouter");

    InstanceAccess access(String hosts) {
        return new InstanceAccess(new OpenRouterKeyStore("", directory, name -> null), hosts, "http://localhost:54321");
    }

    @Test
    void loopbackAutomaticallyClaimsAndPersistsOnlyHash() throws Exception {
        String browser = Pkce.random();
        assertTrue(access("").authorized(browser, local));
        assertEquals(Pkce.challenge(browser), Files.readString(directory.resolve("instance_claim")));
        assertFalse(Files.readString(directory.resolve("instance_claim")).contains(browser));
        assertTrue(access("").authorized(Pkce.random(), local));
        assertTrue(access("arena.lan").authorized(browser, lan));
        assertFalse(access("arena.lan").authorized(Pkce.random(), lan));
    }

    @Test
    void lanRequiresOneTimeTokenEvenWhenVisitedViaLocalhost() throws Exception {
        InstanceAccess access = access("arena.lan");
        String browser = Pkce.random();
        assertFalse(access.authorized(browser, local));
        assertFalse(access.authorized(browser, lan));
        String token = access.setupToken();
        assertEquals(token, access("arena.lan").setupToken());
        assertThrows(WebApplicationException.class, () -> access.claim(browser, "wrong-token"));
        access.claim(browser, token);
        assertTrue(access.authorized(browser, lan));
        assertTrue(access("arena.lan").authorized(browser, lan));
        assertFalse(access.authorized(Pkce.random(), lan));
        assertFalse(Files.exists(directory.resolve("setup_token")));
        assertThrows(WebApplicationException.class, () -> access.claim(Pkce.random(), token));
    }

    @Test
    void foreignHostsAndOriginsAndInvalidBrowserCredentialsFailClosed() {
        InstanceAccess access = access("arena.lan");
        assertThrows(WebApplicationException.class, () -> access.requireHost(URI.create("http://evil.example/api")));
        assertEquals("http://localhost:54321", access.origin(local, "http://localhost:54321", true));
        for (String origin : new String[]{null, "null", "https://foreign.example", "http://localhost:1", "ftp://localhost:54321", "http://localhost:54321/", "http://user@localhost:54321", "http://localhost:54321?x"}) {
            assertThrows(WebApplicationException.class, () -> access.origin(local, origin, true));
        }
        assertFalse(access.authorized(null, lan));
        assertFalse(access.authorized("invalid", lan));
    }

    @Test
    void wildcardAndIpv6HostsMatchNginxRules() {
        InstanceAccess access = access("*.lan arena.* [fd00::1]");
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "lan", "chess.lan", "arena.test", "[fd00::1]"}) {
            access.requireHost(URI.create("http://" + host + ":54321/api"));
        }
        assertThrows(WebApplicationException.class, () -> access.requireHost(URI.create("http://notlan:54321/api")));
    }
}
