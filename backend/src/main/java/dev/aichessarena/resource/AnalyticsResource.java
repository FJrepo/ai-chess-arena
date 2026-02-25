package dev.aichessarena.resource;

import dev.aichessarena.dto.ModelReliabilityDetailDto;
import dev.aichessarena.service.AnalyticsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
public class AnalyticsResource {

    @Inject
    AnalyticsService analyticsService;

    @GET
    @Path("/health")
    public Response getHealth(
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId
    ) {
        return Response.ok(analyticsService.getHealth(days, tournamentId)).build();
    }

    @GET
    @Path("/reliability")
    public Response getReliability(
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId,
            @QueryParam("minGames") @DefaultValue("0") int minGames
    ) {
        return Response.ok(analyticsService.getReliability(days, tournamentId, minGames)).build();
    }

    @GET
    @Path("/reliability/{modelId:.+}")
    public Response getReliabilityForModel(
            @PathParam("modelId") String modelId,
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId
    ) {
        ModelReliabilityDetailDto detail = analyticsService.getReliabilityForModel(modelId, days, tournamentId);
        if (detail == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(detail).build();
    }
}
