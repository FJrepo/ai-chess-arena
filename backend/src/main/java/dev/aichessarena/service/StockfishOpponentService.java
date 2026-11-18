package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
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

    private static final Pattern ELO_OPTION = Pattern.compile("option name UCI_Elo type spin default \\d+ min (\\d+) max (\\d+)");
    private static final Pattern BEST_MOVE = Pattern.compile("bestmove ([a-h][1-8][a-h][1-8][qrbn]?)(?:\\s.*)?");
    private static final long STOP_POLL_MS = 250L;

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

    /** Returns null when the owning game stops before its search. */
    String chooseMove(Board board, int elo, BooleanSupplier shouldContinue) throws IOException, TimeoutException {
        if (!acquireSlot(shouldContinue)) return null;
        try {
            if (!shouldContinue.getAsBoolean()) return null;
            if (closed) throw new IOException("Stockfish opponent is shut down");

            StockfishService.Engine engine = new StockfishService.Engine(startProcess());
            try {
                synchronized (this) {
                    if (closed) throw new IOException("Stockfish opponent is shut down");
                    active = engine;
                }
                engine.call(() -> {
                    configure(engine, elo);
                    return null;
                }, handshakeTimeout);
                if (!shouldContinue.getAsBoolean()) return null;
                return engine.call(() -> search(engine, position(board)), opponentTimeout);
            } finally {
                synchronized (this) {
                    active = null;
                }
                engine.stop();
            }
        } finally {
            searchSlot.release();
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

    private void configure(StockfishService.Engine engine, int elo) throws IOException {
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

        engine.sendCommand("setoption name Threads value 1");
        engine.sendCommand("setoption name Hash value 16");
        engine.sendCommand("setoption name UCI_LimitStrength value true");
        engine.sendCommand("setoption name UCI_Elo value " + elo);
        engine.sendCommand("ucinewgame");
        engine.sendCommand("isready");
        while ((line = engine.reader.readLine()) != null) {
            if (line.equals("readyok")) return;
        }
        throw new EOFException("Stockfish exited before readyok");
    }

    private static String search(StockfishService.Engine engine, String position) throws IOException {
        engine.sendCommand(position);
        engine.sendCommand("go nodes " + SEARCH_NODES);
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
