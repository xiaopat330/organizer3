package com.organizer3.repository.jdbi;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.CatastrophicDeleteException;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.web.TitleSummary;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        String bookmarkedAtStr = rs.getString("bookmarked_at");
        return Title.builder()
                .id(rs.getLong("id"))
                .code(rs.getString("code"))
                .baseCode(rs.getString("base_code"))
                .label(rs.getString("label"))
                .seqNum(seqNumStr != null ? Integer.parseInt(seqNumStr) : null)
                .actressId(actressIdStr != null ? Long.parseLong(actressIdStr) : null)
                .favorite(rs.getBoolean("favorite"))
                .bookmark(rs.getBoolean("bookmark"))
                .bookmarkedAt(bookmarkedAtStr != null ? LocalDateTime.parse(bookmarkedAtStr) : null)
                .grade(gradeStr != null ? Actress.Grade.fromDisplay(gradeStr) : null)
                .rejected(rs.getBoolean("rejected"))
                .titleOriginal(rs.getString("title_original"))
                .titleEnglish(rs.getString("title_english"))
                .releaseDate(releaseDateStr != null && !releaseDateStr.isEmpty() ? java.time.LocalDate.parse(releaseDateStr) : null)
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
    public int countByVolume(String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(DISTINCT t.id) FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId
                        """)
                        .bind("volumeId", volumeId)
                        .mapTo(Integer.class)
                        .one());
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
    public Map<Long, List<Title>> findByActressIds(Collection<Long> actressIds) {
        if (actressIds.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            List<Map.Entry<Long, Title>> pairs = h.createQuery("""
                    SELECT k.actress_key, t.*
                    FROM (
                        SELECT actress_id AS actress_key, id AS title_id
                        FROM titles WHERE actress_id IN (<ids>)
                        UNION
                        SELECT actress_id AS actress_key, title_id
                        FROM title_actresses WHERE actress_id IN (<ids>)
                    ) k
                    JOIN titles t ON t.id = k.title_id
                    ORDER BY k.actress_key, t.code
                    """)
                    .bindList("ids", actressIds)
                    .map((rs, ctx) -> Map.entry(rs.getLong("actress_key"), MAPPER.map(rs, ctx)))
                    .list();

            Map<Long, List<Title>> grouped = new LinkedHashMap<>();
            for (var pair : pairs) {
                grouped.computeIfAbsent(pair.getKey(), k -> new ArrayList<>()).add(pair.getValue());
            }

            // Batch-populate locations for all unique titles in one query
            Set<Long> titleIds = pairs.stream()
                    .map(e -> e.getValue().getId())
                    .collect(Collectors.toSet());
            if (!titleIds.isEmpty()) {
                List<TitleLocation> allLocations = locationRepo.findByTitleIds(new ArrayList<>(titleIds));
                Map<Long, List<TitleLocation>> locationsByTitleId = allLocations.stream()
                        .collect(Collectors.groupingBy(TitleLocation::getTitleId));
                for (var entry : grouped.entrySet()) {
                    grouped.put(entry.getKey(), entry.getValue().stream()
                            .map(t -> t.toBuilder()
                                    .locations(locationsByTitleId.getOrDefault(t.getId(), List.of()))
                                    .build())
                            .toList());
                }
            }
            return grouped;
        });
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
                + "WHERE t.label LIKE :labelPrefix || '%' "
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
                        WHERE t.actress_id = :actressId AND t.label IN (<labels>)
                        GROUP BY t.id
                        ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("actressId", actressId)
                        .bindList("labels", labels.stream().map(String::toUpperCase).toList())
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByActressTagsFiltered(long actressId, List<String> labels, List<String> tags, List<Long> enrichmentTagIds, int limit, int offset) {
        List<Long> safeEnrichTags = enrichmentTagIds != null ? enrichmentTagIds : List.of();
        boolean hasTags         = !tags.isEmpty();
        boolean hasEnrichTags   = !safeEnrichTags.isEmpty();

        StringBuilder sql = new StringBuilder("SELECT t.* FROM titles t\n");
        sql.append("LEFT JOIN title_locations tl ON t.id = tl.title_id\n");
        if (hasTags) {
            sql.append("JOIN title_effective_tags tet ON tet.title_id = t.id AND tet.tag IN (<tags>)\n");
        }
        if (hasEnrichTags) {
            sql.append("JOIN title_enrichment_tags tet_e ON tet_e.title_id = t.id AND tet_e.tag_id IN (<enrichmentTagIds>)\n");
        }
        sql.append("WHERE t.actress_id = :actressId\n");
        if (!labels.isEmpty()) {
            sql.append("AND t.label IN (<labels>)\n");
        }
        sql.append("GROUP BY t.id\n");
        if (hasTags || hasEnrichTags) {
            sql.append("HAVING ");
            if (hasTags)       sql.append("COUNT(DISTINCT tet.tag) = :tagCount");
            if (hasTags && hasEnrichTags) sql.append(" AND ");
            if (hasEnrichTags) sql.append("COUNT(DISTINCT tet_e.tag_id) = :enrichmentTagCount");
            sql.append("\n");
        }
        sql.append("ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC LIMIT :limit OFFSET :offset");

        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString())
                    .bind("actressId", actressId)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (!labels.isEmpty())   q = q.bindList("labels", labels.stream().map(String::toUpperCase).toList());
            if (hasTags)             q = q.bindList("tags", tags).bind("tagCount", tags.size());
            if (hasEnrichTags)       q = q.bindList("enrichmentTagIds", safeEnrichTags).bind("enrichmentTagCount", safeEnrichTags.size());
            return q.map(MAPPER).list();
        });
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
                          AND instr('/' || tl.path, '/_') = 0
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
    public List<Title> findByVolumeFiltered(String volumeId, List<String> labels, List<String> tags, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT t.* FROM titles t\n");
        sql.append("JOIN title_locations tl ON t.id = tl.title_id\n");
        if (!tags.isEmpty()) {
            sql.append("JOIN title_effective_tags tet ON tet.title_id = t.id AND tet.tag IN (<tags>)\n");
        }
        sql.append("WHERE tl.volume_id = :volumeId\n");
        if (!labels.isEmpty()) {
            sql.append("AND t.label IN (<labels>)\n");
        }
        sql.append("GROUP BY t.id\n");
        if (!tags.isEmpty()) {
            sql.append("HAVING COUNT(DISTINCT tet.tag) = :tagCount\n");
        }
        sql.append("ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC LIMIT :limit OFFSET :offset");

        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString())
                    .bind("volumeId", volumeId)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (!labels.isEmpty()) q = q.bindList("labels", labels.stream().map(String::toUpperCase).toList());
            if (!tags.isEmpty())   q = q.bindList("tags", tags).bind("tagCount", tags.size());
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findByVolumeAndPartitionFiltered(String volumeId, String partitionId, List<String> labels, List<String> tags, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT t.* FROM titles t\n");
        sql.append("JOIN title_locations tl ON t.id = tl.title_id\n");
        if (!tags.isEmpty()) {
            sql.append("JOIN title_effective_tags tet ON tet.title_id = t.id AND tet.tag IN (<tags>)\n");
        }
        sql.append("WHERE tl.volume_id = :volumeId AND tl.partition_id = :partitionId\n");
        sql.append("  AND instr('/' || tl.path, '/_') = 0\n");
        if (!labels.isEmpty()) {
            sql.append("AND t.label IN (<labels>)\n");
        }
        sql.append("GROUP BY t.id\n");
        if (!tags.isEmpty()) {
            sql.append("HAVING COUNT(DISTINCT tet.tag) = :tagCount\n");
        }
        sql.append("ORDER BY t.favorite DESC, t.bookmark DESC, MIN(tl.added_date) DESC, t.id DESC LIMIT :limit OFFSET :offset");

        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString())
                    .bind("volumeId", volumeId)
                    .bind("partitionId", partitionId)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (!labels.isEmpty()) q = q.bindList("labels", labels.stream().map(String::toUpperCase).toList());
            if (!tags.isEmpty())   q = q.bindList("tags", tags).bind("tagCount", tags.size());
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<String> findTagsByVolume(String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT tet.tag
                        FROM title_effective_tags tet
                        JOIN title_locations tl ON tl.title_id = tet.title_id
                        WHERE tl.volume_id = :volumeId
                        ORDER BY tet.tag
                        """)
                        .bind("volumeId", volumeId)
                        .mapTo(String.class)
                        .list()
        );
    }

    @Override
    public List<String> findTagsByVolumeAndPartition(String volumeId, String partitionId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT tet.tag
                        FROM title_effective_tags tet
                        JOIN title_locations tl ON tl.title_id = tet.title_id
                        WHERE tl.volume_id = :volumeId AND tl.partition_id = :partitionId
                          AND instr('/' || tl.path, '/_') = 0
                        ORDER BY tet.tag
                        """)
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .mapTo(String.class)
                        .list()
        );
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
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        LEFT JOIN title_locations tl ON t.id = tl.title_id
                        JOIN title_effective_tags tet ON tet.title_id = t.id AND tet.tag IN (<tags>)
                        GROUP BY t.id
                        HAVING COUNT(DISTINCT tet.tag) = :tagCount
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
                            grade = :grade,
                            grade_source = CASE WHEN :grade IS NOT NULL THEN 'ai' ELSE grade_source END
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
    public void setGradeFromEnrichment(long titleId, Actress.Grade grade) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE titles SET grade = :grade, grade_source = 'enrichment'
                        WHERE id = :id AND (grade_source IS NULL OR grade_source != 'manual')
                        """)
                        .bind("grade", grade.display)
                        .bind("id", titleId)
                        .execute()
        );
    }

    @Override
    public void setGradeManual(long titleId, Actress.Grade grade) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE titles SET grade = :grade, grade_source = 'manual' WHERE id = :id")
                        .bind("grade", grade.display)
                        .bind("id", titleId)
                        .execute()
        );
    }

    @Override
    public void clearEnrichmentGrade(long titleId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE titles SET grade = NULL, grade_source = NULL
                        WHERE id = :id AND grade_source = 'enrichment'
                        """)
                        .bind("id", titleId)
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
                h.createUpdate(
                        "UPDATE titles SET bookmark = :bookmark, " +
                                "bookmarked_at = CASE WHEN :bookmark THEN :now ELSE NULL END " +
                                "WHERE id = :id")
                        .bind("bookmark", bookmark)
                        .bind("now", LocalDateTime.now().toString())
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
                WHERE t.label IN (""" + placeholders + """
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
                + "WHERE t.label IN (" + placeholders + ") AND t.actress_id IS NOT NULL "
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
    public Map<String, Long> countTitlesByCompanies(List<String> companies) {
        if (companies == null || companies.isEmpty()) return Map.of();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT l.company AS company, COUNT(*) AS cnt
                        FROM titles t
                        JOIN labels l ON upper(l.code) = upper(t.label)
                        WHERE t.label IS NOT NULL AND t.label != ''
                          AND l.company IN (<companies>)
                        GROUP BY l.company
                        """)
                        .bindList("companies", companies)
                        .<Map.Entry<String, Long>>map((rs, ctx) ->
                                Map.entry(rs.getString("company"), rs.getLong("cnt")))
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @Override
    public int countAll() {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM titles")
                .mapTo(Integer.class).one());
    }

    @Override
    public Set<String> allBaseCodes() {
        return jdbi.withHandle(h ->
                new HashSet<>(h.createQuery("SELECT base_code FROM titles")
                        .mapTo(String.class).list())
        );
    }

    /** Floor threshold — small libraries never trip the guard below this absolute count. */
    public static final int ORPHAN_DELETE_FLOOR = 500;

    /** Fractional threshold — guard trips if orphans exceed this share of the total. */
    public static final int ORPHAN_DELETE_FRACTION_DIVISOR = 4; // 25%

    /** Computes the same cascade-safety threshold used by {@link #deleteOrphaned}. */
    public static int orphanDeleteThreshold(int total) {
        return Math.max(ORPHAN_DELETE_FLOOR, total / ORPHAN_DELETE_FRACTION_DIVISOR);
    }

    @Override
    public int deleteOrphaned() {
        return jdbi.inTransaction(h -> {
            int total = h.createQuery("SELECT COUNT(*) FROM titles").mapTo(Integer.class).one();
            int orphans = h.createQuery("""
                    SELECT COUNT(*) FROM titles WHERE id NOT IN (
                        SELECT DISTINCT title_id FROM title_locations
                    )""").mapTo(Integer.class).one();
            if (orphans == 0) return 0;
            int threshold = orphanDeleteThreshold(total);
            if (orphans > threshold) {
                throw new CatastrophicDeleteException("deleteOrphaned(titles)", orphans, total, threshold);
            }
            return h.createUpdate("""
                    DELETE FROM titles WHERE id NOT IN (
                        SELECT DISTINCT title_id FROM title_locations
                    )""").execute();
        });
    }

    @Override
    public List<TitleRepository.OrphanedTitleRef> findOrphanedTitles() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT t.label AS label, t.base_code AS base_code
                        FROM titles t
                        WHERE t.label IS NOT NULL
                          AND t.base_code IS NOT NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM title_locations tl WHERE tl.title_id = t.id
                          )
                        """)
                .map((rs, ctx) -> new TitleRepository.OrphanedTitleRef(
                        rs.getString("label"), rs.getString("base_code")))
                .list());
    }

    @Override
    public List<Title> findLastVisited(int limit) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE visit_count > 0 ORDER BY last_visited_at DESC LIMIT :limit")
                        .bind("limit", limit)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findMostVisited(int limit) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE visit_count > 0 ORDER BY visit_count DESC, last_visited_at DESC LIMIT :limit")
                        .bind("limit", limit)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    // -------------------------------------------------------------------------
    // Dashboard module queries
    // -------------------------------------------------------------------------

    @Override
    public List<Title> findAddedSince(java.time.LocalDate since, int limit, java.util.Set<String> excludeCodes) {
        String exclusion = excludeCodes != null && !excludeCodes.isEmpty()
                ? "AND t.code NOT IN (<excludeCodes>) " : "";
        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery("""
                    SELECT t.*, MIN(tl.added_date) AS min_added
                    FROM titles t
                    JOIN title_locations tl ON t.id = tl.title_id
                    WHERE t.actress_id IS NOT NULL
                      AND tl.added_date >= :since
                      """ + exclusion + """
                    GROUP BY t.id
                    ORDER BY min_added DESC, t.id DESC
                    LIMIT :limit
                    """)
                    .bind("since", since.toString())
                    .bind("limit", limit);
            if (!exclusion.isEmpty()) q.bindList("excludeCodes", new java.util.ArrayList<>(excludeCodes));
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findAddedSinceByLabels(java.time.LocalDate since, java.util.Collection<String> labels,
                                               int limit, java.util.Set<String> excludeCodes) {
        if (labels == null || labels.isEmpty()) return List.of();
        String exclusion = excludeCodes != null && !excludeCodes.isEmpty()
                ? "AND t.code NOT IN (<excludeCodes>) " : "";
        List<String> upperLabels = labels.stream().map(String::toUpperCase).toList();
        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery("""
                    SELECT t.*, MIN(tl.added_date) AS min_added
                    FROM titles t
                    JOIN title_locations tl ON t.id = tl.title_id
                    WHERE t.actress_id IS NOT NULL
                      AND t.label IN (<labels>)
                      AND tl.added_date >= :since
                      """ + exclusion + """
                    GROUP BY t.id
                    ORDER BY RANDOM()
                    LIMIT :limit
                    """)
                    .bindList("labels", upperLabels)
                    .bind("since", since.toString())
                    .bind("limit", limit);
            if (!exclusion.isEmpty()) q.bindList("excludeCodes", new java.util.ArrayList<>(excludeCodes));
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findAnniversary(int month, int day, int limit) {
        // Match month-day on either release_date or the earliest added_date.
        // Two-step: filter titles having release_date mm-dd in SQL, and fetch enough rows
        // to evaluate min(added_date) mm-dd in Java. Keeps SQL aggregate rules simple.
        String mmdd = String.format("-%02d-%02d", month, day);
        // Pull candidate titles: either release_date mm-dd matches, OR any location's added_date mm-dd matches.
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT t.*
                        FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE t.actress_id IS NOT NULL
                          AND ( substr(t.release_date, 5, 6) = :mmdd
                                OR substr(tl.added_date, 5, 6) = :mmdd )
                        """)
                        .bind("mmdd", mmdd)
                        .map(MAPPER)
                        .list()
        );
        titles = populateLocationsBatch(titles);
        // Sort by year ascending (oldest first): use release_date year if it matches, else addedDate year.
        titles = titles.stream()
                .sorted((a, b) -> {
                    String ya = anniversaryYear(a, mmdd);
                    String yb = anniversaryYear(b, mmdd);
                    if (ya == null && yb == null) return 0;
                    if (ya == null) return 1;
                    if (yb == null) return -1;
                    return ya.compareTo(yb);
                })
                .limit(limit)
                .toList();
        return titles;
    }

    private static String anniversaryYear(Title t, String mmdd) {
        if (t.getReleaseDate() != null) {
            String d = t.getReleaseDate().toString();
            if (d.length() >= 10 && d.substring(4).equals(mmdd)) return d.substring(0, 4);
        }
        java.time.LocalDate added = t.getAddedDate();
        if (added != null) {
            String d = added.toString();
            if (d.length() >= 10 && d.substring(4).equals(mmdd)) return d.substring(0, 4);
        }
        return null;
    }

    @Override
    public List<LabelScore> computeLabelScores(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT upper(t.label) AS label_code,
                               SUM(t.visit_count)
                               + 3 * SUM(CASE WHEN t.favorite = 1 THEN 1 ELSE 0 END)
                               + 2 * SUM(CASE WHEN t.bookmark = 1 THEN 1 ELSE 0 END) AS score
                        FROM titles t
                        WHERE t.label IS NOT NULL AND t.label != ''
                        GROUP BY upper(t.label)
                        HAVING score > 0
                        ORDER BY score DESC
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new LabelScore(rs.getString("label_code"), rs.getDouble("score")))
                        .list()
        );
    }

    @Override
    public LibraryStats computeLibraryStats() {
        String monthStart = java.time.LocalDate.now().withDayOfMonth(1).toString();
        String yearStart = java.time.LocalDate.now().withDayOfYear(1).toString();
        return jdbi.withHandle(h -> {
            long total = h.createQuery("SELECT COUNT(*) FROM titles")
                    .mapTo(Long.class).one();
            long labels = h.createQuery("SELECT COUNT(DISTINCT upper(label)) FROM titles WHERE label IS NOT NULL AND label != ''")
                    .mapTo(Long.class).one();
            long unseen = h.createQuery("SELECT COUNT(*) FROM titles WHERE visit_count = 0")
                    .mapTo(Long.class).one();
            long addedMonth = h.createQuery("""
                            SELECT COUNT(DISTINCT title_id) FROM title_locations
                            WHERE added_date >= :since
                            """)
                    .bind("since", monthStart)
                    .mapTo(Long.class).one();
            long addedYear = h.createQuery("""
                            SELECT COUNT(DISTINCT title_id) FROM title_locations
                            WHERE added_date >= :since
                            """)
                    .bind("since", yearStart)
                    .mapTo(Long.class).one();
            return new LibraryStats(total, labels, unseen, addedMonth, addedYear);
        });
    }

    @Override
    public List<Title> findSpotlightCandidates(java.util.Set<String> lovedLabels,
                                                 java.util.Set<Long> lovedActressIds,
                                                 java.util.Set<String> superstarTiers,
                                                 int limit,
                                                 java.util.Set<String> excludeCodes) {
        boolean hasLovedLabels   = lovedLabels   != null && !lovedLabels.isEmpty();
        boolean hasLovedActress  = lovedActressIds != null && !lovedActressIds.isEmpty();
        boolean hasSuperstarTier = superstarTiers != null && !superstarTiers.isEmpty();
        boolean hasExclusion     = excludeCodes  != null && !excludeCodes.isEmpty();

        StringBuilder where = new StringBuilder("WHERE t.actress_id IS NOT NULL AND (t.favorite = 1 OR t.bookmark = 1");
        if (hasLovedLabels)   where.append(" OR t.label IN (<lovedLabels>)");
        if (hasLovedActress)  where.append(" OR t.actress_id IN (<lovedActressIds>)");
        if (hasSuperstarTier) where.append(" OR a.tier IN (<superstarTiers>)");
        where.append(")");
        if (hasExclusion) where.append(" AND t.code NOT IN (<excludeCodes>)");

        String sql = "SELECT t.* FROM titles t " +
                "LEFT JOIN actresses a ON a.id = t.actress_id " +
                where + " ORDER BY RANDOM() LIMIT :limit";

        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql).bind("limit", limit);
            if (hasLovedLabels)   q.bindList("lovedLabels", lovedLabels.stream().map(String::toUpperCase).toList());
            if (hasLovedActress)  q.bindList("lovedActressIds", new java.util.ArrayList<>(lovedActressIds));
            if (hasSuperstarTier) q.bindList("superstarTiers", new java.util.ArrayList<>(superstarTiers));
            if (hasExclusion)     q.bindList("excludeCodes", new java.util.ArrayList<>(excludeCodes));
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findForgottenAtticCandidates(int limit, java.util.Set<String> excludeCodes) {
        String exclusion = excludeCodes != null && !excludeCodes.isEmpty()
                ? "AND t.code NOT IN (<excludeCodes>) " : "";
        String d180  = java.time.LocalDate.now().minusDays(180).toString();
        String d60   = java.time.LocalDate.now().minusDays(60).toString();
        String d365  = java.time.LocalDate.now().minusDays(365).toString();

        // Age window: recent (<60d) OR old (>365d) — skip the 2-12 month middle.
        // Neglect: visit_count = 0 OR last_visited_at < now - 180d.
        String sql = """
                SELECT t.* FROM titles t
                JOIN title_locations tl ON t.id = tl.title_id
                WHERE t.actress_id IS NOT NULL
                  AND (t.visit_count = 0 OR t.last_visited_at < :d180)
                  """ + exclusion + """
                GROUP BY t.id
                HAVING MIN(tl.added_date) >= :d60 OR MIN(tl.added_date) <= :d365
                ORDER BY RANDOM()
                LIMIT :limit
                """;
        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql)
                    .bind("d180", d180)
                    .bind("d60", d60)
                    .bind("d365", d365)
                    .bind("limit", limit);
            if (!exclusion.isEmpty()) q.bindList("excludeCodes", new java.util.ArrayList<>(excludeCodes));
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public List<Title> findForgottenFavoritesCandidates(int limit, java.util.Set<String> excludeCodes) {
        String exclusion = excludeCodes != null && !excludeCodes.isEmpty()
                ? "AND t.code NOT IN (<excludeCodes>) " : "";
        String d90   = java.time.LocalDate.now().minusDays(90).toString();

        String sql = """
                SELECT t.* FROM titles t
                WHERE t.favorite = 1
                  AND (t.last_visited_at IS NULL OR t.last_visited_at < :d90)
                  """ + exclusion + """
                LIMIT :limit
                """;
        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql)
                    .bind("d90", d90)
                    .bind("limit", limit);
            if (!exclusion.isEmpty()) q.bindList("excludeCodes", new java.util.ArrayList<>(excludeCodes));
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
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

    @Override
    public List<FederatedTitleResult> searchByCodePrefix(String prefix, int limit) {
        String pattern = prefix.toUpperCase() + "%";
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.id, t.code, t.title_original, t.title_english, t.label, t.base_code,
                               t.release_date, t.actress_id, a.canonical_name AS actress_name,
                               t.favorite, t.bookmark
                        FROM titles t
                        LEFT JOIN actresses a ON a.id = t.actress_id
                        WHERE t.code LIKE :pattern COLLATE NOCASE
                          AND t.rejected = 0
                        ORDER BY t.favorite DESC, t.bookmark DESC, t.id DESC
                        LIMIT :limit
                        """)
                        .bind("pattern", pattern)
                        .bind("limit", limit)
                        .map((rs, ctx) -> {
                            String actressIdStr = rs.getString("actress_id");
                            return new FederatedTitleResult(
                                    rs.getLong("id"),
                                    rs.getString("code"),
                                    rs.getString("title_original"),
                                    rs.getString("title_english"),
                                    rs.getString("label"),
                                    rs.getString("base_code"),
                                    rs.getString("release_date"),
                                    actressIdStr != null ? Long.parseLong(actressIdStr) : null,
                                    rs.getString("actress_name"),
                                    rs.getBoolean("favorite"),
                                    rs.getBoolean("bookmark")
                            );
                        })
                        .list()
        );
    }

    @Override
    public List<FederatedTitleResult> searchByTitleName(String query, boolean startsWith, int limit) {
        String pattern = startsWith ? query + "%" : "%" + query + "%";
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.id, t.code, t.title_original, t.title_english, t.label, t.base_code,
                               t.release_date, t.actress_id, a.canonical_name AS actress_name,
                               t.favorite, t.bookmark
                        FROM titles t
                        LEFT JOIN actresses a ON a.id = t.actress_id
                        WHERE (t.title_original LIKE :pattern COLLATE NOCASE
                            OR t.title_english  LIKE :pattern COLLATE NOCASE)
                          AND t.rejected = 0
                        ORDER BY t.favorite DESC, t.bookmark DESC, t.id DESC
                        LIMIT :limit
                        """)
                        .bind("pattern", pattern)
                        .bind("limit", limit)
                        .map((rs, ctx) -> {
                            String actressIdStr = rs.getString("actress_id");
                            return new FederatedTitleResult(
                                    rs.getLong("id"),
                                    rs.getString("code"),
                                    rs.getString("title_original"),
                                    rs.getString("title_english"),
                                    rs.getString("label"),
                                    rs.getString("base_code"),
                                    rs.getString("release_date"),
                                    actressIdStr != null ? Long.parseLong(actressIdStr) : null,
                                    rs.getString("actress_name"),
                                    rs.getBoolean("favorite"),
                                    rs.getBoolean("bookmark")
                            );
                        })
                        .list()
        );
    }

    // ── Library browse ───────────────────────────────────────────────────────

    @Override
    public List<String> findLabelCodesWithPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return List.of();
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT UPPER(label) FROM titles
                        WHERE UPPER(label) LIKE UPPER(:prefix) || '%'
                        ORDER BY UPPER(label)
                        LIMIT 20
                        """)
                        .bind("prefix", prefix.toUpperCase())
                        .mapTo(String.class)
                        .list()
        );
    }

    @Override
    public List<Title> findLibraryPaged(String labelPrefix, String seqPrefix,
                                         List<String> companyLabels, List<String> tags,
                                         List<Long> enrichmentTagIds,
                                         String sort, boolean asc,
                                         int limit, int offset) {
        String safeLabel   = labelPrefix   != null ? labelPrefix   : "";
        String safeSeq     = seqPrefix     != null ? seqPrefix     : "";
        List<String> safeCompany       = companyLabels    != null ? companyLabels    : List.of();
        List<String> safeTags          = tags             != null ? tags             : List.of();
        List<Long>   safeEnrichTags    = enrichmentTagIds != null ? enrichmentTagIds : List.of();
        boolean hasLabelPrefix    = !safeLabel.isBlank();
        boolean hasSeqPrefix      = !safeSeq.isBlank();
        boolean hasCompany        = !safeCompany.isEmpty();
        boolean hasTags           = !safeTags.isEmpty();
        boolean hasEnrichmentTags = !safeEnrichTags.isEmpty();
        boolean sortByActress     = "actressName".equals(sort);
        boolean sortByProduct     = "productCode".equals(sort);
        // default is "addedDate"

        StringBuilder sql = new StringBuilder("SELECT t.* FROM titles t\n");
        sql.append("LEFT JOIN title_locations tl ON t.id = tl.title_id\n");
        if (sortByActress) {
            sql.append("LEFT JOIN actresses a ON t.actress_id = a.id\n");
        }
        if (hasTags) {
            sql.append("JOIN title_effective_tags tet ON tet.title_id = t.id AND tet.tag IN (<tags>)\n");
        }
        if (hasEnrichmentTags) {
            sql.append("JOIN title_enrichment_tags tet_e ON tet_e.title_id = t.id AND tet_e.tag_id IN (<enrichmentTagIds>)\n");
        }
        sql.append("WHERE 1=1\n");
        if (hasLabelPrefix) {
            sql.append("AND UPPER(t.label) LIKE UPPER(:labelPrefix) || '%'\n");
        }
        if (hasSeqPrefix) {
            sql.append("AND CAST(t.seq_num AS TEXT) LIKE :seqPrefix || '%'\n");
        }
        if (hasCompany) {
            sql.append("AND UPPER(t.label) IN (<companyLabels>)\n");
        }
        sql.append("GROUP BY t.id\n");
        if (hasTags || hasEnrichmentTags) {
            List<String> havingClauses = new java.util.ArrayList<>();
            if (hasTags)           havingClauses.add("COUNT(DISTINCT tet.tag) = :tagCount");
            if (hasEnrichmentTags) havingClauses.add("COUNT(DISTINCT tet_e.tag_id) = :enrichmentTagCount");
            sql.append("HAVING ").append(String.join(" AND ", havingClauses)).append("\n");
        }

        String dir = asc ? "ASC" : "DESC";
        if (sortByProduct) {
            sql.append("ORDER BY t.label ").append(dir).append(", t.seq_num ").append(dir).append("\n");
        } else if (sortByActress) {
            // NULLS LAST is achieved by CASE trick in SQLite
            if (asc) {
                sql.append("ORDER BY CASE WHEN a.canonical_name IS NULL THEN 1 ELSE 0 END, a.canonical_name ASC, t.label ASC, t.seq_num ASC\n");
            } else {
                sql.append("ORDER BY CASE WHEN a.canonical_name IS NULL THEN 0 ELSE 1 END, a.canonical_name DESC, t.label DESC, t.seq_num DESC\n");
            }
        } else {
            // addedDate — use MIN(tl.added_date) from the LEFT JOIN
            sql.append("ORDER BY MIN(tl.added_date) ").append(dir).append(", t.id ").append(asc ? "ASC" : "DESC").append("\n");
        }
        sql.append("LIMIT :limit OFFSET :offset");

        List<Title> titles = jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString())
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (hasLabelPrefix)    q = q.bind("labelPrefix", safeLabel.toUpperCase());
            if (hasSeqPrefix)      q = q.bind("seqPrefix", safeSeq);
            if (hasCompany)        q = q.bindList("companyLabels", safeCompany.stream().map(String::toUpperCase).toList());
            if (hasTags)           q = q.bindList("tags", safeTags).bind("tagCount", safeTags.size());
            if (hasEnrichmentTags) q = q.bindList("enrichmentTagIds", safeEnrichTags).bind("enrichmentTagCount", safeEnrichTags.size());
            return q.map(MAPPER).list();
        });
        return populateLocationsBatch(titles);
    }

    @Override
    public Map<String, Long> getTagCounts() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT tag, COUNT(DISTINCT title_id) AS cnt
                FROM title_effective_tags
                GROUP BY tag
                ORDER BY tag
                """)
                .reduceRows(new java.util.LinkedHashMap<>(), (acc, row) -> {
                    acc.put(row.getColumn("tag", String.class), row.getColumn("cnt", Long.class));
                    return acc;
                }));
    }

    // ── Duplication management ────────────────────────────────────────────────

    @Override
    public List<Title> findWithMultipleLocationsPaged(int limit, int offset, String volumeId) {
        String volumeFilter = volumeId != null
                ? "AND EXISTS (SELECT 1 FROM title_locations vf WHERE vf.title_id = t.id AND vf.volume_id = :volumeId)"
                : "";
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        JOIN (
                            SELECT title_id FROM title_locations
                            GROUP BY title_id
                            HAVING COUNT(*) > 1
                        ) dup ON dup.title_id = t.id
                        WHERE 1=1 <volumeFilter>
                        ORDER BY t.code ASC
                        LIMIT :limit OFFSET :offset
                        """.replace("<volumeFilter>", volumeFilter))
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .bindMap(volumeId != null ? Map.of("volumeId", volumeId) : Map.of())
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public int countWithMultipleLocations(String volumeId) {
        String volumeFilter = volumeId != null
                ? "AND EXISTS (SELECT 1 FROM title_locations vf WHERE vf.title_id = t.id AND vf.volume_id = :volumeId)"
                : "";
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM titles t
                        JOIN (
                            SELECT title_id FROM title_locations
                            GROUP BY title_id
                            HAVING COUNT(*) > 1
                        ) dup ON dup.title_id = t.id
                        WHERE 1=1 <volumeFilter>
                        """.replace("<volumeFilter>", volumeFilter))
                        .bindMap(volumeId != null ? Map.of("volumeId", volumeId) : Map.of())
                        .mapTo(Integer.class)
                        .one()
        );
    }

    // ── Backup / restore ─────────────────────────────────────────────────────

    @Override
    public List<TitleBackupRow> findAllForBackup() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT code, favorite, bookmark, bookmarked_at,
                               grade, rejected, visit_count, last_visited_at, notes
                        FROM titles
                        WHERE favorite = 1 OR bookmark = 1 OR grade IS NOT NULL
                           OR rejected = 1 OR visit_count > 0 OR notes IS NOT NULL
                        ORDER BY code
                        """)
                        .map((rs, ctx) -> {
                            String bookmarkedAtStr = rs.getString("bookmarked_at");
                            String lastVisitedStr = rs.getString("last_visited_at");
                            return new TitleBackupRow(
                                    rs.getString("code"),
                                    rs.getBoolean("favorite"),
                                    rs.getBoolean("bookmark"),
                                    bookmarkedAtStr != null ? LocalDateTime.parse(bookmarkedAtStr) : null,
                                    rs.getString("grade"),
                                    rs.getBoolean("rejected"),
                                    rs.getInt("visit_count"),
                                    lastVisitedStr != null ? LocalDateTime.parse(lastVisitedStr) : null,
                                    rs.getString("notes")
                            );
                        })
                        .list()
        );
    }

    @Override
    public void restoreUserData(String code, boolean favorite, boolean bookmark,
                                LocalDateTime bookmarkedAt, String grade,
                                boolean rejected, int visitCount,
                                LocalDateTime lastVisitedAt, String notes) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE titles
                        SET favorite        = :favorite,
                            bookmark        = :bookmark,
                            bookmarked_at   = :bookmarkedAt,
                            grade           = :grade,
                            rejected        = :rejected,
                            visit_count     = :visitCount,
                            last_visited_at = :lastVisitedAt,
                            notes           = :notes
                        WHERE code = :code
                        """)
                        .bind("code", code)
                        .bind("favorite", favorite)
                        .bind("bookmark", bookmark)
                        .bind("bookmarkedAt", bookmarkedAt != null ? bookmarkedAt.toString() : null)
                        .bind("grade", grade)
                        .bind("rejected", rejected)
                        .bind("visitCount", visitCount)
                        .bind("lastVisitedAt", lastVisitedAt != null ? lastVisitedAt.toString() : null)
                        .bind("notes", notes)
                        .execute()
        );
    }

    @Override
    public Map<Long, List<TitleSummary.EnrichmentTagEntry>> findEnrichmentTagsByTitleIds(
            Collection<Long> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) return Map.of();
        List<Long> ids = new ArrayList<>(titleIds);
        return jdbi.withHandle(h -> {
            List<Map<String, Object>> rows = h.createQuery("""
                    SELECT tet.title_id, etd.name, etd.curated_alias
                    FROM title_enrichment_tags tet
                    JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                    WHERE tet.title_id IN (<ids>)
                    ORDER BY tet.title_id, etd.name
                    """)
                    .bindList("ids", ids)
                    .mapToMap()
                    .list();
            Map<Long, List<TitleSummary.EnrichmentTagEntry>> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                long titleId = ((Number) row.get("title_id")).longValue();
                String name = (String) row.get("name");
                String curatedAlias = (String) row.get("curated_alias");
                result.computeIfAbsent(titleId, k -> new ArrayList<>())
                        .add(new TitleSummary.EnrichmentTagEntry(name, curatedAlias));
            }
            return result;
        });
    }
}
