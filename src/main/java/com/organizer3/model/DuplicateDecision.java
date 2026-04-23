package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

/**
 * A user's triage decision for one location of a duplicate title.
 * Primary key: (titleCode, volumeId, nasPath).
 * {@code executedAt} is null until Phase 2C when the decision is acted on.
 */
@Value
@Builder
public class DuplicateDecision {
    String titleCode;
    String volumeId;
    String nasPath;
    String decision;   // KEEP | TRASH | VARIANT
    String createdAt;
    String executedAt; // nullable
}
