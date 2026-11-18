package dev.aichessarena.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.dto.BenchmarkRunDto;
import dev.aichessarena.service.BenchmarkRunService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@Path("/api/benchmarks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BenchmarkResource {
    @Inject BenchmarkRunService benchmarks;
    @Inject ObjectMapper json;

    @POST
    public Response create(String body) {
        var result = benchmarks.create(readBody(body));
        BenchmarkRunDto run = result.run();
        return result.created()
                ? Response.created(URI.create("/api/benchmarks/" + run.run().id())).entity(run).build()
                : Response.ok(run).build();
    }

    @GET
    public List<BenchmarkRunDto.Summary> list() {
        return benchmarks.list();
    }

    @GET
    @Path("/{id}")
    public BenchmarkRunDto get(@PathParam("id") UUID id) {
        return benchmarks.get(id);
    }

    @POST
    @Path("/{id}/resume")
    public BenchmarkRunDto resume(@PathParam("id") UUID id, String body) {
        return benchmarks.resume(id, readBody(body));
    }

    @POST
    @Path("/{id}/pause")
    @Consumes(MediaType.WILDCARD)
    public BenchmarkRunDto pause(@PathParam("id") UUID id) {
        return benchmarks.pause(id);
    }

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    public BenchmarkRunDto cancel(@PathParam("id") UUID id) {
        return benchmarks.cancel(id);
    }

    private JsonNode readBody(String body) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Request body is required");
        try {
            return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid benchmark JSON request");
        }
    }
}
