package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.service.InstanceAccess;
import dev.aichessarena.service.Pkce;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRouterConfigResourceTest {
    @TempDir Path directory;
    ObjectMapper mapper = new ObjectMapper();

    OpenRouterConfigResource resource(String key) {
        var resource = new OpenRouterConfigResource();
        resource.keys = OpenRouterKeyStoreFixture.keys(key, directory);
        resource.access = new InstanceAccess(resource.keys, "", "http://localhost:54321");
        resource.browser = Pkce.random();
        resource.origin = "http://localhost:54321";
        resource.uri = (UriInfo) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UriInfo.class},
                (proxy, method, args) -> method.getName().equals("getPath") ? "/api/config/openrouter"
                        : URI.create("http://localhost:54321/api/config/openrouter"));
        return resource;
    }

    @Test
    void statusIsPassiveMaskedNoStoreAndUsesHttpOnlyLaxBrowserCookie() throws Exception {
        var resource = resource("test-operator-key");
        var response = resource.status();
        var view = (OpenRouterConfigResource.View) response.getEntity();
        assertEquals("env", view.key().source());
        assertEquals("unknown", view.key().validation());
        assertTrue(view.canManage());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertFalse(mapper.writeValueAsString(view).contains("test-operator-key"));
        NewCookie cookie = response.getCookies().get("arena_browser");
        assertTrue(cookie.isHttpOnly());
        assertEquals(NewCookie.SameSite.LAX, cookie.getSameSite());
        assertEquals("/api/config/openrouter", cookie.getPath());
    }

    @Test
    void allOperatorWritePathsRejectBeforeExchangingOrStoring() throws Exception {
        var resource = resource("test-operator-key");
        assertEquals(409, assertThrows(WebApplicationException.class, resource::start).getResponse().getStatus());
        assertEquals(409, assertThrows(WebApplicationException.class, resource::delete).getResponse().getStatus());
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> resource.manual(mapper.readTree("{\"key\":\"test-new-key\"}"))).getResponse().getStatus());
        // Complete must also reject operator-managed configuration before looking at state.
        resource.connection = new dev.aichessarena.service.OpenRouterConnection(resource.keys, mapper, "http://127.0.0.1:9");
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> resource.complete(mapper.readTree("{\"code\":\"fake-code\",\"state\":\"fake-state\"}"))).getResponse().getStatus());
    }

    @Test
    void malformedJsonCannotLeakWriteOnlyInputThroughExceptionMapper() throws Exception {
        var resource = resource(null);
        var errors = new GlobalExceptionMapper();
        errors.uri = resource.uri;
        String secret = "test-malformed-key";
        var response = errors.toResponse(new jakarta.ws.rs.BadRequestException("Malformed JSON containing " + secret));
        assertEquals(400, response.getStatus());
        assertFalse(mapper.writeValueAsString(response.getEntity()).contains(secret));
    }

    @Test
    void foreignHostOriginMissingClaimAndUnknownFieldsAreRejected() throws Exception {
        var resource = resource(null);
        resource.origin = "https://foreign.example";
        assertEquals(403, assertThrows(WebApplicationException.class, resource::start).getResponse().getStatus());
        resource.origin = null;
        assertEquals(403, assertThrows(WebApplicationException.class, resource::delete).getResponse().getStatus());
        resource.origin = "http://localhost:54321";
        resource.access = new InstanceAccess(resource.keys, "arena.lan", "http://localhost:54321");
        assertEquals(403, assertThrows(WebApplicationException.class, resource::start).getResponse().getStatus());
        resource.access = new InstanceAccess(resource.keys, "", "http://localhost:54321");
        assertThrows(IllegalArgumentException.class,
                () -> resource.manual(mapper.readTree("{\"key\":\"test-new-key\",\"unexpected\":true}")));
        resource.uri = (UriInfo) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UriInfo.class},
                (proxy, method, args) -> URI.create("http://foreign.example/api/config/openrouter"));
        assertEquals(421, assertThrows(WebApplicationException.class, resource::status).getResponse().getStatus());
    }
}
