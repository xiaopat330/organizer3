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
        return new Title(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("base_code"),
                rs.getString("volume_id"),
                rs.getString("partition_id"),
                actressIdStr != null ? Long.parseLong(actressIdStr) : null,
                Path.of(rs.getString("path")),
                LocalDate.parse(rs.getString("last_seen_at"))
        );
    };

    private final Jdbi jdbi;

    public JdbiTitleRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
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
    public Title save(Title title) {
        return jdbi.withHandle(h -> {
            if (title.id() == null) {
                long id = h.createUpdate("""
                                INSERT INTO titles
                                    (code, base_code, volume_id, partition_id, actress_id, path, last_seen_at)
                                VALUES (:code, :baseCode, :volumeId, :partitionId, :actressId, :path, :lastSeenAt)
                                """)
                        .bind("code", title.code())
                        .bind("baseCode", title.baseCode())
                        .bind("volumeId", title.volumeId())
                        .bind("partitionId", title.partitionId())
                        .bind("actressId", title.actressId())
                        .bind("path", title.path().toString())
                        .bind("lastSeenAt", title.lastSeenAt().toString())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return new Title(id, title.code(), title.baseCode(), title.volumeId(),
                        title.partitionId(), title.actressId(), title.path(), title.lastSeenAt());
            } else {
                h.createUpdate("""
                                UPDATE titles SET
                                    code = :code, base_code = :baseCode, volume_id = :volumeId,
                                    partition_id = :partitionId, actress_id = :actressId,
                                    path = :path, last_seen_at = :lastSeenAt
                                WHERE id = :id
                                """)
                        .bind("id", title.id())
                        .bind("code", title.code())
                        .bind("baseCode", title.baseCode())
                        .bind("volumeId", title.volumeId())
                        .bind("partitionId", title.partitionId())
                        .bind("actressId", title.actressId())
                        .bind("path", title.path().toString())
                        .bind("lastSeenAt", title.lastSeenAt().toString())
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
}
