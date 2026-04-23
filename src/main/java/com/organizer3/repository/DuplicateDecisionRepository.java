package com.organizer3.repository;

import com.organizer3.model.DuplicateDecision;

import java.util.List;

public interface DuplicateDecisionRepository {

    /** Insert or update a decision. On conflict, updates only the decision value; preserves created_at. */
    void upsert(DuplicateDecision decision);

    /** All decisions that have not yet been executed (executed_at IS NULL). */
    List<DuplicateDecision> listPending();

    /** Remove one location's decision by its full primary key. No-op if not found. */
    void delete(String titleCode, String volumeId, String nasPath);
}
