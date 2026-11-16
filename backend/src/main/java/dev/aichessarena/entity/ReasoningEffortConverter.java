package dev.aichessarena.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a {@link ReasoningEffort} as OpenRouter's lower-case value ({@code "low"}), null as NULL. */
@Converter
public class ReasoningEffortConverter implements AttributeConverter<ReasoningEffort, String> {

    @Override
    public String convertToDatabaseColumn(ReasoningEffort effort) {
        return effort != null ? effort.apiValue() : null;
    }

    @Override
    public ReasoningEffort convertToEntityAttribute(String value) {
        return ReasoningEffort.fromApiValue(value);
    }
}
