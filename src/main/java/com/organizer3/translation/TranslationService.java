package com.organizer3.translation;

import java.util.Optional;

/**
 * High-level service for translating Japanese catalog text to English.
 *
 * <p>Two primary usage patterns:
 * <ol>
 *   <li><b>Cached lookup only:</b> {@link #getCached} — pure DB read, never calls Ollama.
 *       Use this in UI display paths where blocking on translation is unacceptable.</li>
 *   <li><b>Request translation:</b> {@link #requestTranslation} — ensures a translation exists
 *       in the cache. In Phase 1 this runs synchronously in the caller's thread; callers that
 *       need non-blocking behavior should invoke this from a background thread.</li>
 * </ol>
 *
 * <p>The lookup-priority rule (§5.5.1): human_corrected_text &gt; english_text &gt; miss.
 */
public interface TranslationService {

    /**
     * Look up a cached translation for the given source text across any active strategy.
     *
     * <p>Returns the best available translation per the lookup-priority rule:
     * human-corrected text wins over LLM text. Returns empty if no translation has been cached.
     *
     * <p>Never calls Ollama. Safe to call from request-handling threads.
     */
    Optional<String> getCached(String sourceText);

    /**
     * Submit translation work. Returns a queue row id immediately.
     *
     * <p>If the text is already cached for the selected strategy, a queue row with
     * {@code status='done'} is written immediately, the callback (if any) is dispatched
     * synchronously, and the queue row id is returned. No worker involvement.
     *
     * <p>If the text is not cached, a queue row with {@code status='pending'} is written
     * and the worker will process it asynchronously. The caller should not expect the
     * translation to be available immediately after this call returns.
     *
     * @return the {@code translation_queue} row id
     */
    long requestTranslation(TranslationRequest req);

    /**
     * Fast lookup for actress stage names via a curated kanji→romaji table.
     *
     * <p>Phase 5 feature — not yet implemented. Throws {@link UnsupportedOperationException}.
     */
    Optional<String> resolveStageName(String kanjiName);

    /** Basic operational statistics (cache sizes, success/failure counts). */
    TranslationServiceStats stats();
}
