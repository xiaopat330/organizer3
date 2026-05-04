package com.organizer3.translation;


/**
 * Input to {@link TranslationService#requestTranslation}.
 *
 * <p>Three usage modes:
 * <ol>
 *   <li>Fire-and-forget: {@code callbackKind} and {@code callbackId} are null. The caller wants
 *       the result cached but does not need it delivered anywhere specific.</li>
 *   <li>Scheduled with callback: {@code callbackKind} and {@code callbackId} identify the catalog
 *       field to update once the translation completes (Phase 2+ feature; supported in the data
 *       model but not dispatched in Phase 1).</li>
 * </ol>
 */
public record TranslationRequest(
        String sourceText,
        /** Optional context hint — {@code "prose"}, {@code "label_basic"}, or {@code "label_explicit"}. */
        String contextHint,
        /** Catalog field to update on success, e.g. {@code "title.title_original_en"}. */
        String callbackKind,
        /** Row id of the catalog row to update. */
        Long callbackId
) {}
