package com.organizer3.repository.jdbi;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class JdbiTitleRepository implements TitleRepository {

    private static final RowMapper<Title> MAPPER = (rs, ctx) -> {
        String actressIdStr = rs.getString("actress_id");
        String seqNumStr = rs.getString("seq_num");
        String addedDateStr = rs.getString("added_date");
        String gradeStr = rs.getString("grade");
        return Title.builder()
                .id(rs.getLong("id"))
                .code(rs.getString("code"))
                .baseCode(rs.getString("base_code"))
                .label(rs.getString("label"))
                .seqNum(seqNumStr != null ? Integer.parseInt(seqNumStr) : null)
                .volumeId(rs.getString("volume_id"))
                .partitionId(rs.getString("partition_id"))
                .actressId(actressIdStr != null ? Long.parseLong(actressIdStr) : null)
                .path(Path.of(rs.getString("path")))
                .lastSeenAt(LocalDate.parse(rs.getString("last_seen_at")))
                .addedDate(addedDateStr != null ? LocalDate.parse(addedDateStr) : null)
                .favorite(rs.getBoolean("favorite"))
                .bookmark(rs.getBoolean("bookmark"))
                .grade(gradeStr != null ? Actress.Grade.fromDisplay(gradeStr) : null)
                .rejected(rs.getBoolean("rejected"))
                .build();
    };

    private final Jdbi jdbi;

    public JdbiTitleRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

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
        );
    }

    @Override
    public Optional<Title> findByCode(String code) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE code = :code")
                        .bind("code", code)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public List<Title> findByBaseCode(String baseCode) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE base_code = :baseCode")
                        .bind("baseCode", baseCode)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<Title> findByVolume(String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE volume_id = :volumeId ORDER BY code")
                        .bind("volumeId", volumeId)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<Title> findByActress(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE actress_id = :actressId ORDER BY code")
                        .bind("actressId", actressId)
                        .map(MAPPER)
                        .list()
        );
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
        return jdbi.withHandle(h ->
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
    }

    @Override
    public List<Title> findByActressIncludingAliases(long actressId) {
        return jdbi.withHandle(h ->
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
    }

    @Override
    public Title save(Title title) {
        return jdbi.withHandle(h -> {
            if (title.getId() == null) {
                long id = h.createUpdate("""
                                INSERT INTO titles
                                    (code, base_code, label, seq_num, volume_id, partition_id, actress_id, path, last_seen_at, added_date,
                                     favorite, bookmark, grade, rejected)
                                VALUES (:code, :baseCode, :label, :seqNum, :volumeId, :partitionId, :actressId, :path, :lastSeenAt, :addedDate,
                                        :favorite, :bookmark, :grade, :rejected)
                                """)
                        .bind("code", title.getCode())
                        .bind("baseCode", title.getBaseCode())
                        .bind("label", title.getLabel())
                        .bind("seqNum", title.getSeqNum())
                        .bind("volumeId", title.getVolumeId())
                        .bind("partitionId", title.getPartitionId())
                        .bind("actressId", title.getActressId())
                        .bind("path", title.getPath().toString())
                        .bind("lastSeenAt", title.getLastSeenAt().toString())
                        .bind("addedDate", title.getAddedDate() != null ? title.getAddedDate().toString() : null)
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
                                    volume_id = :volumeId, partition_id = :partitionId, actress_id = :actressId,
                                    path = :path, last_seen_at = :lastSeenAt, added_date = :addedDate,
                                    favorite = :favorite, bookmark = :bookmark, grade = :grade, rejected = :rejected
                                WHERE id = :id
                                """)
                        .bind("id", title.getId())
                        .bind("code", title.getCode())
                        .bind("baseCode", title.getBaseCode())
                        .bind("label", title.getLabel())
                        .bind("seqNum", title.getSeqNum())
                        .bind("volumeId", title.getVolumeId())
                        .bind("partitionId", title.getPartitionId())
                        .bind("actressId", title.getActressId())
                        .bind("path", title.getPath().toString())
                        .bind("lastSeenAt", title.getLastSeenAt().toString())
                        .bind("addedDate", title.getAddedDate() != null ? title.getAddedDate().toString() : null)
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
    public void delete(long id) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM titles WHERE id = :id")
                        .bind("id", id)
                        .execute()
        );
    }

    @Override
    public void deleteByVolume(String volumeId) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM titles WHERE volume_id = :volumeId")
                        .bind("volumeId", volumeId)
                        .execute()
        );
    }

    @Override
    public List<Title> findRecent(int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE actress_id IS NOT NULL ORDER BY added_date DESC, id DESC LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<Title> findByActressPaged(long actressId, int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE actress_id = :actressId ORDER BY added_date DESC, id DESC LIMIT :limit OFFSET :offset")
                        .bind("actressId", actressId)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<Title> findByVolumeAndPartition(String volumeId, String partitionId, int limit, int offset) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM titles WHERE volume_id = :volumeId AND partition_id = :partitionId ORDER BY added_date DESC, id DESC LIMIT :limit OFFSET :offset")
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public void deleteByVolumeAndPartition(String volumeId, String partitionId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM titles
                        WHERE volume_id = :volumeId AND partition_id = :partitionId""")
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .execute()
        );
    }
}
