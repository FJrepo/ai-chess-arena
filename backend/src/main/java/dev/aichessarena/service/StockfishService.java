package dev.aichessarena.service;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Startup
@ApplicationScoped
public class StockfishService {

    private static final Logger LOG = Logger.getLogger(StockfishService.class);
    private static final Pattern SCORE_CP_PATTERN = Pattern.compile("score cp (-?\\d+)");
    private static final Pattern SCORE_MATE_PATTERN = Pattern.compile("score mate (-?\\d+)");
    private static final Pattern SIDE_TO_MOVE_PATTERN = Pattern.compile("^[^\\s]+\\s+([wb])\\b");

    private volatile Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean available;
    private volatile boolean restartPending;
    private boolean closed;
    private volatile String unavailableReason = "Stockfish not initialized";

    public record EvalResult(Integer cp, Integer mate) {}
    public record Status(boolean available, String reason) {}

    @PostConstruct
    public synchronized void init() {
        startEngine();
    }

    // Package-private seam for lifecycle tests without a locally installed engine.
    Process startProcess() throws IOException {
        return new ProcessBuilder("stockfish").redirectErrorStream(true).start();
    }

    private boolean startEngine() {
        try {
            LOG.info("Starting Stockfish process...");
            process = startProcess();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            sendCommand("uci");
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) {
                    throw new EOFException("Stockfish exited before uciok");
                }
                if (line.equals("uciok")) break;
            }
            LOG.info("Stockfish initialized successfully.");

            // Set some default options
            sendCommand("setoption name Threads value 1");
            sendCommand("setoption name Hash value 64");
            available = true;
            restartPending = true;
            unavailableReason = null;
            return true;

        } catch (IOException e) {
            stopProcess();
            restartPending = false;
            markUnavailable("Stockfish is unavailable: install it locally or use the Docker backend image.");
            LOG.error("Failed to start Stockfish. Ensure it is installed and in the PATH.", e);
            return false;
        }
    }

    @PreDestroy
    public synchronized void cleanup() {
        LOG.info("Shutting down Stockfish...");
        closed = true;
        restartPending = false;
        markUnavailable("Stockfish is shut down.");
        try {
            if (writer != null) {
                sendCommand("quit");
            }
        } catch (Exception ignored) {}

        stopProcess();
        executor.shutdown();
    }

    private void stopProcess() {
        if (process != null) {
            process.destroyForcibly();
        }
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {}
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}
        process = null;
        reader = null;
        writer = null;
    }

    public CompletableFuture<EvalResult> evaluate(String fen, int depth) {
        if (!available && !restartPending) {
            return CompletableFuture.completedFuture(new EvalResult(null, null));
        }

        LOG.debugf("Queuing evaluation for FEN: %s at depth %d", fen, depth);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try {
                    if (closed) {
                        return new EvalResult(null, null);
                    }
                    if (!available || !isEngineReady()) {
                        if (!restartPending) {
                            return new EvalResult(null, null);
                        }
                        // One attempt for this failure; never replay the input that killed the engine.
                        // Keep accepting queued requests through the handshake. This worker serializes
                        // restarts, and startEngine clears restartPending if the attempt fails.
                        LOG.warn("Stockfish process stopped; restarting for the next evaluation.");
                        stopProcess();
                        if (!startEngine()) {
                            return new EvalResult(null, null);
                        }
                    }

                    LOG.debugf("Starting Stockfish evaluation for FEN: %s", fen);
                    sendCommand("position fen " + fen);
                    sendCommand("go depth " + depth);

                    String line;
                    EvalResult result = new EvalResult(null, null);
                    while ((line = reader.readLine()) != null) {
                        LOG.tracef("Stockfish output: %s", line);
                        if (line.startsWith("info") && line.contains("score")) {
                            EvalResult parsed = parseScore(line);
                            if (parsed != null) {
                                result = parsed;
                            }
                        }
                        if (line.startsWith("bestmove")) {
                            result = normalizeToWhitePerspective(result, fen);
                            LOG.debugf("Evaluation finished: cp=%d, mate=%d", result.cp(), result.mate());
                            return result;
                        }
                    }
                    throw new EOFException("Stockfish output ended before bestmove");
                } catch (IOException e) {
                    markUnavailable("Stockfish process communication failed.");
                    stopProcess();
                    LOG.warn("Stockfish evaluation failed; returning no score. Next evaluation will attempt a restart.", e);
                    return new EvalResult(null, null);
                }
            }
        }, executor);
    }

    private void sendCommand(String cmd) throws IOException {
        if (writer == null) {
            throw new IOException("Stockfish writer is not initialized");
        }
        writer.write(cmd + "\n");
        writer.flush();
    }

    public Status status() {
        boolean ready = isAvailable();
        return new Status(ready, ready ? null : available ? "Stockfish process is not running." : unavailableReason);
    }

    boolean isAvailable() {
        return available && isEngineReady();
    }

    void markUnavailable(String reason) {
        available = false;
        unavailableReason = reason;
    }

    private boolean isEngineReady() {
        Process current = process;
        return current != null && current.isAlive() && reader != null && writer != null;
    }

    static EvalResult parseScore(String line) {
        if (line.contains("lowerbound") || line.contains("upperbound")) {
            return null;
        }

        Integer cp = null;
        Integer mate = null;

        Matcher cpMatcher = SCORE_CP_PATTERN.matcher(line);
        if (cpMatcher.find()) {
            cp = Integer.parseInt(cpMatcher.group(1));
        }

        Matcher mateMatcher = SCORE_MATE_PATTERN.matcher(line);
        if (mateMatcher.find()) {
            mate = Integer.parseInt(mateMatcher.group(1));
        }

        return new EvalResult(cp, mate);
    }

    static EvalResult normalizeToWhitePerspective(EvalResult result, String fen) {
        if (result == null) {
            return new EvalResult(null, null);
        }

        return isBlackToMove(fen)
                ? new EvalResult(negate(result.cp()), negate(result.mate()))
                : result;
    }

    static boolean isBlackToMove(String fen) {
        if (fen == null) {
            return false;
        }

        Matcher sideMatcher = SIDE_TO_MOVE_PATTERN.matcher(fen.trim());
        return sideMatcher.find() && "b".equals(sideMatcher.group(1));
    }

    private static Integer negate(Integer value) {
        return value == null ? null : -value;
    }
}
