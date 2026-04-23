package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

/**
 * A detected pair of title rows that may represent the same physical title.
 * Primary key: (titleCodeA, titleCodeB) — always stored in lexicographic order.
 * {@code decision} is null until the user acts; {@code winnerCode} is set only when
 * decision = MERGE to record which title row survives.
 */
@Value
@Builder
public class MergeCandidate {
    long id;
    String titleCodeA;
    String titleCodeB;
    /** Detection tier: {@code code-normalization} | {@code variant-suffix} */
    String confidence;
    String detectedAt;
    /** Null until decided: MERGE | DISMISS */
    String decision;
    String decidedAt;
    /** Non-null when decision = MERGE: the code of the surviving title row. */
    String winnerCode;
    /** Stamped after the merge transaction completes; null until then. */
    String executedAt;
}
