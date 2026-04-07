package com.organizer3.repository.jdbi;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

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
                h.createQuery("SELECT * FROM titles WHERE actress_id = :actressId ORDER BY code")
                        .bind("actressId", actressId)
                        .map(MAPPER)
                        .list()
        );
        return populateLocationsBatch(titles);
    }

    @Override
    public int countByActress(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles WHERE actress_id = :actressId")
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
                        ORDER BY MIN(tl.added_date) DESC, t.id DESC
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
                        ORDER BY MIN(tl.added_date) DESC, t.id DESC
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
                        ORDER BY MIN(tl.added_date) DESC, t.id DESC
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
    public List<Title> findByVolumeAndPartition(String volumeId, String partitionId, int limit, int offset) {
        List<Title> titles = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.* FROM titles t
                        JOIN title_locations tl ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId AND tl.partition_id = :partitionId
                        GROUP BY t.id
                        ORDER BY MIN(tl.added_date) DESC, t.id DESC
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
