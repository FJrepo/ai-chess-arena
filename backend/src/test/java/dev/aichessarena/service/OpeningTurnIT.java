package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.OpeningMode;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real start/resume, prompts and resignation handling against persisted book and played plies. */
@QuarkusTest
class OpeningTurnIT {

    private static final List<String> AFTER_EIGHT_BOOK_PLIES = List.of(
            "O-O", "Be7", "Re1", "b5", "Bb3", "d6", "c3", "O-O", "h3", "Nb8",
            "d4", "Nbd7", "c4", "c6", "Nc3", "Qc7", "Be3", "Bb7", "Rc1");

    @Inject GameEngineService engine;
    @Inject GameRepository games;
    @Inject MoveRepository moves;
    @Inject MoveAttemptRepository attempts;
    @Inject TournamentRepository tournaments;
    @Inject OpeningBookService book;
    @Inject ChessService chess;

    @ParameterizedTest
    @CsvSource({
            "ruy-lopez, WAITING, false", "ruy-lopez, PAUSED, false",
            "italian, WAITING, false", "italian, PAUSED, false",
            "ruy-lopez, WAITING, true", "ruy-lopez, PAUSED, true",
            "italian, WAITING, true", "italian, PAUSED, true"
    })
    void firstModelPromptNamesTheFinalBookPlyOnStartAndResume(
            String openingId, GameStatus status, boolean humanOpponent) throws InterruptedException {
        UUID id = createGame(openingId, status, humanOpponent, List.of());
        CapturingModel model = new CapturingModel();
        startAndAwait(id, model, GameStatus.PAUSED);

        String prompt = model.calls.getFirst().getLast().content();
        List<String> line = book.require(openingId).moves();
        String opponent = line.size() % 2 == 0 ? "Black" : "White";
        assertTrue(prompt.contains("Your opponent (" + opponent + ") played " + line.getLast() + "."), prompt);
        assertFalse(prompt.contains("null"), prompt);
        assertFalse(prompt.contains("You have the first move"), prompt);
        assertTrue(prompt.contains("PGN: " + QuarkusTransaction.requiringNew().call(() -> games.findById(id).pgn)));
        assertEquals(line.size() % 2 == 0 ? "model-white" : "model-black", model.models.getFirst());
    }

    @ParameterizedTest
    @CsvSource({"WHITE, true", "WHITE, false", "BLACK, true", "BLACK, false"})
    void resumeRestoresOnlyTheMessageAttachedToTheFinalPlayedPly(
            String lastColor, boolean withLastMessage) throws InterruptedException {
        List<String> played = List.of("c3", "Nf6", "d3", "d6");
        UUID id = createGame("italian", GameStatus.PAUSED, false,
                played.subList(0, "WHITE".equals(lastColor) ? 3 : 4));
        String lastSan = QuarkusTransaction.requiringNew().call(() -> {
            Game game = games.findById(id);
            for (int i = 6; i < game.moves.size(); i++) {
                Move move = game.moves.get(i);
                boolean last = i == game.moves.size() - 1;
                if (last && !withLastMessage) {
                    continue;
                }
                ChatMessage chat = new ChatMessage();
                chat.game = game;
                chat.moveNumber = move.moveNumber;
                chat.senderColor = move.color;
                chat.message = last ? "Final ply message" : "Stale earlier message";
                game.chatMessages.add(chat);
            }
            return game.moves.getLast().san;
        });
        CapturingModel model = new CapturingModel();
        startAndAwait(id, model, GameStatus.PAUSED);

        String prompt = model.calls.getFirst().getLast().content();
        assertTrue(prompt.contains("played " + lastSan), prompt);
        assertEquals(withLastMessage, prompt.contains("Final ply message"), prompt);
        assertFalse(prompt.contains("Stale earlier message"), prompt);
        assertFalse(prompt.contains("null"), prompt);
    }

    @ParameterizedTest
    @CsvSource({"WHITE, 5", "BLACK, 5", "WHITE, 8", "BLACK, 8", "WHITE, 9", "BLACK, 9"})
    void eightBookPliesDoNotCountTowardTheTenthOwnMove(String side, int completedOwnMoves)
            throws InterruptedException {
        int playedPlies = 2 * completedOwnMoves + ("BLACK".equals(side) ? 1 : 0);
        UUID id = createGame("ruy-lopez-morphy", GameStatus.PAUSED, false,
                AFTER_EIGHT_BOOK_PLIES.subList(0, playedPlies));
        String legalSan = QuarkusTransaction.requiringNew().call(() -> {
            Game game = games.findById(id);
            assertEquals(completedOwnMoves, moves.countNonBookByGameIdAndColor(id, side));
            Board board = chess.boardFromFen(game.currentFen);
            assertEquals(side, chess.getSideToMove(board));
            assertEquals(completedOwnMoves + 5, chess.getMoveNumber(board));
            return chess.getLegalMovesAsSan(board).getFirst();
        });
        CapturingModel model = new CapturingModel("{\"resign\":true}", "{\"move\":\"" + legalSan + "\"}");
        boolean eligible = completedOwnMoves >= 9;
        startAndAwait(id, model, eligible ? GameStatus.COMPLETED : GameStatus.PAUSED);

        String prompt = model.calls.getFirst().getLast().content();
        assertEquals(eligible, prompt.contains(PromptService.MAY_RESIGN_LINE), prompt);
        assertTrue(model.calls.getFirst().getFirst().content().contains("book moves do not count"));
        List<Outcome> outcomes = QuarkusTransaction.requiringNew().call(() -> attempts.findByGameId(id).stream()
                .map(attempt -> attempt.outcome).toList());
        if (eligible) {
            assertEquals(List.of(Outcome.RESIGNED), outcomes);
        } else {
            assertEquals(List.of(Outcome.RESIGN_NOT_ALLOWED, Outcome.ACCEPTED, Outcome.PROVIDER_FATAL), outcomes);
            assertTrue(model.calls.get(1).getLast().content().contains(
                    "before your own move 10 (book moves do not count)"));
            assertEquals(completedOwnMoves + 1,
                    QuarkusTransaction.requiringNew().call(() -> moves.countNonBookByGameIdAndColor(id, side)));
        }
    }

    private UUID createGame(String openingId, GameStatus status, boolean humanOpponent, List<String> played) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Opening turn regression";
            tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
            tournament.openingMode = OpeningMode.FIXED;
            tournament.openingId = openingId;
            tournament.resignationAllowed = true;
            tournament.maxRetries = 3;
            for (String color : List.of("White", "Black")) {
                TournamentParticipant participant = new TournamentParticipant();
                participant.tournament = tournament;
                participant.playerName = color;
                participant.modelId = "model-" + color.toLowerCase(java.util.Locale.ROOT);
                tournament.participants.add(participant);
            }
            tournaments.persist(tournament);
            Game game = new Game();
            game.tournament = tournament;
            game.whiteParticipant = tournament.participants.getFirst();
            game.blackParticipant = tournament.participants.getLast();
            game.whitePlayerName = "White";
            game.blackPlayerName = "Black";
            game.whiteModelId = "model-white";
            game.blackModelId = "model-black";
            game.bracketRound = "Final";
            game.bracketPosition = 0;
            game.status = status;
            book.prepare(game);
            Board board = chess.boardFromFen(game.currentFen);
            if (humanOpponent) {
                TournamentParticipant opponent = "WHITE".equals(chess.getSideToMove(board))
                        ? game.blackParticipant : game.whiteParticipant;
                opponent.controlType = TournamentParticipant.ControlType.HUMAN;
                opponent.modelId = null;
            }
            for (String san : played) {
                Move move = new Move();
                move.game = game;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                move.san = san;
                assertTrue(chess.validateAndApply(board, san).valid(), san);
                move.fen = board.getFen();
                move.modelId = "WHITE".equals(move.color) ? game.whiteModelId : game.blackModelId;
                game.moves.add(move);
                game.pgn += " " + ("WHITE".equals(move.color) ? move.moveNumber + ". " : "") + san;
            }
            game.currentFen = board.getFen();
            games.persist(game);
            return game.id;
        });
    }

    private void startAndAwait(UUID id, CapturingModel model, GameStatus expected) throws InterruptedException {
        QuarkusMock.installMockForType(model, OpenRouterService.class);
        engine.startGame(id);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            // A terminal commit flags the loop before its final attempt transaction finishes. Wait for
            // the owner to release its claim so assertions see all work from that turn.
            GameEngineService.GameLoop claim = engine.tryClaimLoop(id);
            if (claim != null) {
                engine.releaseLoop(id, claim);
                assertEquals(expected, QuarkusTransaction.requiringNew().call(() -> games.findById(id).status));
                assertFalse(model.calls.isEmpty(), "the model received a prompt");
                return;
            }
            Thread.sleep(20);
        }
        engine.requestStop(id);
        throw new AssertionError("Game loop did not finish");
    }

    /** Scripted answers, then a provider failure to pause without changing the position further. */
    private static final class CapturingModel extends OpenRouterService {
        private final List<String> answers;
        private final List<List<ChatMsg>> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> models = Collections.synchronizedList(new ArrayList<>());

        CapturingModel(String... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public ReasoningEffort resolveReasoningEffort(String modelId, ReasoningEffort requested) {
            return null;
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline deadline,
                                int maxOutputTokens, ReasoningEffort effort) {
            calls.add(List.copyOf(messages));
            models.add(modelId);
            int index = calls.size() - 1;
            return index < answers.size()
                    ? new LlmResponse(answers.get(index), 0, 0, null, 0, null, false, null)
                    : LlmResponse.providerFailure(ProviderOutcome.FATAL, 400, "Script finished", 0, null);
        }
    }
}
