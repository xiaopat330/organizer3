package com.organizer3.repository;

import com.organizer3.model.Label;

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
}
