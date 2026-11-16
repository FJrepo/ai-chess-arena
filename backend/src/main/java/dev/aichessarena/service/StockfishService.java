package dev.aichessarena.service;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Startup
@ApplicationScoped
public class StockfishService {

    private static final Logger LOG = Logger.getLogger(StockfishService.class);
    private static final Pattern SCORE_CP_PATTERN = Pattern.compile("score cp (-?\\d+)");
    private static final Pattern SCORE_MATE_PATTERN = Pattern.compile("score mate (-?\\d+)");
    private static final Pattern SIDE_TO_MOVE_PATTERN = Pattern.compile("^[^\\s]+\\s+([wb])\\b");

    private enum State { STARTING, RECOVERING, READY, UNAVAILABLE, CLOSED }

    @ConfigProperty(name = "stockfish.handshake-timeout", defaultValue = "PT10S")
    Duration handshakeTimeout = Duration.ofSeconds(10);

    @ConfigProperty(name = "stockfish.evaluation-timeout", defaultValue = "PT30S")
    Duration evaluationTimeout = Duration.ofSeconds(30);

    private volatile Engine engine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile State state = State.UNAVAILABLE;
    private volatile boolean restartPending;
    private volatile String unavailableReason = "Stockfish not initialized";

    public record EvalResult(Integer cp, Integer mate) {}
    public record Status(boolean available, String reason) {}

    @PostConstruct
    public void init() {
        startEngine();
    }

    // Package-private seam for lifecycle tests without a locally installed engine.
    Process startProcess() throws IOException {
        return new ProcessBuilder("stockfish").redirectErrorStream(true).start();
    }

    private boolean startEngine() {
        synchronized (this) {
            if (state == State.CLOSED) return false;
            state = restartPending ? State.RECOVERING : State.STARTING;
            unavailableReason = "Stockfish is initializing.";
        }
        try {
            LOG.info("Starting Stockfish process...");
            Engine replacement = new Engine(startProcess());
            synchronized (this) {
                if (state == State.CLOSED) {
                    replacement.stop();
                    return false;
                }
                engine = replacement;
            }
            replacement.call(() -> {
                replacement.sendCommand("uci");
                String line;
                while ((line = replacement.reader.readLine()) != null) {
                    if (line.equals("uciok")) {
                        replacement.sendCommand("setoption name Threads value 1");
                        replacement.sendCommand("setoption name Hash value 64");
                        return null;
                    }
                }
                throw new EOFException("Stockfish exited before uciok");
            }, handshakeTimeout);
            synchronized (this) {
                if (state == State.CLOSED) return false;
                state = State.READY;
                restartPending = true;
                unavailableReason = null;
            }
            LOG.info("Stockfish initialized successfully.");
            return true;
        } catch (IOException | TimeoutException e) {
            synchronized (this) {
                restartPending = false;
                markUnavailable("Stockfish initialization failed or timed out; restart the backend to retry.");
            }
            stopProcess();
            LOG.error("Failed to initialize Stockfish.", e);
            return false;
        }
    }

    @PreDestroy
    public void cleanup() {
        synchronized (this) {
            state = State.CLOSED;
            restartPending = false;
            unavailableReason = "Stockfish is shut down.";
        }
        stopProcess();
        // Drain queued suppliers: they observe CLOSED and complete with no score.
        executor.shutdown();
    }

    private void stopProcess() {
        Engine current;
        synchronized (this) {
            current = engine;
            engine = null;
        }
        if (current != null) current.stop();
    }

    public CompletableFuture<EvalResult> evaluate(String fen, int depth) {
        if (state == State.CLOSED || (state != State.READY && !restartPending)) {
            return CompletableFuture.completedFuture(new EvalResult(null, null));
        }
        try {
            return CompletableFuture.supplyAsync(() -> evaluatePosition(fen, depth), executor);
        } catch (RejectedExecutionException e) {
            // Shutdown may race with submission.
            return CompletableFuture.completedFuture(new EvalResult(null, null));
        }
    }

    private EvalResult evaluatePosition(String fen, int depth) {
        try {
            if (state == State.CLOSED) return new EvalResult(null, null);
            if (!isEngineReady()) {
                if (!restartPending) return new EvalResult(null, null);
                // Clear readiness before spawning, including exits between evaluations.
                synchronized (this) {
                    if (state == State.CLOSED) return new EvalResult(null, null);
                    state = State.RECOVERING;
                    unavailableReason = "Stockfish is recovering.";
                }
                stopProcess();
                if (!startEngine()) return new EvalResult(null, null);
            }
            Engine current = engine;
            if (current == null) return new EvalResult(null, null);
            return current.call(() -> {
                current.sendCommand("position fen " + fen);
                current.sendCommand("go depth " + depth);
                String line;
                EvalResult result = new EvalResult(null, null);
                while ((line = current.reader.readLine()) != null) {
                    if (line.startsWith("info") && line.contains("score")) {
                        EvalResult parsed = parseScore(line);
                        if (parsed != null) result = parsed;
                    }
                    if (line.startsWith("bestmove")) {
                        return normalizeToWhitePerspective(result, fen);
                    }
                }
                throw new EOFException("Stockfish output ended before bestmove");
            }, evaluationTimeout);
        } catch (TimeoutException e) {
            synchronized (this) {
                restartPending = false;
                markUnavailable("Stockfish evaluation timed out; restart the backend to retry.");
            }
            stopProcess();
            LOG.warn("Stockfish evaluation timed out; returning no score.", e);
        } catch (IOException e) {
            markUnavailable("Stockfish process communication failed.");
            stopProcess();
            LOG.warn("Stockfish evaluation failed; returning no score.", e);
        }
        return new EvalResult(null, null);
    }

    public Status status() {
        boolean ready = isAvailable();
        return new Status(ready, ready ? null
                : state == State.READY ? "Stockfish process is not running." : unavailableReason);
    }

    boolean isAvailable() {
        return isEngineReady();
    }

    synchronized void markUnavailable(String reason) {
        if (state == State.CLOSED) return;
        unavailableReason = reason;
        state = State.UNAVAILABLE;
    }

    private boolean isEngineReady() {
        Engine current = engine;
        return state == State.READY && current != null && current.process.isAlive();
    }

    /** Isolates blocking protocol I/O so deadlines and shutdown can cancel the waiting worker. */
    private static final class Engine {
        final Process process;
        final BufferedReader reader;
        final BufferedWriter writer;
        final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .daemon().name("stockfish-io").factory());
        private Future<?> active;
        private boolean stopped;

        Engine(Process process) {
            this.process = process;
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        }

        <T> T call(Callable<T> operation, Duration timeout) throws IOException, TimeoutException {
            Future<T> task;
            synchronized (this) {
                if (stopped) throw new IOException("Stockfish stopped");
                task = io.submit(operation);
                active = task;
            }
            try {
                return task.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Stockfish operation interrupted", e);
            } catch (ExecutionException | CancellationException e) {
                throw new IOException("Stockfish operation failed or cancelled", e);
            }
        }

        void sendCommand(String command) throws IOException {
            writer.write(command + "\n");
            writer.flush();
        }

        void stop() {
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                if (active != null) active.cancel(true);
                io.shutdownNow();
            }
            // Descendants may inherit stdout and keep the protocol read blocked after the engine exits.
            // BufferedReader.close() shares that read lock, so keep all closing off the caller.
            Thread.ofPlatform().daemon().name("stockfish-close").start(() -> {
                try {
                    terminateDescendants();
                } finally {
                    process.destroyForcibly();
                    close(process.getInputStream());
                    close(process.getOutputStream());
                    close(process.getErrorStream());
                    close(reader);
                    close(writer);
                }
            });
        }

        private void terminateDescendants() {
            var descendants = process.descendants().toList();
            descendants.reversed().forEach(ProcessHandle::destroyForcibly);
            try {
                // Keep the parent alive briefly so it can reap its children before it is killed.
                CompletableFuture.allOf(descendants.stream().map(ProcessHandle::onExit)
                        .toArray(CompletableFuture<?>[]::new)).get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                LOG.debug("Stockfish descendants did not finish exiting before engine termination.", e);
            }
        }

        private static void close(Closeable stream) {
            try {
                stream.close();
            } catch (IOException ignored) {}
        }
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
