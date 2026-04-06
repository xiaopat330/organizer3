package com.organizer3.repository.jdbi;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class JdbiActressRepository implements ActressRepository {

    private static final RowMapper<Actress> ACTRESS_MAPPER = (rs, ctx) ->
            Actress.builder()
                    .id(rs.getLong("id"))
                    .canonicalName(rs.getString("canonical_name"))
                    .tier(Actress.Tier.valueOf(rs.getString("tier")))
                    .favorite(rs.getInt("favorite") != 0)
                    .firstSeenAt(LocalDate.parse(rs.getString("first_seen_at")))
                    .build();

    private static final RowMapper<ActressAlias> ALIAS_MAPPER = (rs, ctx) ->
            new ActressAlias(rs.getLong("actress_id"), rs.getString("alias_name"));

    private final Jdbi jdbi;

    public JdbiActressRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<Actress> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE id = :id")
                        .bind("id", id)
                        .map(ACTRESS_MAPPER)
                        .findFirst()
        );
    }

    @Override
    public Optional<Actress> findByCanonicalName(String name) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE canonical_name = :name")
                        .bind("name", name)
                        .map(ACTRESS_MAPPER)
                        .findFirst()
        );
    }

    @Override
    public Optional<Actress> resolveByName(String name) {
        return jdbi.withHandle(h -> {
            Optional<Actress> byCanonical = h
                    .createQuery("SELECT * FROM actresses WHERE canonical_name = :name")
                    .bind("name", name)
                    .map(ACTRESS_MAPPER)
                    .findFirst();
            if (byCanonical.isPresent()) return byCanonical;

            return h.createQuery("""
                            SELECT a.* FROM actresses a
                            JOIN actress_aliases aa ON a.id = aa.actress_id
                            WHERE aa.alias_name = :name
                            """)
                    .bind("name", name)
                    .map(ACTRESS_MAPPER)
                    .findFirst();
        });
    }

    @Override
    public List<Actress> findAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses ORDER BY canonical_name")
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findByTier(Actress.Tier tier) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE tier = :tier ORDER BY canonical_name")
                        .bind("tier", tier.name())
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findFavorites() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE favorite = 1 ORDER BY canonical_name")
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public Actress save(Actress actress) {
        return jdbi.withHandle(h -> {
            if (actress.getId() == null) {
                long id = h.createUpdate("""
                                INSERT INTO actresses (canonical_name, tier, favorite, first_seen_at)
                                VALUES (:name, :tier, :favorite, :date)
                                """)
                        .bind("name", actress.getCanonicalName())
                        .bind("tier", actress.getTier().name())
                        .bind("favorite", actress.isFavorite() ? 1 : 0)
                        .bind("date", actress.getFirstSeenAt().toString())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return Actress.builder()
                        .id(id)
                        .canonicalName(actress.getCanonicalName())
                        .tier(actress.getTier())
                        .favorite(actress.isFavorite())
                        .firstSeenAt(actress.getFirstSeenAt())
                        .build();
            } else {
                h.createUpdate("""
                                UPDATE actresses
                                SET canonical_name = :name, tier = :tier, favorite = :favorite, first_seen_at = :date
                                WHERE id = :id
                                """)
                        .bind("id", actress.getId())
                        .bind("name", actress.getCanonicalName())
                        .bind("tier", actress.getTier().name())
                        .bind("favorite", actress.isFavorite() ? 1 : 0)
                        .bind("date", actress.getFirstSeenAt().toString())
                        .execute();
                return actress;
            }
        });
    }

    @Override
    public void toggleFavorite(long actressId, boolean favorite) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET favorite = :favorite WHERE id = :id")
                        .bind("favorite", favorite ? 1 : 0)
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public void updateTier(long actressId, Actress.Tier tier) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET tier = :tier WHERE id = :id")
                        .bind("tier", tier.name())
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public List<ActressAlias> findAliases(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actress_aliases WHERE actress_id = :id")
                        .bind("id", actressId)
                        .map(ALIAS_MAPPER)
                        .list()
        );
    }

    @Override
    public void saveAlias(ActressAlias alias) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT OR IGNORE INTO actress_aliases (actress_id, alias_name)
                        VALUES (:actressId, :aliasName)
                        """)
                        .bind("actressId", alias.actressId())
                        .bind("aliasName", alias.aliasName())
                        .execute()
        );
    }

    @Override
    public void deleteAlias(long actressId, String aliasName) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM actress_aliases
                        WHERE actress_id = :actressId AND alias_name = :aliasName
                        """)
                        .bind("actressId", actressId)
                        .bind("aliasName", aliasName)
                        .execute()
        );
    }

    @Override
    public void replaceAllAliases(long actressId, List<String> aliasNames) {
        jdbi.useHandle(h -> {
            h.createUpdate("DELETE FROM actress_aliases WHERE actress_id = :id")
                    .bind("id", actressId)
                    .execute();
            insertAliases(h, actressId, aliasNames);
        });
    }

    @Override
    public void importFromYaml(List<AliasYamlEntry> entries) {
        jdbi.useTransaction(h -> {
            for (AliasYamlEntry entry : entries) {
                Actress actress = h.createQuery(
                                "SELECT * FROM actresses WHERE canonical_name = :name")
                        .bind("name", entry.name())
                        .map(ACTRESS_MAPPER)
                        .findFirst()
                        .orElseGet(() -> {
                            long id = h.createUpdate("""
                                            INSERT INTO actresses (canonical_name, tier, first_seen_at)
                                            VALUES (:name, :tier, :date)
                                            """)
                                    .bind("name", entry.name())
                                    .bind("tier", Actress.Tier.LIBRARY.name())
                                    .bind("date", LocalDate.now().toString())
                                    .executeAndReturnGeneratedKeys("id")
                                    .mapTo(Long.class)
                                    .one();
                            return Actress.builder()
                                    .id(id)
                                    .canonicalName(entry.name())
                                    .tier(Actress.Tier.LIBRARY)
                                    .firstSeenAt(LocalDate.now())
                                    .build();
                        });

                h.createUpdate("DELETE FROM actress_aliases WHERE actress_id = :id")
                        .bind("id", actress.getId())
                        .execute();

                if (entry.aliases() != null) {
                    insertAliases(h, actress.getId(), entry.aliases());
                }
            }
        });
    }

    private static void insertAliases(Handle h, long actressId, List<String> aliases) {
        for (String alias : aliases) {
            h.createUpdate("""
                    INSERT INTO actress_aliases (actress_id, alias_name)
                    VALUES (:actressId, :aliasName)
                    """)
                    .bind("actressId", actressId)
                    .bind("aliasName", alias)
                    .execute();
        }
    }
}
