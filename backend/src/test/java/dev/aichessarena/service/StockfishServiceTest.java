package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
        assertFalse(replacement.isAlive());
    }

    @Test
    void eofWithoutProcessExitAlsoRestartsOnNextEvaluation() throws Exception {
        var eof = new FakeProcess("uciok\n", false);
        var replacement = new FakeProcess("uciok\ninfo score mate 2\nbestmove e2e4\n", false);
        var service = new TestService(eof, replacement);
        try {
            service.init();
            assertNoEvaluation(service.evaluate("invalid", 12).get(2, TimeUnit.SECONDS));
            assertFalse(eof.isAlive());
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
            assertFalse(incompleteStartup.isAlive());
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
        private boolean alive = true;
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
        public void destroy() { alive = false; }
    }

}
