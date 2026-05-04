package com.organizer3.translation;

import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Dispatches translation callbacks to catalog fields once a translation completes.
 *
 * <p>Each callback kind maps to a {@code BiConsumer<Long, String>} that receives the catalog
 * row id and the translated English text and writes it to the appropriate column.
 *
 * <p>If {@code callbackKind} is null, no callback runs (fire-and-forget mode). If
 * {@code callbackKind} is unknown, a warning is logged but no error is thrown — the
 * translation_cache row is still valid and useful for future lookups.
 *
 * <p>If the callback target row has been deleted, the update affects 0 rows and is silently
 * skipped (the cache row is still retained).
 *
 * <p>If the callback target column is already non-null (human edit or race), the update is
 * skipped to avoid clobbering a manual correction.
 */
@Slf4j
public class CallbackDispatcher {

    private final Map<String, BiConsumer<Long, String>> handlers = new HashMap<>();
    private final Jdbi jdbi;

    public CallbackDispatcher(Jdbi jdbi) {
        this.jdbi = jdbi;
        registerBuiltins();
    }

    /**
     * Register a custom callback kind. Visible for testing.
     */
    public void register(String kind, BiConsumer<Long, String> handler) {
        handlers.put(kind, handler);
    }

    /**
     * Dispatch a callback if kind is non-null and registered.
     *
     * @param callbackKind  the callback identifier, or null for fire-and-forget
     * @param callbackId    the catalog row id
     * @param englishText   the translated text to write
     */
    public void dispatch(String callbackKind, Long callbackId, String englishText) {
        if (callbackKind == null || callbackId == null) {
            return; // fire-and-forget
        }
        BiConsumer<Long, String> handler = handlers.get(callbackKind);
        if (handler == null) {
            log.warn("CallbackDispatcher: unknown callback_kind='{}' — skipping dispatch (cache still written)",
                    callbackKind);
            return;
        }
        try {
            handler.accept(callbackId, englishText);
        } catch (Exception e) {
            log.warn("CallbackDispatcher: callback '{}' id={} threw exception — skipping (cache still written): {}",
                    callbackKind, callbackId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Built-in registrations for title_javdb_enrichment *_en columns
    // -------------------------------------------------------------------------

    private void registerBuiltins() {
        register("title_javdb_enrichment.title_original_en",
                (id, en) -> updateEnrichmentColumn(id, "title_original_en", en));
        register("title_javdb_enrichment.series_en",
                (id, en) -> updateEnrichmentColumn(id, "series_en", en));
        register("title_javdb_enrichment.maker_en",
                (id, en) -> updateEnrichmentColumn(id, "maker_en", en));
        register("title_javdb_enrichment.publisher_en",
                (id, en) -> updateEnrichmentColumn(id, "publisher_en", en));
    }

    /**
     * Update a single *_en column on title_javdb_enrichment, skipping if already set
     * (to avoid clobbering manual corrections).
     */
    private void updateEnrichmentColumn(long titleId, String column, String englishText) {
        // Guard: only update if the column is currently null (skip if already populated)
        int updated = jdbi.withHandle(h ->
                h.createUpdate(
                        "UPDATE title_javdb_enrichment SET " + column + " = :en " +
                        "WHERE title_id = :id AND " + column + " IS NULL")
                        .bind("en", englishText)
                        .bind("id", titleId)
                        .execute()
        );
        if (updated == 0) {
            log.debug("CallbackDispatcher: {} for title_id={} already set or row missing — skipped",
                    column, titleId);
        } else {
            log.info("CallbackDispatcher: wrote {} for title_id={}", column, titleId);
        }
    }
}
