package dev.aichessarena.resource;

import dev.aichessarena.dto.*;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.AnalyticsService;
import dev.aichessarena.service.TournamentService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Path("/api/tournaments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TournamentResource {

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentService tournamentService;

    @Inject
    AnalyticsService analyticsService;

    @POST
    @Transactional
    public Response create(CreateTournamentRequest req) {
        Tournament t = new Tournament();
        t.name = req.name();
        t.defaultSystemPrompt = req.defaultSystemPrompt();
        if (req.moveTimeoutSeconds() != null) t.moveTimeoutSeconds = req.moveTimeoutSeconds();
        if (req.maxRetries() != null) t.maxRetries = req.maxRetries();
        if (req.trashTalkEnabled() != null) t.trashTalkEnabled = req.trashTalkEnabled();
        if (req.drawPolicy() != null) t.drawPolicy = parseDrawPolicy(req.drawPolicy());
        tournamentService.create(t);
        return Response.status(Response.Status.CREATED).entity(DtoMapper.toDto(t)).build();
    }

    @GET
    public List<TournamentDto> list() {
        return tournamentRepository.listAll().stream().map(DtoMapper::toDto).toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        Tournament t = tournamentRepository.findById(id);
        if (t == null) return Response.status(404).build();
        return Response.ok(DtoMapper.toDto(t)).build();
    }

    @GET
    @Path("/{id}/cost-summary")
    public Response getCostSummary(@PathParam("id") UUID id) {
        Tournament t = tournamentRepository.findById(id);
        if (t == null) return Response.status(404).build();
        return Response.ok(analyticsService.getTournamentCostSummary(id)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") UUID id, CreateTournamentRequest req) {
        Tournament t = tournamentRepository.findById(id);
        if (t == null) return Response.status(404).build();
        if (req.name() != null) t.name = req.name();
        if (req.defaultSystemPrompt() != null) t.defaultSystemPrompt = req.defaultSystemPrompt();
        if (req.moveTimeoutSeconds() != null) t.moveTimeoutSeconds = req.moveTimeoutSeconds();
        if (req.maxRetries() != null) t.maxRetries = req.maxRetries();
        if (req.trashTalkEnabled() != null) t.trashTalkEnabled = req.trashTalkEnabled();
        if (req.drawPolicy() != null) t.drawPolicy = parseDrawPolicy(req.drawPolicy());
        t.updatedAt = LocalDateTime.now();
        tournamentRepository.persist(t);
        return Response.ok(DtoMapper.toDto(t)).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        boolean deleted = tournamentService.deleteTournament(id);
        if (!deleted) {
            return Response.status(404).build();
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/participants")
    public Response addParticipant(@PathParam("id") UUID id, AddParticipantRequest req) {
        TournamentParticipant p = new TournamentParticipant();
        p.playerName = req.playerName();
        p.modelId = req.modelId();
        p.customSystemPrompt = req.customSystemPrompt();
        if (req.seed() != null) p.seed = req.seed();
        TournamentParticipant saved = tournamentService.addParticipant(id, p);
        return Response.status(Response.Status.CREATED).entity(DtoMapper.toDto(saved)).build();
    }

    @DELETE
    @Path("/{id}/participants/{pid}")
    public Response removeParticipant(@PathParam("id") UUID id, @PathParam("pid") UUID pid) {
        boolean removed = tournamentService.removeParticipant(id, pid);
        if (!removed) {
            return Response.status(404).build();
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/generate-bracket")
    public Response generateBracket(@PathParam("id") UUID id) {
        List<Game> games = tournamentService.generateBracket(id);
        return Response.ok(games.stream().map(g -> DtoMapper.toDto(g, false)).toList()).build();
    }

    private Tournament.DrawPolicy parseDrawPolicy(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("drawPolicy must not be empty");
        }
        try {
            return Tournament.DrawPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid drawPolicy: " + value);
        }
    }
}
