package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class TournamentService {

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentParticipantRepository participantRepository;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    ChatMessageRepository chatMessageRepository;

    @Inject
    OpenRouterService openRouterService;

    @ConfigProperty(name = "openrouter.validate-participant-models", defaultValue = "true")
    boolean validateParticipantModels;

    @Transactional
    public Tournament create(Tournament tournament) {
        tournamentRepository.persist(tournament);
        return tournament;
    }

    @Transactional
    public TournamentParticipant addParticipant(UUID tournamentId, TournamentParticipant participant) {
        Tournament tournament = tournamentRepository.findById(tournamentId);
        if (tournament == null) throw new IllegalArgumentException("Tournament not found");

        if (participant == null) {
            throw new IllegalArgumentException("Participant payload is required");
        }

        String playerName = participant.playerName == null ? "" : participant.playerName.trim();
        if (playerName.isEmpty()) {
            throw new IllegalArgumentException("Participant playerName is required");
        }

        String modelId = participant.modelId == null ? "" : participant.modelId.trim();
        if (modelId.isEmpty()) {
            throw new IllegalArgumentException("Participant modelId is required");
        }

        if (participant.seed < 0) {
            throw new IllegalArgumentException("Participant seed must be >= 0");
        }

        if (validateParticipantModels && !openRouterService.isModelAllowed(modelId)) {
            throw new IllegalArgumentException("Model is not allowed by backend policy: " + modelId);
        }

        participant.playerName = playerName;
        participant.modelId = modelId;
        participant.tournament = tournament;
        participantRepository.persist(participant);
        return participant;
    }

    @Transactional
    public boolean removeParticipant(UUID tournamentId, UUID participantId) {
        TournamentParticipant p = participantRepository.findById(participantId);
        if (p == null || !p.tournament.id.equals(tournamentId)) {
            return false;
        }

        long referencedGames = gameRepository.count("(whiteParticipant.id = ?1) or (blackParticipant.id = ?1)",
                participantId);
        if (p.tournament.status != Tournament.TournamentStatus.CREATED || referencedGames > 0) {
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
        Tournament tournament = tournamentRepository.findById(tournamentId);
        if (tournament == null) throw new IllegalArgumentException("Tournament not found");

        List<TournamentParticipant> participants = participantRepository.findByTournamentId(tournamentId);
        participants.sort(Comparator.comparingInt(p -> p.seed));

        if (participants.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 participants");
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

            gameRepository.persist(game);
            games.add(game);
        }

        // Generate placeholder games for subsequent rounds
        int gamesInRound = firstRoundMatches.size();
        for (int r = 1; r < roundNames.length; r++) {
            gamesInRound /= 2;
            for (int pos = 0; pos < gamesInRound; pos++) {
                Game game = createBracketGame(tournament, roundNames[r], pos, bestOfForRound(tournament, roundNames[r]));
                gameRepository.persist(game);
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
        Game completedGame = gameRepository.findById(gameId);
        if (completedGame == null || completedGame.tournament == null) return;

        if (completedGame.result == GameResult.DRAW
                && completedGame.seriesBestOf <= 1
                && completedGame.tournament.drawPolicy == Tournament.DrawPolicy.REPLAY_GAME) {
            resetGameForReplay(completedGame);
            return;
        }

        List<Game> seriesGames = seriesGamesFor(completedGame);
        TournamentParticipant winner = resolveSeriesWinner(completedGame, seriesGames);
        if (winner == null) {
            ensureNextSeriesGameExists(completedGame, seriesGames);
            return;
        }

        List<Game> tournamentGames = gameRepository.findByTournamentId(completedGame.tournament.id);
        int nextPosition = completedGame.bracketPosition / 2;

        // Find the next round game
        String nextRound = getNextRound(completedGame.bracketRound);
        if (nextRound == null) {
            // This was the final, tournament is complete
            completedGame.tournament.status = Tournament.TournamentStatus.COMPLETED;
            return;
        }

        for (Game nextGame : tournamentGames) {
            if (nextRound.equals(nextGame.bracketRound) && nextGame.bracketPosition != null
                    && nextGame.bracketPosition == nextPosition
                    && nextGame.seriesGameNumber == 1) {
                if (completedGame.bracketPosition % 2 == 0) {
                    nextGame.whiteParticipant = winner;
                    nextGame.whitePlayerName = winner.playerName;
                    nextGame.whiteModelId = winner.modelId;
                } else {
                    nextGame.blackParticipant = winner;
                    nextGame.blackPlayerName = winner.playerName;
                    nextGame.blackModelId = winner.modelId;
                }
                gameRepository.persist(nextGame);
                break;
            }
        }
    }

    private TournamentParticipant resolveDrawWinner(Game game) {
        TournamentParticipant white = game.whiteParticipant;
        TournamentParticipant black = game.blackParticipant;
        Tournament.DrawPolicy drawPolicy = game.tournament != null
                ? game.tournament.drawPolicy
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
        UUID seriesId = game.seriesId != null ? game.seriesId : game.id;
        List<Game> games = gameRepository.findBySeriesId(seriesId);
        return games.isEmpty() ? List.of(game) : games;
    }

    private TournamentParticipant resolveSeriesWinner(Game completedGame, List<Game> seriesGames) {
        if (completedGame.whiteParticipant == null || completedGame.blackParticipant == null) {
            return completedGame.whiteParticipant != null ? completedGame.whiteParticipant : completedGame.blackParticipant;
        }

        if (completedGame.seriesBestOf <= 1) {
            return switch (completedGame.result) {
                case WHITE_WINS, BLACK_FORFEIT -> completedGame.whiteParticipant;
                case BLACK_WINS, WHITE_FORFEIT -> completedGame.blackParticipant;
                case DRAW -> resolveDrawWinner(completedGame);
                default -> null;
            };
        }

        Game firstGame = seriesGames.isEmpty() ? completedGame : seriesGames.getFirst();
        TournamentParticipant bracketWhite = firstGame.whiteParticipant;
        TournamentParticipant bracketBlack = firstGame.blackParticipant;
        if (bracketWhite == null || bracketBlack == null) {
            return bracketWhite != null ? bracketWhite : bracketBlack;
        }

        int whiteWins = 0;
        int blackWins = 0;
        for (Game game : seriesGames) {
            TournamentParticipant gameWinner = winnerOfGame(game);
            if (gameWinner == null) {
                continue;
            }
            if (Objects.equals(gameWinner.id, bracketWhite.id)) {
                whiteWins++;
            } else if (Objects.equals(gameWinner.id, bracketBlack.id)) {
                blackWins++;
            }
        }

        int winsRequired = winsRequired(completedGame.seriesBestOf);
        if (whiteWins >= winsRequired) {
            return bracketWhite;
        }
        if (blackWins >= winsRequired) {
            return bracketBlack;
        }
        return null;
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
        if (completedGame.seriesBestOf <= 1) {
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

        Game nextGame = new Game();
        nextGame.tournament = completedGame.tournament;
        nextGame.bracketRound = completedGame.bracketRound;
        nextGame.bracketPosition = completedGame.bracketPosition;
        nextGame.seriesId = completedGame.seriesId != null ? completedGame.seriesId : completedGame.id;
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

        gameRepository.persist(nextGame);
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

    private void resetGameForReplay(Game game) {
        moveRepository.delete("game.id", game.id);
        chatMessageRepository.delete("game.id", game.id);

        game.status = Game.GameStatus.WAITING;
        game.result = null;
        game.resultReason = null;
        game.pgn = null;
        game.currentFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        game.totalCostUsd = java.math.BigDecimal.ZERO;
        game.startedAt = null;
        game.completedAt = null;
        gameRepository.persist(game);
    }

    private int bestOfForRound(Tournament tournament, String roundName) {
        if ("Final".equals(roundName) && tournament.finalsBestOf != null) {
            return tournament.finalsBestOf;
        }
        return tournament.matchupBestOf;
    }

    private int winsRequired(int bestOf) {
        return (bestOf / 2) + 1;
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

    @Transactional
    public boolean deleteTournament(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId);
        if (tournament == null) {
            return false;
        }

        // Remove games first so participant FK references in games cannot block tournament delete.
        gameRepository.delete("tournament.id", tournamentId);
        tournamentRepository.delete(tournament);
        return true;
    }
}
