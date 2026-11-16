package dev.aichessarena.dto;

/**
 * How a game ended by resignation. {@code color} resigned instead of playing move {@code moveNumber}.
 * {@code winChance} is the resigning side's winning chance (0–1) in that position from Stockfish's evaluation of the
 * last move, null while unknown; {@code notLosing} is true when it was at least 35%. {@code lastWords} is the message
 * sent with the resignation, {@code opponentMessage} the opponent's most recent chat message before it (from move
 * {@code opponentMessageMoveNumber}); each null when there is none.
 */
public record ResignationDto(
        String color,
        int moveNumber,
        Double winChance,
        boolean notLosing,
        String lastWords,
        String opponentMessage,
        Integer opponentMessageMoveNumber
) {}
