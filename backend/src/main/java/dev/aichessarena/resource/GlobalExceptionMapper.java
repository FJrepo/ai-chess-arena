package dev.aichessarena.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
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
            if (status >= 500) {
                LOG.error("Web application exception", exception);
            } else {
                LOG.debug("Web application exception", exception);
            }
            return error(status, message);
        }

        LOG.error("Unhandled exception", exception);
        return error(Response.Status.INTERNAL_SERVER_ERROR, "Internal server error");
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
