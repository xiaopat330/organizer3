package com.organizer3.avstars.web;

import lombok.Builder;
import lombok.Value;

/**
 * Full actress profile returned by {@code GET /api/av/actresses/:id}.
 * All IAFD-sourced fields are nullable — the UI degrades gracefully for unresolved actresses.
 */
@Value
@Builder
public class AvActressDetail {
    long id;
    String stageName;
    String folderName;
    String headshotUrl;
    boolean resolved;   // iafd_id is set

    // Career
    Integer activeFrom;
    Integer activeTo;
    String iafdId;

    // Personal (nullable — IAFD only)
    String dateOfBirth;
    String birthplace;
    String nationality;
    String ethnicity;

    // Physical (nullable — IAFD only)
    String hairColor;
    String eyeColor;
    Integer heightCm;
    Integer weightKg;
    String measurements;
    String cup;
    String tattoos;
    String piercings;

    // External links
    String websiteUrl;

    // Editorial
    String notes;
    String grade;

    // Curation flags
    boolean favorite;
    boolean bookmark;
    boolean rejected;

    // Library stats
    int videoCount;
    long totalSizeBytes;

    // Visit tracking
    int visitCount;
    String lastVisitedAt;
}
