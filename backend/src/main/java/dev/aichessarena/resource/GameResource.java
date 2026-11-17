package dev.aichessarena.resource;

import dev.aichessarena.dto.*;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.GameEngineService;
import dev.aichessarena.service.ExportService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

@Path("/api/games")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameResource {

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    @Inject
    GameEngineService gameEngineService;

    @Inject
    ExportService exports;

    @GET
    @Path("/{id}")
    public Response getGame(@PathParam("id") UUID id) {
        Game game = gameRepository.findById(id);
        if (game == null) return Response.status(404).build();
        return Response.ok(DtoMapper.toDto(game, true)).build();
    }

    @POST
    @Path("/{id}/start")
    public Response startGame(@PathParam("id") UUID id) {
        Game game = gameRepository.findById(id);
        if (game == null) return Response.status(404).build();
        if (game.tournament != null
                && game.tournament.status == TournamentStatus.CANCELLED) {
            throw new WebApplicationException("Tournament has been cancelled", Response.Status.CONFLICT);
        }
        if (!hasConfiguredPlayer(game.whiteParticipant, game.whitePlayerName, game.whiteModelId)
                || !hasConfiguredPlayer(game.blackParticipant, game.blackPlayerName, game.blackModelId)) {
            throw new IllegalArgumentException("Both players must be set");
        }
        return Response.ok(gameEngineService.startGame(id)).build();
    }

    @POST
    @Path("/{id}/pause")
    public Response pauseGame(@PathParam("id") UUID id) {
        if (gameRepository.findById(id) == null) {
            return Response.status(404).build();
        }
        gameEngineService.pauseGame(id);
        return Response.ok().build();
    }

    @POST
    @Path("/{id}/override-move")
    public Response overrideMove(@PathParam("id") UUID id, OverrideMoveRequest req) {
        Game game = gameRepository.findById(id);
        if (game == null) {
            return Response.status(404).build();
        }
        gameEngineService.overrideMove(id, req.move());
        return Response.ok().build();
    }

    @POST
    @Path("/{id}/human-move")
    public Response submitHumanMove(@PathParam("id") UUID id, HumanMoveRequest req) {
        Game game = gameRepository.findById(id);
        if (game == null) {
            return Response.status(404).build();
        }
        gameEngineService.submitHumanMove(id, req.move(), req.from(), req.to(), req.promotion(), req.message());
        return Response.ok().build();
    }

    @GET
    @Path("/{id}/moves")
    public Response getMoves(@PathParam("id") UUID id) {
        return Response.ok(
                moveRepository.findByGameId(id).stream().map(DtoMapper::toDto).toList()
        ).build();
    }

    /** Every model attempt of the game, including rejected ones, in play order (White first) and by time. */
    @GET
    @Path("/{id}/attempts")
    public Response getAttempts(@PathParam("id") UUID id) {
        if (gameRepository.findById(id) == null) {
            return Response.status(404).build();
        }
        return Response.ok(
                moveAttemptRepository.findByGameId(id).stream().map(DtoMapper::toDto).toList()
        ).build();
    }

    @GET
    @Path("/{id}/pgn")
    @Produces("text/plain; charset=utf-8")
    @Transactional
    public Response getPgn(@PathParam("id") UUID id,
                           @QueryParam("includeChat") @DefaultValue("false") boolean includeChat) {
        return TournamentExportResource.attachment(exports.gamePgn(id, includeChat), "text/plain",
                ExportService.filename("game", id, "pgn"));
    }

    private boolean hasConfiguredPlayer(
            dev.aichessarena.entity.TournamentParticipant participant,
            String playerName,
            String modelId
    ) {
        if (participant == null) {
            return playerName != null && !playerName.isBlank() && modelId != null && !modelId.isBlank();
        }
        if (participant.controlType == dev.aichessarena.entity.TournamentParticipant.ControlType.HUMAN) {
            return participant.playerName != null && !participant.playerName.isBlank();
        }
        return participant.modelId != null && !participant.modelId.isBlank();
    }
}
