package dev.aichessarena.entity;

/**
 * Whether models talk to each other in a tournament. Stored per tournament (the setting) and per model move (the
 * mode that applied to that move, for the talkativeness metric).
 */
public enum ChatMode {
    /** Models are told to stay silent; anything they send anyway is dropped. */
    OFF,
    /** Models may send a message with a move (the default). Whether they do is their own choice. */
    OPTIONAL,
    /**
     * Models are asked for a short message with every move. A missing message is recorded (the move counts as
     * silent) but never rejects the move or costs an attempt.
     */
    MANDATORY;

    /** Whether messages are stored, shown and passed to the opponent. */
    public boolean allowsMessages() {
        return this != OFF;
    }

    /** The legacy {@code trashTalkEnabled} flag: false → OFF, true → OPTIONAL. */
    public static ChatMode fromTrashTalkEnabled(boolean trashTalkEnabled) {
        return trashTalkEnabled ? OPTIONAL : OFF;
    }
}
