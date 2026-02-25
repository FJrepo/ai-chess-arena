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

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public record EvalResult(Integer cp, Integer mate) {}

    @PostConstruct
    public void init() {
        try {
            LOG.info("Starting Stockfish process...");
            process = new ProcessBuilder("stockfish").start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            sendCommand("uci");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("uciok")) break;
            }
            LOG.info("Stockfish initialized successfully.");

            // Set some default options
            sendCommand("setoption name Threads value 1");
            sendCommand("setoption name Hash value 64");

        } catch (IOException e) {
            LOG.error("Failed to start Stockfish. Ensure it is installed and in the PATH.", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        LOG.info("Shutting down Stockfish...");
        try {
            if (writer != null) {
                sendCommand("quit");
            }
        } catch (Exception ignored) {}

        if (process != null) {
            process.destroy();
        }
        executor.shutdown();
    }

    public CompletableFuture<EvalResult> evaluate(String fen, int depth) {
        LOG.debugf("Queuing evaluation for FEN: %s at depth %d", fen, depth);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try {
                    LOG.debugf("Starting Stockfish evaluation for FEN: %s", fen);
                    sendCommand("position fen " + fen);
                    sendCommand("go depth " + depth);

                    String line;
                    EvalResult result = new EvalResult(0, null);
                    while ((line = reader.readLine()) != null) {
                        LOG.tracef("Stockfish output: %s", line);
                        if (line.startsWith("info") && line.contains("score")) {
                            result = parseScore(line);
                        }
                        if (line.startsWith("bestmove")) {
                            break;
                        }
                    }
                    LOG.debugf("Evaluation finished: cp=%d, mate=%d", result.cp(), result.mate());
                    return result;
                } catch (IOException e) {
                    LOG.error("Error communicating with Stockfish", e);
                    return new EvalResult(null, null);
                }
            }
        }, executor);
    }

    private void sendCommand(String cmd) throws IOException {
        writer.write(cmd + "\n");
        writer.flush();
    }

    private EvalResult parseScore(String line) {
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
}
