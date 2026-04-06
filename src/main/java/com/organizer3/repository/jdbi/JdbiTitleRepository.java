package com.organizer3.repository.jdbi;

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
        return new Title(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("base_code"),
                rs.getString("label"),
                seqNumStr != null ? Integer.parseInt(seqNumStr) : null,
                rs.getString("volume_id"),
                rs.getString("partition_id"),
                actressIdStr != null ? Long.parseLong(actressIdStr) : null,
                Path.of(rs.getString("path")),
                LocalDate.parse(rs.getString("last_seen_at")),
                addedDateStr != null ? LocalDate.parse(addedDateStr) : null
        );
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
            if (title.id() == null) {
                long id = h.createUpdate("""
                                INSERT INTO titles
                                    (code, base_code, label, seq_num, volume_id, partition_id, actress_id, path, last_seen_at, added_date)
                                VALUES (:code, :baseCode, :label, :seqNum, :volumeId, :partitionId, :actressId, :path, :lastSeenAt, :addedDate)
                                """)
                        .bind("code", title.code())
                        .bind("baseCode", title.baseCode())
                        .bind("label", title.label())
                        .bind("seqNum", title.seqNum())
                        .bind("volumeId", title.volumeId())
                        .bind("partitionId", title.partitionId())
                        .bind("actressId", title.actressId())
                        .bind("path", title.path().toString())
                        .bind("lastSeenAt", title.lastSeenAt().toString())
                        .bind("addedDate", title.addedDate() != null ? title.addedDate().toString() : null)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return new Title(id, title.code(), title.baseCode(), title.label(), title.seqNum(),
                        title.volumeId(), title.partitionId(), title.actressId(), title.path(), title.lastSeenAt(),
                        title.addedDate());
            } else {
                h.createUpdate("""
                                UPDATE titles SET
                                    code = :code, base_code = :baseCode, label = :label, seq_num = :seqNum,
                                    volume_id = :volumeId, partition_id = :partitionId, actress_id = :actressId,
                                    path = :path, last_seen_at = :lastSeenAt, added_date = :addedDate
                                WHERE id = :id
                                """)
                        .bind("id", title.id())
                        .bind("code", title.code())
                        .bind("baseCode", title.baseCode())
                        .bind("label", title.label())
                        .bind("seqNum", title.seqNum())
                        .bind("volumeId", title.volumeId())
                        .bind("partitionId", title.partitionId())
                        .bind("actressId", title.actressId())
                        .bind("path", title.path().toString())
                        .bind("lastSeenAt", title.lastSeenAt().toString())
                        .bind("addedDate", title.addedDate() != null ? title.addedDate().toString() : null)
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
