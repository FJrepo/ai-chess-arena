package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.OpeningMode;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpeningBookServiceTest {
    private final OpeningBookService book = new OpeningBookService(new Random(42));
    private final ChessService chess = new ChessService();

    @TestFactory
    Stream<DynamicTest> everyAuthoredLineIsLegalAndNonterminal() {
        return book.list().stream().map(line -> DynamicTest.dynamicTest(line.id(), () -> {
            assertTrue(line.moves().size() >= 4 && line.moves().size() <= 8);
            Board board = chess.createBoard();
            for (String san : line.moves()) {
                assertTrue(chess.validateAndApply(board, san).valid(), san);
                assertFalse(chess.isGameOver(board));
            }
        }));
    }

    @Test
    void positionsAreBalancedWhenStockfishIsInstalled() throws Exception {
        StockfishService stockfish = new StockfishService();
        try {
            stockfish.init();
            assumeTrue(stockfish.isAvailable(), "Stockfish not installed: legality validated for every line instead");
            for (var line : book.list()) {
                Game game = fixed(line.id());
                var result = stockfish.evaluate(game.currentFen, 12).get(10, TimeUnit.SECONDS);
                assertNull(result.mate(), line.id());
                assertNotNull(result.cp(), line.id());
                assertTrue(Math.abs(result.cp()) <= 150, line.id() + ": " + result.cp());
            }
        } finally {
            stockfish.cleanup();
        }
    }

    @Test
    void seededSelectionIsReproducibleAndUsesTheEntireBook() {
        Random expected = new Random(42);
        var selected = new java.util.HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            Game game = new Game();
            game.tournament = new Tournament();
            game.tournament.openingMode = OpeningMode.RANDOM_BOOK;
            book.prepare(game);
            assertEquals(book.list().get(expected.nextInt(book.list().size())).id(), game.openingId);
            selected.add(game.openingId);
        }
        assertEquals(book.list().size(), selected.size());
    }

    @Test
    void standardRemainsUntouchedAndNoModelUsageIsAssignedToBookMoves() {
        Game standard = new Game();
        standard.tournament = new Tournament();
        book.prepare(standard);
        assertNull(standard.openingId);
        assertNull(standard.pgn);
        assertTrue(standard.moves.isEmpty());
        Game fixed = fixed("ruy-lopez");
        assertEquals(5, fixed.moves.size());
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5", fixed.pgn);
        assertEquals(java.math.BigDecimal.ZERO, fixed.totalCostUsd);
        assertEquals("C60", DtoMapper.toDto(fixed, true).openingEco());
        for (Move move : fixed.moves) {
            assertTrue(DtoMapper.toDto(move).isBook());
            assertNull(move.modelId);
            assertNull(move.chatMode);
            assertNull(move.promptVersion);
            assertNull(move.promptTokens);
            assertNull(move.completionTokens);
            assertNull(move.costUsd);
            assertNull(move.responseTimeMs);
            assertEquals(0, move.retryCount);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ruy-lopez", "italian"})
    void actualLoopContinuesFromBookWithFullPromptHistory(String id) {
        Game game = fixed(id);
        game.id = UUID.randomUUID();
        game.status = Game.GameStatus.IN_PROGRESS;
        game.whitePlayerName = "White";
        game.blackPlayerName = "Black";
        game.whiteModelId = "model-white";
        game.blackModelId = "model-black";
        GameRepository games = new GameRepository() {
            @Override public Game findById(UUID ignored) { return game; }
            @Override public void detachAll() {}
        };
        GameConversationService conversation = new GameConversationService();
        conversation.chessService = chess;
        conversation.promptService = new PromptService();
        conversation.gameRepository = games;
        conversation.moveRepository = new MoveRepository() {
            @Override public List<Move> findByGameId(UUID ignored) { return game.moves; }
        };
        GameEngineService engine = new GameEngineService();
        engine.chessService = chess;
        engine.gameRepository = games;
        engine.gameConversationService = conversation;
        List<GameTurnService.TurnRequest> requests = new ArrayList<>();
        engine.gameTurnService = new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                requests.add(request);
                return TurnResult.paused();
            }
        };
        engine.runGameLoop(game.id, engine.tryClaimLoop(game.id), null, null);
        var turn = requests.getFirst();
        int plies = book.require(id).moves().size();
        assertEquals(plies / 2 + 1, turn.moveNumber());
        assertEquals(plies % 2 == 0 ? "WHITE" : "BLACK", turn.sideToMove());
        assertEquals(game.currentFen, turn.fen());
        assertEquals(game.pgn, turn.pgn());
        assertFalse(turn.isFirstMove());
        String prompt = new PromptService().buildTurnPrompt(turn.pgn(), turn.fen(), chess.toAsciiBoard(turn.board()),
                turn.sideToMove(), turn.moveNumber(), turn.legalMoves(), "Opponent", null, null, false);
        assertTrue(prompt.contains(game.pgn));
        assertEquals(game.currentFen, conversation.initializeConversation(game.id).board().getFen(), "resume preserves book");
    }

    @Test
    void bookScoresProvideQualityBaselineButNeverReceiveJudgementOrMissedMate() {
        Game game = fixed("ruy-lopez");
        Move lastBook = game.moves.getLast();
        lastBook.evaluationMate = -2;
        Move played = new Move();
        played.moveNumber = 3;
        played.color = "BLACK";
        played.san = "a6";
        Board board = chess.boardFromFen(game.currentFen);
        played.fen = chess.validateAndApply(board, played.san).fen();
        played.evaluationCp = 0;
        game.moves.add(played);
        var qualities = MoveQualityClassifier.analyze(game.moves);
        for (int i = 0; i < game.moves.size() - 1; i++) {
            assertNull(qualities.get(i));
        }
        assertTrue(qualities.getLast().missedMate());
        assertEquals(1000, qualities.getLast().centipawnLoss());
        assertEquals(1, MoveQualityClassifier.summarize(game.moves, qualities).black().analyzedMoves());
        assertEquals(0, MoveQualityClassifier.summarize(game.moves, qualities).white().analyzedMoves());
    }

    @Test
    void bookPliesCannotFillTheAdjudicationWindow() {
        List<Move> moves = new ArrayList<>(fixed("italian").moves);
        moves.forEach(move -> move.evaluationCp = 1200);
        for (int i = 0; i < 9; i++) {
            Move move = new Move();
            move.moveNumber = 4 + i / 2;
            move.color = i % 2 == 0 ? "WHITE" : "BLACK";
            move.evaluationCp = 1200;
            moves.add(move);
        }
        assertNull(WinAdjudication.winner(moves));
        Move tenth = new Move();
        tenth.moveNumber = 8;
        tenth.color = "BLACK";
        tenth.evaluationCp = 1200;
        moves.add(tenth);
        assertEquals("WHITE", WinAdjudication.winner(moves));
    }

    @Test
    void reusedSnapshotIncludesOnlyBookMovesEvenAfterModelsHavePlayed() {
        Game partner = fixed("ruy-lopez");
        Move modelMove = new Move();
        modelMove.moveNumber = 3;
        modelMove.color = "BLACK";
        modelMove.san = "a6";
        partner.moves.add(modelMove);
        partner.openingName = "Stored book title";
        Game next = new Game();
        book.reuse(next, partner);
        assertEquals(5, next.moves.size());
        assertTrue(next.moves.stream().allMatch(move -> move.isBook && move.game == next));
        assertEquals("Stored book title", next.openingName);
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5", next.pgn);
    }

    @Test
    void validatesEffectiveSettingsAndClearsFixedIdWhenLeavingFixed() {
        Tournament tournament = new Tournament();
        assertThrows(IllegalArgumentException.class, () -> book.configure(tournament, "FIXED", null));
        assertThrows(IllegalArgumentException.class, () -> book.configure(tournament, "random", null));
        assertThrows(IllegalArgumentException.class, () -> book.configure(tournament, "STANDARD", "ruy-lopez"));
        book.configure(tournament, " fixed ", "ruy-lopez");
        book.configure(tournament, null, null);
        assertEquals(OpeningMode.FIXED, tournament.openingMode);
        assertEquals("ruy-lopez", tournament.openingId);
        assertThrows(IllegalArgumentException.class, () -> book.configure(tournament, null, "unknown"));
        assertEquals("ruy-lopez", tournament.openingId);
        book.configure(tournament, "RANDOM_BOOK", null);
        assertNull(tournament.openingId);
    }

    private Game fixed(String id) {
        Game game = new Game();
        game.tournament = new Tournament();
        game.tournament.openingMode = OpeningMode.FIXED;
        game.tournament.openingId = id;
        book.prepare(game);
        return game;
    }
}
