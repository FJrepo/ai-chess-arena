package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.BenchmarkDefinition;
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
    static final String TEST_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static final String NETWORK_NAME = "nn-test.nnue";
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
    void refusesToLaunchABenchmarkWhenTheExecutableChanges(@TempDir Path directory) throws IOException, TimeoutException {
        var fixture = engineWithExport(directory);
        var benchmark = benchmark(fixture.opponent());
        Path binary = directory.resolve("stockfish");
        Files.delete(directory.resolve("launched"));
        Files.writeString(binary, "changed");
        var mismatch = assertThrows(IOException.class, () ->
                fixture.opponent().chooseMove(new ChessService().createBoard(), 1500, benchmark, () -> true));
        assertEquals("Stockfish build does not match this benchmark", mismatch.getMessage());
        assertFalse(Files.exists(directory.resolve("launched")));
    }

    @Test
    void launchesTheVerifiedPrivateCopyInsteadOfTheInstalledExecutable(@TempDir Path directory) throws Exception {
        var fixture = engineWithExport(directory);
        var benchmark = benchmark(fixture.opponent());
        assertEquals("g1f3", fixture.opponent().chooseMove(new ChessService().createBoard(), 1500, benchmark, () -> true));
        Path launched = Path.of(Files.readString(directory.resolve("launched")).strip());
        assertFalse(launched.equals(directory.resolve("stockfish")));
        assertFalse(Files.exists(launched), "the private executable should be cleaned up after the move");
    }

    @Test
    void refusesSearchWhenTheLoadedWeightsChangeWithoutChangingTheExecutable(@TempDir Path directory) throws Exception {
        var fixture = engineWithExport(directory);
        var benchmark = benchmark(fixture.opponent());
        Files.writeString(fixture.network(), "replacement weights");
        assertThrows(IOException.class,
                () -> fixture.opponent().chooseMove(new ChessService().createBoard(), 1500, benchmark, () -> true));
        assertFalse(Files.exists(directory.resolve("searched")));
    }

    private static BenchmarkDefinition benchmark(StockfishOpponentService opponent) throws IOException, TimeoutException {
        var strengths = List.of(1500, 2000);
        var fingerprint = opponent.benchmarkFingerprint(strengths);
        return new BenchmarkDefinition("fixture", List.of(), List.of(), strengths, List.of("WHITE", "BLACK"),
                fingerprint.sha256(), fingerprint.networks(), 50000, 1, 16, "v2-silent", null);
    }

    private record EngineFixture(StockfishOpponentService opponent, Path network) {}

    private static EngineFixture engineWithExport(Path directory) throws IOException {
        Path network = Files.writeString(directory.resolve("external.nnue"), "original weights");
        Path binary = Files.writeString(directory.resolve("stockfish"), """
                #!/bin/sh
                printf '%%s\n' "$0" > '%s/launched'
                while IFS= read -r command; do
                  case "$command" in
                    uci)
                      printf '%%s\n' 'option name UCI_LimitStrength type check default false' \
                        'option name UCI_Elo type spin default 1320 min 1320 max 3190' \
                        'option name EvalFile type string default nn-test.nnue' uciok ;;
                    isready) echo readyok ;;
                    export_net) cp '%s' nn-test.nnue && echo 'Network saved successfully to nn-test.nnue' ;;
                    'go nodes 50000') touch '%s/searched'; echo 'bestmove g1f3' ;;
                  esac
                done
                """.formatted(directory, network, directory));
        assertTrue(binary.toFile().setExecutable(true, true));
        return new EngineFixture(new StockfishOpponentService() {
            @Override Path engineBinary() { return binary; }
        }, network);
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
