package dev.aichessarena.dto;

import java.math.BigDecimal;

/**
 * How often a model talked in one chat mode: of its {@code moves} (model moves in finished, non-bye games made in
 * that mode), {@code messages} came with a non-empty message. {@code talkRate} is messages / moves (null without
 * moves); {@code averageMessageLength} is the average length of those messages in characters (null without
 * messages).
 */
public record TalkativenessDto(
        long moves,
        long messages,
        BigDecimal talkRate,
        BigDecimal averageMessageLength
) {}
