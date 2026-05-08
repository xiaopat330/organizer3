package com.organizer3.translation;

import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository.TitleAwaitingTranslation;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import com.organizer3.utilities.task.TaskRunner;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Task IDs whose presence in RUNNING state cause this sweeper to skip its tick.
     * Mirrors {@code EnrichmentRunner.PAUSE_ISSUING_TASKS} — duplication is intentional
     * (two call sites is the threshold for premature factoring, per spec §3.4).
     */
    private static final Set<String> PAUSE_ISSUING_TASKS = Set.of(
            "volume.sync",                       // single-volume sync
            "volume.sync_coherent",              // coherent multi-volume sync
            "volume.clean_stale_locations"       // stale-row cleaner; writes heavily
    );

    private final JavdbEnrichmentRepository enrichmentRepo;
    private final TranslationService translationService;
    private final TranslationQueueRepository queueRepo;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationStrategyRepository strategyRepo;
    private final boolean enabled;
    private final int batchSize;

    /**
     * Injected after construction (via {@link #setTaskRunner}) to avoid a circular
     * dependency: sweeper is constructed before TaskRunner. Null-safe: when not yet
     * set, no task-induced pause applies.
     */
    private volatile TaskRunner taskRunner;

    public TitleTranslationSweeper(JavdbEnrichmentRepository enrichmentRepo,
                                   TranslationService translationService,
                                   TranslationQueueRepository queueRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationStrategyRepository strategyRepo,
                                   boolean enabled,
                                   int batchSize) {
        this.enrichmentRepo     = enrichmentRepo;
        this.translationService = translationService;
        this.queueRepo          = queueRepo;
        this.cacheRepo          = cacheRepo;
        this.strategyRepo       = strategyRepo;
        this.enabled            = enabled;
        this.batchSize          = batchSize;
    }

    /** Inject TaskRunner after construction (mirrors {@code EnrichmentRunner.setTaskRunner}). */
    public void setTaskRunner(TaskRunner runner) {
        this.taskRunner = runner;
    }

    @Override
    public void run() {
        if (!enabled) {
            log.debug("TitleTranslationSweeper: disabled, skipping tick");
            return;
        }
        TaskRunner tr = taskRunner;
        if (tr != null) {
            String running = tr.currentlyRunning()
                    .map(run -> run.taskId())
                    .orElse(null);
            if (running != null && PAUSE_ISSUING_TASKS.contains(running)) {
                log.debug("TitleTranslationSweeper: sync task active ({}) — skipping tick", running);
                return;
            }
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

    /**
     * Result of {@link #forceTranslateOne(long)}: whether work was submitted, plus how
     * many prior queue rows and cache rows were cleared. {@code reason} is non-null only
     * when {@code submitted=false} (no enrichment row, blank title_original, missing strategy).
     */
    public record ForceTranslateResult(
            boolean submitted,
            int queueRowsDeleted,
            int cacheRowsDeleted,
            Long queueId,
            String reason
    ) {}

    /**
     * Force a fresh re-translation for a single title's {@code title_original_en}.
     * Discards any existing cache row + queue rows for the title and submits a new
     * request through {@link TranslationService#requestTranslation}.
     *
     * <p>Cache deletion happens before re-enqueue so the next worker tick actually calls
     * Ollama instead of short-circuiting on the prior cached value. This is the agent /
     * operator escape hatch when substitution maps change or a sanitized result needs
     * to be re-attempted on a per-title basis.
     *
     * <p>Best-effort race avoidance: cache is cleared first, then enqueue, then any other
     * queue rows for the callback are deleted. The scheduled sweeper's {@code NOT EXISTS}
     * predicate sees the new pending row immediately, so concurrent ticks won't
     * double-submit.
     */
    public ForceTranslateResult forceTranslateOne(long titleId) {
        Optional<String> orig = enrichmentRepo.findTitleOriginalByTitleId(titleId);
        if (orig.isEmpty() || orig.get().isBlank()) {
            log.info("TitleTranslationSweeper.forceTranslateOne: titleId={} has no title_original — skipping", titleId);
            return new ForceTranslateResult(false, 0, 0, null, "no_title_original");
        }
        String sourceText = orig.get();
        String normalised = TranslationNormalization.normalize(sourceText);
        String hash = TranslationNormalization.sha256Hex(normalised);

        Optional<TranslationStrategy> strategy = strategyRepo.findByName(StrategySelector.LABEL_BASIC);
        if (strategy.isEmpty()) {
            log.warn("TitleTranslationSweeper.forceTranslateOne: label_basic strategy not seeded — cannot force-translate titleId={}", titleId);
            return new ForceTranslateResult(false, 0, 0, null, "strategy_missing");
        }

        // Order: clear cache → clear prior queue rows → enqueue fresh.
        // The race window with the scheduled sweeper (between the queue delete and
        // the new enqueue) is microseconds vs a 5-minute tick interval, so a duplicate
        // enqueue is theoretically possible but vanishingly rare. Even if it happens,
        // the worker simply processes both rows; the second one cache-hits and short-
        // circuits — benign.
        int cacheDeleted = cacheRepo.deleteByHashAndStrategy(hash, strategy.get().id());
        int queueDeleted = queueRepo.deleteForCallback(
                JavdbEnrichmentRepository.TITLE_ORIGINAL_EN_CALLBACK_KIND, titleId);

        TranslationRequest req = new TranslationRequest(
                sourceText, StrategySelector.LABEL_BASIC,
                JavdbEnrichmentRepository.TITLE_ORIGINAL_EN_CALLBACK_KIND, titleId);
        long newQueueId = translationService.requestTranslation(req);

        log.info("TitleTranslationSweeper.forceTranslateOne: titleId={} cacheRowsDeleted={} queueRowsDeleted={} newQueueId={}",
                titleId, cacheDeleted, queueDeleted, newQueueId);
        return new ForceTranslateResult(true, queueDeleted, cacheDeleted, newQueueId, null);
    }
}
