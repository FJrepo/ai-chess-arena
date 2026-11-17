package dev.aichessarena.resource;

import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.ExportService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/api/tournaments/{id}")
@Transactional
public class TournamentExportResource {
    @Inject
    TournamentRepository tournaments;

    @Inject
    ExportService exports;

    @GET
    @Path("/export.pgn")
    @Produces("application/x-chess-pgn; charset=utf-8")
    public Response pgn(@PathParam("id") UUID id,
                        @QueryParam("includeChat") @DefaultValue("false") boolean includeChat) {
        return attachment(exports.tournamentPgn(tournament(id), includeChat), "application/x-chess-pgn",
                ExportService.filename("tournament", id, "pgn"));
    }

    @GET
    @Path("/export.csv")
    @Produces("text/csv; charset=utf-8")
    public Response csv(@PathParam("id") UUID id) {
        return attachment(exports.tournamentCsv(tournament(id)), "text/csv",
                ExportService.filename("tournament", id, "csv"));
    }

    @GET
    @Path("/export.json")
    @Produces("application/json; charset=utf-8")
    public Response json(@PathParam("id") UUID id) {
        return attachment(exports.tournamentJson(tournament(id)), "application/json",
                ExportService.filename("tournament", id, "json"));
    }

    private Tournament tournament(UUID id) {
        Tournament tournament = tournaments.findById(id);
        if (tournament == null) throw new NotFoundException();
        return tournament;
    }

    static Response attachment(Object body, String type, String filename) {
        return Response.ok(body, type + "; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .build();
    }
}
