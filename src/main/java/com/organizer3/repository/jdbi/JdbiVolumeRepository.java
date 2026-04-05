package com.organizer3.repository.jdbi;

import com.organizer3.model.Volume;
import com.organizer3.repository.VolumeRepository;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class JdbiVolumeRepository implements VolumeRepository {

    private static final RowMapper<Volume> MAPPER = (rs, ctx) -> {
        Volume v = new Volume(
                rs.getString("id"),
                rs.getString("structure_type")
        );
        String syncedAt = rs.getString("last_synced_at");
        if (syncedAt != null) {
            v.setLastSyncedAt(LocalDateTime.parse(syncedAt));
        }
        return v;
    };

    private final Jdbi jdbi;

    public JdbiVolumeRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<Volume> findById(String id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM volumes WHERE id = :id")
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public List<Volume> findAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM volumes ORDER BY id")
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public void save(Volume volume) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO volumes (id, structure_type, last_synced_at)
                        VALUES (:id, :structureType, :lastSyncedAt)
                        ON CONFLICT(id) DO UPDATE SET
                            structure_type = excluded.structure_type,
                            last_synced_at = excluded.last_synced_at
                        """)
                        .bind("id", volume.getId())
                        .bind("structureType", volume.getStructureType())
                        .bind("lastSyncedAt", volume.getLastSyncedAt() != null
                                ? volume.getLastSyncedAt().toString() : null)
                        .execute()
        );
    }

    @Override
    public void updateLastSyncedAt(String volumeId, LocalDateTime at) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE volumes SET last_synced_at = :at WHERE id = :id")
                        .bind("at", at.toString())
                        .bind("id", volumeId)
                        .execute()
        );
    }
}
