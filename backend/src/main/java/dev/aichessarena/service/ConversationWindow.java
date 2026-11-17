package dev.aichessarena.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounds each side's append-only conversation. Re-anchoring happens only between turns; requests and
 * retries inside a chunk replay the original messages verbatim. Every new turn contains the full position and PGN.
 */
final class ConversationWindow {

    static final int DEFAULT_MAX_PRIOR_MESSAGES = 12;
    static final int DEFAULT_RETAINED_MESSAGES = 4;
    static final int LEGACY_PRIOR_MESSAGES = 6;

    private ConversationWindow() {
    }

    /**
     * Before appending a new turn, keep the current anchor until its prior-message budget is exceeded.
     * Then retain at most {@code retainedMessages}, starting at a complete turn (never an answer or retry).
     * The system prompt is outside the budget. Current-turn retries are also outside this prior-message budget.
     */
    static void reanchor(List<OpenRouterService.ChatMsg> history, int maxPriorMessages, int retainedMessages) {
        if (maxPriorMessages < 0 || retainedMessages < 0 || retainedMessages > maxPriorMessages) {
            throw new IllegalArgumentException("Prompt history requires 0 <= retained messages <= max prior messages");
        }
        int base = !history.isEmpty() && "system".equals(history.getFirst().role()) ? 1 : 0;
        if (history.size() - base <= maxPriorMessages) {
            return;
        }
        int anchor = Math.max(base, history.size() - retainedMessages);
        while (anchor < history.size() && !history.get(anchor).startsTurn()) {
            anchor++;
        }
        history.subList(base, anchor).clear();
    }

    /**
     * Keeps the system prompt, at most {@code maxPriorMessages} messages before {@code currentTurnStart}
     * (starting at a user message), and everything from the current turn on, including its retries.
     */
    static List<OpenRouterService.ChatMsg> trim(List<OpenRouterService.ChatMsg> history, int currentTurnStart,
                                                int maxPriorMessages) {
        if (history.isEmpty()) {
            return history;
        }
        boolean hasSystem = "system".equals(history.getFirst().role());
        int base = hasSystem ? 1 : 0;
        int turnStart = Math.max(base, Math.min(currentTurnStart, history.size()));
        int priorStart = Math.max(base, turnStart - Math.max(0, maxPriorMessages));
        while (priorStart < turnStart && !"user".equals(history.get(priorStart).role())) {
            priorStart++;
        }
        List<OpenRouterService.ChatMsg> window = new ArrayList<>(history.size() - priorStart + base);
        if (hasSystem) {
            window.add(history.getFirst());
        }
        window.addAll(history.subList(priorStart, history.size()));
        return window;
    }
}
