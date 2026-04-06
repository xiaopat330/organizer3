package com.organizer3.web;

import java.util.List;

/**
 * Lightweight actress projection for the web browse UI.
 */
public record ActressSummary(
        long id,
        String canonicalName,
        String tier,
        boolean favorite,
        boolean bookmark,
        String grade,       // display string e.g. "A+", "B-", or null
        boolean rejected,
        int titleCount,
        List<String> coverUrls,
        List<String> folderPaths,
        String firstAddedDate,
        String lastAddedDate
) {}
