package dev.aichessarena.resource;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aichessarena.dto.*;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.GameConfigurationHistory;
import dev.aichessarena.service.GameEngineService;
import dev.aichessarena.service.GameLifecycleService;
import dev.aichessarena.service.PracticeGameService;
import dev.aichessarena.service.ExportService;
import dev.aichessarena.websocket.GameStream;
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
    GameLifecycleService lifecycle;

    @Inject
    ExportService exports;

    @Inject
    GameStream gameStream;

    @Inject
    GameConfigurationHistory configurations;

    @Inject
    PracticeGameService practice;

    @GET
    @Path("/practice")
    public java.util.List<GameDto> listPractice() {
        return practice.list();
    }

    @POST
    @Path("/{id}/practice")
    public Response createPractice(@PathParam("id") UUID id, JsonNode body) {
        if (body == null || !body.isObject() || body.propertyStream()
                .anyMatch(field -> !java.util.Set.of("ply", "side", "clientRequestId", "moveId").contains(field.getKey()))) {
            throw new IllegalArgumentException("Provide ply, side and an optional clientRequestId");
        }
        JsonNode ply = body.path("ply");
        if (!ply.isIntegralNumber() || !ply.canConvertToInt() || ply.intValue() < 0) {
            throw new IllegalArgumentException("Ply must be a non-negative integer");
        }
        String side = body.path("side").textValue();
        if (!"WHITE".equals(side) && !"BLACK".equals(side)) {
            throw new IllegalArgumentException("Side must be WHITE or BLACK");
        }
        return Response.status(201).entity(practice.fork(id, ply.intValue(), side,
                optionalUuid(body, "clientRequestId"), optionalUuid(body, "moveId"))).build();
    }

    private static UUID optionalUuid(JsonNode body, String field) {
        if (!body.hasNonNull(field)) return null;
        String value = body.get(field).textValue();
        if (value == null) throw new IllegalArgumentException(field + " must be a UUID");
        return UUID.fromString(value);
    }

    @GET
    @Path("/{id}")
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Response getGame(@PathParam("id") UUID id) {
        // Match the game revision, board, history and costs at one PostgreSQL MVCC snapshot.
        // This is the first statement of this read transaction.
        gameRepository.beginSnapshotRead();
        Game game = gameRepository.findById(id);
        if (game == null) return Response.status(404).build();
        return Response.ok(DtoMapper.toDto(game, true, gameRepository.queuedResumeCapacity(game),
                moveAttemptRepository.findByGameId(id).stream().map(DtoMapper::toDto).toList(),
                gameStream.id(), configurations.read(id))).build();
    }

    @POST
    @Path("/{id}/start")
    public Response startGame(@PathParam("id") UUID id,
                              @QueryParam("queueIfFull") @DefaultValue("false") String queueIfFull) {
        if (!"true".equals(queueIfFull) && !"false".equals(queueIfFull)) {
            throw new IllegalArgumentException("queueIfFull must be true or false");
        }
        Game game = gameRepository.findById(id);
        if (game == null) return Response.status(404).build();
        if (game.tournament != null
                && game.tournament.status == TournamentStatus.CANCELLED) {
            throw new WebApplicationException("Tournament has been cancelled", Response.Status.CONFLICT);
        }
        if (!hasConfiguredPlayer(game, true) || !hasConfiguredPlayer(game, false)) {
            throw new IllegalArgumentException("Both players must be set");
        }
        return Response.ok("true".equals(queueIfFull)
                ? gameEngineService.startGame(id, true) : gameEngineService.startGame(id)).build();
    }

    @DELETE
    @Path("/{id}/resume-request")
    public GameDto cancelResumeRequest(@PathParam("id") UUID id, @QueryParam("statusRevision") String revision) {
        long statusRevision;
        try {
            statusRevision = Long.parseLong(revision);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("statusRevision must be a non-negative integer");
        }
        if (statusRevision < 0) throw new IllegalArgumentException("statusRevision must be a non-negative integer");
        return lifecycle.cancelResumeRequest(id, statusRevision);
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

    private boolean hasConfiguredPlayer(Game game, boolean white) {
        TournamentParticipant participant = white ? game.whiteParticipant : game.blackParticipant;
        boolean human = game.playerType(white ? "WHITE" : "BLACK") == Game.PlayerType.HUMAN;
        if (participant != null) {
            return human ? present(participant.playerName) : present(participant.modelId);
        }
        return present(white ? game.whitePlayerName : game.blackPlayerName)
                && (human || present(white ? game.whiteModelId : game.blackModelId));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
