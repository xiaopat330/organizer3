package com.organizer3.translation.repository;

import com.organizer3.translation.TranslationStrategy;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@link TranslationStrategy} rows.
 */
public interface TranslationStrategyRepository {

    /** Return all active strategies. */
    List<TranslationStrategy> findAllActive();

    /** Find a strategy by name (exact match). */
    Optional<TranslationStrategy> findByName(String name);

    /** Find a strategy by id. */
    Optional<TranslationStrategy> findById(long id);

    /**
     * Insert a new strategy. Returns the assigned id.
     * Caller is responsible for deactivating the old strategy row before inserting a replacement.
     */
    long insert(TranslationStrategy strategy);
}
