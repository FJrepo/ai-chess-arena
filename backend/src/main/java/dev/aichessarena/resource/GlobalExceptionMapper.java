package dev.aichessarena.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Context
    UriInfo uri;

    @Override
    public Response toResponse(Throwable exception) {
        boolean credentialRequest = uri != null && uri.getPath().replaceFirst("^/", "").startsWith("api/config/openrouter");
        if (credentialRequest && (exception instanceof BadRequestException || hasJsonFailure(exception))) {
            // JSON parse errors can embed the write-only input; never log or reflect their detail.
            return error(Response.Status.BAD_REQUEST, "Invalid OpenRouter configuration request");
        }
        if (exception instanceof IllegalArgumentException iae) {
            return error(Response.Status.BAD_REQUEST, iae.getMessage());
        }

        if (exception instanceof WebApplicationException wae) {
            int status = wae.getResponse() != null
                    ? wae.getResponse().getStatus()
                    : Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = status >= 500 ? "Internal server error" : "Request failed";
            }
            if (credentialRequest) {
                // These endpoints supply fixed safe errors; no provider cause or input belongs in logs.
            } else if (status >= 500) {
                LOG.error("Web application exception", exception);
            } else {
                LOG.debug("Web application exception", exception);
            }
            return error(status, message);
        }

        if (!credentialRequest) LOG.error("Unhandled exception", exception);
        return error(Response.Status.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private static boolean hasJsonFailure(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) return true;
        }
        return false;
    }

    private Response error(Response.Status status, String message) {
        return error(status.getStatusCode(), message);
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message))
                .build();
    }
}
