package dev.aichessarena.resource;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Keep credential endpoint errors as private as successful responses. */
@Provider
public class OpenRouterConfigHeaders implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/config/openrouter")) {
            response.getHeaders().putSingle("Cache-Control", "no-store");
        }
    }
}
