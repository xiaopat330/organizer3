package com.organizer3.web;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared helper that derives the ordered list of actress names for a title's staging folder,
 * sourced entirely from canonical DB state.
 *
 * <h3>Ordering contract</h3>
 * <ol>
 *   <li>First: the {@code canonical_name} of {@code titles.actress_id} (the filing actress).</li>
 *   <li>Then: every other {@code title_actresses} row, excluding the filing actress and any row
 *       whose actress has {@code is_sentinel = 1}, ordered by {@code title_actresses.rowid ASC}.</li>
 * </ol>
 *
 * <p>If {@code titles.actress_id} is NULL the method returns an empty list; callers treat an
 * empty list as a no-op and must NOT invent a primary.
 *
 * <p>Used by {@link com.organizer3.javdb.draft.DraftPromotionService},
 * {@link com.organizer3.javdb.draft.PromotionFolderRenameReconciler}, and
 * {@link UnsortedEditorService} so all three agree byte-for-byte on the computed folder name.
 * Never depends on a {@link TitleFolderRenamer} instance, which is mockable in tests; callers
 * pass the list into the renamer.
 */
public final class StagingCastHelper {

    private StagingCastHelper() {}

    /**
     * Returns the ordered folder-name actress names for the given title, resolved from canonical
     * DB state via the provided JDBI handle (shares the caller's transaction when inside one).
     *
     * @param h       an open JDBI handle.
     * @param titleId the title to query.
     * @return ordered list of canonical actress names; empty when {@code titles.actress_id} is NULL.
     */
    public static List<String> orderedNamesForTitle(Handle h, long titleId) {
        // Step 1: resolve the filing actress id and canonical name.
        Long filingId = h.createQuery("SELECT actress_id FROM titles WHERE id = :id")
                .bind("id", titleId)
                .mapTo(Long.class)
                .findFirst()
                .orElse(null);
        if (filingId == null) {
            return List.of();
        }

        String filingName = h.createQuery(
                "SELECT canonical_name FROM actresses WHERE id = :id")
                .bind("id", filingId)
                .mapTo(String.class)
                .findFirst()
                .orElse(null);
        if (filingName == null) {
            return List.of();
        }

        // Step 2: collect the co-credits (excluding filing actress and sentinels), rowid-ordered.
        List<String> coNames = h.createQuery("""
                SELECT a.canonical_name
                FROM title_actresses ta
                JOIN actresses a ON a.id = ta.actress_id
                WHERE ta.title_id = :titleId
                  AND ta.actress_id != :filingId
                  AND COALESCE(a.is_sentinel, 0) = 0
                ORDER BY ta.rowid ASC
                """)
                .bind("titleId", titleId)
                .bind("filingId", filingId)
                .mapTo(String.class)
                .list();

        List<String> result = new ArrayList<>(1 + coNames.size());
        result.add(filingName);
        result.addAll(coNames);
        return result;
    }

    /**
     * Convenience overload that opens its own handle. Use when there is no ambient transaction.
     *
     * @param jdbi    a JDBI instance.
     * @param titleId the title to query.
     * @return ordered list of canonical actress names; empty when {@code titles.actress_id} is NULL.
     */
    public static List<String> orderedNamesForTitle(Jdbi jdbi, long titleId) {
        return jdbi.withHandle(h -> orderedNamesForTitle(h, titleId));
    }
}
