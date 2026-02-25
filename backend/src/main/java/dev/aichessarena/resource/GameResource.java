package dev.aichessarena.resource;

import dev.aichessarena.dto.*;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.GameEngineService;
import jakarta.inject.Inject;
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
    GameEngineService gameEngineService;

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
        if (game.whiteModelId == null || game.blackModelId == null) {
            return Response.status(400).entity("Both players must be set").build();
        }
        gameEngineService.startGame(id);
        return Response.ok(DtoMapper.toDto(game, false)).build();
    }

    @POST
    @Path("/{id}/pause")
    public Response pauseGame(@PathParam("id") UUID id) {
        gameEngineService.pauseGame(id);
        return Response.ok().build();
    }

    @POST
    @Path("/{id}/override-move")
    public Response overrideMove(@PathParam("id") UUID id, OverrideMoveRequest req) {
        gameEngineService.overrideMove(id, req.move());
        return Response.ok().build();
    }

    @GET
    @Path("/{id}/moves")
    public Response getMoves(@PathParam("id") UUID id) {
        return Response.ok(
                moveRepository.findByGameId(id).stream().map(DtoMapper::toDto).toList()
        ).build();
    }

    @GET
    @Path("/{id}/pgn")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getPgn(@PathParam("id") UUID id) {
        Game game = gameRepository.findById(id);
        if (game == null) return Response.status(404).build();

        String pgn = "[Event \"AI Chess Arena\"]\n"
                + "[White \"" + game.whitePlayerName + "\"]\n"
                + "[Black \"" + game.blackPlayerName + "\"]\n"
                + (game.result != null ? "[Result \"" + pgnResult(game) + "\"]\n" : "")
                + "\n"
                + (game.pgn != null ? game.pgn : "")
                + (game.result != null ? " " + pgnResult(game) : "");
        return Response.ok(pgn).build();
    }

    private String pgnResult(Game game) {
        return switch (game.result) {
            case WHITE_WINS, BLACK_FORFEIT -> "1-0";
            case BLACK_WINS, WHITE_FORFEIT -> "0-1";
            case DRAW -> "1/2-1/2";
        };
    }
}
