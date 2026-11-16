package dev.aichessarena.entity;

import java.util.Collection;
import java.util.Locale;

/**
 * How much a reasoning model may think before it answers: OpenRouter's {@code reasoning.effort}, from least to most.
 * Stored and sent in lower case ({@link #apiValue()}), as OpenRouter names them. A model may support only some
 * levels (its catalog entry lists {@code reasoning.supported_efforts}); see {@link #nearest}.
 */
public enum ReasoningEffort {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** The value OpenRouter expects and the API and database use, e.g. {@code "xhigh"}. */
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** All levels in order, as API values, for error messages. */
    public static String allowedValues() {
        StringBuilder values = new StringBuilder();
        for (ReasoningEffort effort : values()) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append(effort.apiValue());
        }
        return values.toString();
    }

    /**
     * The level for an API value, case-insensitive and trimmed.
     *
     * @throws IllegalArgumentException for null, blank or unknown values
     */
    public static ReasoningEffort parse(String value) {
        ReasoningEffort effort = fromApiValue(value);
        if (effort == null) {
            throw new IllegalArgumentException("Invalid reasoningEffort: " + value + " (must be one of "
                    + allowedValues() + ")");
        }
        return effort;
    }

    /** The level for an API value, case-insensitive and trimmed; null for null, blank or unknown values. */
    public static ReasoningEffort fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The supported level closest to {@code requested} in the order none < minimal < low < medium < high < xhigh <
     * max: the requested level itself when supported, otherwise the nearest one; on a tie the lower one, because a
     * level is usually set to rein in a model that thinks too long. {@code none} switches reasoning off rather than
     * lowering it, so it is chosen only when requested. Null when nothing is left to choose from.
     */
    public static ReasoningEffort nearest(ReasoningEffort requested, Collection<ReasoningEffort> supported) {
        if (requested == null || supported == null || supported.isEmpty()) {
            return null;
        }
        ReasoningEffort best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ReasoningEffort candidate : values()) {
            if (!supported.contains(candidate) || (candidate == NONE && requested != NONE)) {
                continue;
            }
            int distance = Math.abs(candidate.ordinal() - requested.ordinal());
            // values() runs from low to high, so the first candidate at a distance is the lower one.
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }
}
