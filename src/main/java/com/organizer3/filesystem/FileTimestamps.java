package com.organizer3.filesystem;

import java.time.Instant;

/**
 * The three timestamps a file or folder exposes. Any field may be {@code null} if the
 * underlying filesystem doesn't track it or the value is unknown.
 *
 * <ul>
 *   <li>{@code created} — creation time. May be {@code null} on filesystems that
 *       don't preserve it (e.g. some remote mounts, older Linux volumes).</li>
 *   <li>{@code modified} — last-write time. Always populated for an existing file.</li>
 *   <li>{@code accessed} — last-access time. Often disabled for performance; may be
 *       stale or equal to {@code modified}.</li>
 * </ul>
 */
public record FileTimestamps(Instant created, Instant modified, Instant accessed) {

    /**
     * Returns {@code created} if non-null, else {@code modified}. Useful when
     * computing an "earliest known" time where the creation timestamp is preferred
     * but modification is acceptable.
     */
    public Instant createdOrModified() {
        return created != null ? created : modified;
    }
}
