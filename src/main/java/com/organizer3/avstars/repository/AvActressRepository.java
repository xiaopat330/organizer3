package com.organizer3.avstars.repository;

import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.backup.AvActressBackupEntry;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link AvActress} records (Western performer library).
 */
public interface AvActressRepository {

    /** Finds an actress by her database ID. */
    Optional<AvActress> findById(long id);

    /** Finds an actress by (volumeId, folderName). */
    Optional<AvActress> findByVolumeAndFolder(String volumeId, String folderName);

    /** Returns all actresses for the given volume, sorted by stage name. */
    List<AvActress> findByVolume(String volumeId);

    /** Returns all actresses across all volumes, sorted by video_count DESC, then stage name. */
    List<AvActress> findAllByVideoCountDesc();

    /** Returns all favorited actresses sorted by stage name. */
    List<AvActress> findFavorites();

    /**
     * Inserts a new actress record and returns the generated ID.
     * Uses INSERT OR IGNORE on (volume_id, folder_name) — returns existing ID if already present.
     */
    long upsert(AvActress actress);

    /** Updates video_count and total_size_bytes for the given actress. */
    void updateCounts(long actressId, int videoCount, long totalSizeBytes);

    /** Updates last_scanned_at to now for the given actress. */
    void updateLastScanned(long actressId, java.time.LocalDateTime at);

    /** Updates curation fields (favorite, bookmark, rejected, grade, notes). */
    void updateCuration(long actressId, boolean favorite, boolean bookmark,
                        boolean rejected, String grade, String notes);

    void toggleFavorite(long actressId, boolean favorite);
    void toggleBookmark(long actressId, boolean bookmark);
    void toggleRejected(long actressId, boolean rejected);
    /** Sets or clears the grade. Pass {@code null} to clear. */
    void setGrade(long actressId, String grade);

    /** Overrides the display name (stage_name) without touching the folder_name identity key. */
    void updateStageName(long actressId, String stageName);

    /**
     * Returns all actresses that have not yet been resolved against IAFD ({@code iafd_id IS NULL}),
     * sorted by stage name. Used by {@code av resolve all}.
     */
    List<AvActress> findUnresolved();

    /**
     * Applies all IAFD-sourced fields from {@code profile} to the actress row and sets
     * {@code last_iafd_synced_at} to now.
     */
    void updateIafdFields(long actressId, IafdResolvedProfile profile, String headshotPath);

    /** Increments visit_count and updates last_visited_at for the given actress. */
    void recordVisit(long actressId);

    /**
     * Copies curation fields (favorite, bookmark, rejected, grade, notes, iafd_id,
     * headshot_path) from {@code fromId} to {@code toId}, then deletes the {@code fromId}
     * row. Used by {@code av migrate} when an actress folder is renamed on disk.
     */
    void migrateCuration(long fromActressId, long toActressId);

    /**
     * Permanently deletes an actress and all her associated videos, video tags,
     * and screenshots. Used by {@code av delete} to remove placeholder/temp rows.
     */
    void delete(long actressId);

    // ── Backup / restore ──────────────────────────────────────────────────────

    /**
     * Lightweight backup projection — all rows where at least one user-altered field
     * differs from its default value.
     */
    record AvActressBackupRow(
            String volumeId,
            String folderName,
            boolean favorite,
            boolean bookmark,
            boolean rejected,
            String grade,
            String notes,
            int visitCount,
            String lastVisitedAt
    ) {}

    /** Returns all AV actress rows that have at least one non-default user field. */
    List<AvActressBackupRow> findAllForBackup();

    /**
     * Overlays user-altered fields from a backup entry onto the row identified by
     * {@code (volumeId, folderName)}. No-ops if the row does not exist.
     */
    void restoreUserData(String volumeId, String folderName, boolean favorite, boolean bookmark,
                         boolean rejected, String grade, String notes,
                         int visitCount, String lastVisitedAt);

    // ── Federated search ──────────────────────────────────────────────────────

    /** Lightweight AV actress projection for federated search results. */
    record FederatedAvActressResult(
            long   id,
            String stageName,
            int    videoCount,
            String headshotPath
    ) {}

    /**
     * Search AV actresses by stage name for the federated search overlay.
     * Returns at most {@code limit} results, ordered by video_count DESC.
     */
    List<FederatedAvActressResult> searchForFederated(String query, int limit);
}
