package dev.aichessarena.entity;

import static dev.aichessarena.entity.ReasoningEffort.HIGH;
import static dev.aichessarena.entity.ReasoningEffort.LOW;
import static dev.aichessarena.entity.ReasoningEffort.MAX;
import static dev.aichessarena.entity.ReasoningEffort.MEDIUM;
import static dev.aichessarena.entity.ReasoningEffort.MINIMAL;
import static dev.aichessarena.entity.ReasoningEffort.NONE;
import static dev.aichessarena.entity.ReasoningEffort.XHIGH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReasoningEffortTest {

    @Test
    void apiValuesAreLowerCaseAndParseCaseInsensitively() {
        assertEquals("xhigh", XHIGH.apiValue());
        assertEquals(LOW, ReasoningEffort.parse(" LOW "));
        assertEquals(MAX, ReasoningEffort.fromApiValue("Max"));
        assertNull(ReasoningEffort.fromApiValue(""));
        assertNull(ReasoningEffort.fromApiValue("ultra"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReasoningEffort.parse("ultra"));
        assertTrue(error.getMessage().contains("none, minimal, low, medium, high, xhigh, max"), error.getMessage());
    }

    @Test
    void aSupportedLevelIsKept() {
        assertEquals(LOW, ReasoningEffort.nearest(LOW, EnumSet.of(MAX, HIGH, LOW, NONE)));
    }

    @Test
    void anUnsupportedLevelBecomesTheNearestSupportedOne() {
        // Meta Muse Ember (mandatory reasoning, no "none"): none -> minimal.
        Set<ReasoningEffort> museEmber = EnumSet.of(MAX, XHIGH, HIGH, MEDIUM, LOW, MINIMAL);
        assertEquals(MINIMAL, ReasoningEffort.nearest(NONE, museEmber));
        // A model with only high, medium and low.
        Set<ReasoningEffort> threeLevels = EnumSet.of(HIGH, MEDIUM, LOW);
        assertEquals(LOW, ReasoningEffort.nearest(MINIMAL, threeLevels));
        assertEquals(LOW, ReasoningEffort.nearest(NONE, threeLevels));
        assertEquals(HIGH, ReasoningEffort.nearest(MAX, threeLevels));
        assertEquals(HIGH, ReasoningEffort.nearest(XHIGH, threeLevels));
    }

    @Test
    void aTieGoesToTheLowerLevel() {
        // Kimi K3.1 Pro: max, high, low, none. Medium is as far from low as from high.
        Set<ReasoningEffort> kimi = EnumSet.of(MAX, HIGH, LOW, NONE);
        assertEquals(LOW, ReasoningEffort.nearest(MEDIUM, kimi));
        assertEquals(HIGH, ReasoningEffort.nearest(XHIGH, kimi));
    }

    @Test
    void noneIsOnlyChosenWhenRequested() {
        // Minimal is as close to none as to low, but none would switch reasoning off.
        assertEquals(LOW, ReasoningEffort.nearest(MINIMAL, EnumSet.of(LOW, NONE)));
        assertEquals(NONE, ReasoningEffort.nearest(NONE, EnumSet.of(LOW, NONE)));
        assertNull(ReasoningEffort.nearest(LOW, EnumSet.of(NONE)));
    }

    @Test
    void nothingToMapToGivesNull() {
        assertNull(ReasoningEffort.nearest(LOW, Set.of()));
        assertNull(ReasoningEffort.nearest(null, EnumSet.of(LOW)));
    }
}
