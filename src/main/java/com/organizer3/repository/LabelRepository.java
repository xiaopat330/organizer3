package com.organizer3.repository;

import com.organizer3.model.Label;

import java.util.List;
import java.util.Map;

/**
 * Read-only access to the JAV label reference data.
 */
public interface LabelRepository {

    /**
     * Returns all labels keyed by their code (uppercased).
     * Intended for bulk lookups when rendering title lists.
     */
    Map<String, Label> findAllAsMap();

    /** Lightweight projection for federated search label results. */
    record LabelSearchResult(String code, String labelName, String company) {}

    /**
     * Search labels by code or label_name.
     * If {@code startsWith} is true, matches prefixes; otherwise uses contains matching.
     */
    List<LabelSearchResult> searchLabels(String query, boolean startsWith, int limit);

    /**
     * Search companies (distinct company names from the labels table).
     * If {@code startsWith} is true, matches prefixes; otherwise uses contains matching.
     */
    List<String> searchCompanies(String query, boolean startsWith, int limit);
}
