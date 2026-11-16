package dev.aichessarena.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounds the conversation sent to a model. Each turn prompt is self-contained (PGN, FEN, legal moves), so
 * older exchanges add cost and context length without adding information.
 */
final class ConversationWindow {

    static final int DEFAULT_PRIOR_MESSAGES = 6;

    private ConversationWindow() {
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
