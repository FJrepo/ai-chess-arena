package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StockfishOpponentServiceTest {
    static final String HANDSHAKE = """
            id name Stockfish 19
            option name UCI_LimitStrength type check default false
            option name UCI_Elo type spin default 1320 min 1320 max 3190
            uciok
            readyok
            """;

    @Test
    void sendsPinnedSettingsAndFullUciHistoryToADedicatedEngine() throws Exception {
        var process = new StockfishServiceTest.FakeProcess(HANDSHAKE + "bestmove g1f3 ponder b8c6\n", false);
        var opponent = service(process);
        ChessService chess = new ChessService();
        var board = chess.createBoard();
        assertTrue(chess.validateAndApply(board, "e4").valid());
        assertTrue(chess.validateAndApply(board, "e5").valid());

        assertEquals("g1f3", opponent.chooseMove(board, 1750, () -> true));
        assertEquals(List.of(
                "uci", "setoption name Threads value 1", "setoption name Hash value 16",
                "setoption name UCI_LimitStrength value true", "setoption name UCI_Elo value 1750",
                "ucinewgame", "isready", "position startpos moves e2e4 e7e5", "go nodes 50000"
        ), process.commands.toString(StandardCharsets.UTF_8).lines().toList());
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void refusesToLaunchABenchmarkWhenTheExecutableChanges(@TempDir Path directory) throws IOException {
        Path binary = Files.writeString(directory.resolve("stockfish"), "abc");
        var opponent = new StockfishOpponentService() {
            @Override
            Path engineBinary() {
                return binary;
            }
        };
        String pinned = opponent.benchmarkEngineSha256();
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", pinned);
        Files.writeString(binary, "changed");
        var mismatch = assertThrows(IOException.class, () -> opponent.startBenchmarkProcess(pinned));
        assertEquals("Stockfish build does not match this benchmark", mismatch.getMessage());
    }

    @Test
    void unsupportedEloDoesNotSendASearch() throws InterruptedException {
        var process = new StockfishServiceTest.FakeProcess(HANDSHAKE.replace("min 1320", "min 1500"), false);
        var opponent = service(process);

        assertThrows(IOException.class, () -> opponent.chooseMove(new ChessService().createBoard(), 1320, () -> true));
        assertFalse(process.commands.toString(StandardCharsets.UTF_8).contains("go nodes"));
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void stoppedGameDoesNotLaunchAnEngine() throws Exception {
        var opponent = service(null);
        assertNull(opponent.chooseMove(new ChessService().createBoard(), 1500, () -> false));
    }

    @Test
    void engineMayNotReturnAnEmptyMoveForAPlayablePosition() throws InterruptedException {
        var process = new StockfishServiceTest.FakeProcess(HANDSHAKE + "bestmove (none)\n", false);
        var opponent = service(process);
        assertThrows(IOException.class, () -> opponent.chooseMove(new ChessService().createBoard(), 1500, () -> true));
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void timesOutAndTerminatesTheDedicatedProcessWhenTheSearchStalls() throws InterruptedException {
        var process = new StockfishServiceTest.StalledProcess(HANDSHAKE);
        var opponent = service(process);
        opponent.opponentTimeout = Duration.ofMillis(50);

        assertThrows(TimeoutException.class, () -> opponent.chooseMove(new ChessService().createBoard(), 1500, () -> true));
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS));
        assertTrue(process.streamsClosed.await(2, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pausingCancelsAnActiveHandshakeOrSearch(boolean stallInHandshake) throws Exception {
        var process = new StockfishServiceTest.StalledProcess(stallInHandshake ? "" : HANDSHAKE);
        var opponent = service(process);
        var running = new AtomicBoolean(true);
        var result = new FutureTask<>(() -> opponent.chooseMove(new ChessService().createBoard(), 1500, running::get));
        Thread.startVirtualThread(result);
        try {
            assertTrue(process.readStarted.await(2, TimeUnit.SECONDS));
            running.set(false);
            assertNull(result.get(3, TimeUnit.SECONDS));
            assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        } finally {
            running.set(false);
            opponent.close();
        }
    }

    private static StockfishOpponentService service(Process process) {
        return new StockfishOpponentService() {
            @Override
            Process startProcess() {
                if (process == null) throw new AssertionError("No process should be started");
                return process;
            }
        };
    }
}
