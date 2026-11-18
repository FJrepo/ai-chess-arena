package dev.aichessarena.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class TimestampsTest {

    @Test
    void nowIsTheUtcInstantAtDatabasePrecisionWhateverTheJvmTimeZone() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        try {
            Instant before = Instant.now().minusNanos(1_000);
            Instant now = Timestamps.now();
            Instant after = Instant.now();
            assertFalse(now.isBefore(before) || now.isAfter(after), now + " outside " + before + " .. " + after);
            assertEquals(0, now.getNano() % 1_000, "PostgreSQL keeps microseconds");
            assertEquals(0, new Move().createdAt.getNano() % 1_000, "record defaults use the same clock");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
