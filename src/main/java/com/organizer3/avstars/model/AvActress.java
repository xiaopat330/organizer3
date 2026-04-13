package com.organizer3.avstars.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Western/European performer record. Maps to the {@code av_actresses} DB table.
 *
 * <p>Identity is (volumeId, folderName) — the actress folder as seen on disk.
 * IAFD enrichment fields are populated separately via {@code av resolve}.
 */
@Value
@Builder
public class AvActress {

    Long id;
    String volumeId;
    String folderName;
    @Builder.Default String stageName = "";   // defaults to folderName when not set

    // IAFD identity
    String iafdId;
    String headshotPath;
    String akaNamesJson;

    // Personal
    String gender;
    String dateOfBirth;
    String dateOfDeath;
    String birthplace;
    String nationality;
    String ethnicity;

    // Physical
    String hairColor;
    String eyeColor;
    Integer heightCm;
    Integer weightKg;
    String measurements;
    String cup;
    String shoeSize;
    String tattoos;
    String piercings;

    // Career
    Integer activeFrom;
    Integer activeTo;
    Integer directorFrom;
    Integer directorTo;
    Integer iafdTitleCount;

    // External links
    String websiteUrl;
    String socialJson;
    String platformsJson;
    String externalRefsJson;

    // Editorial
    String iafdCommentsJson;
    String awardsJson;

    // Curation
    @Builder.Default boolean favorite = false;
    @Builder.Default boolean bookmark = false;
    @Builder.Default boolean rejected = false;
    String grade;
    String notes;

    // Housekeeping
    LocalDateTime firstSeenAt;
    LocalDateTime lastScannedAt;
    LocalDateTime lastIafdSyncedAt;
    @Builder.Default int videoCount = 0;
    @Builder.Default long totalSizeBytes = 0;
}
