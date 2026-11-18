package dev.aichessarena.resource;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aichessarena.dto.*;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.LiveGamesRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.GameConfigurationHistory;
import dev.aichessarena.service.GameEngineService;
import dev.aichessarena.service.GameLifecycleService;
import dev.aichessarena.service.PracticeGameService;
import dev.aichessarena.service.StockfishOpponentService;
import dev.aichessarena.service.ExportService;
import dev.aichessarena.websocket.GameStream;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Path("/api/games")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameResource {

    /** Quarkus' own setting (default true), so this parser splits the query exactly like the framework does. */
    @ConfigProperty(name = "quarkus.http.use-semicolon-as-query-param-delimiter", defaultValue = "true")
    boolean semicolonIsQueryDelimiter = true;

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

    @Inject
    LiveGamesRepository liveGames;

    @GET
    @Path("/live")
    @Transactional
    public Response listLiveGames() {
        return Response.ok(liveGames.snapshot()).header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/practice")
    public java.util.List<GameDto> listPractice() {
        return practice.list();
    }

    @POST
    @Path("/practice/stockfish")
    public Response createStockfishPractice(JsonNode body) {
        requireFields(body, java.util.Set.of("side", "elo"));
        String side = practiceSide(body);
        JsonNode elo = body.path("elo");
        if (!elo.isIntegralNumber() || !elo.canConvertToInt() || elo.intValue() < StockfishOpponentService.MIN_ELO
                || elo.intValue() > StockfishOpponentService.MAX_ELO) {
            throw new IllegalArgumentException("Stockfish target Elo must be an integer from 1320 to 3190");
        }
        return Response.status(201).entity(practice.createStockfish(side, elo.intValue())).build();
    }

    @POST
    @Path("/{id}/practice")
    public Response createPractice(@PathParam("id") UUID id, JsonNode body) {
        requireFields(body, java.util.Set.of("ply", "side", "clientRequestId", "moveId"));
        JsonNode ply = body.path("ply");
        if (!ply.isIntegralNumber() || !ply.canConvertToInt() || ply.intValue() < 0) {
            throw new IllegalArgumentException("Ply must be a non-negative integer");
        }
        return Response.status(201).entity(practice.fork(id, ply.intValue(), practiceSide(body),
                optionalUuid(body, "clientRequestId"), optionalUuid(body, "moveId"))).build();
    }

    private static UUID optionalUuid(JsonNode body, String field) {
        if (!body.hasNonNull(field)) return null;
        String value = body.get(field).textValue();
        if (value == null) throw new IllegalArgumentException(field + " must be a UUID");
        return UUID.fromString(value);
    }

    private static void requireFields(JsonNode body, java.util.Set<String> fields) {
        if (body == null || !body.isObject() || body.propertyStream()
                .anyMatch(field -> !fields.contains(field.getKey()))) {
            throw new IllegalArgumentException("Unsupported practice settings");
        }
    }

    private static String practiceSide(JsonNode body) {
        String side = body.path("side").textValue();
        if (!"WHITE".equals(side) && !"BLACK".equals(side)) {
            throw new IllegalArgumentException("Side must be WHITE or BLACK");
        }
        return side;
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

    /**
     * Starts or resumes a game. In a tournament with an active run, a paused game queues for the next free slot when
     * all slots are occupied or reserved (default since 2.0); {@code queueIfFull=false} returns 409 instead.
     */
    @POST
    @Path("/{id}/start")
    public Response startGame(@PathParam("id") UUID id, @Context UriInfo uriInfo) {
        String query = uriInfo.getRequestUri().getRawQuery();
        return startGame(id, parseQueueIfFull(queryValuesIgnoringCase(query, "queueIfFull", semicolonIsQueryDelimiter)),
                parseAcknowledgement(queryValuesIgnoringCase(query, "acknowledgeUncertainRequest",
                        semicolonIsQueryDelimiter)));
    }

    Response startGame(UUID id, boolean queueIfFull) {
        return startGame(id, queueIfFull, false);
    }

    /**
     * {@code acknowledgeUncertainRequest=true} states that spend for the game's unresolved model request was checked;
     * it is the only way to resume such a game (409 otherwise, and 409 when there is nothing to acknowledge).
     */
    Response startGame(UUID id, boolean queueIfFull, boolean acknowledgeUncertainRequest) {
        Game game = gameRepository.findById(id);
        if (game == null) return Response.status(404).build();
        if (game.tournament != null
                && game.tournament.status == TournamentStatus.CANCELLED) {
            throw new WebApplicationException("Tournament has been cancelled", Response.Status.CONFLICT);
        }
        if (!hasConfiguredPlayer(game, true) || !hasConfiguredPlayer(game, false)) {
            throw new IllegalArgumentException("Both players must be set");
        }
        if (acknowledgeUncertainRequest) {
            return Response.ok(gameEngineService.startGame(id, queueIfFull, true)).build();
        }
        return Response.ok(queueIfFull ? gameEngineService.startGame(id, true) : gameEngineService.startGame(id))
                .build();
    }

    static boolean parseAcknowledgement(List<String> values) {
        if (values == null) return false;
        if (values.size() != 1) {
            throw new IllegalArgumentException("acknowledgeUncertainRequest must be given at most once");
        }
        String value = values.getFirst();
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("acknowledgeUncertainRequest must be true or false");
        }
        return "true".equals(value);
    }

    /**
     * Every value of a query parameter whose name matches regardless of case (as with the former {@code @QueryParam}),
     * or null when none is present. Parsed from the raw query: the case-insensitive parameter map can merge names
     * that differ only in special characters (such as a dotless i) and drop values. Pairs are separated by
     * {@code &} and, like Quarkus' own query parsing by default, also by {@code ;}.
     */
    static List<String> queryValuesIgnoringCase(String query, String name, boolean semicolonIsDelimiter) {
        if (query == null || query.isEmpty()) return null;
        List<String> values = null;
        for (String pair : query.split(semicolonIsDelimiter ? "[&;]" : "&", -1)) {
            int separator = pair.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator), StandardCharsets.UTF_8);
            if (key.equalsIgnoreCase(name)) {
                if (values == null) values = new ArrayList<>();
                values.add(separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    /**
     * Only an absent parameter means the default (true). Read explicitly because {@code @DefaultValue} would also
     * replace an empty value, which must fail closed instead of queuing a paid resume.
     */
    static boolean parseQueueIfFull(List<String> values) {
        if (values == null) return true;
        if (values.size() != 1) {
            throw new IllegalArgumentException("queueIfFull must be given at most once");
        }
        String value = values.getFirst();
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("queueIfFull must be true or false");
        }
        return "true".equals(value);
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
                DtoMapper.toMoveDtos(moveRepository.findByGameId(id))
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
        Game.PlayerType type = game.playerType(white ? "WHITE" : "BLACK");
        if (participant != null) {
            return type == Game.PlayerType.HUMAN ? present(participant.playerName) : present(participant.modelId);
        }
        return present(white ? game.whitePlayerName : game.blackPlayerName)
                && (type != Game.PlayerType.AI || present(white ? game.whiteModelId : game.blackModelId));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
