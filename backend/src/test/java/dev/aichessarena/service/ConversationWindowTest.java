package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.aichessarena.service.OpenRouterService.ChatMsg;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationWindowTest {

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
            history.add(msg("user", "turn " + i));
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
