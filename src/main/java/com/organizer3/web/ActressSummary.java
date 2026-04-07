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
}
