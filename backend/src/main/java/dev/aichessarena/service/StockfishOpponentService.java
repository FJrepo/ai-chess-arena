package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.BenchmarkDefinition;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Runs bounded opponent searches in processes owned independently of the evaluator. */
@ApplicationScoped
public class StockfishOpponentService {

    public static final int MIN_ELO = 1320;
    public static final int MAX_ELO = 3190;
    static final int SEARCH_NODES = 50_000;
    static final int THREADS = 1;
    static final int HASH_MIB = 16;

    private static final Pattern ELO_OPTION = Pattern.compile("option name UCI_Elo type spin default \\d+ min (\\d+) max (\\d+)");
    private static final Pattern BEST_MOVE = Pattern.compile("bestmove ([a-h][1-8][a-h][1-8][qrbn]?)(?:\\s.*)?");
    private static final long STOP_POLL_MS = 250L;
    private static final int EXIT_WAIT_SECONDS = 2;

    private final Semaphore searchSlot = new Semaphore(1, true);
    private StockfishService.Engine active;
    private volatile boolean closed;

    @ConfigProperty(name = "stockfish.handshake-timeout", defaultValue = "PT10S")
    Duration handshakeTimeout = Duration.ofSeconds(10);

    @ConfigProperty(name = "stockfish.opponent-timeout", defaultValue = "PT15S")
    Duration opponentTimeout = Duration.ofSeconds(15);

    Process startProcess() throws IOException {
        return new ProcessBuilder("stockfish").redirectErrorStream(true).start();
    }

    String benchmarkEngineSha256() throws IOException {
        return sha256(engineBinary());
    }

    Path engineBinary() throws IOException {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(Pattern.quote(File.pathSeparator), -1)) {
                Path candidate = Path.of(directory, "stockfish");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toRealPath();
            }
        }
        throw new IOException("Stockfish executable is missing from PATH");
    }

    private static String sha256(Path binary) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(binary), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    Process startBenchmarkProcess(String expectedSha256) throws IOException {
        Path binary = engineBinary();
        if (!sha256(binary).equals(expectedSha256)) {
            throw new IOException("Stockfish build does not match this benchmark");
        }
        return new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
    }

    /** Returns null when the owning game stops before its search. */
    String chooseMove(Board board, int elo, BooleanSupplier shouldContinue) throws IOException, TimeoutException {
        return chooseMove(board, elo, null, shouldContinue);
    }

    String chooseMove(Board board, int elo, BenchmarkDefinition benchmark, BooleanSupplier shouldContinue)
            throws IOException, TimeoutException {
        if (!acquireSlot(shouldContinue)) return null;
        try {
            if (!shouldContinue.getAsBoolean()) return null;
            requireProcessSlot();

            Process process = benchmark == null ? startProcess() : startBenchmarkProcess(benchmark.engineSha256());
            StockfishService.Engine engine = new StockfishService.Engine(process);
            try {
                synchronized (this) {
                    if (closed) throw new IOException("Stockfish opponent is shut down");
                    active = engine;
                }
                engine.call(() -> {
                    configure(engine, elo, benchmark == null ? THREADS : benchmark.threads(),
                            benchmark == null ? HASH_MIB : benchmark.hashMiB());
                    return null;
                }, handshakeTimeout, shouldContinue);
                if (!shouldContinue.getAsBoolean()) return null;
                int nodes = benchmark == null ? SEARCH_NODES : benchmark.searchNodes();
                return engine.call(() -> search(engine, position(board), nodes), opponentTimeout, shouldContinue);
            } finally {
                stopAndAwaitExit(engine);
            }
        } finally {
            searchSlot.release();
        }
    }

    private synchronized void requireProcessSlot() throws IOException {
        if (closed) throw new IOException("Stockfish opponent is shut down");
        if (active != null && active.process.isAlive()) {
            throw new IOException("The previous Stockfish opponent process is still stopping");
        }
    }

    /** Wait before releasing the slot. Retain a still-live process as a guard if the exit wait expires. */
    private void stopAndAwaitExit(StockfishService.Engine engine) {
        engine.stop();
        try {
            engine.process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (active == engine && !engine.process.isAlive()) active = null;
            }
        }
    }

    private boolean acquireSlot(BooleanSupplier shouldContinue) throws IOException {
        try {
            while (shouldContinue.getAsBoolean()) {
                if (closed) throw new IOException("Stockfish opponent is shut down");
                if (searchSlot.tryAcquire(STOP_POLL_MS, TimeUnit.MILLISECONDS)) return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Stockfish opponent", e);
        }
    }

    private static void configure(StockfishService.Engine engine, int elo, int threads, int hashMiB) throws IOException {
        engine.sendCommand("uci");
        boolean supportedElo = false;
        boolean limitStrength = false;
        String line;
        while ((line = engine.reader.readLine()) != null && !line.equals("uciok")) {
            if (line.startsWith("option name UCI_LimitStrength type check ")) limitStrength = true;
            var advertisedRange = ELO_OPTION.matcher(line);
            if (advertisedRange.matches()) {
                supportedElo = elo >= Integer.parseInt(advertisedRange.group(1))
                        && elo <= Integer.parseInt(advertisedRange.group(2));
            }
        }
        if (line == null) throw new EOFException("Stockfish exited before uciok");
        if (!supportedElo || !limitStrength) {
            throw new IOException("This Stockfish version does not support the requested target Elo");
        }

        engine.sendCommand("setoption name Threads value " + threads);
        engine.sendCommand("setoption name Hash value " + hashMiB);
        engine.sendCommand("setoption name UCI_LimitStrength value true");
        engine.sendCommand("setoption name UCI_Elo value " + elo);
        engine.sendCommand("ucinewgame");
        engine.sendCommand("isready");
        while ((line = engine.reader.readLine()) != null) {
            if (line.equals("readyok")) return;
        }
        throw new EOFException("Stockfish exited before readyok");
    }

    private static String search(StockfishService.Engine engine, String position, int nodes) throws IOException {
        engine.sendCommand(position);
        engine.sendCommand("go nodes " + nodes);
        String line;
        while ((line = engine.reader.readLine()) != null) {
            if (line.startsWith("bestmove")) {
                var move = BEST_MOVE.matcher(line.strip());
                if (move.matches()) return move.group(1);
                throw new IOException("Stockfish returned no playable move");
            }
        }
        throw new EOFException("Stockfish exited before bestmove");
    }

    private static String position(Board board) {
        if (board.getBackup().isEmpty()) return "position fen " + board.getFen();

        String moves = board.getBackup().stream().map(backup -> backup.getMove().toString().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
        return "position startpos moves " + moves;
    }

    @PreDestroy
    synchronized void close() {
        closed = true;
        if (active != null) active.stop();
    }
}
