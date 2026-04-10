package com.organizer3.repository.jdbi;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class JdbiActressRepository implements ActressRepository {

    private static final RowMapper<Actress> ACTRESS_MAPPER = (rs, ctx) -> {
        String gradeStr = rs.getString("grade");
        String dobStr = rs.getString("date_of_birth");
        String activeFromStr = rs.getString("active_from");
        String activeToStr = rs.getString("active_to");
        String heightStr = rs.getString("height_cm");
        String bustStr = rs.getString("bust");
        String waistStr = rs.getString("waist");
        String hipStr = rs.getString("hip");
        String lastVisitedStr = rs.getString("last_visited_at");
        return Actress.builder()
                .id(rs.getLong("id"))
                .canonicalName(rs.getString("canonical_name"))
                .stageName(rs.getString("stage_name"))
                .tier(Actress.Tier.valueOf(rs.getString("tier")))
                .favorite(rs.getInt("favorite") != 0)
                .bookmark(rs.getInt("bookmark") != 0)
                .grade(gradeStr != null ? Actress.Grade.fromDisplay(gradeStr) : null)
                .rejected(rs.getInt("rejected") != 0)
                .firstSeenAt(LocalDate.parse(rs.getString("first_seen_at")))
                .dateOfBirth(dobStr != null ? LocalDate.parse(dobStr) : null)
                .birthplace(rs.getString("birthplace"))
                .bloodType(rs.getString("blood_type"))
                .heightCm(heightStr != null ? Integer.parseInt(heightStr) : null)
                .bust(bustStr != null ? Integer.parseInt(bustStr) : null)
                .waist(waistStr != null ? Integer.parseInt(waistStr) : null)
                .hip(hipStr != null ? Integer.parseInt(hipStr) : null)
                .cup(rs.getString("cup"))
                .activeFrom(activeFromStr != null ? LocalDate.parse(activeFromStr) : null)
                .activeTo(activeToStr != null ? LocalDate.parse(activeToStr) : null)
                .biography(rs.getString("biography"))
                .legacy(rs.getString("legacy"))
                .visitCount(rs.getInt("visit_count"))
                .lastVisitedAt(lastVisitedStr != null ? LocalDateTime.parse(lastVisitedStr) : null)
                .build();
    };

    private static final RowMapper<ActressAlias> ALIAS_MAPPER = (rs, ctx) ->
            new ActressAlias(rs.getLong("actress_id"), rs.getString("alias_name"));

    private final Jdbi jdbi;

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
    public List<Actress> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        return jdbi.withHandle(h -> {
            var query = h.createQuery("SELECT * FROM actresses WHERE id IN (" + placeholders + ")");
            for (int i = 0; i < ids.size(); i++) query.bind(i, ids.get(i));
            return query.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public Optional<Actress> findByCanonicalName(String name) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE canonical_name = :name COLLATE NOCASE")
                        .bind("name", name)
                        .map(ACTRESS_MAPPER)
                        .findFirst()
        );
    }

    @Override
    public Optional<Actress> resolveByName(String name) {
        return jdbi.withHandle(h -> {
            Optional<Actress> byCanonical = h
                    .createQuery("SELECT * FROM actresses WHERE canonical_name = :name COLLATE NOCASE")
                    .bind("name", name)
                    .map(ACTRESS_MAPPER)
                    .findFirst();
            if (byCanonical.isPresent()) return byCanonical;

            return h.createQuery("""
                            SELECT a.* FROM actresses a
                            JOIN actress_aliases aa ON a.id = aa.actress_id
                            WHERE aa.alias_name = :name COLLATE NOCASE
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
    public List<Actress> searchByNamePrefix(String prefix) {
        String lower = prefix.toLowerCase();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM actresses
                        WHERE LOWER(canonical_name) LIKE :startsWith
                           OR LOWER(canonical_name) LIKE :wordStartsWith
                        ORDER BY canonical_name
                        """)
                        .bind("startsWith", lower + "%")
                        .bind("wordStartsWith", "% " + lower + "%")
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> searchByNamePrefixPaged(String prefix, int limit, int offset) {
        String trimmed = prefix.trim().toLowerCase();
        // Compound form: "<first> <second>" → first name must start with <first>
        //                 AND some later name token must start with <second>.
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace > 0 && firstSpace < trimmed.length() - 1) {
            String first  = trimmed.substring(0, firstSpace).trim();
            String second = trimmed.substring(firstSpace + 1).trim();
            if (!first.isEmpty() && !second.isEmpty()) {
                return jdbi.withHandle(h ->
                        h.createQuery("""
                                SELECT * FROM actresses
                                WHERE LOWER(canonical_name) LIKE :first
                                  AND LOWER(canonical_name) LIKE :second
                                ORDER BY favorite DESC, bookmark DESC, canonical_name
                                LIMIT :limit OFFSET :offset
                                """)
                                .bind("first",  first + "%")
                                .bind("second", "% " + second + "%")
                                .bind("limit", limit)
                                .bind("offset", offset)
                                .map(ACTRESS_MAPPER)
                                .list()
                );
            }
        }
        // Single-token form: first-name-starts-with OR any-later-word-starts-with.
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM actresses
                        WHERE LOWER(canonical_name) LIKE :startsWith
                           OR LOWER(canonical_name) LIKE :wordStartsWith
                        ORDER BY favorite DESC, bookmark DESC, canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("startsWith", trimmed + "%")
                        .bind("wordStartsWith", "% " + trimmed + "%")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findByFirstNamePrefix(String prefix) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM actresses
                        WHERE LOWER(canonical_name) LIKE :prefix
                        ORDER BY canonical_name
                        """)
                        .bind("prefix", prefix.toLowerCase() + "%")
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findByFirstNamePrefixPaged(String prefix, Actress.Tier tier, int limit, int offset) {
        return jdbi.withHandle(h -> {
            if (tier != null) {
                return h.createQuery("""
                        SELECT * FROM actresses
                        WHERE LOWER(canonical_name) LIKE :prefix AND tier = :tier
                        ORDER BY canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("prefix", prefix.toLowerCase() + "%")
                        .bind("tier", tier.name())
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER).list();
            } else {
                return h.createQuery("""
                        SELECT * FROM actresses
                        WHERE LOWER(canonical_name) LIKE :prefix
                        ORDER BY canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("prefix", prefix.toLowerCase() + "%")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER).list();
            }
        });
    }

    @Override
    public Map<String, Integer> countByFirstNamePrefixGroupedByTier(String prefix) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tier, COUNT(*) AS cnt FROM actresses
                        WHERE LOWER(canonical_name) LIKE :prefix
                        GROUP BY tier
                        """)
                        .bind("prefix", prefix.toLowerCase() + "%")
                        .<Map.Entry<String, Integer>>map((rs, ctx) ->
                                Map.entry(rs.getString("tier"), rs.getInt("cnt")))
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
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
    public List<Actress> findByTierPaged(Actress.Tier tier, int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE tier = :tier ORDER BY canonical_name LIMIT :limit OFFSET :offset")
                        .bind("tier", tier.name())
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findAllPaged(int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses ORDER BY canonical_name LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findFavoritesPaged(int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE favorite = 1 ORDER BY canonical_name LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findBookmarksPaged(int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE bookmark = 1 ORDER BY canonical_name LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findLastVisited(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE visit_count > 0 ORDER BY last_visited_at DESC LIMIT :limit")
                        .bind("limit", limit)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findMostVisited(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses WHERE visit_count > 0 ORDER BY visit_count DESC, last_visited_at DESC LIMIT :limit")
                        .bind("limit", limit)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findByVolumeIds(List<String> volumeIds) {
        if (volumeIds == null || volumeIds.isEmpty()) return List.of();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT a.* FROM actresses a
                        JOIN titles t ON t.actress_id = a.id
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE tl.volume_id IN (<volumeIds>)
                        ORDER BY a.canonical_name
                        """)
                        .bindList("volumeIds", volumeIds)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findByVolumeIdsPaged(List<String> volumeIds, int limit, int offset) {
        if (volumeIds == null || volumeIds.isEmpty()) return List.of();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT a.* FROM actresses a
                        JOIN titles t ON t.actress_id = a.id
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE tl.volume_id IN (<volumeIds>)
                        ORDER BY a.canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bindList("volumeIds", volumeIds)
                        .bind("limit", limit)
                        .bind("offset", offset)
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
    public List<Actress> findRandom(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM actresses ORDER BY RANDOM() LIMIT :limit")
                        .bind("limit", limit)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public Actress save(Actress actress) {
        return jdbi.withHandle(h -> {
            String gradeStr = actress.getGrade() != null ? actress.getGrade().display : null;
            if (actress.getId() == null) {
                long id = h.createUpdate("""
                                INSERT INTO actresses (canonical_name, stage_name, tier, favorite, bookmark, grade, rejected, first_seen_at)
                                VALUES (:name, :stageName, :tier, :favorite, :bookmark, :grade, :rejected, :date)
                                """)
                        .bind("name", actress.getCanonicalName())
                        .bind("stageName", actress.getStageName())
                        .bind("tier", actress.getTier().name())
                        .bind("favorite", actress.isFavorite() ? 1 : 0)
                        .bind("bookmark", actress.isBookmark() ? 1 : 0)
                        .bind("grade", gradeStr)
                        .bind("rejected", actress.isRejected() ? 1 : 0)
                        .bind("date", actress.getFirstSeenAt().toString())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return Actress.builder()
                        .id(id)
                        .canonicalName(actress.getCanonicalName())
                        .stageName(actress.getStageName())
                        .tier(actress.getTier())
                        .favorite(actress.isFavorite())
                        .bookmark(actress.isBookmark())
                        .grade(actress.getGrade())
                        .rejected(actress.isRejected())
                        .firstSeenAt(actress.getFirstSeenAt())
                        .build();
            } else {
                h.createUpdate("""
                                UPDATE actresses
                                SET canonical_name = :name, stage_name = :stageName, tier = :tier,
                                    favorite = :favorite, bookmark = :bookmark,
                                    grade = :grade, rejected = :rejected,
                                    first_seen_at = :date
                                WHERE id = :id
                                """)
                        .bind("id", actress.getId())
                        .bind("name", actress.getCanonicalName())
                        .bind("stageName", actress.getStageName())
                        .bind("tier", actress.getTier().name())
                        .bind("favorite", actress.isFavorite() ? 1 : 0)
                        .bind("bookmark", actress.isBookmark() ? 1 : 0)
                        .bind("grade", gradeStr)
                        .bind("rejected", actress.isRejected() ? 1 : 0)
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
    public void toggleBookmark(long actressId, boolean bookmark) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET bookmark = :bookmark WHERE id = :id")
                        .bind("bookmark", bookmark ? 1 : 0)
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public void setGrade(long actressId, Actress.Grade grade) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET grade = :grade WHERE id = :id")
                        .bind("grade", grade != null ? grade.display : null)
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public void toggleRejected(long actressId, boolean rejected) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET rejected = :rejected WHERE id = :id")
                        .bind("rejected", rejected ? 1 : 0)
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public void recordVisit(long actressId) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET visit_count = visit_count + 1, " +
                                "last_visited_at = :now WHERE id = :id")
                        .bind("now", LocalDateTime.now().toString())
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public void setFlags(long actressId, boolean favorite, boolean bookmark, boolean rejected) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET favorite = :favorite, " +
                                "bookmark = :bookmark, rejected = :rejected WHERE id = :id")
                        .bind("favorite", favorite ? 1 : 0)
                        .bind("bookmark", bookmark ? 1 : 0)
                        .bind("rejected", rejected ? 1 : 0)
                        .bind("id", actressId)
                        .execute()
        );
    }

    @Override
    public void updateProfile(long actressId, String stageName, LocalDate dateOfBirth,
                              String birthplace, String bloodType, Integer heightCm,
                              Integer bust, Integer waist, Integer hip, String cup,
                              LocalDate activeFrom, LocalDate activeTo,
                              String biography, String legacy) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE actresses SET
                            stage_name = :stageName,
                            date_of_birth = :dateOfBirth,
                            birthplace = :birthplace,
                            blood_type = :bloodType,
                            height_cm = :heightCm,
                            bust = :bust,
                            waist = :waist,
                            hip = :hip,
                            cup = :cup,
                            active_from = :activeFrom,
                            active_to = :activeTo,
                            biography = :biography,
                            legacy = :legacy
                        WHERE id = :id
                        """)
                        .bind("id", actressId)
                        .bind("stageName", stageName)
                        .bind("dateOfBirth", dateOfBirth != null ? dateOfBirth.toString() : null)
                        .bind("birthplace", birthplace)
                        .bind("bloodType", bloodType)
                        .bind("heightCm", heightCm)
                        .bind("bust", bust)
                        .bind("waist", waist)
                        .bind("hip", hip)
                        .bind("cup", cup)
                        .bind("activeFrom", activeFrom != null ? activeFrom.toString() : null)
                        .bind("activeTo", activeTo != null ? activeTo.toString() : null)
                        .bind("biography", biography)
                        .bind("legacy", legacy)
                        .execute()
        );
    }

    @Override
    public void setStageName(long actressId, String stageName) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE actresses SET stage_name = :stageName WHERE id = :id")
                        .bind("stageName", stageName)
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
    public int recalcTiers() {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE actresses SET tier = CASE
                          WHEN (SELECT COUNT(DISTINCT title_id) FROM title_actresses WHERE actress_id = actresses.id) >= 100 THEN 'GODDESS'
                          WHEN (SELECT COUNT(DISTINCT title_id) FROM title_actresses WHERE actress_id = actresses.id) >= 50  THEN 'SUPERSTAR'
                          WHEN (SELECT COUNT(DISTINCT title_id) FROM title_actresses WHERE actress_id = actresses.id) >= 20  THEN 'POPULAR'
                          WHEN (SELECT COUNT(DISTINCT title_id) FROM title_actresses WHERE actress_id = actresses.id) >= 5   THEN 'MINOR'
                          ELSE 'LIBRARY'
                        END
                        """)
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
