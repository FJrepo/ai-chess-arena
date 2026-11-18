package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.BenchmarkDefinition;
import dev.aichessarena.entity.BenchmarkDefinition.Network;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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
    private static final Pattern NETWORK_OPTION = Pattern.compile(
            "option name EvalFile(?:Small)? type string default ([A-Za-z0-9][A-Za-z0-9_.-]*[.]nnue)");
    private static final String EXPORTED_NETWORK = "Network saved successfully to ";
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

    record EngineFingerprint(String sha256, List<Network> networks) {}

    EngineFingerprint benchmarkFingerprint(List<Integer> strengths) throws IOException, TimeoutException {
        return withEngine(true, null, () -> true, (engine, binary) -> engine.call(() -> {
            List<String> names = configure(engine, strengths, THREADS, HASH_MIB);
            return new EngineFingerprint(binary.sha256(), fingerprintNetworks(engine, binary.directory(), names));
        }, handshakeTimeout));
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

    Process startBenchmarkProcess(Path verifiedBinary) throws IOException {
        return new ProcessBuilder(verifiedBinary.toString()).directory(verifiedBinary.getParent().toFile())
                .redirectErrorStream(true).start();
    }

    private PinnedBinary pinBinary() throws IOException {
        Path directory = Files.createTempDirectory("ladder-stockfish-", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Path binary = directory.resolve("stockfish");
        try {
            Files.copy(engineBinary(), binary);
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("r-x------"));
            return new PinnedBinary(directory, binary, sha256(binary));
        } catch (IOException | RuntimeException e) {
            try {
                cleanDirectory(directory);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private record PinnedBinary(Path directory, Path binary, String sha256) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            cleanDirectory(directory);
        }
    }

    private static void cleanDirectory(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) Files.deleteIfExists(path);
        }
        Files.deleteIfExists(directory);
    }

    /** Returns null when the owning game stops before its search. */
    String chooseMove(Board board, int elo, BooleanSupplier shouldContinue) throws IOException, TimeoutException {
        return chooseMove(board, elo, null, shouldContinue);
    }

    String chooseMove(Board board, int elo, BenchmarkDefinition benchmark, BooleanSupplier shouldContinue)
            throws IOException, TimeoutException {
        if (benchmark != null && benchmark.engineNetworks().isEmpty()) {
            throw new IOException("This benchmark has no verified Stockfish NNUE weights");
        }
        return withEngine(benchmark != null, benchmark == null ? null : benchmark.engineSha256(), shouldContinue,
                (engine, binary) -> {
                    engine.call(() -> {
                        List<String> names = configure(engine, List.of(elo), benchmark == null ? THREADS : benchmark.threads(),
                                benchmark == null ? HASH_MIB : benchmark.hashMiB());
                        if (benchmark != null
                                && !fingerprintNetworks(engine, binary.directory(), names).equals(benchmark.engineNetworks())) {
                            throw new IOException("Stockfish NNUE weights do not match this benchmark");
                        }
                        return null;
                    }, handshakeTimeout, shouldContinue);
                    if (!shouldContinue.getAsBoolean()) return null;
                    int nodes = benchmark == null ? SEARCH_NODES : benchmark.searchNodes();
                    return engine.call(() -> search(engine, position(board), nodes), opponentTimeout, shouldContinue);
                });
    }

    @FunctionalInterface
    private interface EngineOperation<T> {
        T run(StockfishService.Engine engine, PinnedBinary binary) throws IOException, TimeoutException;
    }

    private <T> T withEngine(boolean pin, String expectedSha256, BooleanSupplier shouldContinue, EngineOperation<T> operation)
            throws IOException, TimeoutException {
        if (!acquireSlot(shouldContinue)) return null;
        try {
            if (!shouldContinue.getAsBoolean()) return null;
            requireProcessSlot();
            try (PinnedBinary binary = pin ? pinBinary() : null) {
                if (!shouldContinue.getAsBoolean()) return null;
                if (expectedSha256 != null && !expectedSha256.equals(binary.sha256())) {
                    throw new IOException("Stockfish build does not match this benchmark");
                }
                Process process = binary == null ? startProcess() : startBenchmarkProcess(binary.binary());
                StockfishService.Engine engine = new StockfishService.Engine(process);
                try {
                    synchronized (this) {
                        if (closed) throw new IOException("Stockfish opponent is shut down");
                        active = engine;
                    }
                    return operation.run(engine, binary);
                } finally {
                    stopAndAwaitExit(engine);
                }
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

    private static List<String> configure(StockfishService.Engine engine, List<Integer> strengths, int threads,
                                          int hashMiB) throws IOException {
        engine.sendCommand("uci");
        boolean supportedElo = false;
        boolean limitStrength = false;
        List<String> networks = new ArrayList<>();
        String line;
        while ((line = engine.reader.readLine()) != null && !line.equals("uciok")) {
            if (line.startsWith("option name UCI_LimitStrength type check ")) limitStrength = true;
            var advertisedRange = ELO_OPTION.matcher(line);
            if (advertisedRange.matches()) {
                int min = Integer.parseInt(advertisedRange.group(1));
                int max = Integer.parseInt(advertisedRange.group(2));
                supportedElo = strengths.stream().allMatch(elo -> elo >= min && elo <= max);
            }
            var network = NETWORK_OPTION.matcher(line);
            if (network.matches()) networks.add(network.group(1));
        }
        if (line == null) throw new EOFException("Stockfish exited before uciok");
        if (!supportedElo || !limitStrength) {
            throw new IOException("This Stockfish version does not support the requested target Elo");
        }

        engine.sendCommand("setoption name Threads value " + threads);
        engine.sendCommand("setoption name Hash value " + hashMiB);
        engine.sendCommand("setoption name UCI_LimitStrength value true");
        engine.sendCommand("setoption name UCI_Elo value " + strengths.getFirst());
        engine.sendCommand("ucinewgame");
        engine.sendCommand("isready");
        while ((line = engine.reader.readLine()) != null) {
            if (line.equals("readyok")) return networks;
        }
        throw new EOFException("Stockfish exited before readyok");
    }

    /** Stockfish exports its loaded parameters. Hash those bytes before asking that process to search. */
    private static List<Network> fingerprintNetworks(StockfishService.Engine engine, Path directory, List<String> names)
            throws IOException {
        if (names.isEmpty() || new HashSet<>(names).size() != names.size()) {
            throw new IOException("Stockfish did not advertise verifiable NNUE weights");
        }
        engine.sendCommand("export_net");
        engine.sendCommand("isready");
        var exported = new HashSet<String>();
        String line;
        while ((line = engine.reader.readLine()) != null && !line.equals("readyok")) {
            if (line.startsWith("Failed to export a net")) {
                throw new IOException("Stockfish could not export all loaded NNUE weights");
            }
            if (line.startsWith(EXPORTED_NETWORK)) exported.add(line.substring(EXPORTED_NETWORK.length()));
        }
        if (line == null || !exported.equals(new HashSet<>(names))) {
            throw new IOException("Stockfish could not export all loaded NNUE weights");
        }
        List<Network> fingerprints = new ArrayList<>();
        for (String name : names.stream().sorted().toList()) {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0) {
                throw new IOException("Stockfish did not export usable NNUE weights");
            }
            fingerprints.add(new Network(name, sha256(file)));
            Files.delete(file);
        }
        return List.copyOf(fingerprints);
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
