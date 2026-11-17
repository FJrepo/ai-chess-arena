package dev.aichessarena.resource;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aichessarena.service.InstanceAccess;
import dev.aichessarena.service.OpenRouterConnection;
import dev.aichessarena.service.OpenRouterKeyStore;
import dev.aichessarena.service.Pkce;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Map;
import java.util.Set;

@Path("/api/config/openrouter")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenRouterConfigResource {
    static final String COOKIE = "arena_browser";
    @Inject OpenRouterKeyStore keys;
    @Inject OpenRouterConnection connection;
    @Inject InstanceAccess access;
    @Context UriInfo uri;
    @HeaderParam("Origin") String origin;
    @CookieParam(COOKIE) String browser;

    @GET
    public Response status() {
        access.origin(uri.getRequestUri(), origin, false);
        String credential = browser != null && browser.matches("[A-Za-z0-9_-]{43}") ? browser : Pkce.random();
        boolean authorized = access.authorized(credential, uri.getRequestUri());
        return response(new View(keys.status(), authorized), credential);
    }

    @POST
    @Path("/claim")
    public Response claim(JsonNode body) {
        access.origin(uri.getRequestUri(), origin, true);
        access.claim(browser, text(body, "token", Set.of("token")));
        return response(new View(keys.status(), true), browser);
    }

    @POST
    @Path("/connect")
    public Response start() {
        String validatedOrigin = authorize();
        keys.requireMutable();
        return response(Map.of("authUrl", connection.start(browser, validatedOrigin)), null);
    }

    @POST
    @Path("/complete")
    public Response complete(JsonNode body) {
        String validatedOrigin = authorize();
        String state = text(body, "state", Set.of("code", "state"));
        String code = text(body, "code", Set.of("code", "state"));
        connection.complete(browser, validatedOrigin, state, code);
        return response(new View(keys.status(), true), null);
    }

    @POST
    @Path("/key")
    public Response manual(JsonNode body) {
        authorize();
        keys.requireMutable();
        connection.manual(text(body, "key", Set.of("key")));
        return response(new View(keys.status(), true), null);
    }

    @DELETE
    @Path("/key")
    public Response delete() {
        authorize();
        keys.delete();
        return response(new View(keys.status(), true), null);
    }

    @POST
    @Path("/test")
    public Response test() {
        authorize();
        return response(new View(connection.test(), true), null);
    }

    private String authorize() {
        String validatedOrigin = access.origin(uri.getRequestUri(), origin, true);
        access.requireClaim(browser, uri.getRequestUri());
        return validatedOrigin;
    }

    private static String text(JsonNode body, String name, Set<String> fields) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Request body must be an object");
        body.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new IllegalArgumentException("Unexpected request field");
        });
        if (!body.path(name).isTextual() || body.path(name).asText().isBlank()) {
            throw new IllegalArgumentException("Required field missing or invalid: " + name);
        }
        return body.path(name).asText();
    }

    private Response response(Object body, String credential) {
        Response.ResponseBuilder response = Response.ok(body).header("Cache-Control", "no-store");
        if (credential != null) {
            response.cookie(new NewCookie.Builder(COOKIE).value(credential).path("/api/config/openrouter")
                    .httpOnly(true).secure(origin != null ? origin.startsWith("https:") : "https".equals(uri.getRequestUri().getScheme()))
                    .sameSite(NewCookie.SameSite.LAX).maxAge(365 * 24 * 3600).build());
        }
        return response.build();
    }

    public record View(OpenRouterKeyStore.Status key, boolean canManage) {}
}
