package dev.aichessarena.dto;

/** Move quality of both sides of a game. */
public record GameQualitySummaryDto(SideQualityDto white, SideQualityDto black) {}
