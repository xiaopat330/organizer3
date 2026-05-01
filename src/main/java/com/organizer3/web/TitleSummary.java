package com.organizer3.web;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Lightweight projection of a title for the browse home page.
 * All fields except {@code code} may be null.
 */
@Value
@Builder
public class TitleSummary {
    String code;
    String baseCode;
    String label;
    Long actressId;
    String actressName;
    String actressTier;
    /** ISO date of birth for the primary actress, when known — used to compute age at release. */
    String actressDateOfBirth;
    String addedDate;
    String coverUrl;
    String companyName;
    String labelName;
    /** Primary location path (first/best location). */
    String location;
    /** All known locations for this title. */
    @Builder.Default
    List<String> locations = List.of();
    /** Full NAS SMB paths for this title (volumeSmbPath + relative path). */
    @Builder.Default
    List<String> nasPaths = List.of();
    /** Structured location entries pairing volumeId with the full NAS SMB path. */
    @Builder.Default
    List<LocationEntry> locationEntries = List.of();
    /** All actresses linked via the title_actresses junction table (for multi-actress titles). */
    @Builder.Default
    List<ActressEntry> actresses = List.of();

    // Enrichment fields (populated via load actress command)
    String titleEnglish;
    String titleOriginal;
    String releaseDate;
    String grade;
    /** 'enrichment', 'ai', or 'manual'. Null when ungraded. */
    String gradeSource;
    /** Raw javdb rating average (null when not enriched). */
    Double ratingAvg;
    /** Number of votes contributing to ratingAvg (null when not enriched). */
    Integer ratingCount;
    boolean favorite;
    boolean bookmark;
    String lastWatchedAt;
    int watchCount;
    int visitCount;
    String lastVisitedAt;  // ISO datetime string, null until first visit
    @Builder.Default
    List<String> tags = List.of();
    @Builder.Default
    List<EnrichmentTagEntry> enrichmentTags = List.of();

    /** Pairs a volumeId with the full NAS SMB path for one title location. */
    @Value
    @Builder
    public static class LocationEntry {
        String volumeId;
        String nasPath;
        /** Relative path within the volume (e.g. "stars/Jessica Kizaki/Jessica Kizaki (ADN-118)"). */
        String locPath;
    }

    /** One raw JavDB enrichment tag for a title card. */
    @Value
    public static class EnrichmentTagEntry {
        String name;
        String curatedAlias;
    }

    /** Lightweight actress reference for multi-actress title cards. */
    @Value
    @Builder
    public static class ActressEntry {
        long id;
        String name;
        String tier;
        /** ISO date of birth, when known — used to compute age at title release. */
        String dateOfBirth;
    }
}
