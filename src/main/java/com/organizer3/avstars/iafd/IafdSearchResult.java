package com.organizer3.avstars.iafd;

import java.util.List;

/**
 * One row from an IAFD search results page.
 *
 * <p>Populated by {@link IafdSearchParser} before the user selects a candidate.
 * The {@code uuid} is used to fetch the full profile via {@link IafdClient#fetchProfile}.
 */
public record IafdSearchResult(
        String uuid,
        String name,
        List<String> akas,
        Integer activeFrom,
        Integer activeTo,
        Integer titleCount,
        String headshotUrl
) {}
