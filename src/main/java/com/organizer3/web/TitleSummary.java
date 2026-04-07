package com.organizer3.web;

import lombok.Builder;
import lombok.Getter;

/**
 * Lightweight projection of a title for the browse home page.
 * All fields except {@code code} may be null.
 */
@Getter
@Builder
public class TitleSummary {
    private final String code;
    private final String baseCode;
    private final String label;
    private final Long actressId;
    private final String actressName;
    private final String actressTier;
    private final String addedDate;
    private final String coverUrl;
    private final String companyName;
    private final String labelName;
    private final String location;
}
