package com.organizer3.translation.repository.jdbi;

import com.organizer3.translation.TranslationStrategy;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiTranslationStrategyRepository implements TranslationStrategyRepository {

    private static final RowMapper<TranslationStrategy> MAPPER = (rs, ctx) -> {
        Object tier2Obj = rs.getObject("tier2_strategy_id");
        Long tier2Id = tier2Obj != null ? ((Number) tier2Obj).longValue() : null;
        return new TranslationStrategy(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("model_id"),
                rs.getString("prompt_template"),
                rs.getString("options_json"),
                rs.getInt("is_active") != 0,
                tier2Id
        );
    };

    private final Jdbi jdbi;

    @Override
    public List<TranslationStrategy> findAllActive() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id, name, model_id, prompt_template, options_json, is_active, tier2_strategy_id " +
                              "FROM translation_strategy WHERE is_active = 1 ORDER BY id")
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public Optional<TranslationStrategy> findByName(String name) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id, name, model_id, prompt_template, options_json, is_active, tier2_strategy_id " +
                              "FROM translation_strategy WHERE name = :name")
                        .bind("name", name)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public Optional<TranslationStrategy> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id, name, model_id, prompt_template, options_json, is_active, tier2_strategy_id " +
                              "FROM translation_strategy WHERE id = :id")
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public long insert(TranslationStrategy strategy) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO translation_strategy (name, model_id, prompt_template, options_json, is_active, tier2_strategy_id) " +
                               "VALUES (:name, :modelId, :promptTemplate, :optionsJson, :isActive, :tier2StrategyId)")
                        .bind("name", strategy.name())
                        .bind("modelId", strategy.modelId())
                        .bind("promptTemplate", strategy.promptTemplate())
                        .bind("optionsJson", strategy.optionsJson())
                        .bind("isActive", strategy.isActive() ? 1 : 0)
                        .bind("tier2StrategyId", strategy.tier2StrategyId())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public void setTier2StrategyId(long strategyId, long tier2StrategyId) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE translation_strategy SET tier2_strategy_id = :tier2Id WHERE id = :id")
                        .bind("tier2Id", tier2StrategyId)
                        .bind("id", strategyId)
                        .execute()
        );
    }
}
