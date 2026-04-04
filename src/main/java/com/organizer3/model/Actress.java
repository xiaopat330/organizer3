package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;

/**
 * Represents a known performer. Maps directly to the {@code actresses} DB table.
 *
 * <p>The {@code canonicalName} is the authoritative name for this person. Any alternate
 * stage names are stored separately as alias mappings and resolve back to this record.
 *
 * <p>The {@code tier} reflects how many titles she appears in and determines which
 * subfolder under {@code stars/} her content lives in.
 */
@Value
@Builder
public class Actress implements Comparable<Actress> {

    Long id;               // null for actresses not yet persisted
    String canonicalName;
    Tier tier;
    LocalDate firstSeenAt;

    /**
     * Title count thresholds that determine folder tier placement under {@code stars/}.
     */
    public enum Tier {
        LIBRARY,     // fewer than 5 titles (default)
        MINOR,       // 5–19 titles
        POPULAR,     // 20–49 titles
        SUPERSTAR,   // 50–99 titles
        GODDESS      // 100+ titles
    }

    @Override
    public int compareTo(Actress o) {
        return this.canonicalName.compareToIgnoreCase(o.canonicalName);
    }
}
