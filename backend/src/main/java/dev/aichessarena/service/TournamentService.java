package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.entity.BracketDrawMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.OpeningMode;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class TournamentService {

    private static final Logger LOG = Logger.getLogger(TournamentService.class);

    private final Random bracketRandom;

    public TournamentService() {
        this(new SecureRandom());
    }

    TournamentService(Random bracketRandom) {
        this.bracketRandom = Objects.requireNonNull(bracketRandom);
    }

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentParticipantRepository participantRepository;

    @Inject
    GameRepository gameRepository;

    @Inject
    LiveGameUpdates liveUpdates;

    @Inject
    OpeningBookService openingBook;

    @Inject
    Event<MoveEvaluationRequested> moveEvaluationEvents;

    @Inject
    OpenRouterService openRouterService;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameEngineService gameEngineService;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    @ConfigProperty(name = "openrouter.validate-participant-models", defaultValue = "true")
    boolean validateParticipantModels;

    /** Cancels once; terminal tournaments (including repeated cancellation) consistently return 409. */
    @Transactional
    public Tournament cancelTournament(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) {
            throw new WebApplicationException("Tournament not found", Response.Status.NOT_FOUND);
        }
        if (tournament.status == Tournament.TournamentStatus.COMPLETED
                || tournament.status == Tournament.TournamentStatus.CANCELLED) {
            throw new WebApplicationException("Tournament has already finished", Response.Status.CONFLICT);
        }
        tournament.status = Tournament.TournamentStatus.CANCELLED;
        tournament.autoRunStatus = Tournament.AutoRunStatus.OFF;
        tournament.autoRunPauseReason = null;
        tournament.updatedAt = LocalDateTime.now();
        for (Game game : gameRepository.findByTournamentId(tournamentId)) {
            gameLifecycleService.cancelGame(game.id);
        }
        return tournament;
    }

    @Transactional
    public Tournament create(Tournament tournament) {
        tournamentRepository.persist(tournament);
        return tournament;
    }

    /** Stores a new tournament and its participants together; participants must come from {@link #prepareParticipant}. */
    @Transactional
    public Tournament persistNewTournament(Tournament tournament, List<TournamentParticipant> participants) {
        tournamentRepository.persist(tournament);
        for (TournamentParticipant participant : participants) {
            participant.tournament = tournament;
            participantRepository.persist(participant);
            tournament.participants.add(participant);
        }
        return tournament;
    }

    /** Completes the original create intent exactly once, also when concurrent/retried creates arrive. */
    @Transactional
    public TournamentDto completeCreation(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) {
            throw new WebApplicationException("Tournament not found", Response.Status.NOT_FOUND);
        }
        if (tournament.creationDrawMode != null && tournament.status == Tournament.TournamentStatus.CREATED) {
            generateBracket(tournamentId, tournament.creationDrawMode);
            // The create request may already hold initialized empty collections. Return the committed bracket.
            tournamentRepository.getEntityManager().flush();
            tournamentRepository.getEntityManager().refresh(tournament);
        }
        // Capture the response while relationships are attached to this transaction's session.
        return DtoMapper.toDto(tournament);
    }

    @Transactional
    public TournamentParticipant addParticipant(UUID tournamentId, TournamentParticipant participant) {
        // Validate first: model validation may call OpenRouter, which must not happen while holding the lock.
        TournamentParticipant prepared = prepareParticipant(participant);

        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) throw new IllegalArgumentException("Tournament not found");
        if (tournament.status != Tournament.TournamentStatus.CREATED) {
            throw new WebApplicationException(
                    "Participants can only be added before the bracket is generated", Response.Status.CONFLICT);
        }
        if (prepared.controlType == TournamentParticipant.ControlType.HUMAN) {
            long humanCount = participantRepository.findByTournamentId(tournamentId).stream()
                    .filter(existing -> existing.controlType == TournamentParticipant.ControlType.HUMAN)
                    .count();
            if (humanCount >= 1) {
                throw new IllegalArgumentException("Only one human participant is supported per tournament");
            }
        }

        prepared.tournament = tournament;
        participantRepository.persist(prepared);
        return prepared;
    }

    /**
     * Validates and normalizes a participant without storing it. For AI participants this may call
     * OpenRouter to check the model, so callers should not hold database locks while calling it.
     */
    public TournamentParticipant prepareParticipant(TournamentParticipant participant) {
        if (participant == null) {
            throw new IllegalArgumentException("Participant payload is required");
        }

        String playerName = participant.playerName == null ? "" : participant.playerName.trim();
        if (playerName.isEmpty()) {
            throw new IllegalArgumentException("Participant playerName is required");
        }

        TournamentParticipant.ControlType controlType =
                participant.controlType != null ? participant.controlType : TournamentParticipant.ControlType.AI;

        String modelId = participant.modelId == null ? "" : participant.modelId.trim();
        if (controlType == TournamentParticipant.ControlType.AI && modelId.isEmpty()) {
            throw new IllegalArgumentException("Participant modelId is required for AI participants");
        }
        if (controlType == TournamentParticipant.ControlType.HUMAN && !modelId.isEmpty()) {
            throw new IllegalArgumentException("Human participants must not specify a modelId");
        }

        if (participant.seed < 0) {
            throw new IllegalArgumentException("Participant seed must be >= 0");
        }

        if (controlType == TournamentParticipant.ControlType.AI
                && validateParticipantModels
                && !openRouterService.isModelAllowed(modelId)) {
            throw new IllegalArgumentException("Model is not allowed by backend policy: " + modelId);
        }

        participant.playerName = playerName;
        participant.controlType = controlType;
        participant.modelId = controlType == TournamentParticipant.ControlType.AI ? modelId : null;
        participant.customInstructions = normalizeInstructions(participant.customInstructions);
        if (controlType == TournamentParticipant.ControlType.HUMAN && participant.reasoningEffort != null) {
            throw new IllegalArgumentException("Human participants have no reasoning effort");
        }
        return participant;
    }

    /** The participant of the tournament; 404 when either does not exist or they do not belong together. */
    public TournamentParticipant findParticipant(UUID tournamentId, UUID participantId) {
        TournamentParticipant participant = participantRepository.findById(participantId);
        if (participant == null || participant.tournament == null || !participant.tournament.id.equals(tournamentId)) {
            throw new WebApplicationException("Participant not found", Response.Status.NOT_FOUND);
        }
        return participant;
    }

    /**
     * Sets or clears (null: use the tournament's) a participant's reasoning-effort override until the tournament
     * ends, so a paused game can be fixed in place; running games pick it up from the participant's next
     * turn.
     */
    @Transactional
    public TournamentParticipant updateParticipantReasoningEffort(UUID tournamentId, UUID participantId,
                                                                  ReasoningEffort effort) {
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) {
            throw new WebApplicationException("Tournament not found", Response.Status.NOT_FOUND);
        }
        requireEditableSettings(tournament);
        TournamentParticipant participant = findParticipant(tournamentId, participantId);
        // This request may have cached the participant before obtaining the parent lock (e.g. before a draw).
        participantRepository.getEntityManager().refresh(participant);
        if (participant.controlType == TournamentParticipant.ControlType.HUMAN && effort != null) {
            throw new IllegalArgumentException("Human participants have no reasoning effort");
        }
        participant.reasoningEffort = effort;
        tournament.updatedAt = LocalDateTime.now();
        return participant;
    }

    /** Terminal tournaments retain their final settings as well as their results. Call with the row locked. */
    public static void requireEditableSettings(Tournament tournament) {
        if (tournament.status == Tournament.TournamentStatus.CANCELLED
                || tournament.status == Tournament.TournamentStatus.COMPLETED) {
            throw new WebApplicationException("Finished tournament settings cannot be changed", Response.Status.CONFLICT);
        }
    }

    @Transactional
    public boolean removeParticipant(UUID tournamentId, UUID participantId) {
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) return false;
        TournamentParticipant p = participantRepository.findById(participantId);
        if (p == null || !p.tournament.id.equals(tournamentId)) {
            return false;
        }

        long referencedGames = gameRepository.count("(whiteParticipant.id = ?1) or (blackParticipant.id = ?1)",
                participantId);
        if (tournament.status != Tournament.TournamentStatus.CREATED || referencedGames > 0) {
            throw new WebApplicationException(
                    "Cannot remove participants after bracket generation has started",
                    Response.Status.CONFLICT
            );
        }

        participantRepository.delete(p);
        return true;
    }

    @Transactional
    public List<Game> generateBracket(UUID tournamentId) {
        return generateBracket(tournamentId, BracketDrawMode.ORDER);
    }

    @Transactional
    public List<Game> generateBracket(UUID tournamentId, BracketDrawMode drawMode) {
        Objects.requireNonNull(drawMode, "drawMode");
        // The row lock makes concurrent or repeated generation requests wait, then see the bracket.
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) throw new IllegalArgumentException("Tournament not found");
        if (tournament.status != Tournament.TournamentStatus.CREATED
                || !gameRepository.findByTournamentId(tournamentId).isEmpty()) {
            throw new WebApplicationException("The bracket has already been generated", Response.Status.CONFLICT);
        }

        List<TournamentParticipant> participants = new ArrayList<>(participantRepository.findByTournamentId(tournamentId));
        participants.sort(Comparator.comparingInt(p -> p.seed));

        if (participants.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 participants");
        }

        if (drawMode == BracketDrawMode.RANDOM) {
            Collections.shuffle(participants, bracketRandom);
            // These are managed entities: the transaction stores drawn seeds together with the bracket.
            for (int seed = 0; seed < participants.size(); seed++) {
                participants.get(seed).seed = seed;
            }
        }

        int size = nextPowerOf2(participants.size());
        List<FirstRoundMatch> firstRoundMatches = buildFirstRoundMatches(participants, size);

        List<Game> games = new ArrayList<>();
        String[] roundNames = getRoundNames(size);

        // Generate first round games
        for (int i = 0; i < firstRoundMatches.size(); i++) {
            TournamentParticipant white = firstRoundMatches.get(i).white();
            TournamentParticipant black = firstRoundMatches.get(i).black();

            Game game = createBracketGame(tournament, roundNames[0], i, bestOfForRound(tournament, roundNames[0]));

            if (white != null) {
                game.whiteParticipant = white;
                game.whitePlayerName = white.playerName;
                game.whiteModelId = white.modelId;
            }
            if (black != null) {
                game.blackParticipant = black;
                game.blackPlayerName = black.playerName;
                game.blackModelId = black.modelId;
            }

            // Handle byes
            if (white == null || black == null) {
                game.status = Game.GameStatus.COMPLETED;
                if (white == null && black != null) {
                    game.result = Game.GameResult.BLACK_WINS;
                    game.whitePlayerName = "BYE";
                } else if (black == null && white != null) {
                    game.result = Game.GameResult.WHITE_WINS;
                    game.blackPlayerName = "BYE";
                }
            }

            if (white != null && black != null) {
                prepareOpening(game);
            }
            persistGameWithOpening(game);
            games.add(game);
        }

        // Generate placeholder games for subsequent rounds
        int gamesInRound = firstRoundMatches.size();
        for (int r = 1; r < roundNames.length; r++) {
            gamesInRound /= 2;
            for (int pos = 0; pos < gamesInRound; pos++) {
                Game game = createBracketGame(tournament, roundNames[r], pos, bestOfForRound(tournament, roundNames[r]));
                prepareOpening(game);
                persistGameWithOpening(game);
                games.add(game);
            }
        }

        tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
        tournamentRepository.persist(tournament);

        for (Game game : games) {
            if (game.status == Game.GameStatus.COMPLETED && game.result != null) {
                advanceWinner(game.id);
            }
        }

        return games;
    }

    @Transactional
    public void advanceWinner(UUID gameId) {
        Game completedGame = gameRepository.findByIdForUpdate(gameId);
        if (completedGame == null || completedGame.tournament == null) return;
        if (completedGame.tournament.status == Tournament.TournamentStatus.CANCELLED
                || (completedGame.status != Game.GameStatus.COMPLETED
                && completedGame.status != Game.GameStatus.FORFEIT)) return;

        List<Game> seriesGames = seriesGamesFor(completedGame);
        TournamentParticipant winner = resolveSeriesWinner(completedGame, seriesGames);
        if (winner == null) {
            ensureNextSeriesGameExists(completedGame, seriesGames);
            return;
        }
        if (completedGame.advancedParticipant == null
                || !Objects.equals(completedGame.advancedParticipant.id, winner.id)) {
            completedGame.advancedParticipant = winner;
            reviseGameDetails(completedGame);
        }

        List<Game> tournamentGames = gameRepository.findByTournamentId(completedGame.tournament.id);
        int nextPosition = completedGame.bracketPosition / 2;

        // Find the next round game
        String nextRound = getNextRound(completedGame.bracketRound);
        if (nextRound == null) {
            // This was the final, tournament is complete
            completedGame.tournament.status = Tournament.TournamentStatus.COMPLETED;
            if (completedGame.tournament.autoRunStatus != Tournament.AutoRunStatus.OFF) {
                completedGame.tournament.autoRunStatus = Tournament.AutoRunStatus.FINISHED;
                completedGame.tournament.autoRunPauseReason = null;
                gameLifecycleService.clearResumeRequests(completedGame.tournament.id);
            }
            return;
        }

        for (Game nextGame : tournamentGames) {
            if (nextRound.equals(nextGame.bracketRound) && nextGame.bracketPosition != null
                    && nextGame.bracketPosition == nextPosition
                    && nextGame.seriesGameNumber == 1) {
                // Serialize with concurrent advancements into the same game and with its start.
                gameRepository.refreshForUpdate(nextGame);
                if (nextGame.status != Game.GameStatus.WAITING) {
                    // Never rewrite the pairing of a game that has already started or finished.
                    TournamentParticipant seated = completedGame.bracketPosition % 2 == 0
                            ? nextGame.whiteParticipant : nextGame.blackParticipant;
                    if (seated == null || !Objects.equals(seated.id, winner.id)) {
                        LOG.warnf("Not seating %s in game %s: it is already %s",
                                winner.playerName, nextGame.id, nextGame.status);
                    }
                    break;
                }
                boolean whiteSeat = completedGame.bracketPosition % 2 == 0;
                TournamentParticipant seated = whiteSeat ? nextGame.whiteParticipant : nextGame.blackParticipant;
                String seatedName = whiteSeat ? nextGame.whitePlayerName : nextGame.blackPlayerName;
                String seatedModel = whiteSeat ? nextGame.whiteModelId : nextGame.blackModelId;
                if (seated != null && Objects.equals(seated.id, winner.id) && Objects.equals(seatedName, winner.playerName)
                        && Objects.equals(seatedModel, winner.modelId)) {
                    break;
                }
                if (whiteSeat) {
                    nextGame.whiteParticipant = winner;
                    nextGame.whitePlayerName = winner.playerName;
                    nextGame.whiteModelId = winner.modelId;
                } else {
                    nextGame.blackParticipant = winner;
                    nextGame.blackPlayerName = winner.playerName;
                    nextGame.blackModelId = winner.modelId;
                }
                gameRepository.persist(nextGame);
                reviseGameDetails(nextGame);
                break;
            }
        }
    }

    private void reviseGameDetails(Game game) {
        if (liveUpdates != null) {
            liveUpdates.changed(game, liveUpdates.change("gameDetailsChanged", game.id));
        }
    }

    /**
     * Who advances from a level match. {@code white}/{@code black} are the single game's colors, or for a
     * series the colors of its first game. Returns null for REPLAY_GAME: another game must be played.
     */
    private TournamentParticipant applyDrawPolicy(Tournament tournament, TournamentParticipant white,
                                                  TournamentParticipant black) {
        Tournament.DrawPolicy drawPolicy = tournament != null
                ? tournament.drawPolicy
                : Tournament.DrawPolicy.WHITE_ADVANCES;

        return switch (drawPolicy) {
            case WHITE_ADVANCES -> white;
            case BLACK_ADVANCES -> black;
            case HIGHER_SEED_ADVANCES -> chooseHigherSeed(white, black);
            case RANDOM_ADVANCES -> chooseRandom(white, black);
            case REPLAY_GAME -> null;
        };
    }

    private Game createBracketGame(Tournament tournament, String roundName, int position, int seriesBestOf) {
        Game game = new Game();
        game.tournament = tournament;
        game.bracketRound = roundName;
        game.bracketPosition = position;
        game.seriesId = UUID.randomUUID();
        game.seriesGameNumber = 1;
        game.seriesBestOf = seriesBestOf;
        game.status = Game.GameStatus.WAITING;
        return game;
    }

    private List<Game> seriesGamesFor(Game game) {
        if (game.seriesId == null) {
            // Rows without a series id are a series of their own. Store that id so tiebreak games created
            // for this game and later lookups by series id find it.
            game.seriesId = game.id;
            gameRepository.persist(game);
            reviseGameDetails(game);
        }
        List<Game> games = gameRepository.findBySeriesId(game.seriesId);
        return games.isEmpty() ? List.of(game) : games;
    }

    private TournamentParticipant resolveSeriesWinner(Game completedGame, List<Game> seriesGames) {
        if (completedGame.whiteParticipant == null || completedGame.blackParticipant == null) {
            return completedGame.whiteParticipant != null ? completedGame.whiteParticipant : completedGame.blackParticipant;
        }

        Game firstGame = seriesGames.isEmpty() ? completedGame : seriesGames.getFirst();
        TournamentParticipant bracketWhite = firstGame.whiteParticipant;
        TournamentParticipant bracketBlack = firstGame.blackParticipant;
        if (bracketWhite == null || bracketBlack == null) {
            return bracketWhite != null ? bracketWhite : bracketBlack;
        }

        // Best of N (a single game is best of 1): at most N regular games. A draw is a game played without a winner. The series is
        // decided once the lead can no longer be caught in the remaining regular games; if it is level
        // after all N, the draw policy decides (REPLAY_GAME: sudden-death games until one is decisive).
        // Only regular games (number <= N) count toward the series score. Games beyond N are sudden-death
        // tiebreaks under REPLAY_GAME; extra games created by older versions under other policies are ignored.
        int bestOf = completedGame.seriesBestOf;
        int whiteWins = 0;
        int blackWins = 0;
        int regularGamesFinished = 0;
        TournamentParticipant firstTiebreakWinner = null;
        for (Game game : seriesGames) {
            if (game.result == null) {
                continue;
            }
            TournamentParticipant gameWinner = winnerOfGame(game);
            TournamentParticipant side = gameWinner == null ? null
                    : Objects.equals(gameWinner.id, bracketWhite.id) ? bracketWhite
                    : Objects.equals(gameWinner.id, bracketBlack.id) ? bracketBlack
                    : null;
            if (game.seriesGameNumber <= bestOf) {
                regularGamesFinished++;
                if (side == bracketWhite) {
                    whiteWins++;
                } else if (side == bracketBlack) {
                    blackWins++;
                }
            } else if (side != null && firstTiebreakWinner == null) {
                firstTiebreakWinner = side;
            }
        }

        int remainingRegularGames = Math.max(0, bestOf - regularGamesFinished);
        if (Math.abs(whiteWins - blackWins) > remainingRegularGames) {
            return whiteWins > blackWins ? bracketWhite : bracketBlack;
        }
        if (remainingRegularGames > 0) {
            return null;
        }
        boolean suddenDeath = completedGame.tournament != null
                && completedGame.tournament.drawPolicy == Tournament.DrawPolicy.REPLAY_GAME;
        if (suddenDeath) {
            return firstTiebreakWinner;
        }
        return applyDrawPolicy(completedGame.tournament, bracketWhite, bracketBlack);
    }

    private TournamentParticipant winnerOfGame(Game game) {
        if (game == null || game.result == null) {
            return null;
        }

        return switch (game.result) {
            case WHITE_WINS, BLACK_FORFEIT -> game.whiteParticipant;
            case BLACK_WINS, WHITE_FORFEIT -> game.blackParticipant;
            case DRAW -> null;
        };
    }

    private void ensureNextSeriesGameExists(Game completedGame, List<Game> seriesGames) {
        boolean suddenDeathAllowed = completedGame.tournament != null
                && completedGame.tournament.drawPolicy == Tournament.DrawPolicy.REPLAY_GAME;
        if (completedGame.seriesBestOf <= 1 && !suddenDeathAllowed) {
            return;
        }
        if (completedGame.whiteParticipant == null || completedGame.blackParticipant == null) {
            return;
        }
        boolean hasPendingGame = seriesGames.stream().anyMatch(game ->
                game.status == Game.GameStatus.WAITING
                        || game.status == Game.GameStatus.IN_PROGRESS
                        || game.status == Game.GameStatus.PAUSED
        );
        if (hasPendingGame) {
            return;
        }

        Game firstGame = seriesGames.isEmpty() ? completedGame : seriesGames.getFirst();
        int nextGameNumber = seriesGames.stream().mapToInt(game -> game.seriesGameNumber).max().orElse(1) + 1;
        if (nextGameNumber > completedGame.seriesBestOf && !suddenDeathAllowed) {
            LOG.errorf("Not creating game %d of a best-of-%d series %s", nextGameNumber,
                    completedGame.seriesBestOf, completedGame.seriesId);
            return;
        }

        Game nextGame = new Game();
        nextGame.tournament = completedGame.tournament;
        nextGame.bracketRound = completedGame.bracketRound;
        nextGame.bracketPosition = completedGame.bracketPosition;
        nextGame.seriesId = completedGame.seriesId;
        nextGame.seriesGameNumber = nextGameNumber;
        nextGame.seriesBestOf = completedGame.seriesBestOf;
        nextGame.status = Game.GameStatus.WAITING;

        boolean sameOrientationAsFirst = nextGameNumber % 2 == 1;
        TournamentParticipant white = sameOrientationAsFirst ? firstGame.whiteParticipant : firstGame.blackParticipant;
        TournamentParticipant black = sameOrientationAsFirst ? firstGame.blackParticipant : firstGame.whiteParticipant;

        if (white != null) {
            nextGame.whiteParticipant = white;
            nextGame.whitePlayerName = white.playerName;
            nextGame.whiteModelId = white.modelId;
        }
        if (black != null) {
            nextGame.blackParticipant = black;
            nextGame.blackPlayerName = black.playerName;
            nextGame.blackModelId = black.modelId;
        }

        if (nextGameNumber <= nextGame.seriesBestOf && nextGameNumber % 2 == 0) {
            Game partner = seriesGames.stream().filter(g -> g.seriesGameNumber == nextGameNumber - 1)
                    .findFirst().orElseThrow();
            if (partner.openingId != null) {
                openingBook.reuse(nextGame, partner);
            }
        } else {
            prepareOpening(nextGame);
        }
        persistGameWithOpening(nextGame);
    }

    private void prepareOpening(Game game) {
        if (game.tournament.openingMode != OpeningMode.STANDARD) {
            openingBook.prepare(game);
        }
    }

    private void persistGameWithOpening(Game game) {
        gameRepository.persist(game);
        if (!game.moves.isEmpty()) {
            // Only the final book position needs an evaluation: baseline for the first model move.
            var last = game.moves.getLast();
            moveEvaluationEvents.fire(new MoveEvaluationRequested(last.id, game.id,
                    last.moveNumber, last.color, last.fen));
        }
    }

    private TournamentParticipant chooseHigherSeed(TournamentParticipant white, TournamentParticipant black) {
        if (white == null) return black;
        if (black == null) return white;
        if (white.seed < black.seed) return white;
        if (black.seed < white.seed) return black;
        return white;
    }

    private TournamentParticipant chooseRandom(TournamentParticipant white, TournamentParticipant black) {
        if (white == null) return black;
        if (black == null) return white;
        return ThreadLocalRandom.current().nextBoolean() ? white : black;
    }

    private int bestOfForRound(Tournament tournament, String roundName) {
        if ("Final".equals(roundName) && tournament.finalsBestOf != null) {
            return tournament.finalsBestOf;
        }
        return tournament.matchupBestOf;
    }

    static String getNextRound(String currentRound) {
        int currentRoundSize = roundSizeForName(currentRound);
        if (currentRoundSize <= 2) {
            return null;
        }
        return roundNameForSize(currentRoundSize / 2);
    }

    private int nextPowerOf2(int n) {
        int power = 1;
        while (power < n) power *= 2;
        return power;
    }

    static String[] getRoundNames(int bracketSize) {
        List<String> roundNames = new ArrayList<>();
        for (int roundSize = bracketSize; roundSize >= 2; roundSize /= 2) {
            roundNames.add(roundNameForSize(roundSize));
        }
        return roundNames.toArray(String[]::new);
    }

    static List<FirstRoundMatch> buildFirstRoundMatches(List<TournamentParticipant> participants, int bracketSize) {
        int byes = bracketSize - participants.size();
        List<FirstRoundMatch> matches = new ArrayList<>(bracketSize / 2);
        int index = 0;

        for (int i = 0; i < byes; i++) {
            matches.add(new FirstRoundMatch(participants.get(index++), null));
        }

        while (index < participants.size()) {
            TournamentParticipant white = participants.get(index++);
            TournamentParticipant black = index < participants.size() ? participants.get(index++) : null;
            matches.add(new FirstRoundMatch(white, black));
        }

        return matches;
    }

    static String roundNameForSize(int roundSize) {
        return switch (roundSize) {
            case 2 -> "Final";
            case 4 -> "Semifinal";
            case 8 -> "Quarterfinal";
            default -> "Round of " + roundSize;
        };
    }

    static int roundSizeForName(String roundName) {
        if (roundName == null || roundName.isBlank()) {
            return -1;
        }
        return switch (roundName) {
            case "Final" -> 2;
            case "Semifinal" -> 4;
            case "Quarterfinal" -> 8;
            default -> {
                if (roundName.startsWith("Round of ")) {
                    yield Integer.parseInt(roundName.substring("Round of ".length()));
                }
                yield -1;
            }
        };
    }

    record FirstRoundMatch(TournamentParticipant white, TournamentParticipant black) {}

    private String normalizeInstructions(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public boolean deleteTournament(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);
        if (tournament == null) {
            return false;
        }

        // Capture IDs while the rows still exist. Rollback must leave conversations and loop owners intact.
        List<UUID> gameIds = gameRepository.findIdsByTournamentId(tournamentId);
        // Remove games first so participant FK references in games cannot block tournament delete.
        gameRepository.delete("tournament.id", tournamentId);
        tournamentRepository.delete(tournament);
        AfterCommit.run(transactionRegistry, () -> gameIds.forEach(gameEngineService::requestStop));
        if (liveUpdates != null && !gameIds.isEmpty()) liveUpdates.gamesRemoved();
        return true;
    }
}
