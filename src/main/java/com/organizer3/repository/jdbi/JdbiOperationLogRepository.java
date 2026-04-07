package com.organizer3.repository.jdbi;

import com.organizer3.model.OperationLogEntry;
import com.organizer3.repository.OperationLogRepository;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

public class JdbiOperationLogRepository implements OperationLogRepository {

    private static final RowMapper<OperationLogEntry> MAPPER = (rs, ctx) -> {
        String destPath = rs.getString("dest_path");
        return OperationLogEntry.builder()
                .id(rs.getLong("id"))
                .timestamp(LocalDateTime.parse(rs.getString("timestamp")))
                .type(OperationLogEntry.OperationType.valueOf(rs.getString("type")))
                .sourcePath(Path.of(rs.getString("source_path")))
                .destPath(destPath != null ? Path.of(destPath) : null)
                .wasArmed(rs.getInt("was_armed") == 1)
                .build();
    };

    private final Jdbi jdbi;

    public JdbiOperationLogRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void log(OperationLogEntry entry) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO operations (timestamp, type, source_path, dest_path, was_armed)
                        VALUES (:timestamp, :type, :sourcePath, :destPath, :wasArmed)
                        """)
                        .bind("timestamp", entry.getTimestamp().toString())
                        .bind("type", entry.getType().name())
                        .bind("sourcePath", entry.getSourcePath().toString())
                        .bind("destPath", entry.getDestPath() != null ? entry.getDestPath().toString() : null)
                        .bind("wasArmed", entry.isWasArmed() ? 1 : 0)
                        .execute()
        );
    }

    @Override
    public List<OperationLogEntry> findSince(LocalDateTime since) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM operations
                        WHERE timestamp >= :since
                        ORDER BY timestamp
                        """)
                        .bind("since", since.toString())
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<OperationLogEntry> findAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM operations ORDER BY timestamp")
                        .map(MAPPER)
                        .list()
        );
    }
}
