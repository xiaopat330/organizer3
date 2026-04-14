package com.organizer3.avstars.repository;

import com.organizer3.avstars.model.AvTagDefinition;

import java.util.List;
import java.util.Optional;

public interface AvTagDefinitionRepository {
    List<AvTagDefinition> findAll();
    Optional<AvTagDefinition> findBySlug(String slug);
    void upsert(AvTagDefinition def);
    void deleteAll();
}
