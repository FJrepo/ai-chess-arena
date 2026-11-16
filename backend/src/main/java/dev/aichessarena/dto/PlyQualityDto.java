package dev.aichessarena.dto;

/** The quality of the move identified by move number and color; {@code quality} is null when it is not judged. */
public record PlyQualityDto(int moveNumber, String color, MoveQualityDto quality) {}
