package dev.aichessarena.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Wall-clock timestamps for persisted records: UTC instants, independent of the JVM default time zone, at the
 * microsecond precision PostgreSQL stores, so a response built before a re-read shows the stored value.
 */
public final class Timestamps {
    private Timestamps() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
