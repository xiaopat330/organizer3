package com.organizer3.avstars.repository;

import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.model.AvActress;

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

    /**
     * Copies curation fields (favorite, bookmark, rejected, grade, notes, iafd_id,
     * headshot_path) from {@code fromId} to {@code toId}, then deletes the {@code fromId}
     * row. Used by {@code av migrate} when an actress folder is renamed on disk.
     */
    void migrateCuration(long fromActressId, long toActressId);
}
