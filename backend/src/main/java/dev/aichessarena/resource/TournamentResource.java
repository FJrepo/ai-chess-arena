package dev.aichessarena.resource;

import dev.aichessarena.dto.*;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.AnalyticsService;
import dev.aichessarena.service.TournamentCreationService;
import dev.aichessarena.service.TournamentService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Path("/api/tournaments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TournamentResource {
    private static final Set<Integer> ALLOWED_BEST_OF_VALUES = Set.of(1, 3, 5, 7);

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentService tournamentService;

    @Inject
    TournamentCreationService tournamentCreationService;

    @Inject
    AnalyticsService analyticsService;

    @POST
    public Response create(CreateTournamentRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        Tournament t = new Tournament();
        t.name = req.name();
        t.sharedCustomInstructions = normalizeInstructions(req.sharedCustomInstructions());
        // null keeps the default of 60 seconds; 0 means no time limit per move.
        if (req.moveTimeoutSeconds() != null) t.moveTimeoutSeconds = parseMoveTimeout(req.moveTimeoutSeconds());
        if (req.maxRetries() != null) t.maxRetries = req.maxRetries();
        if (req.matchupBestOf() != null) t.matchupBestOf = parseBestOf(req.matchupBestOf(), "matchupBestOf");
        if (req.finalsBestOf() != null) t.finalsBestOf = parseBestOf(req.finalsBestOf(), "finalsBestOf");
        ChatMode chatMode = resolveChatMode(req.chatMode(), req.trashTalkEnabled());
        if (chatMode != null) t.chatMode = chatMode;
        if (req.maxOutputTokens() != null) t.maxOutputTokens = parseMaxOutputTokens(req.maxOutputTokens());
        if (req.drawPolicy() != null) t.drawPolicy = parseDrawPolicy(req.drawPolicy());

        List<TournamentParticipant> participants = new ArrayList<>();
        if (req.participants() != null) {
            for (AddParticipantRequest participant : req.participants()) {
                participants.add(toParticipant(participant));
            }
        }
        TournamentCreationService.Result result = tournamentCreationService.create(t, participants, req.clientRequestId());
        Response.Status status = result.created() ? Response.Status.CREATED : Response.Status.OK;
        return Response.status(status).entity(DtoMapper.toDto(result.tournament())).build();
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
        if (req.sharedCustomInstructions() != null) {
            t.sharedCustomInstructions = normalizeInstructions(req.sharedCustomInstructions());
        }
        // null leaves the move time limit unchanged; 0 removes it.
        if (req.moveTimeoutSeconds() != null) t.moveTimeoutSeconds = parseMoveTimeout(req.moveTimeoutSeconds());
        if (req.maxRetries() != null) t.maxRetries = req.maxRetries();
        if (req.matchupBestOf() != null) t.matchupBestOf = parseBestOf(req.matchupBestOf(), "matchupBestOf");
        if (req.finalsBestOf() != null) t.finalsBestOf = parseBestOf(req.finalsBestOf(), "finalsBestOf");
        ChatMode chatMode = resolveChatMode(req.chatMode(), req.trashTalkEnabled());
        if (chatMode != null) t.chatMode = chatMode;
        if (req.maxOutputTokens() != null) t.maxOutputTokens = parseMaxOutputTokens(req.maxOutputTokens());
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
        TournamentParticipant saved = tournamentService.addParticipant(id, toParticipant(req));
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

    private TournamentParticipant toParticipant(AddParticipantRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Participant payload is required");
        }
        TournamentParticipant p = new TournamentParticipant();
        p.playerName = req.playerName();
        p.modelId = req.modelId();
        p.controlType = parseControlType(req.controlType());
        p.customInstructions = normalizeInstructions(req.customInstructions());
        if (req.seed() != null) p.seed = req.seed();
        return p;
    }

    /** {@code 0} means no time limit per move (stored as null); other values must be 10-3600 seconds. */
    static Integer parseMoveTimeout(int value) {
        if (value == 0) {
            return null;
        }
        if (value < Tournament.MIN_MOVE_TIMEOUT_SECONDS || value > Tournament.MAX_MOVE_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("moveTimeoutSeconds must be 0 (no limit) or between %d and %d"
                    .formatted(Tournament.MIN_MOVE_TIMEOUT_SECONDS, Tournament.MAX_MOVE_TIMEOUT_SECONDS));
        }
        return value;
    }

    private int parseMaxOutputTokens(int value) {
        if (value < Tournament.MIN_MAX_OUTPUT_TOKENS || value > Tournament.MAX_MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens must be between %d and %d"
                    .formatted(Tournament.MIN_MAX_OUTPUT_TOKENS, Tournament.MAX_MAX_OUTPUT_TOKENS));
        }
        return value;
    }

    /**
     * The chat mode a request asks for, or null to keep the default (create) or the current mode (update).
     * {@code chatMode} wins over the older {@code trashTalkEnabled} (false → OFF, true → OPTIONAL).
     */
    static ChatMode resolveChatMode(String chatMode, Boolean trashTalkEnabled) {
        if (chatMode != null) {
            if (chatMode.isBlank()) {
                throw new IllegalArgumentException("chatMode must be one of OFF, OPTIONAL or MANDATORY");
            }
            try {
                return ChatMode.valueOf(chatMode.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid chatMode: " + chatMode + " (must be one of OFF, OPTIONAL or MANDATORY)");
            }
        }
        return trashTalkEnabled != null ? ChatMode.fromTrashTalkEnabled(trashTalkEnabled) : null;
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

    private int parseBestOf(Integer value, String fieldName) {
        if (value == null || !ALLOWED_BEST_OF_VALUES.contains(value)) {
            throw new IllegalArgumentException(fieldName + " must be one of 1, 3, 5, or 7");
        }
        return value;
    }

    private String normalizeInstructions(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TournamentParticipant.ControlType parseControlType(String value) {
        if (value == null || value.isBlank()) {
            return TournamentParticipant.ControlType.AI;
        }
        try {
            return TournamentParticipant.ControlType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid controlType: " + value);
        }
    }
}
