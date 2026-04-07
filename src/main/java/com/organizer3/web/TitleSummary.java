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
    String addedDate;
    String coverUrl;
    String companyName;
    String labelName;
    /** Primary location path (first/best location). */
    String location;
    /** All known locations for this title. */
    @Builder.Default
    List<String> locations = List.of();
}
