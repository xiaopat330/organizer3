package com.organizer3.avstars.web;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Lightweight AV actress projection for the web browse UI (index grid, favorites, bookmarks).
 */
@Value
@Builder
public class AvActressSummary {
    long id;
    String stageName;
    /** URL path for the cached headshot image, e.g. {@code /api/av/headshots/uuid.jpg}. Null if not yet downloaded. */
    String headshotUrl;
    Integer activeFrom;
    Integer activeTo;
    int videoCount;
    boolean favorite;
    boolean bookmark;
    /** True when the actress has been resolved against IAFD. */
    boolean resolved;
    int visitCount;
    String lastVisitedAt;
    /** Top tag slugs for this actress (by frequency across her videos). Empty list if none tagged. */
    List<String> topTags;
}
