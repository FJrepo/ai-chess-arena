package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StockfishServiceTest {

    @Test
    void parseScoreExtractsExactCentipawnValues() {
        StockfishService.EvalResult result = StockfishService.parseScore(
                "info depth 12 seldepth 18 multipv 1 score cp 47 nodes 12345 nps 99999"
        );

        assertEquals(47, result.cp());
        assertNull(result.mate());
    }

    @Test
    void parseScoreExtractsExactMateValues() {
        StockfishService.EvalResult result = StockfishService.parseScore(
                "info depth 16 score mate -3 nodes 999"
        );

        assertNull(result.cp());
        assertEquals(-3, result.mate());
    }

    @Test
    void parseScoreIgnoresBoundScores() {
        assertNull(StockfishService.parseScore("info depth 14 score cp 120 lowerbound nodes 1000"));
        assertNull(StockfishService.parseScore("info depth 14 score mate 4 upperbound nodes 1000"));
    }

    @Test
    void normalizeToWhitePerspectiveLeavesWhiteToMoveScoresUnchanged() {
        StockfishService.EvalResult result = new StockfishService.EvalResult(85, 3);

        StockfishService.EvalResult normalized = StockfishService.normalizeToWhitePerspective(
                result,
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        );

        assertEquals(85, normalized.cp());
        assertEquals(3, normalized.mate());
    }

    @Test
    void normalizeToWhitePerspectiveFlipsBlackToMoveScores() {
        StockfishService.EvalResult result = new StockfishService.EvalResult(120, -2);

        StockfishService.EvalResult normalized = StockfishService.normalizeToWhitePerspective(
                result,
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
        );

        assertEquals(-120, normalized.cp());
        assertEquals(2, normalized.mate());
    }

    @Test
    void detectsSideToMoveFromFen() {
        assertFalse(StockfishService.isBlackToMove("8/8/8/8/8/8/8/8 w - - 0 1"));
        assertTrue(StockfishService.isBlackToMove("8/8/8/8/8/8/8/8 b - - 0 1"));
    }

    @Test
    void evaluateReturnsNoEvalImmediatelyWhenUnavailable() {
        StockfishService service = new StockfishService();
        service.markUnavailable("Stockfish missing");

        StockfishService.EvalResult result = service.evaluate("8/8/8/8/8/8/8/8 w - - 0 1", 12).join();

        assertNull(result.cp());
        assertNull(result.mate());
        assertFalse(service.status().available());
        assertEquals("Stockfish missing", service.status().reason());
    }

    @Test
    void statusIncludesReasonWhenUnavailable() {
        StockfishService service = new StockfishService();
        service.markUnavailable("Stockfish process is not running.");

        StockfishService.Status status = service.status();

        assertFalse(status.available());
        assertNotNull(status.reason());
        assertEquals("Stockfish process is not running.", status.reason());
    }

    @ParameterizedTest
    @CsvSource({"w, 3, 3", "w, -3, -3", "b, 3, -3", "b, -3, 3"})
    void mateForAndAgainstEachColorIsStoredFromWhitesView(String color, int uciMate, int whiteMate) {
        var parsed = StockfishService.parseScore("info depth 12 score mate " + uciMate);
        var result = StockfishService.normalizeToWhitePerspective(parsed, "8/8/8/8/8/8/8/8 " + color + " - - 0 1");
        assertNull(result.cp());
        assertEquals(whiteMate, result.mate());
    }

    @Test
    void exitDuringEvaluationDiscardsPartialScoreAndRestartsForAlreadyQueuedWork() throws Exception {
        var killed = new FakeProcess("uciok\ninfo depth 1 score cp 999\n", true);
        var replacement = new FakeProcess("uciok\ninfo depth 12 score cp 47\nbestmove e2e4\n", false);
        var service = new TestService(killed, replacement);
        try {
            service.init();
            var failed = service.evaluate("invalid", 12);
            var queued = service.evaluate("8/8/8/8/8/8/8/8 b - - 0 1", 12);
            assertNoEvaluation(failed.get(2, TimeUnit.SECONDS));
            assertEquals(-47, queued.get(2, TimeUnit.SECONDS).cp());
            assertEquals(2, service.starts);
            assertTrue(service.status().available());
            assertNull(service.status().reason());
            assertFalse(replacement.commands.toString(StandardCharsets.UTF_8).contains("invalid"));
        } finally {
            service.cleanup();
        }
        assertStopped(replacement);
    }

    @Test
    void eofWithoutProcessExitAlsoRestartsOnNextEvaluation() throws Exception {
        var eof = new FakeProcess("uciok\n", false);
        var replacement = new FakeProcess("uciok\ninfo score mate 2\nbestmove e2e4\n", false);
        var service = new TestService(eof, replacement);
        try {
            service.init();
            assertNoEvaluation(service.evaluate("invalid", 12).get(2, TimeUnit.SECONDS));
            assertStopped(eof);
            assertFalse(service.status().available());
            assertEquals(2, service.evaluate("8/8/8/8/8/8/8/8 w - - 0 1", 12)
                    .get(2, TimeUnit.SECONDS).mate());
            assertEquals(2, service.starts);
        } finally {
            service.cleanup();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requestsDuringRestartHandshakeWaitForRecovery(boolean restartSucceeds) throws Exception {
        var handshakeStarted = new CountDownLatch(1);
        var releaseHandshake = new CountDownLatch(1);
        var replacement = new FakeProcess(restartSucceeds
                ? "uciok\ninfo score cp 42\nbestmove e2e4\ninfo score cp 47\nbestmove d2d4\n"
                : "Stockfish 19\n", false) {
            @Override
            public InputStream getInputStream() {
                return new FilterInputStream(super.getInputStream()) {
                    @Override
                    public int read(byte[] bytes, int off, int len) throws IOException {
                        handshakeStarted.countDown();
                        try {
                            if (!releaseHandshake.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting to release fake handshake");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Fake handshake interrupted", e);
                        }
                        return super.read(bytes, off, len);
                    }
                };
            }
        };
        var service = new TestService(new FakeProcess("uciok\ninfo score cp 999\n", true), replacement);
        try {
            service.init();
            assertNoEvaluation(service.evaluate("invalid", 12).get(2, TimeUnit.SECONDS));
            var restarting = service.evaluate("8/8/8/8/8/8/8/8 w - - 0 1", 12);
            assertTrue(handshakeStarted.await(2, TimeUnit.SECONDS));
            assertFalse(service.status().available());

            var concurrent = service.evaluate("8/8/8/8/8/8/8/8 b - - 0 1", 12);
            assertFalse(restarting.isDone());
            assertFalse(concurrent.isDone(), "Requests during recovery must join the evaluation queue");
            releaseHandshake.countDown();

            if (restartSucceeds) {
                assertEquals(42, restarting.get(2, TimeUnit.SECONDS).cp());
                assertEquals(-47, concurrent.get(2, TimeUnit.SECONDS).cp());
                assertTrue(service.status().available());
            } else {
                assertNoEvaluation(restarting.get(2, TimeUnit.SECONDS));
                assertNoEvaluation(concurrent.get(2, TimeUnit.SECONDS));
                var unavailable = service.evaluate("valid", 12);
                assertTrue(unavailable.isDone(), "A failed restart must return no score immediately");
                assertNoEvaluation(unavailable.join());
                assertFalse(service.status().available());
            }
            assertEquals(2, service.starts);
            assertFalse(replacement.commands.toString(StandardCharsets.UTF_8).contains("invalid"));
        } finally {
            releaseHandshake.countDown();
            service.cleanup();
        }
    }

    @Test
    void processExitBetweenEvaluationsRestartsEvenAfterStatusWasRead() throws Exception {
        var original = new FakeProcess("uciok\n", false);
        var replacement = new FakeProcess("uciok\ninfo score cp 23\nbestmove e2e4\n", false);
        var service = new TestService(original, replacement);
        try {
            service.init();
            original.destroy();
            assertFalse(service.status().available());
            assertEquals(23, service.evaluate("8/8/8/8/8/8/8/8 w - - 0 1", 12)
                    .get(2, TimeUnit.SECONDS).cp());
            assertEquals(2, service.starts);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void brokenPipeReturnsNullAndNextEvaluationRestarts() throws Exception {
        var original = new FakeProcess("uciok\n", false);
        var replacement = new FakeProcess("uciok\ninfo score cp 10\nbestmove e2e4\n", false);
        var service = new TestService(original, replacement);
        try {
            service.init();
            original.failWrites = true;
            assertNoEvaluation(service.evaluate("invalid", 12).get(2, TimeUnit.SECONDS));
            assertEquals(10, service.evaluate("valid", 12).get(2, TimeUnit.SECONDS).cp());
            assertEquals(2, service.starts);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void failedRestartIsAttemptedOnlyOnceAndDoesNotBlockQueue() throws Exception {
        var service = new TestService(new FakeProcess("uciok\n", true));
        try {
            service.init();
            assertNoEvaluation(service.evaluate("invalid", 12).get(2, TimeUnit.SECONDS));
            assertNoEvaluation(service.evaluate("valid", 12).get(2, TimeUnit.SECONDS));
            assertNoEvaluation(service.evaluate("valid", 12).get(2, TimeUnit.SECONDS));
            assertEquals(2, service.starts);
            assertFalse(service.status().available());
            assertNotNull(service.status().reason());
        } finally {
            service.cleanup();
        }
    }

    @Test
    void restartMustReceiveUciokAndNeverLoopsOnStartupEof() throws Exception {
        var incompleteStartup = new FakeProcess("Stockfish 19\n", true);
        var service = new TestService(new FakeProcess("uciok\n", true), incompleteStartup);
        try {
            service.init();
            assertNoEvaluation(service.evaluate("invalid", 12).get(2, TimeUnit.SECONDS));
            assertNoEvaluation(service.evaluate("valid", 12).get(2, TimeUnit.SECONDS));
            assertNoEvaluation(service.evaluate("valid", 12).get(2, TimeUnit.SECONDS));
            assertEquals(2, service.starts);
            assertFalse(service.status().available());
            assertStopped(incompleteStartup);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void missingEngineAtStartupDoesNotRetryEveryMove() throws Exception {
        var service = new TestService();
        try {
            service.init();
            assertNoEvaluation(service.evaluate("valid", 12).get(2, TimeUnit.SECONDS));
            assertEquals(1, service.starts);
            assertFalse(service.status().available());
        } finally {
            service.cleanup();
        }
    }

    @Test
    @Timeout(5)
    void stalledReplacementHandshakeTimesOutAndDrainsQueue() throws Exception {
        var original = new FakeProcess("uciok\n", false);
        var stalled = new StalledProcess("Stockfish 19\n");
        var service = new TestService(original, stalled);
        service.handshakeTimeout = Duration.ofMillis(250);
        try {
            service.init();
            original.destroy(); // Exit between evaluations: readiness must be cleared before spawn.
            var current = service.evaluate("valid", 12);
            assertTrue(stalled.readStarted.await(2, TimeUnit.SECONDS));
            assertFalse(service.status().available());
            assertFalse(service.isAvailable());
            var queued = service.evaluate("queued", 12);
            assertNoEvaluation(current.get(2, TimeUnit.SECONDS));
            assertNoEvaluation(queued.get(2, TimeUnit.SECONDS));
            assertTrue(stalled.streamsClosed.await(2, TimeUnit.SECONDS));
            assertStopped(stalled);
            assertFalse(service.status().available());
            assertTrue(service.evaluate("later", 12).isDone());
            assertEquals(2, service.starts);
        } finally {
            service.cleanup();
        }
    }

    @Test
    @Timeout(5)
    void initialHandshakeWithoutUciokIsAlsoBounded() throws Exception {
        var stalled = new StalledProcess("");
        var service = new TestService(stalled);
        service.handshakeTimeout = Duration.ofMillis(100);
        try {
            service.init();
            assertTrue(stalled.streamsClosed.await(2, TimeUnit.SECONDS));
            assertStopped(stalled);
            assertFalse(service.status().available());
            assertNoEvaluation(service.evaluate("valid", 12).get(2, TimeUnit.SECONDS));
            assertEquals(1, service.starts);
        } finally {
            service.cleanup();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(5)
    void shutdownCancelsStalledHandshakeOrEvaluationAndDrainsQueue(boolean handshake) throws Exception {
        var original = new FakeProcess("uciok\n", false);
        var stalled = new StalledProcess(handshake ? "" : "uciok\ninfo score cp 999\n");
        var service = handshake ? new TestService(original, stalled) : new TestService(stalled);
        service.handshakeTimeout = Duration.ofSeconds(60);
        service.evaluationTimeout = Duration.ofSeconds(60);
        try {
            service.init();
            if (handshake) original.destroy();
            var current = service.evaluate("valid", 12);
            assertTrue(stalled.readStarted.await(2, TimeUnit.SECONDS));
            var queued = service.evaluate("queued", 12);
            CompletableFuture.runAsync(service::cleanup).get(1, TimeUnit.SECONDS);
            assertNoEvaluation(current.get(1, TimeUnit.SECONDS));
            assertNoEvaluation(queued.get(1, TimeUnit.SECONDS));
            assertTrue(stalled.streamsClosed.await(1, TimeUnit.SECONDS));
            assertStopped(stalled);
            assertFalse(service.status().available());
            assertEquals("Stockfish is shut down.", service.status().reason());
            assertNoEvaluation(service.evaluate("after shutdown", 12).join());
        } finally {
            service.cleanup();
        }
    }

    @Test
    @Timeout(5)
    void evaluationWithoutBestmoveTimesOutAndDiscardsPartialScore() throws Exception {
        var stalled = new StalledProcess("uciok\ninfo score cp 999\n");
        var service = new TestService(stalled);
        service.evaluationTimeout = Duration.ofMillis(250);
        try {
            service.init();
            var current = service.evaluate("valid", 12);
            assertTrue(stalled.readStarted.await(2, TimeUnit.SECONDS));
            var queued = service.evaluate("queued", 12);
            assertNoEvaluation(current.get(2, TimeUnit.SECONDS));
            assertNoEvaluation(queued.get(2, TimeUnit.SECONDS));
            assertTrue(stalled.streamsClosed.await(2, TimeUnit.SECONDS));
            assertStopped(stalled);
            assertFalse(service.status().available());
            assertTrue(service.evaluate("later", 12).isDone());
            assertEquals(1, service.starts);
        } finally {
            service.cleanup();
        }
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    @EnabledIfSystemProperty(named = "file.separator", matches = "/")
    @Timeout(15)
    void realProcessCleanupTerminatesDescendantsAndIoThreads(boolean handshake, boolean timeout) throws Exception {
        Set<Thread> existingThreads = Thread.getAllStackTraces().keySet();
        Process process = new ProcessBuilder("/bin/sh", "-c",
                handshake ? "sleep 60 & wait" : "sleep 60 & printf 'uciok\\n'; wait")
                .redirectErrorStream(true).start();
        var service = new TestService(process);
        service.handshakeTimeout = Duration.ofSeconds(timeout ? 1 : 60);
        service.evaluationTimeout = Duration.ofSeconds(timeout ? 1 : 60);
        List<ProcessHandle> descendants = List.of();
        try {
            awaitCondition(() -> process.descendants().findAny().isPresent(), "Shell must fork a child");
            descendants = process.descendants().toList();
            assertFalse(descendants.isEmpty());

            CompletableFuture<?> current;
            if (handshake) {
                current = CompletableFuture.runAsync(service::init);
            } else {
                service.init();
                current = service.evaluate("valid", 12);
            }
            awaitCondition(() -> stockfishThreadsSince(existingThreads).anyMatch(thread ->
                    Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals(BufferedReader.class.getName())
                                    && frame.getMethodName().equals("readLine"))),
                    "Protocol thread must block reading the real process pipe");

            if (!timeout) CompletableFuture.runAsync(service::cleanup).get(1, TimeUnit.SECONDS);
            Object result = current.get(3, TimeUnit.SECONDS);
            if (!handshake) assertNoEvaluation((StockfishService.EvalResult) result);
            assertFalse(service.status().available());
            assertTrue(process.waitFor(3, TimeUnit.SECONDS), "Engine process must exit");
            for (ProcessHandle descendant : descendants) {
                awaitCondition(() -> !descendant.isAlive(), "Engine descendant must exit");
            }
            awaitCondition(() -> stockfishThreadsSince(existingThreads).findAny().isEmpty(),
                    "stockfish-io and stockfish-close threads must finish");
        } finally {
            service.cleanup();
            // Also clean up after a failing assertion against a broken implementation.
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private static Stream<Thread> stockfishThreadsSince(Set<Thread> existingThreads) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !existingThreads.contains(thread) && thread.isAlive())
                .filter(thread -> thread.getName().equals("stockfish-io")
                        || thread.getName().equals("stockfish-close"));
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void assertStopped(FakeProcess process) throws InterruptedException {
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    private static void assertNoEvaluation(StockfishService.EvalResult result) {
        assertNull(result.cp());
        assertNull(result.mate());
    }

    private static class TestService extends StockfishService {
        private final Deque<Process> processes;
        int starts;

        TestService(Process... processes) {
            this.processes = new ArrayDeque<>(Arrays.asList(processes));
        }

        @Override
        Process startProcess() throws IOException {
            starts++;
            if (processes.isEmpty()) throw new IOException("Fake process could not start");
            return processes.removeFirst();
        }
    }

    private static class FakeProcess extends Process {
        private volatile boolean alive = true;
        final CountDownLatch destroyed = new CountDownLatch(1);
        boolean failWrites;
        final ByteArrayOutputStream commands = new ByteArrayOutputStream();
        private final InputStream output;

        FakeProcess(String output, boolean exitOnEof) {
            this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public synchronized int read(byte[] bytes, int off, int len) {
                    int count = super.read(bytes, off, len);
                    if (count == -1 && exitOnEof) alive = false;
                    return count;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    if (!alive || failWrites) throw new IOException("Fake broken pipe");
                    commands.write(value);
                }
            };
        }

        @Override
        public InputStream getInputStream() { return output; }

        @Override
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        @Override
        public int waitFor() { alive = false; return 1; }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException("Fake engine is running");
            return 1;
        }

        @Override
        public boolean isAlive() { return alive; }

        @Override
        public Stream<ProcessHandle> descendants() { return Stream.empty(); }

        @Override
        public void destroy() { alive = false; destroyed.countDown(); }
    }

    /** A live engine whose read ignores interruption and only unblocks when its pipe is closed. */
    private static class StalledProcess extends FakeProcess {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch streamsClosed = new CountDownLatch(3);
        private final InputStream input;
        private final OutputStream output = new FilterOutputStream(super.getOutputStream()) {
            @Override
            public void close() throws IOException {
                super.close();
                streamsClosed.countDown();
            }
        };
        private final InputStream error = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                streamsClosed.countDown();
            }
        };

        StalledProcess(String prefix) {
            super("", false);
            input = new InputStream() {
                private final ByteArrayInputStream initial = new ByteArrayInputStream(
                        prefix.getBytes(StandardCharsets.UTF_8));
                private boolean closed;

                @Override
                public synchronized int read(byte[] bytes, int off, int len) {
                    if (initial.available() > 0) return initial.read(bytes, off, len);
                    readStarted.countDown();
                    while (!closed) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                            // Model a blocking pipe: cancelling the future alone is insufficient.
                        }
                    }
                    return -1;
                }

                @Override
                public int read() {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
                }

                @Override
                public synchronized void close() {
                    closed = true;
                    notifyAll();
                    streamsClosed.countDown();
                }
            };
        }

        @Override
        public InputStream getInputStream() { return input; }

        @Override
        public OutputStream getOutputStream() { return output; }

        @Override
        public InputStream getErrorStream() { return error; }
    }

}
