package com.organizer3.repository.jdbi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class JdbiActressRepository implements ActressRepository {

    /** Shared Jackson mapper for (de)serializing list columns. Uses JSR-310 so
     *  LocalDate fields on StudioTenure/etc. round-trip as ISO strings. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final TypeReference<List<Actress.AlternateName>> ALT_NAMES_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<Actress.StudioTenure>> STUDIOS_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<Actress.Award>> AWARDS_TYPE =
            new TypeReference<>() {};

    private static final RowMapper<Actress> ACTRESS_MAPPER = (rs, ctx) -> {
        String gradeStr = rs.getString("grade");
        String dobStr = rs.getString("date_of_birth");
        String activeFromStr = rs.getString("active_from");
        String activeToStr = rs.getString("active_to");
        String retirementStr = rs.getString("retirement_announced");
        String heightStr = rs.getString("height_cm");
        String bustStr = rs.getString("bust");
        String waistStr = rs.getString("waist");
        String hipStr = rs.getString("hip");
        String lastVisitedStr = rs.getString("last_visited_at");
        String bookmarkedAtStr = rs.getString("bookmarked_at");
        return Actress.builder()
                .id(rs.getLong("id"))
                .canonicalName(rs.getString("canonical_name"))
                .stageName(rs.getString("stage_name"))
                .nameReading(rs.getString("name_reading"))
                .tier(Actress.Tier.valueOf(rs.getString("tier")))
                .favorite(rs.getInt("favorite") != 0)
                .bookmark(rs.getInt("bookmark") != 0)
                .bookmarkedAt(bookmarkedAtStr != null ? LocalDateTime.parse(bookmarkedAtStr) : null)
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
                .retirementAnnounced(retirementStr != null ? LocalDate.parse(retirementStr) : null)
                .biography(rs.getString("biography"))
                .legacy(rs.getString("legacy"))
                .alternateNames(readJson(rs.getString("alternate_names_json"), ALT_NAMES_TYPE))
                .primaryStudios(readJson(rs.getString("primary_studios_json"), STUDIOS_TYPE))
                .awards(readJson(rs.getString("awards_json"), AWARDS_TYPE))
                .visitCount(rs.getInt("visit_count"))
                .lastVisitedAt(lastVisitedStr != null ? LocalDateTime.parse(lastVisitedStr) : null)
                .build();
    };

    private static <T> List<T> readJson(String raw, TypeReference<List<T>> type) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<T> v = JSON.readValue(raw, type);
            return v != null ? v : List.of();
        } catch (Exception e) {
            log.warn("Failed to parse JSON column: {}", e.getMessage());
            return List.of();
        }
    }

    private static String writeJson(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list && list.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize JSON column: {}", e.getMessage());
            return null;
        }
    }

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
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM actresses
                        WHERE canonical_name LIKE :startsWith COLLATE NOCASE
                           OR canonical_name LIKE :wordStartsWith COLLATE NOCASE
                        ORDER BY canonical_name
                        """)
                        .bind("startsWith", prefix + "%")
                        .bind("wordStartsWith", "% " + prefix + "%")
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
                                WHERE canonical_name LIKE :first COLLATE NOCASE
                                  AND canonical_name LIKE :second COLLATE NOCASE
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
                        WHERE canonical_name LIKE :startsWith COLLATE NOCASE
                           OR canonical_name LIKE :wordStartsWith COLLATE NOCASE
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
                        WHERE canonical_name LIKE :prefix COLLATE NOCASE
                        ORDER BY canonical_name
                        """)
                        .bind("prefix", prefix + "%")
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
                        WHERE canonical_name LIKE :prefix COLLATE NOCASE AND tier = :tier
                        ORDER BY canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("prefix", prefix + "%")
                        .bind("tier", tier.name())
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER).list();
            } else {
                return h.createQuery("""
                        SELECT * FROM actresses
                        WHERE canonical_name LIKE :prefix COLLATE NOCASE
                        ORDER BY canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("prefix", prefix + "%")
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
                        WHERE canonical_name LIKE :prefix COLLATE NOCASE
                        GROUP BY tier
                        """)
                        .bind("prefix", prefix + "%")
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
    public List<Actress> findByTierAndCompaniesPaged(Actress.Tier tier, List<String> companies, int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.* FROM actresses a
                        WHERE a.rejected = 0
                          AND a.tier = :tier
                          AND EXISTS (
                            SELECT 1 FROM actress_companies ac
                            WHERE ac.actress_id = a.id
                              AND ac.company IN (<companies>)
                          )
                        ORDER BY canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("tier", tier.name())
                        .bindList("companies", companies)
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
                        SELECT a.* FROM actresses a
                        WHERE EXISTS (
                            SELECT 1 FROM titles t
                            JOIN title_locations tl ON tl.title_id = t.id
                            WHERE t.actress_id = a.id
                              AND tl.volume_id IN (<volumeIds>)
                        )
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
                        SELECT a.* FROM actresses a
                        WHERE EXISTS (
                            SELECT 1 FROM titles t
                            JOIN title_locations tl ON tl.title_id = t.id
                            WHERE t.actress_id = a.id
                              AND tl.volume_id IN (<volumeIds>)
                        )
                        ORDER BY (a.favorite + a.bookmark) DESC, a.canonical_name
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
    public List<Actress> findByVolumesAndCompaniesPaged(List<String> volumeIds, List<String> companies,
                                                        int limit, int offset) {
        if (volumeIds == null || volumeIds.isEmpty()) return List.of();
        if (companies == null || companies.isEmpty()) return List.of();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.* FROM actresses a
                        WHERE a.rejected = 0
                          AND EXISTS (
                            SELECT 1 FROM titles t
                            JOIN title_locations tl ON tl.title_id = t.id
                            WHERE t.actress_id = a.id
                              AND tl.volume_id IN (<volumeIds>)
                          )
                          AND EXISTS (
                            SELECT 1 FROM actress_companies ac
                            WHERE ac.actress_id = a.id
                              AND ac.company IN (<companies>)
                          )
                        ORDER BY (a.favorite + a.bookmark) DESC, a.canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bindList("volumeIds", volumeIds)
                        .bindList("companies", companies)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findByStudioGroupCompaniesPaged(List<String> companies, int limit, int offset) {
        if (companies == null || companies.isEmpty()) return List.of();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.* FROM actresses a
                        WHERE a.rejected = 0
                          AND EXISTS (
                            SELECT 1 FROM actress_companies ac
                            WHERE ac.actress_id = a.id
                              AND ac.company IN (<companies>)
                          )
                        ORDER BY CASE a.tier
                                     WHEN 'GODDESS'   THEN 0
                                     WHEN 'SUPERSTAR' THEN 1
                                     WHEN 'POPULAR'   THEN 2
                                     WHEN 'MINOR'     THEN 3
                                     ELSE 4
                                 END,
                                 a.canonical_name
                        LIMIT :limit OFFSET :offset
                        """)
                        .bindList("companies", companies)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public long countByStudioGroupCompanies(List<String> companies) {
        if (companies == null || companies.isEmpty()) return 0L;
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM actresses a
                        WHERE a.rejected = 0
                          AND EXISTS (
                            SELECT 1 FROM actress_companies ac
                            WHERE ac.actress_id = a.id
                              AND ac.company IN (<companies>)
                          )
                        """)
                        .bindList("companies", companies)
                        .mapTo(Long.class)
                        .one()
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
                h.createUpdate(
                        "UPDATE actresses SET bookmark = :bookmark, " +
                                "bookmarked_at = CASE WHEN :bookmark THEN :now ELSE NULL END " +
                                "WHERE id = :id")
                        .bind("bookmark", bookmark)
                        .bind("now", LocalDateTime.now().toString())
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
                                "bookmark = :bookmark, " +
                                "bookmarked_at = CASE WHEN :bookmark THEN :now ELSE NULL END, " +
                                "rejected = :rejected WHERE id = :id")
                        .bind("favorite", favorite ? 1 : 0)
                        .bind("bookmark", bookmark)
                        .bind("now", LocalDateTime.now().toString())
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
    public void updateExtendedProfile(long actressId, String nameReading,
                                      LocalDate retirementAnnounced,
                                      List<Actress.AlternateName> alternateNames,
                                      List<Actress.StudioTenure> primaryStudios,
                                      List<Actress.Award> awards) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE actresses SET
                            name_reading = :nameReading,
                            retirement_announced = :retirement,
                            alternate_names_json = :altNames,
                            primary_studios_json = :studios,
                            awards_json = :awards
                        WHERE id = :id
                        """)
                        .bind("id", actressId)
                        .bind("nameReading", nameReading)
                        .bind("retirement", retirementAnnounced != null ? retirementAnnounced.toString() : null)
                        .bind("altNames", writeJson(alternateNames))
                        .bind("studios", writeJson(primaryStudios))
                        .bind("awards", writeJson(awards))
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
    public Optional<Actress> findPrimaryForAlias(String aliasName) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.* FROM actresses a
                        JOIN actress_aliases aa ON a.id = aa.actress_id
                        WHERE aa.alias_name = :name COLLATE NOCASE
                        """)
                        .bind("name", aliasName)
                        .map(ACTRESS_MAPPER)
                        .findFirst()
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
                                "SELECT * FROM actresses WHERE canonical_name = :name COLLATE NOCASE")
                        .bind("name", entry.name())
                        .map(ACTRESS_MAPPER)
                        .findFirst()
                        .orElseGet(() -> {
                            log.warn("importFromYaml: no actress found for '{}' — creating stub record", entry.name());
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

    // ─── Dashboard module queries ────────────────────────────────────────────

    @Override
    public List<Actress> findSpotlightCandidates(java.util.Set<Actress.Tier> superstarTiers,
                                                 int limit,
                                                 java.util.Set<Long> excludeIds) {
        boolean hasTiers   = superstarTiers != null && !superstarTiers.isEmpty();
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();

        // Pool: favorite OR bookmark OR grade IN (high) OR tier IN superstar.
        // High grades: SSS, SS, S, A_PLUS, A — anything ≥ A.
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM actresses
                WHERE rejected = 0
                  AND (favorite = 1
                       OR bookmark = 1
                       OR grade IN ('SSS','SS','S','A+','A')
                """);
        if (hasTiers) sql.append("       OR tier IN (<tiers>) ");
        sql.append(") ");
        if (hasExclude) sql.append("AND id NOT IN (<excludeIds>) ");
        sql.append("ORDER BY RANDOM() LIMIT :limit");

        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString()).bind("limit", limit);
            if (hasTiers)   q.bindList("tiers", superstarTiers.stream().map(Enum::name).toList());
            if (hasExclude) q.bindList("excludeIds", new java.util.ArrayList<>(excludeIds));
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public List<Actress> findBirthdaysToday(int month, int day, int limit) {
        // SQLite stores LocalDate as ISO yyyy-MM-dd, so substr(date, 6, 5) = "MM-dd".
        String mmdd = String.format("%02d-%02d", month, day);
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM actresses
                        WHERE rejected = 0
                          AND date_of_birth IS NOT NULL
                          AND substr(date_of_birth, 6, 5) = :mmdd
                        ORDER BY favorite DESC, bookmark DESC,
                                 CASE tier
                                     WHEN 'GODDESS' THEN 0
                                     WHEN 'SUPERSTAR' THEN 1
                                     WHEN 'POPULAR' THEN 2
                                     WHEN 'MINOR' THEN 3
                                     ELSE 4
                                 END,
                                 canonical_name
                        LIMIT :limit
                        """)
                        .bind("mmdd", mmdd)
                        .bind("limit", limit)
                        .map(ACTRESS_MAPPER)
                        .list()
        );
    }

    @Override
    public List<Actress> findNewFaces(LocalDate since, int limit, java.util.Set<Long> excludeIds) {
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();
        String exclusion = hasExclude ? "AND id NOT IN (<excludeIds>) " : "";
        // first_seen_at is a date (no time), so when many actresses are imported on the same
        // day they share the same value — secondary sort by id DESC keeps insertion order
        // instead of degenerating into alphabetical (which made the strip all A-names).
        String sql = "SELECT * FROM actresses WHERE rejected = 0 AND first_seen_at >= :since "
                + exclusion
                + "ORDER BY first_seen_at DESC, id DESC LIMIT :limit";
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql)
                    .bind("since", since.toString())
                    .bind("limit", limit);
            if (hasExclude) q.bindList("excludeIds", new java.util.ArrayList<>(excludeIds));
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public List<Actress> findNewFacesFallback(int limit, java.util.Set<Long> excludeIds) {
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();
        String exclusion = hasExclude ? "AND id NOT IN (<excludeIds>) " : "";
        // See findNewFaces — secondary sort by id DESC, not canonical_name.
        String sql = "SELECT * FROM actresses WHERE rejected = 0 "
                + exclusion
                + "ORDER BY first_seen_at DESC, id DESC LIMIT :limit";
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql).bind("limit", limit);
            if (hasExclude) q.bindList("excludeIds", new java.util.ArrayList<>(excludeIds));
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public List<Actress> findBookmarksOrderedByBookmarkedAt(int limit, java.util.Set<Long> excludeIds) {
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();
        String exclusion = hasExclude ? "AND id NOT IN (<excludeIds>) " : "";
        // NULLs sort last so backfilled bookmarks (now stamped) sort by their backfill time;
        // any future NULL stragglers fall to the bottom.
        String sql = "SELECT * FROM actresses WHERE bookmark = 1 AND rejected = 0 "
                + exclusion
                + "ORDER BY bookmarked_at IS NULL, bookmarked_at DESC, canonical_name LIMIT :limit";
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql).bind("limit", limit);
            if (hasExclude) q.bindList("excludeIds", new java.util.ArrayList<>(excludeIds));
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public List<Actress> findUndiscoveredElites(java.util.Set<Actress.Tier> minTiers,
                                                int maxVisitCount,
                                                int limit,
                                                java.util.Set<Long> excludeIds) {
        if (minTiers == null || minTiers.isEmpty()) return List.of();
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();
        String exclusion = hasExclude ? "AND id NOT IN (<excludeIds>) " : "";
        String sql = "SELECT * FROM actresses "
                + "WHERE rejected = 0 AND visit_count < :maxVisits AND tier IN (<tiers>) "
                + exclusion
                + "ORDER BY RANDOM() LIMIT :limit";
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql)
                    .bind("maxVisits", maxVisitCount)
                    .bind("limit", limit)
                    .bindList("tiers", minTiers.stream().map(Enum::name).toList());
            if (hasExclude) q.bindList("excludeIds", new java.util.ArrayList<>(excludeIds));
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public List<Actress> findForgottenGemsCandidates(java.util.Set<Actress.Grade> topGrades,
                                                     java.util.Set<Actress.Tier> highTiers,
                                                     LocalDate staleBefore,
                                                     int limit,
                                                     java.util.Set<Long> excludeIds) {
        boolean hasGrades  = topGrades != null && !topGrades.isEmpty();
        boolean hasTiers   = highTiers != null && !highTiers.isEmpty();
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();
        StringBuilder sql = new StringBuilder("SELECT * FROM actresses WHERE rejected = 0 AND (favorite = 1");
        if (hasGrades) sql.append(" OR grade IN (<grades>)");
        if (hasTiers)  sql.append(" OR tier IN (<tiers>)");
        sql.append(") AND (last_visited_at IS NULL OR last_visited_at < :staleBefore) ");
        if (hasExclude) sql.append("AND id NOT IN (<excludeIds>) ");
        sql.append("ORDER BY RANDOM() LIMIT :limit");

        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString())
                    .bind("staleBefore", staleBefore.toString())
                    .bind("limit", limit);
            if (hasGrades)
                q.bindList("grades", topGrades.stream().map(g -> g.display).toList());
            if (hasTiers)
                q.bindList("tiers", highTiers.stream().map(Enum::name).toList());
            if (hasExclude)
                q.bindList("excludeIds", new java.util.ArrayList<>(excludeIds));
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public List<Actress> findResearchGapCandidates(java.util.Set<Actress.Tier> superstarTiers, int limit) {
        boolean hasTiers = superstarTiers != null && !superstarTiers.isEmpty();
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM actresses
                WHERE rejected = 0
                  AND (favorite = 1 OR grade IS NOT NULL
                """);
        if (hasTiers) sql.append("       OR tier IN (<tiers>) ");
        sql.append(") AND biography IS NULL ");
        sql.append("ORDER BY ");
        // Score: tier weight + favorite bonus + graded bonus.
        sql.append("CASE tier ");
        sql.append("  WHEN 'GODDESS' THEN 5 ");
        sql.append("  WHEN 'SUPERSTAR' THEN 3 ");
        sql.append("  WHEN 'POPULAR' THEN 1 ");
        sql.append("  ELSE 0 END DESC, ");
        sql.append("favorite DESC, grade IS NOT NULL DESC, canonical_name ");
        sql.append("LIMIT :limit");
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString()).bind("limit", limit);
            if (hasTiers) q.bindList("tiers", superstarTiers.stream().map(Enum::name).toList());
            return q.map(ACTRESS_MAPPER).list();
        });
    }

    @Override
    public ActressLibraryStats computeActressLibraryStats() {
        String monthStart = LocalDate.now().withDayOfMonth(1).toString();
        return jdbi.withHandle(h -> {
            long total = h.createQuery("SELECT COUNT(*) FROM actresses WHERE rejected = 0")
                    .mapTo(Long.class).one();
            long favorites = h.createQuery("SELECT COUNT(*) FROM actresses WHERE favorite = 1 AND rejected = 0")
                    .mapTo(Long.class).one();
            long graded = h.createQuery("SELECT COUNT(*) FROM actresses WHERE grade IS NOT NULL AND rejected = 0")
                    .mapTo(Long.class).one();
            long elites = h.createQuery(
                            "SELECT COUNT(*) FROM actresses WHERE tier IN ('SUPERSTAR','GODDESS') AND rejected = 0")
                    .mapTo(Long.class).one();
            long newThisMonth = h.createQuery(
                            "SELECT COUNT(*) FROM actresses WHERE first_seen_at >= :since AND rejected = 0")
                    .bind("since", monthStart)
                    .mapTo(Long.class).one();
            // Research total = qualifying pool (favorite/graded/elite), regardless of bio state.
            long researchTotal = h.createQuery("""
                            SELECT COUNT(*) FROM actresses
                            WHERE rejected = 0
                              AND (favorite = 1 OR grade IS NOT NULL
                                   OR tier IN ('SUPERSTAR','GODDESS'))
                            """).mapTo(Long.class).one();
            long researchCovered = h.createQuery("""
                            SELECT COUNT(*) FROM actresses
                            WHERE rejected = 0
                              AND (favorite = 1 OR grade IS NOT NULL
                                   OR tier IN ('SUPERSTAR','GODDESS'))
                              AND biography IS NOT NULL AND biography != ''
                            """).mapTo(Long.class).one();
            return new ActressLibraryStats(total, favorites, graded, elites, newThisMonth,
                    researchCovered, researchTotal);
        });
    }

    @Override
    public List<ActressLabelEngagement> findActressLabelEngagements() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.id            AS actress_id,
                               t.label         AS label_code,
                               a.visit_count   AS visit_count,
                               a.favorite      AS favorite,
                               a.bookmark      AS bookmark
                        FROM actresses a
                        JOIN title_actresses ta ON ta.actress_id = a.id
                        JOIN titles t ON t.id = ta.title_id
                        WHERE a.rejected = 0
                          AND t.label IS NOT NULL AND t.label != ''
                        GROUP BY a.id, t.label
                        """)
                        .map((rs, ctx) -> new ActressLabelEngagement(
                                rs.getLong("actress_id"),
                                rs.getString("label_code"),
                                rs.getInt("visit_count"),
                                rs.getInt("favorite") != 0,
                                rs.getInt("bookmark") != 0))
                        .list()
        );
    }

    // ─── Name-check queries ──────────────────────────────────────────────────

    @Override
    public Map<Long, Integer> countAllTitlesByActress() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT actress_id, COUNT(*) AS cnt FROM (
                            SELECT actress_id FROM titles WHERE actress_id IS NOT NULL
                            UNION ALL
                            SELECT actress_id FROM title_actresses
                        ) GROUP BY actress_id
                        """)
                        .map((rs, ctx) -> Map.entry(rs.getLong("actress_id"), rs.getInt("cnt")))
                        .list()
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @Override
    public Map<Long, List<FilingLocation>> findFilingLocations() {
        List<FilingLocation> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.actress_id, t.code, tl.volume_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id IS NOT NULL
                        ORDER BY t.actress_id, t.code
                        """)
                        .map((rs, ctx) -> new FilingLocation(
                                rs.getLong("actress_id"),
                                rs.getString("code"),
                                rs.getString("volume_id"),
                                rs.getString("path")))
                        .list()
        );
        Map<Long, List<FilingLocation>> result = new java.util.LinkedHashMap<>();
        for (FilingLocation loc : rows) {
            result.computeIfAbsent(loc.actressId(), k -> new java.util.ArrayList<>()).add(loc);
        }
        return result;
    }

    @Override
    public List<FederatedActressResult> searchForFederated(String query, boolean startsWith, int limit) {
        String pattern = startsWith ? query + "%" : "%" + query + "%";
        return jdbi.withHandle(h ->
                h.createQuery("""
                        WITH matches AS (
                          SELECT a.id, a.canonical_name, a.stage_name, a.tier, a.grade,
                                 a.favorite, a.bookmark,
                                 aa.alias_name AS matched_alias, 0 AS is_canonical
                          FROM actresses a
                          JOIN actress_aliases aa ON aa.actress_id = a.id
                          WHERE aa.alias_name LIKE :pattern COLLATE NOCASE AND a.rejected = 0
                          UNION ALL
                          SELECT a.id, a.canonical_name, a.stage_name, a.tier, a.grade,
                                 a.favorite, a.bookmark,
                                 NULL AS matched_alias, 1 AS is_canonical
                          FROM actresses a
                          WHERE a.canonical_name LIKE :pattern COLLATE NOCASE AND a.rejected = 0
                        ),
                        ranked AS (
                          SELECT *, ROW_NUMBER() OVER (PARTITION BY id ORDER BY is_canonical DESC) AS rn
                          FROM matches
                        )
                        SELECT r.id, r.canonical_name, r.stage_name, r.tier, r.grade,
                               r.favorite, r.bookmark, r.matched_alias,
                               COUNT(t.id) AS title_count,
                               (SELECT tc.label FROM titles tc
                                WHERE tc.actress_id = r.id
                                  AND tc.base_code IS NOT NULL AND tc.label IS NOT NULL
                                ORDER BY tc.id DESC LIMIT 1) AS cover_label,
                               (SELECT tc.base_code FROM titles tc
                                WHERE tc.actress_id = r.id
                                  AND tc.base_code IS NOT NULL AND tc.label IS NOT NULL
                                ORDER BY tc.id DESC LIMIT 1) AS cover_base_code
                        FROM ranked r
                        LEFT JOIN titles t ON t.actress_id = r.id
                        WHERE r.rn = 1
                        GROUP BY r.id
                        HAVING COUNT(t.id) >= 2
                        ORDER BY r.favorite DESC, r.bookmark DESC, r.canonical_name
                        LIMIT :limit
                        """)
                        .bind("pattern", pattern)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new FederatedActressResult(
                                rs.getLong("id"),
                                rs.getString("canonical_name"),
                                rs.getString("stage_name"),
                                rs.getString("tier"),
                                rs.getString("grade"),
                                rs.getInt("favorite") != 0,
                                rs.getInt("bookmark") != 0,
                                rs.getString("matched_alias"),
                                rs.getInt("title_count"),
                                rs.getString("cover_label"),
                                rs.getString("cover_base_code")
                        ))
                        .list()
        );
    }

    // ── Backup / restore ─────────────────────────────────────────────────────

    @Override
    public List<ActressBackupRow> findAllForBackup() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT canonical_name, favorite, bookmark, bookmarked_at,
                               grade, rejected, visit_count, last_visited_at
                        FROM actresses
                        WHERE favorite = 1 OR bookmark = 1 OR grade IS NOT NULL
                           OR rejected = 1 OR visit_count > 0
                        ORDER BY canonical_name
                        """)
                        .map((rs, ctx) -> {
                            String bookmarkedAtStr = rs.getString("bookmarked_at");
                            String lastVisitedStr = rs.getString("last_visited_at");
                            return new ActressBackupRow(
                                    rs.getString("canonical_name"),
                                    rs.getInt("favorite") != 0,
                                    rs.getInt("bookmark") != 0,
                                    bookmarkedAtStr != null ? LocalDateTime.parse(bookmarkedAtStr) : null,
                                    rs.getString("grade"),
                                    rs.getInt("rejected") != 0,
                                    rs.getInt("visit_count"),
                                    lastVisitedStr != null ? LocalDateTime.parse(lastVisitedStr) : null
                            );
                        })
                        .list()
        );
    }

    @Override
    public void restoreUserData(String canonicalName, boolean favorite, boolean bookmark,
                                LocalDateTime bookmarkedAt, String grade,
                                boolean rejected, int visitCount, LocalDateTime lastVisitedAt) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE actresses
                        SET favorite        = :favorite,
                            bookmark        = :bookmark,
                            bookmarked_at   = :bookmarkedAt,
                            grade           = :grade,
                            rejected        = :rejected,
                            visit_count     = :visitCount,
                            last_visited_at = :lastVisitedAt
                        WHERE canonical_name = :canonicalName
                        """)
                        .bind("canonicalName", canonicalName)
                        .bind("favorite", favorite ? 1 : 0)
                        .bind("bookmark", bookmark ? 1 : 0)
                        .bind("bookmarkedAt", bookmarkedAt != null ? bookmarkedAt.toString() : null)
                        .bind("grade", grade)
                        .bind("rejected", rejected ? 1 : 0)
                        .bind("visitCount", visitCount)
                        .bind("lastVisitedAt", lastVisitedAt != null ? lastVisitedAt.toString() : null)
                        .execute()
        );
    }
}
