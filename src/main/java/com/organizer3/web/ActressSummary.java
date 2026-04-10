package com.organizer3.web;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Lightweight actress projection for the web browse UI.
 */
@Value
@Builder
public class ActressSummary {
    long id;
    String canonicalName;
    String stageName;
    String tier;
    boolean favorite;
    boolean bookmark;
    String grade;
    boolean rejected;
    int titleCount;
    List<String> coverUrls;
    List<String> folderPaths;
    String firstAddedDate;
    String lastAddedDate;
    List<String> companies;
    List<String> aliases;

    // Enrichment / profile fields
    String activeFrom;
    String activeTo;
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

    // Visit tracking
    int visitCount;
    String lastVisitedAt;  // ISO datetime string, null until first visit
}
