package com.organizer3.repository.jdbi;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class JdbiTitleRepository implements TitleRepository {

    /** Maps a titles row to a Title with an empty locations list. */
    private static final RowMapper<Title> MAPPER = (rs, ctx) -> {
        String actressIdStr = rs.getString("actress_id");
        String seqNumStr = rs.getString("seq_num");
        String gradeStr = rs.getString("grade");
        String releaseDateStr = rs.getString("release_date");
        String lastVisitedStr = rs.getString("last_visited_at");
        return Title.builder()
                .id(rs.getLong("id"))
                .code(rs.getString("code"))
                .baseCode(rs.getString("base_code"))
                .label(rs.getString("label"))
                .seqNum(seqNumStr != null ? Integer.parseInt(seqNumStr) : null)
                .actressId(actressIdStr != null ? Long.parseLong(actressIdStr) : null)
                .favorite(rs.getBoolean("favorite"))
                .bookmark(rs.getBoolean("bookmark"))
                .grade(gradeStr != null ? Actress.Grade.fromDisplay(gradeStr) : null)
                .rejected(rs.getBoolean("rejected"))
                .titleOriginal(rs.getString("title_original"))
                .titleEnglish(rs.getString("title_english"))
                .releaseDate(releaseDateStr != null ? java.time.LocalDate.parse(releaseDateStr) : null)
                .notes(rs.getString("notes"))
                .visitCount(rs.getInt("visit_count"))
                .lastVisitedAt(lastVisitedStr != null ? LocalDateTime.parse(lastVisitedStr) : null)
                .build();
    };

    private final Jdbi jdbi;
    private final TitleLocationRepository locationRepo;

    @Override
    public Optional<Long> findDominantActressForLabel(String label) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT actress_id FROM titles
                        WHERE label = :label AND actress_id IS NOT NULL
                        GROUP BY actress_id ORDER BY COUNT(*) DESC LIMIT 1
                        """)
                        .bind("label", label)
                        .mapTo(Long.class)
                        .findFirst()
        );
    }

    @Override
    public Optional<Title> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE id = :id")
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst()
        ).map(this::populateLocations);
    }

    @Override
    public Optional<Title> findByCode(String code) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE code = :code")
                        .bind("code", code)
                        .map(MAPPER)
                        .findFirst()
        ).map(this::populateLocations);
    }

    @Override
    public List<Title> findByBaseCode(String baseCode) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE base_code = :baseCode")
                        .bind("baseCode", baseCode)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByVolume(String volumeId) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT t.* FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId
                        ORDER BY t.code
                        """)
                        .bind("volumeId", volumeId)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByActress(long actressId) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT t.* FROM titles t
                        LEFT JOIN title_actresses ta ON ta.title_id = t.id
                        WHERE t.actress_id = :actressId OR ta.actress_id = :actressId
                        ORDER BY t.code
                        """)
                        .bind("actressId", actressId)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public int countByActress(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(DISTINCT t.id) FROM titles t
                        LEFT JOIN title_actresses ta ON ta.title_id = t.id
                        WHERE t.actress_id = :actressId OR ta.actress_id = :actressId
                        """)
                        .bind("actressId", actressId)
                        .mapTo(Integer.class)
                        .one()
        );
    }

    @Override
    public List<Title> findByAliasesOnly(long actressId) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                            JOIN actresses a ON t.actress_id = a.id
                            JOIN actress_aliases aa ON a.canonical_name = aa.alias_name
                        WHERE aa.actress_id = :actressId
                        ORDER BY code
                        """)
                        .bind("actressId", actressId)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByActressIncludingAliases(long actressId) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM titles WHERE actress_id = :actressId
                        UNION
                        SELECT t.* FROM titles t
                            JOIN actresses a ON t.actress_id = a.id
                            JOIN actress_aliases aa ON a.canonical_name = aa.alias_name
                        WHERE aa.actress_id = :actressId
                        ORDER BY code
                        """)
                        .bind("actressId", actressId)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public Title save(Title title) {
        return jdbi.withHandle(h -> {
            if (title.getId() == null) {
                long id = h.createUpdate("""
                                INSERT INTO titles
                                    (code, base_code, label, seq_num, actress_id,
                                     favorite, bookmark, grade, rejected)
                                VALUES (:code, :baseCode, :label, :seqNum, :actressId,
                                        :favorite, :bookmark, :grade, :rejected)
                                """)
                        .bind("code", title.getCode())
                        .bind("baseCode", title.getBaseCode())
                        .bind("label", title.getLabel())
                        .bind("seqNum", title.getSeqNum())
                        .bind("actressId", title.getActressId())
                        .bind("favorite", title.isFavorite())
                        .bind("bookmark", title.isBookmark())
                        .bind("grade", title.getGrade() != null ? title.getGrade().display : null)
                        .bind("rejected", title.isRejected())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return title.toBuilder().id(id).build();
            } else {
                h.createUpdate("""
                                UPDATE titles SET
                                    code = :code, base_code = :baseCode, label = :label, seq_num = :seqNum,
                                    actress_id = :actressId,
                                    favorite = :favorite, bookmark = :bookmark, grade = :grade, rejected = :rejected
                                WHERE id = :id
                                """)
                        .bind("id", title.getId())
                        .bind("code", title.getCode())
                        .bind("baseCode", title.getBaseCode())
                        .bind("label", title.getLabel())
                        .bind("seqNum", title.getSeqNum())
                        .bind("actressId", title.getActressId())
                        .bind("favorite", title.isFavorite())
                        .bind("bookmark", title.isBookmark())
                        .bind("grade", title.getGrade() != null ? title.getGrade().display : null)
                        .bind("rejected", title.isRejected())
                        .execute();
                return title;
            }
        });
    }

    @Override
    public Title findOrCreateByCode(Title template) {
        return jdbi.withHandle(h -> {
            // Try to find existing
            Optional<Title> existing = h.createQuery("SELECT * FROM titles WHERE code = :code")
                    .bind("code", template.getCode())
                    .map(MAPPER)
                    .findFirst();

            if (existing.isPresent()) {
                Title title = existing.get();
                // Update actress if existing has none and template provides one
                if (title.getActressId() == null && template.getActressId() != null) {
                    h.createUpdate("UPDATE titles SET actress_id = :actressId WHERE id = :id")
                            .bind("actressId", template.getActressId())
                            .bind("id", title.getId())
                            .execute();
                    return title.toBuilder().actressId(template.getActressId()).build();
                }
                return title;
            }

            // Insert new
            long id = h.createUpdate("""
                            INSERT INTO titles
                                (code, base_code, label, seq_num, actress_id,
                                 favorite, bookmark, grade, rejected)
                            VALUES (:code, :baseCode, :label, :seqNum, :actressId,
                                    :favorite, :bookmark, :grade, :rejected)
                            """)
                    .bind("code", template.getCode())
                    .bind("baseCode", template.getBaseCode())
                    .bind("label", template.getLabel())
                    .bind("seqNum", template.getSeqNum())
                    .bind("actressId", template.getActressId())
                    .bind("favorite", template.isFavorite())
                    .bind("bookmark", template.isBookmark())
                    .bind("grade", template.getGrade() != null ? template.getGrade().display : null)
                    .bind("rejected", template.isRejected())
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            return template.toBuilder().id(id).build();
        });
    }

    @Override
    public void delete(long id) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM titles WHERE id = :id")
                        .bind("id", id)
                        .execute()
        );
    }

    @Override
    public List<Title> findRecent(int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.actress_id IS NOT NULL
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByCodePrefixPaged(String labelPrefix, String seqPrefix, int limit, int offset) {
        boolean hasSeq = seqPrefix != null && !seqPrefix.isEmpty();
        String sql = "SELECT t.* FROM titles t "
                + "WHERE upper(t.label) LIKE :labelPrefix || '%' "
                + (hasSeq ? "AND CAST(t.seq_num AS TEXT) LIKE :seqPrefix || '%' " : "")
                + "ORDER BY t.favorite DESC, t.bookmark DESC, t.label ASC, t.seq_num ASC "
                + "LIMIT :limit OFFSET :offset";

        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql)
                    .bind("labelPrefix", labelPrefix == null ? "" : labelPrefix.toUpperCase())
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (hasSeq) q.bind("seqPrefix", seqPrefix);
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findFavoritesPaged(int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        LEFT JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.favorite = 1
                        GROUP BY t.id
                        ORDER BY t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findBookmarksPaged(int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        LEFT JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.bookmark = 1
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByActressPaged(long actressId, int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        LEFT JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.actress_id = :actressId
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("actressId", actressId)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByActressAndLabelsPaged(long actressId, List<String> labels, int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        LEFT JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.actress_id = :actressId AND upper(t.label) IN (<labels>)
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("actressId", actressId)
                        .bindList("labels", labels)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByVolumePaged(String volumeId, int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("volumeId", volumeId)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByVolumeAndPartition(String volumeId, String partitionId, int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId AND tl.partition_id = :partitionId
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findRandom(int limit) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.actress_id IS NOT NULL
                        GROUP BY t.id
                        ORDER BY RANDOM()
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByTagsPaged(List<String> tags, int limit, int offset) {
        // A title matches if, for every required tag, that tag appears in either
        // title_tags (direct per-title tag) or label_tags (indirect via label code).
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        LEFT JOIN title_locations tl ON t.id = tl.title_id
                        WHERE (
                            SELECT COUNT(DISTINCT merged.tag)
                            FROM (
                                SELECT tag FROM title_tags WHERE title_id = t.id
                                UNION
                                SELECT lt.tag FROM label_tags lt WHERE lt.label_code = upper(t.label)
                            ) merged
                            WHERE merged.tag IN (<tags>)
                        ) = :tagCount
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bindList("tags", tags)
                        .bind("tagCount", tags.size())
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public void enrichTitle(long titleId, String titleOriginal, String titleEnglish,
                            java.time.LocalDate releaseDate, String notes, Actress.Grade grade) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE titles SET
                            title_original = :titleOriginal,
                            title_english = :titleEnglish,
                            release_date = :releaseDate,
                            notes = :notes,
                            grade = :grade
                        WHERE id = :id
                        """)
                        .bind("id", titleId)
                        .bind("titleOriginal", titleOriginal)
                        .bind("titleEnglish", titleEnglish)
                        .bind("releaseDate", releaseDate != null ? releaseDate.toString() : null)
                        .bind("notes", notes)
                        .bind("grade", grade != null ? grade.display : null)
                        .execute()
        );
    }

    @Override
    public void toggleFavorite(long titleId, boolean favorite) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE titles SET favorite = :favorite WHERE id = :id")
                        .bind("favorite", favorite)
                        .bind("id", titleId)
                        .execute()
        );
    }

    @Override
    public void toggleBookmark(long titleId, boolean bookmark) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE titles SET bookmark = :bookmark WHERE id = :id")
                        .bind("bookmark", bookmark)
                        .bind("id", titleId)
                        .execute()
        );
    }

    @Override
    public void recordVisit(long titleId) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE titles SET visit_count = visit_count + 1, " +
                                "last_visited_at = :now WHERE id = :id")
                        .bind("now", LocalDateTime.now().toString())
                        .bind("id", titleId)
                        .execute()
        );
    }

    @Override
    public List<Object[]> findTopActressesByLabels(List<String> labels, int limit) {
        if (labels == null || labels.isEmpty()) return List.of();
        String placeholders = labels.stream().map(l -> "?").collect(Collectors.joining(", "));
        String sql = """
                SELECT a.id, a.canonical_name, a.tier, COUNT(*) AS title_count
                FROM titles t
                JOIN actresses a ON t.actress_id = a.id
                WHERE UPPER(t.label) IN (""" + placeholders + """
                )
                GROUP BY a.id
                ORDER BY title_count DESC
                LIMIT ?
                """;
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql);
            for (int i = 0; i < labels.size(); i++) {
                query.bind(i, labels.get(i).toUpperCase());
            }
            query.bind(labels.size(), limit);
            return query.map((rs, ctx) -> new Object[]{
                    rs.getLong("id"),
                    rs.getString("canonical_name"),
                    rs.getString("tier"),
                    rs.getLong("title_count")
            }).list();
        });
    }

    @Override
    public List<Object[]> findNewestActressesByLabels(List<String> labels, int limit) {
        if (labels == null || labels.isEmpty()) return List.of();
        String placeholders = labels.stream().map(l -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT t.actress_id AS id, a.canonical_name, a.tier, MAX(tl.added_date) AS latest_date "
                + "FROM titles t "
                + "JOIN actresses a ON t.actress_id = a.id "
                + "JOIN title_locations tl ON tl.title_id = t.id "
                + "WHERE UPPER(t.label) IN (" + placeholders + ") AND t.actress_id IS NOT NULL "
                + "GROUP BY t.actress_id "
                + "ORDER BY latest_date DESC "
                + "LIMIT ?";
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql);
            for (int i = 0; i < labels.size(); i++) query.bind(i, labels.get(i).toUpperCase());
            query.bind(labels.size(), limit);
            return query.map((rs, ctx) -> new Object[]{
                    rs.getLong("id"),
                    rs.getString("canonical_name"),
                    rs.getString("tier")
            }).list();
        });
    }

    @Override
    public void deleteOrphaned() {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM titles WHERE id NOT IN (
                            SELECT DISTINCT title_id FROM title_locations
                        )""")
                        .execute()
        );
    }

    // -------------------------------------------------------------------------
    // Location population helpers
    // -------------------------------------------------------------------------

    private Title populateLocations(Title title) {
        if (title.getId() == null) return title;
        List<TitleLocation> locations = locationRepo.findByTitle(title.getId());
        return title.toBuilder().locations(locations).build();
    }

    private List<Title> populateLocationsBatch(List<Title> titles) {
        if (titles.isEmpty()) return titles;
        List<Long> ids = titles.stream().map(Title::getId).toList();
        List<TitleLocation> allLocations = locationRepo.findByTitleIds(ids);
        Map<Long, List<TitleLocation>> byTitleId = allLocations.stream()
                .collect(Collectors.groupingBy(TitleLocation::getTitleId));
        return titles.stream()
                .map(t -> t.toBuilder()
                        .locations(byTitleId.getOrDefault(t.getId(), List.of()))
                        .build())
                .toList();
    }
}
