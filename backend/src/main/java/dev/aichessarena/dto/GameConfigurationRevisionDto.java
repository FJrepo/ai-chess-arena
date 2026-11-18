package dev.aichessarena.dto;

import dev.aichessarena.entity.GameConfiguration;

public record GameConfigurationRevisionDto(int revision, GameConfiguration settings) {}
