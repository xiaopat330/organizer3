package com.organizer3.sync;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;

import java.util.List;

/**
 * In-memory cache of a mounted volume's database records for the current session.
 *
 * <p>Built at mount time (loaded from DB) and refreshed after each sync. Commands that
 * need to read volume data work against this index rather than querying the DB on every call.
 *
 * <p>This is a snapshot — it reflects the DB state at load time. It becomes stale if the
 * filesystem changes without a subsequent sync.
 */
public record VolumeIndex(
        String volumeId,
        List<Title> titles,
        List<Actress> actresses
) {
    public int titleCount()   { return titles.size(); }
    public int actressCount() { return actresses.size(); }

    /** An empty index — used when a volume has no DB records yet (cold state). */
    public static VolumeIndex empty(String volumeId) {
        return new VolumeIndex(volumeId, List.of(), List.of());
    }
}
