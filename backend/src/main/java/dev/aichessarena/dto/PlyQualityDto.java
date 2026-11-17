package dev.aichessarena.dto;

import java.util.UUID;

/** The quality of a stored move. A null quality means the move is not judged. */
public record PlyQualityDto(int moveNumber, String color, MoveQualityDto quality, UUID moveId) {
    /** Legacy tests and callers can still identify a ply by move number and color. */
    public PlyQualityDto(int moveNumber, String color, MoveQualityDto quality) {
        this(moveNumber, color, quality, null);
    }
}
