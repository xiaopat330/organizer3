package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvTagDefinition;
import com.organizer3.avstars.repository.AvTagDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiAvTagDefinitionRepository implements AvTagDefinitionRepository {

    private final Jdbi jdbi;

    private static final RowMapper<AvTagDefinition> MAPPER = new RowMapper<>() {
        @Override
        public AvTagDefinition map(ResultSet rs, StatementContext ctx) throws SQLException {
            return AvTagDefinition.builder()
                    .slug(rs.getString("slug"))
                    .displayName(rs.getString("display_name"))
                    .category(rs.getString("category"))
                    .aliasesJson(rs.getString("aliases_json"))
                    .build();
        }
    };

    @Override
    public List<AvTagDefinition> findAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_tag_definitions ORDER BY slug")
                        .map(MAPPER)
                        .list());
    }

    @Override
    public Optional<AvTagDefinition> findBySlug(String slug) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_tag_definitions WHERE slug = :slug")
                        .bind("slug", slug)
                        .map(MAPPER)
                        .findFirst());
    }

    @Override
    public void upsert(AvTagDefinition def) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO av_tag_definitions (slug, display_name, category, aliases_json)
                        VALUES (:slug, :displayName, :category, :aliasesJson)
                        ON CONFLICT(slug) DO UPDATE SET
                            display_name = excluded.display_name,
                            category     = excluded.category,
                            aliases_json = excluded.aliases_json""")
                        .bind("slug",        def.getSlug())
                        .bind("displayName", def.getDisplayName())
                        .bind("category",    def.getCategory())
                        .bind("aliasesJson", def.getAliasesJson())
                        .execute());
    }

    @Override
    public void deleteAll() {
        jdbi.useHandle(h -> h.execute("DELETE FROM av_tag_definitions"));
    }
}
