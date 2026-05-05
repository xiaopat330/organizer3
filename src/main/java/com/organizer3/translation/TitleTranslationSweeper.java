package com.organizer3.translation;

import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository.TitleAwaitingTranslation;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Background sweeper that submits enrichment {@code title_original} values for
 * translation into {@code title_original_en}. Phase 6a of
 * {@code spec/PROPOSAL_TRANSLATION_PHASE6.md}.
 *
 * <p>Each tick:
 * <ol>
 *   <li>Selects up to {@code batchSize} rows where {@code title_original} is non-empty,
 *       {@code title_original_en} is null/empty, and no {@code translation_queue} row
 *       already exists for the {@code title_original_en} callback target on this title.</li>
 *   <li>Submits each via {@link TranslationService#requestTranslation} with
 *       {@code contextHint="label_basic"} — the strategy chosen by the Phase 6 spot-check
 *       (see {@code reference/translation_poc/PHASE6_TITLE_REPORT.md}). The hint is
 *       authoritative in {@link StrategySelector#pick}.</li>
 *   <li>Cache hits resolve immediately via {@link CallbackDispatcher}; misses queue
 *       for the worker.</li>
 * </ol>
 *
 * <p>Dedup: the {@code NOT EXISTS} predicate in
 * {@link JavdbEnrichmentRepository#findTitlesAwaitingTranslation} guarantees each title
 * is enqueued at most once per lifetime regardless of how often the sweeper runs.
 *
 * <p>Edge case: if a cache-hit's callback throws (e.g. DB locked), the queue row stays
 * {@code done} but {@code title_original_en} stays null. The sweeper will then permanently
 * skip this title because the {@code NOT EXISTS} clause sees the {@code done} row.
 * Recovery: {@code DELETE FROM translation_queue WHERE callback_kind='title_javdb_enrichment.title_original_en'
 * AND callback_id=:titleId AND status='done'} re-eligibility this title for the next tick.
 */
@Slf4j
public class TitleTranslationSweeper implements Runnable {

    private final JavdbEnrichmentRepository enrichmentRepo;
    private final TranslationService translationService;
    private final boolean enabled;
    private final int batchSize;

    public TitleTranslationSweeper(JavdbEnrichmentRepository enrichmentRepo,
                                   TranslationService translationService,
                                   boolean enabled,
                                   int batchSize) {
        this.enrichmentRepo     = enrichmentRepo;
        this.translationService = translationService;
        this.enabled            = enabled;
        this.batchSize          = batchSize;
    }

    @Override
    public void run() {
        if (!enabled) {
            log.debug("TitleTranslationSweeper: disabled, skipping tick");
            return;
        }
        sweepOnce(batchSize);
    }

    /**
     * Runs a single sweep pass with an explicit limit. Returns the number of
     * translations successfully submitted. Used by the scheduled tick (with the
     * configured {@code batchSize}) and by the "sweep title backlog now" endpoint
     * (with an operator-supplied larger limit).
     *
     * <p>This bypasses the {@link #enabled} gate — manual invocation is always honored.
     */
    public int sweepOnce(int limit) {
        try {
            List<TitleAwaitingTranslation> rows = enrichmentRepo.findTitlesAwaitingTranslation(limit);
            if (rows.isEmpty()) {
                log.info("TitleTranslationSweeper: pass produced no work (all titles already translated or queued)");
                return 0;
            }
            int submitted = 0;
            int errors = 0;
            for (TitleAwaitingTranslation row : rows) {
                try {
                    TranslationRequest req = new TranslationRequest(
                            row.titleOriginal(),
                            StrategySelector.LABEL_BASIC,
                            JavdbEnrichmentRepository.TITLE_ORIGINAL_EN_CALLBACK_KIND,
                            row.titleId());
                    translationService.requestTranslation(req);
                    submitted++;
                } catch (Exception e) {
                    errors++;
                    log.warn("TitleTranslationSweeper: failed to submit titleId={}: {}",
                            row.titleId(), e.getMessage());
                }
            }
            log.info("TitleTranslationSweeper: submitted {} title translation(s) (errors={}, limit={})",
                    submitted, errors, limit);
            return submitted;
        } catch (Exception e) {
            log.error("TitleTranslationSweeper: error during sweep", e);
            return 0;
        }
    }
}
