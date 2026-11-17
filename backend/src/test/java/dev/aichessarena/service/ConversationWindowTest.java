package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.service.OpenRouterService.ChatMsg;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConversationWindowTest {

    @ParameterizedTest
    @CsvSource({"12,4", "6,2", "5,3", "2,0", "0,0", "4,4"})
    void prefixBytesStayIdenticalUntilTheBudgetForcesAReanchor(int max, int retained) throws Exception {
        List<ChatMsg> history = new ArrayList<>(List.of(msg("system", "rules\n  unchanged ♞")));
        List<ChatMsg> previousRequest = List.of();
        ObjectMapper json = new ObjectMapper();
        int reanchors = 0;
        for (int turn = 0; turn < 40; turn++) {
            boolean reanchored = history.size() - 1 > max;
            ConversationWindow.reanchor(history, max, retained);
            assertTrue(history.size() - 1 <= max);
            if (reanchored) {
                reanchors++;
                assertTrue(history.size() - 1 <= retained);
                assertTrue(history.size() == 1 || history.get(1).startsTurn());
            }
            history.add(new ChatMsg("user", "PGN: " + turn + "\nFEN: position " + turn, true));
            if (!previousRequest.isEmpty() && !reanchored) {
                assertEquals(json.writeValueAsString(previousRequest),
                        json.writeValueAsString(history.subList(0, previousRequest.size())));
                for (int i = 0; i < previousRequest.size(); i++) {
                    assertSame(previousRequest.get(i), history.get(i), "replay the original immutable objects");
                }
            }
            if (reanchored) {
                assertTrue(history.size() < previousRequest.size() + 2);
            }
            previousRequest = List.copyOf(history);
            history.add(msg("assistant", "{ \"message\": \"♞\", \"move\": \"e4\" }\n"));
        }
        assertTrue(reanchors >= 4, "exercise several chunk boundaries");
    }

    @Test
    void exactlyTwelvePriorMessagesStayUntilTheNextTurnExceedsTheBudget() {
        List<ChatMsg> history = conversation(6);
        List<ChatMsg> original = List.copyOf(history);
        ConversationWindow.reanchor(history, 12, 4);
        assertEquals(original, history);

        history.add(new ChatMsg("user", "turn 6", true));
        history.add(msg("assistant", "answer 6"));
        ConversationWindow.reanchor(history, 12, 4);
        assertEquals(List.of("system", "turn 5", "answer 5", "turn 6", "answer 6"), contents(history));
    }

    @Test
    void aLongRetryTurnIsDroppedAsAWholeWhenItCannotFitTheRetainedBudget() {
        List<ChatMsg> history = conversation(6);
        history.add(new ChatMsg("user", "current", true));
        history.add(msg("assistant", "illegal"));
        history.add(msg("user", "retry"));
        history.add(msg("assistant", "still illegal"));
        history.add(msg("user", "retry again"));
        history.add(msg("assistant", "legal"));

        ConversationWindow.reanchor(history, 12, 4);

        assertEquals(List.of("system"), contents(history));
    }

    @Test
    void reanchorWithoutSystemAndEmptyHistoriesAreSupported() {
        List<ChatMsg> history = new ArrayList<>(conversation(8).subList(1, 17));
        ConversationWindow.reanchor(history, 12, 4);
        assertEquals(List.of("turn 6", "answer 6", "turn 7", "answer 7"), contents(history));
        List<ChatMsg> empty = new ArrayList<>();
        ConversationWindow.reanchor(empty, 12, 4);
        assertTrue(empty.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "12,-1", "4,6"})
    void rejectsInvalidBudgets(int max, int retained) {
        assertThrows(IllegalArgumentException.class, () -> ConversationWindow.reanchor(new ArrayList<>(), max, retained));
    }

    @Test
    void keepsSystemPromptRecentPriorMessagesAndTheWholeCurrentTurn() {
        List<ChatMsg> history = conversation(5);
        int turnStart = history.size();
        history.add(msg("user", "turn 5"));
        history.add(msg("assistant", "illegal"));
        history.add(msg("user", "retry 5"));

        List<ChatMsg> window = ConversationWindow.trim(history, turnStart, 4);

        assertEquals(List.of("system", "turn 3", "answer 3", "turn 4", "answer 4", "turn 5", "illegal", "retry 5"),
                contents(window));
    }

    @Test
    void priorWindowStartsAtAUserMessage() {
        List<ChatMsg> history = conversation(5);
        int turnStart = history.size();
        history.add(msg("user", "turn 5"));

        // Three prior messages would start at an assistant answer; that orphaned answer is dropped.
        List<ChatMsg> window = ConversationWindow.trim(history, turnStart, 3);

        assertEquals(List.of("system", "turn 4", "answer 4", "turn 5"), contents(window));
    }

    @Test
    void shortHistoriesAreSentUnchanged() {
        List<ChatMsg> history = conversation(2);
        int turnStart = history.size();
        history.add(msg("user", "turn 2"));

        assertEquals(contents(history), contents(ConversationWindow.trim(history, turnStart, 6)));
    }

    @Test
    void zeroPriorMessagesKeepsOnlySystemPromptAndCurrentTurn() {
        List<ChatMsg> history = conversation(3);
        int turnStart = history.size();
        history.add(msg("user", "turn 3"));

        assertEquals(List.of("system", "turn 3"), contents(ConversationWindow.trim(history, turnStart, 0)));
    }

    @Test
    void historyWithoutSystemPromptIsTrimmedToo() {
        List<ChatMsg> history = new ArrayList<>(conversation(3).subList(1, 7));
        int turnStart = history.size();
        history.add(msg("user", "turn 3"));

        assertEquals(List.of("turn 2", "answer 2", "turn 3"), contents(ConversationWindow.trim(history, turnStart, 2)));
    }

    @Test
    void emptyHistoryIsReturnedAsIs() {
        List<ChatMsg> empty = new ArrayList<>();

        assertSame(empty, ConversationWindow.trim(empty, 0, 6));
    }

    /** A system prompt followed by {@code turns} completed user/assistant exchanges. */
    private static List<ChatMsg> conversation(int turns) {
        List<ChatMsg> history = new ArrayList<>();
        history.add(msg("system", "system"));
        for (int i = 0; i < turns; i++) {
            history.add(new ChatMsg("user", "turn " + i, true));
            history.add(msg("assistant", "answer " + i));
        }
        return history;
    }

    private static ChatMsg msg(String role, String content) {
        return new ChatMsg(role, content);
    }

    private static List<String> contents(List<ChatMsg> messages) {
        return messages.stream().map(ChatMsg::content).toList();
    }
}
