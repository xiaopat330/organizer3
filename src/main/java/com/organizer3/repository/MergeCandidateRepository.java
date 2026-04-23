package com.organizer3.repository;

import com.organizer3.model.MergeCandidate;

import java.util.List;
import java.util.Optional;

public interface MergeCandidateRepository {

    /**
     * Insert a candidate pair. If the pair already exists and has been dismissed,
     * leaves it alone. If undecided, leaves it alone (re-detection is idempotent).
     * Codes are stored in lexicographic order regardless of the argument order.
     */
    void insertIfAbsent(String codeA, String codeB, String confidence, String detectedAt);

    /** All candidates without a decision yet, ordered by confidence then code pair. */
    List<MergeCandidate> listPending();

    /** All candidates where decision = MERGE and executed_at is not yet stamped. */
    List<MergeCandidate> listPendingMerge();

    /** Find a specific candidate by its two codes (order-insensitive). */
    Optional<MergeCandidate> find(String codeA, String codeB);

    /**
     * Record a user decision (MERGE or DISMISS) on a candidate.
     * For MERGE, {@code winnerCode} must be one of the two codes; for DISMISS it is ignored.
     * No-op if the candidate does not exist.
     */
    void decide(long id, String decision, String winnerCode, String decidedAt);

    /**
     * Stamp executed_at on a MERGE candidate after the merge transaction completes.
     * No-op if not found.
     */
    void markExecuted(long id, String executedAt);

    /** Remove all undecided candidates — used at the start of a fresh detection scan. */
    void deleteUndecided();
}
