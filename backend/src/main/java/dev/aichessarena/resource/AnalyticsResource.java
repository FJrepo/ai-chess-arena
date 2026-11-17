package dev.aichessarena.resource;

import dev.aichessarena.dto.ModelReliabilityDetailDto;
import dev.aichessarena.service.AnalyticsService;
import dev.aichessarena.service.LeaderboardService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.UUID;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
public class AnalyticsResource {

    @Inject
    AnalyticsService analyticsService;

    @Inject
    LeaderboardService leaderboardService;

    @GET
    @Path("/leaderboard")
    public Response getLeaderboard(@Context UriInfo uriInfo) {
        // Read the query map to distinguish an omitted parameter from an explicitly empty one.
        var query = uriInfo.getQueryParameters();
        String includeForfeits = query.containsKey("includeForfeits") ? query.getFirst("includeForfeits") : "true";
        if (!"true".equalsIgnoreCase(includeForfeits) && !"false".equalsIgnoreCase(includeForfeits)) {
            throw new IllegalArgumentException("includeForfeits must be true or false");
        }
        return Response.ok(leaderboardService.getLeaderboard(Boolean.parseBoolean(includeForfeits),
                parseLegalMoveHints(uriInfo))).build();
    }

    @GET
    @Path("/health")
    public Response getHealth(
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId,
            @Context UriInfo uriInfo
    ) {
        return Response.ok(analyticsService.getHealth(days, tournamentId, parseLegalMoveHints(uriInfo))).build();
    }

    @GET
    @Path("/comparison")
    public Response getComparison(
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId,
            @QueryParam("minGames") @DefaultValue("0") int minGames,
            @Context UriInfo uriInfo
    ) {
        return Response.ok(analyticsService.getComparison(days, tournamentId, minGames,
                parseLegalMoveHints(uriInfo))).build();
    }

    @GET
    @Path("/reliability")
    public Response getReliability(
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId,
            @QueryParam("minGames") @DefaultValue("0") int minGames,
            @Context UriInfo uriInfo
    ) {
        return Response.ok(analyticsService.getReliability(days, tournamentId, minGames,
                parseLegalMoveHints(uriInfo))).build();
    }

    @GET
    @Path("/reliability/{modelId:.+}")
    public Response getReliabilityForModel(
            @PathParam("modelId") String modelId,
            @QueryParam("days") @DefaultValue("30") int days,
            @QueryParam("tournamentId") UUID tournamentId,
            @Context UriInfo uriInfo
    ) {
        ModelReliabilityDetailDto detail = analyticsService.getReliabilityForModel(modelId, days, tournamentId,
                parseLegalMoveHints(uriInfo));
        if (detail == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(detail).build();
    }

    private static Boolean parseLegalMoveHints(UriInfo uriInfo) {
        var values = uriInfo.getQueryParameters().get("legalMoveHints");
        if (values == null) return null;
        if (values.size() != 1) {
            throw new IllegalArgumentException("legalMoveHints must be true or false");
        }
        return switch (values.getFirst()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("legalMoveHints must be true or false");
        };
    }
}
