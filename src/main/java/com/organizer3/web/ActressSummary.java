package com.organizer3.web;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Lightweight actress projection for the web browse UI.
 */
@Value
@Builder
public class ActressSummary {
    long id;
    String canonicalName;
    String stageName;
    /** Hiragana reading of the stage name, for furigana-style display. */
    String nameReading;
    String tier;
    boolean favorite;
    boolean bookmark;
    String grade;
    boolean rejected;
    /** Bayesian-derived grade from her enriched titles' javdb ratings. Null if she didn't qualify. */
    String computedGrade;
    /** Raw shrunken score that {@link #computedGrade} maps from. Null if no computed grade. */
    Double computedGradeScore;
    /** Number of enriched-rated titles that fed the computed grade. */
    Integer computedGradeN;
    int titleCount;
    /** Number of this actress's titles that have a non-null grade in the DB. */
    int gradedTitleCount;
    /** grade-display → count, only for grades present in this actress's titles. Insertion-ordered SSS→F. */
    Map<String, Integer> gradeBreakdown;
    List<String> coverUrls;
    List<String> folderPaths;
    String firstAddedDate;
    String lastAddedDate;
    List<String> companies;
    /** Flat alias list — sync name resolution aliases, with optional link to actress entity. */
    List<AliasDto> aliases;
    /** Alternate names with attribution notes — for display. */
    List<AlternateNameDto> alternateNames;

    // Enrichment / profile fields
    String activeFrom;
    String activeTo;
    /** ISO date when retirement was publicly announced, if distinct from {@code activeTo}. */
    String retirementAnnounced;
    String dateOfBirth;
    String birthplace;
    String bloodType;
    Integer heightCm;
    Integer bust;
    Integer waist;
    Integer hip;
    String cup;
    String biography;
    String legacy;

    /** Ordered studio tenures from YAML research. */
    List<StudioTenureDto> primaryStudios;
    /** Awards, honors, and poll placements from YAML research. */
    List<AwardDto> awards;

    /** Set when this actress's canonical name is stored as an alias of another actress. */
    Long primaryActressId;
    String primaryActressName;

    // Visit tracking
    int visitCount;
    String lastVisitedAt;  // ISO datetime string, null until first visit

    /** URL to the locally cached actress avatar (from javdb enrichment), or null if none. */
    String localAvatarUrl;

    // --- Nested DTOs ------------------------------------------------------

    @Value
    @Builder
    public static class AlternateNameDto {
        String name;
        String note;
    }

    @Value
    @Builder
    public static class AliasDto {
        String name;
        /** ID of the actress entity for this alias name, or null if no entity exists. */
        Long actressId;
    }

    @Value
    @Builder
    public static class StudioTenureDto {
        String name;
        String company;
        String from;   // ISO date
        String to;     // ISO date
        String role;
    }

    @Value
    @Builder
    public static class AwardDto {
        String event;
        Integer year;
        String category;
    }
}
