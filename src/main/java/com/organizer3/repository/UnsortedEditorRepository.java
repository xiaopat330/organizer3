package com.organizer3.repository;

import java.util.List;
import java.util.Optional;

/**
 * Queries and mutations used by the Title Editor to enrich fully-structured titles
 * in the unsorted (queue) volume. See {@code spec/PROPOSAL_TITLE_EDITOR.md}.
 */
public interface UnsortedEditorRepository {

    /** Lightweight row for the editor sidebar queue. */
    record EligibleTitle(
            long titleId,
            String code,
            String folderName,
            String label,
            String baseCode,
            int actressCount,
            /** Full path on the NAS to the title folder (SMB share-relative). */
            String folderPath
    ) {}

    /** Actress row for the editor's actress panel. */
    record AssignedActress(
            long actressId,
            String canonicalName,
            String stageName,
            boolean primary
    ) {}

    /** Full detail for a single eligible title. */
    record TitleDetail(
            long titleId,
            String code,
            String folderName,
            String label,
            String baseCode,
            String folderPath,
            List<AssignedActress> actresses
    ) {}

    /**
     * Return all eligible titles in the unsorted volume, ordered FIFO by discovery
     * ({@code title_locations.added_date} ASC). A title is eligible when all hold:
     * <ol>
     *   <li>The folder name contains the title's base code wrapped in parentheses.</li>
     *   <li>The title has a parseable code (guaranteed for any row in {@code titles}).</li>
     *   <li>At least one video for the title+volume has a parent directory named
     *       {@code video}, {@code h265}, or {@code 4K} under the title folder.</li>
     * </ol>
     */
    List<EligibleTitle> listEligible(String volumeId);

    /** Load full detail for a single title. Returns empty if the title is not eligible. */
    Optional<TitleDetail> findEligibleById(long titleId, String volumeId);

    /** Returns true when the title still has a location on {@code volumeId}. */
    boolean hasLocationInVolume(long titleId, String volumeId);

    /**
     * Create a draft actress inline from the Title Editor flow. Canonical name is the only
     * input; the actress is persisted with {@code needs_profiling = 1}, tier {@code LIBRARY},
     * and {@code first_seen_at} set to today. Returns the generated id.
     *
     * <p>Intended to run inside a transaction alongside the title_actresses update so that a
     * failed save leaves no orphan row.
     */
    long createDraftActress(org.jdbi.v3.core.Handle h, String canonicalName);

    /**
     * Replace the actress assignments for a title in a single transaction:
     * <ul>
     *   <li>Delete any existing {@code title_actresses} rows for the title.</li>
     *   <li>Insert rows for every id in {@code actressIds}.</li>
     *   <li>Update {@code titles.actress_id} to {@code primaryActressId}.</li>
     * </ul>
     */
    void replaceActresses(long titleId, List<Long> actressIds, long primaryActressId);

    /** Run {@code work} inside a single JDBI transaction. */
    <T> T inTransaction(java.util.function.Function<org.jdbi.v3.core.Handle, T> work);

    /**
     * In-transaction variant of {@link #replaceActresses} — the caller supplies the Handle
     * so this can run alongside other mutations (e.g. draft actress creation) in a single
     * transaction.
     */
    void replaceActressesInTx(org.jdbi.v3.core.Handle h, long titleId, List<Long> actressIds, long primaryActressId);
}
