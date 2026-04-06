package com.organizer3.repository;

import com.organizer3.model.OperationLogEntry;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only log of all file operations performed by the organizer.
 * Both armed (real) and dry-run operations are recorded; {@code wasArmed} distinguishes them.
 */
public interface OperationLogRepository {

    void log(OperationLogEntry entry);

    /** Return all operations recorded since (inclusive) the given timestamp. */
    List<OperationLogEntry> findSince(LocalDateTime since);

    List<OperationLogEntry> findAll();
}
