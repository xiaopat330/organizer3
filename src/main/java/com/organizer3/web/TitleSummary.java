package com.organizer3.web;

/**
 * Lightweight projection of a title for the browse home page.
 * All fields except {@code code} may be null.
 */
public record TitleSummary(
        String code,
        String baseCode,
        String label,
        Long actressId,
        String actressName,
        String addedDate,
        String coverUrl,
        String companyName,
        String labelName
) {}
