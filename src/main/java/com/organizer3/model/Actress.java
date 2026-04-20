package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a known performer. Maps directly to the {@code actresses} DB table.
 *
 * <p>The {@code canonicalName} is the authoritative name for this person. Any alternate
 * stage names are stored separately as alias mappings and resolve back to this record.
 *
 * <p>The {@code tier} reflects how many titles she appears in and determines which
 * subfolder under {@code stars/} her content lives in.
 *
 * <p>Profile fields ({@code biography}, {@code dateOfBirth}, etc.) are populated by
 * the {@code load actress} command from curated YAML research data, not from sync.
 */
@Value
@Builder
public class Actress implements Comparable<Actress> {

    Long id;               // null for actresses not yet persisted
    String canonicalName;
    String stageName;      // Japanese kanji/kana stage name, nullable
    Tier tier;
    boolean favorite;
    boolean bookmark;
    LocalDateTime bookmarkedAt;   // null when not bookmarked; stamped when bookmark flips on
    Grade grade;           // nullable
    boolean rejected;
    LocalDate firstSeenAt;

    // --- Enrichment fields (populated via load actress command) ---
    String nameReading;       // hiragana reading of stage name
    LocalDate dateOfBirth;
    String birthplace;
    String bloodType;
    Integer heightCm;
    Integer bust;
    Integer waist;
    Integer hip;
    String cup;
    LocalDate activeFrom;
    LocalDate activeTo;
    LocalDate retirementAnnounced;
    String biography;
    String legacy;

    /** Alternate names with attribution notes (distinct from flat aliases used for sync name resolution). */
    List<AlternateName> alternateNames;
    /** Ordered studio tenures from YAML. */
    List<StudioTenure> primaryStudios;
    /** Awards and honors from YAML. */
    List<Award> awards;

    // --- Visit tracking (populated from DB; updated when user views detail page) ---
    @Builder.Default
    int visitCount = 0;
    LocalDateTime lastVisitedAt;  // null until first visit

    /** Flags actresses that were created inline via the Title Editor and need enrichment. */
    boolean needsProfiling;

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

    /**
     * An alternate name (romanization variant, prior stage name, nickname) with an
     * attribution note. Distinct from the flat alias list, which is used for name
     * resolution during sync — these are for display and preserve provenance.
     */
    public record AlternateName(String name, String note) {}

    /**
     * One tenure in an actress's studio history — the name of the label/sublabel,
     * its parent company, the date range she was contracted, and her role (e.g.
     * "Exclusive contract actress").
     */
    public record StudioTenure(
            String name,
            String company,
            LocalDate from,
            LocalDate to,
            String role
    ) {}

    /** An award, honor, or poll placement. */
    public record Award(String event, Integer year, String category) {}

    /**
     * Personal quality rating, displayed using school-grade notation.
     * Stored in the DB as the display string (e.g. "A+", "B-").
     */
    public enum Grade {
        SSS("SSS"),
        SS("SS"),
        S("S"),
        A_PLUS("A+"),
        A("A"),
        A_MINUS("A-"),
        B_PLUS("B+"),
        B("B"),
        B_MINUS("B-"),
        C_PLUS("C+"),
        C("C"),
        C_MINUS("C-"),
        D("D"),
        F("F");

        public final String display;

        Grade(String display) {
            this.display = display;
        }

        public static Grade fromDisplay(String display) {
            for (Grade g : values()) {
                if (g.display.equals(display)) return g;
            }
            throw new IllegalArgumentException("Unknown grade: " + display);
        }
    }

    @Override
    public int compareTo(Actress o) {
        return this.canonicalName.compareToIgnoreCase(o.canonicalName);
    }
}
