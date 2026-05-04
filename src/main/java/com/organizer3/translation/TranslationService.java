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
     * Ensure a translation exists in the cache for the given request.
     *
     * <p>If the text is already cached (any strategy), returns immediately with the cached
     * row id. Otherwise calls Ollama synchronously in the calling thread and writes the result
     * to the cache before returning.
     *
     * <p>In Phase 2 this will enqueue work instead of calling synchronously — callers should
     * not rely on the translation being available immediately after this call returns.
     *
     * @return the {@code translation_cache} row id (new or existing)
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
