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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    public void removeParticipant(UUID tournamentId, UUID participantId) {
        TournamentParticipant p = participantRepository.findById(participantId);
        if (p != null && p.tournament.id.equals(tournamentId)) {
            participantRepository.delete(p);
        }
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

        // Pad to next power of 2 for byes
        int size = nextPowerOf2(participants.size());
        List<TournamentParticipant> seeded = new ArrayList<>(participants);
        while (seeded.size() < size) {
            seeded.add(null); // bye
        }

        List<Game> games = new ArrayList<>();
        int round = 1;
        String[] roundNames = getRoundNames(size);

        // Generate first round games
        for (int i = 0; i < seeded.size(); i += 2) {
            TournamentParticipant white = seeded.get(i);
            TournamentParticipant black = seeded.get(i + 1);

            Game game = new Game();
            game.tournament = tournament;
            game.bracketRound = roundNames[0];
            game.bracketPosition = i / 2;

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
        int gamesInRound = size / 2;
        for (int r = 1; r < roundNames.length; r++) {
            gamesInRound /= 2;
            for (int pos = 0; pos < gamesInRound; pos++) {
                Game game = new Game();
                game.tournament = tournament;
                game.bracketRound = roundNames[r];
                game.bracketPosition = pos;
                game.status = Game.GameStatus.WAITING;
                gameRepository.persist(game);
                games.add(game);
            }
        }

        tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
        tournamentRepository.persist(tournament);

        return games;
    }

    @Transactional
    public void advanceWinner(UUID gameId) {
        Game completedGame = gameRepository.findById(gameId);
        if (completedGame == null || completedGame.tournament == null) return;

        if (completedGame.result == GameResult.DRAW
                && completedGame.tournament.drawPolicy == Tournament.DrawPolicy.REPLAY_GAME) {
            resetGameForReplay(completedGame);
            return;
        }

        TournamentParticipant winner;
        if (completedGame.result == GameResult.WHITE_WINS || completedGame.result == GameResult.BLACK_FORFEIT) {
            winner = completedGame.whiteParticipant;
        } else if (completedGame.result == GameResult.BLACK_WINS || completedGame.result == GameResult.WHITE_FORFEIT) {
            winner = completedGame.blackParticipant;
        } else if (completedGame.result == GameResult.DRAW) {
            winner = resolveDrawWinner(completedGame);
        } else {
            return;
        }

        if (winner == null) return;

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
                    && nextGame.bracketPosition == nextPosition) {
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

    private String getNextRound(String currentRound) {
        return switch (currentRound) {
            case "Round of 16" -> "Quarterfinal";
            case "Quarterfinal" -> "Semifinal";
            case "Semifinal" -> "Final";
            case "Round 1" -> "Semifinal";
            default -> null;
        };
    }

    private int nextPowerOf2(int n) {
        int power = 1;
        while (power < n) power *= 2;
        return power;
    }

    private String[] getRoundNames(int bracketSize) {
        return switch (bracketSize) {
            case 2 -> new String[]{"Final"};
            case 4 -> new String[]{"Semifinal", "Final"};
            case 8 -> new String[]{"Quarterfinal", "Semifinal", "Final"};
            case 16 -> new String[]{"Round of 16", "Quarterfinal", "Semifinal", "Final"};
            default -> new String[]{"Round 1", "Semifinal", "Final"};
        };
    }

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
