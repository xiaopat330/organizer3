package com.organizer3.web;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Lightweight actress projection for the web browse UI.
 */
@Getter
@Builder
public class ActressSummary {
    private final long id;
    private final String canonicalName;
    private final String tier;
    private final boolean favorite;
    private final boolean bookmark;
    private final String grade;
    private final boolean rejected;
    private final int titleCount;
    private final List<String> coverUrls;
    private final List<String> folderPaths;
    private final String firstAddedDate;
    private final String lastAddedDate;
    private final List<String> companies;
    private final List<String> aliases;
}
