package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.JavdbForbiddenException;
import com.organizer3.javdb.JavdbNotFoundException;
import com.organizer3.javdb.JavdbRateLimitException;
// JavdbSearchParser is no longer used directly — slug resolution flows through JavdbSlugResolver.
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.rating.EnrichmentGradeStamper;
import com.organizer3.rating.RatingCurveRecomputer;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.utilities.task.TaskRunner;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background thread that processes {@code javdb_enrichment_queue} jobs one at a time.
 *
 * <p>Lifecycle mirrors {@code BackgroundThumbnailWorker}: start on app boot,
 * stop on shutdown.
 *
 * <p>On boot, stuck {@code in_flight} rows older than 5 min are reset to {@code pending}
 * before the loop begins.
 *
 * <p><strong>Task-aware pause (Phase 6):</strong> when a task in {@link #PAUSE_ISSUING_TASKS}
 * is actively running (checked via {@link TaskRunner#currentlyRunning()}), {@link #isPaused()}
 * returns {@code true} even if the manual {@code paused} flag is {@code false}. The check is
 * purely in-memory — no DB poll.  Missed events self-heal on the next tick.
 */
@Slf4j
public class EnrichmentRunner {

    private static final long LOOP_SLEEP_MS = 1_000;
    private static final ObjectMapper RECOVERY_JSON = new ObjectMapper();

    /**
     * Task IDs whose presence in RUNNING state cause this runner to pause.
     * Currently only {@code enrichment.bulk_enrich_to_draft}.
     */
    private static final Set<String> PAUSE_ISSUING_TASKS = Set.of(
            "enrichment.bulk_enrich_to_draft"
    );

    private final JavdbConfig config;
    private final JavdbClient client;
    private final JavdbSlugResolver slugResolver;
    private final JavdbExtractor extractor;
    private final JavdbProjector projector;
    private final JavdbStagingRepository stagingRepo;
    private final JavdbEnrichmentRepository enrichmentRepo;
    private final EnrichmentQueue queue;
    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final com.organizer3.repository.TitleActressRepository titleActressRepo;
    private final AutoPromoter autoPromoter;
    private final ActressAvatarStore avatarStore;
    private final EnrichmentGradeStamper gradeStamper;
    private final RatingCurveRecomputer ratingCurveRecomputer;

    // Tracks titles fetched since the queue last became empty; triggers batch recompute on drain.
    private int processedThisBatch = 0;

    private final ProfileChainGate profileChainGate;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final CastMatcher castMatcher;
    private final Jdbi jdbi;
    private final RevalidationPendingRepository revalidationPendingRepo;
    private final DisambiguationSnapshotter disambiguationSnapshotter;

    /**
     * Injected after construction (via {@link #setTaskRunner}) to avoid a circular
     * dependency: {@code EnrichmentRunner} is constructed before {@code TaskRunner}.
     * Null-safe: when not yet set, no task-induced pause applies.
     */
    private volatile TaskRunner taskRunner;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    // When > now, the runner is in a rate-limit triggered global pause.
    private volatile Instant pauseUntil = Instant.EPOCH;
    private volatile String pauseReason = null;
    // Prevents spamming the "pause active" log on every sleep tick.
    private volatile boolean pauseLoggedThisCycle = false;
    // Consecutive rate-limit hits; used for exponential backoff. Reset after successful fetches.
    private int consecutiveRateLimitHits = 0;
    private int successfulFetchesSinceRateLimit = 0;
    private static final int SUCCESSES_TO_RESET = 10;

    // Burst tracking: fetch a random number of titles then take a long break.
    private int requestsInCurrentBurst = 0;
    private int nextBurstTarget = 0; // drawn lazily on first use

    private Thread thread;

    public EnrichmentRunner(
            JavdbConfig config,
            JavdbClient client,
            JavdbSlugResolver slugResolver,
            JavdbExtractor extractor,
            JavdbProjector projector,
            JavdbStagingRepository stagingRepo,
            JavdbEnrichmentRepository enrichmentRepo,
            EnrichmentQueue queue,
            TitleRepository titleRepo,
            ActressRepository actressRepo,
            AutoPromoter autoPromoter,
            ActressAvatarStore avatarStore,
            EnrichmentGradeStamper gradeStamper,
            RatingCurveRecomputer ratingCurveRecomputer,
            ProfileChainGate profileChainGate,
            com.organizer3.repository.TitleActressRepository titleActressRepo,
            EnrichmentReviewQueueRepository reviewQueueRepo,
            CastMatcher castMatcher,
            Jdbi jdbi,
            RevalidationPendingRepository revalidationPendingRepo,
            DisambiguationSnapshotter disambiguationSnapshotter) {
        this.config = config;
        this.client = client;
        this.slugResolver = slugResolver;
        this.extractor = extractor;
        this.projector = projector;
        this.stagingRepo = stagingRepo;
        this.enrichmentRepo = enrichmentRepo;
        this.queue = queue;
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.autoPromoter = autoPromoter;
        this.avatarStore = avatarStore;
        this.gradeStamper = gradeStamper;
        this.ratingCurveRecomputer = ratingCurveRecomputer;
        this.profileChainGate = profileChainGate;
        this.titleActressRepo = titleActressRepo;
        this.reviewQueueRepo = reviewQueueRepo;
        this.castMatcher = castMatcher;
        this.jdbi = jdbi;
        this.revalidationPendingRepo = revalidationPendingRepo;
        this.disambiguationSnapshotter = disambiguationSnapshotter;
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stopRequested.set(false);
        queue.resetOrphanedInFlightJobs();
        backfillActressSlugsFromEnrichment();
        recoverCastAnomaliesAfterMatcherFix();
        thread = new Thread(this::runLoop, "javdb-enrichment");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        log.info("javdb enrichment runner started (enabled={})", config.enabledOrDefault());
    }

    public synchronized void stop() {
        stopRequested.set(true);
        if (thread != null) thread.interrupt();
    }

    /**
     * Injects the {@link TaskRunner} for task-aware pause detection. Called from
     * {@code Application.java} after both objects are constructed.
     */
    public void setTaskRunner(TaskRunner runner) {
        this.taskRunner = runner;
    }

    public void setPaused(boolean on) {
        paused.set(on);
        log.info("javdb enrichment runner paused={}", on);
    }

    /**
     * Returns {@code true} when the runner should not process the next job.
     *
     * <p>Two independent pause sources, either is sufficient:
     * <ol>
     *   <li><em>Manual</em>: the operator explicitly called {@link #setPaused(boolean) setPaused(true)}.
     *   <li><em>Task-induced</em>: a pause-issuing Utilities task (e.g.
     *       {@code enrichment.bulk_enrich_to_draft}) is currently RUNNING. Checked by
     *       polling {@link TaskRunner#currentlyRunning()} — no DB, no events, self-healing.
     * </ol>
     *
     * <p>Note: {@link #setPaused(boolean) setPaused(false)} does <em>not</em> override a
     * task-induced pause; the runner stays paused until the task completes.
     */
    public boolean isPaused() {
        if (paused.get()) return true;
        TaskRunner tr = taskRunner;
        if (tr == null) return false;
        return tr.currentlyRunning()
                .map(run -> PAUSE_ISSUING_TASKS.contains(run.taskId()))
                .orElse(false);
    }

    /** Returns the instant until which the runner is rate-limit paused, or {@code Instant.EPOCH} if not paused. */
    public Instant getPauseUntil() {
        return pauseUntil;
    }

    /** Returns a human-readable reason for the current rate-limit pause, or null if not paused. */
    public String getPauseReason() {
        return Instant.now().isBefore(pauseUntil) ? pauseReason : null;
    }

    /** Returns the number of consecutive rate-limit hits since the last clean run. */
    public int getConsecutiveRateLimitHits() {
        return consecutiveRateLimitHits;
    }

    /**
     * Immediately lifts any active rate-limit or burst pause and resets all backoff counters.
     * Intended for manual use after switching VPN — the runner will pick up the next job on
     * its next loop tick (within ~30s at most).
     */
    public void forceResume() {
        pauseUntil = Instant.EPOCH;
        pauseReason = null;
        consecutiveRateLimitHits = 0;
        successfulFetchesSinceRateLimit = 0;
        requestsInCurrentBurst = 0;
        nextBurstTarget = 0;
        log.info("javdb: force-resumed by user — pause and rate-limit backoff cleared");
    }

    /**
     * Returns {@code "burst"} when the current pause is a proactive burst break,
     * {@code "rate_limit"} when caused by a 429/403 response, or {@code null} if
     * there is no active pause.
     */
    public String getPauseType() {
        if (getPauseReason() == null) return null;
        return pauseReason != null && pauseReason.startsWith("burst break") ? "burst" : "rate_limit";
    }

    private void runLoop() {
        while (!stopRequested.get()) {
            try {
                runOneStep();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                log.error("javdb enrichment loop error: {}", t.getMessage(), t);
                sleepInterruptibly(30_000);
            }
        }
    }

    /** Exposed for tests — runs one claim-and-execute cycle. */
    void runOneStep() throws InterruptedException {
        // 1. Operator/task-induced pause: respected by everything, including URGENT.
        if (isPaused()) {
            sleepInterruptibly(30_000);
            return;
        }

        // 2. URGENT bypass: check before rate-limit pause.
        Optional<EnrichmentJob> urgentJob = queue.claimNextUrgentJob();
        if (urgentJob.isPresent()) {
            executeJob(urgentJob.get());
            sleepInterruptibly(LOOP_SLEEP_MS);
            return;
        }

        // 3. Rate-limit / burst pause: gates only LOW/NORMAL/HIGH.
        Instant now = Instant.now();
        if (now.isBefore(pauseUntil)) {
            if (!pauseLoggedThisCycle) {
                long waitMs = ChronoUnit.MILLIS.between(now, pauseUntil);
                log.warn("javdb: *** RATE LIMIT PAUSE ACTIVE *** reason={} resuming in {}s (at {})",
                        pauseReason, waitMs / 1000, pauseUntil);
                pauseLoggedThisCycle = true;
            }
            sleepInterruptibly(Math.min(ChronoUnit.MILLIS.between(Instant.now(), pauseUntil), 30_000));
            return;
        }
        if (pauseLoggedThisCycle) {
            log.info("javdb: rate-limit pause lifted — resuming enrichment");
            pauseLoggedThisCycle = false;
        }

        // 4. Normal claim — LOW/NORMAL/HIGH ordering.
        Optional<EnrichmentJob> maybeJob = queue.claimNextJob();
        if (maybeJob.isEmpty()) {
            if (processedThisBatch > 0 && ratingCurveRecomputer != null) {
                log.info("javdb: queue drained ({} titles fetched) — running rating curve recompute", processedThisBatch);
                try {
                    RatingCurveRecomputer.RecomputeResult result = ratingCurveRecomputer.recompute();
                    log.info("javdb: recompute complete — updated={}, skippedManual={}", result.updatedCount(), result.skippedManualCount());
                } catch (Exception e) {
                    log.error("javdb: rating curve recompute failed: {}", e.getMessage(), e);
                }
                processedThisBatch = 0;
            } else if (processedThisBatch > 0) {
                processedThisBatch = 0;
            }
            sleepInterruptibly(LOOP_SLEEP_MS);
            return;
        }

        executeJob(maybeJob.get());
        sleepInterruptibly(LOOP_SLEEP_MS);
    }

    private void executeJob(EnrichmentJob job) {
        try {
            switch (job.jobType()) {
                case EnrichmentJob.FETCH_TITLE -> executeFetchTitle(job);
                case EnrichmentJob.FETCH_ACTRESS_PROFILE -> executeFetchActressProfile(job);
                default -> {
                    log.warn("javdb: unknown job type '{}' — marking failed", job.jobType());
                    queue.markPermanentlyFailed(job.id(), "unknown_job_type");
                }
            }
            // Track successful fetches so we can reset the rate-limit backoff counter
            successfulFetchesSinceRateLimit++;
            if (successfulFetchesSinceRateLimit >= SUCCESSES_TO_RESET && consecutiveRateLimitHits > 0) {
                log.info("javdb: {} successful fetches — resetting rate-limit backoff counter", SUCCESSES_TO_RESET);
                consecutiveRateLimitHits = 0;
                successfulFetchesSinceRateLimit = 0;
            }

            // Burst tracking: after N requests, take a randomised long break
            if (nextBurstTarget == 0) nextBurstTarget = randomBurstSize();
            requestsInCurrentBurst++;
            if (requestsInCurrentBurst >= nextBurstTarget) {
                int breakMinutes = randomBurstBreak();
                pauseReason = "burst break (" + requestsInCurrentBurst + " requests fetched)";
                pauseUntil = Instant.now().plus(breakMinutes, ChronoUnit.MINUTES);
                pauseLoggedThisCycle = false;
                log.info("javdb: burst of {} complete — taking {}m break before next burst", requestsInCurrentBurst, breakMinutes);
                requestsInCurrentBurst = 0;
                nextBurstTarget = randomBurstSize();
            }
        } catch (JavdbRateLimitException e) {
            consecutiveRateLimitHits++;
            successfulFetchesSinceRateLimit = 0;
            int basePause = config.rate429PauseMinutesOrDefault();
            int pauseMinutes = Math.min(basePause * (1 << (consecutiveRateLimitHits - 1)), 120);
            pauseReason = "HTTP 429 — rate limited (hit #" + consecutiveRateLimitHits + ")";
            pauseUntil = Instant.now().plus(pauseMinutes, ChronoUnit.MINUTES);
            pauseLoggedThisCycle = false;
            log.warn("javdb: *** RATE LIMITED (429) *** hit #{}, pausing {}m (base {}m × 2^{}) — consider switching VPN if this persists",
                    consecutiveRateLimitHits, pauseMinutes, basePause, consecutiveRateLimitHits - 1);
            queue.releaseToRetry(job.id());
        } catch (JavdbForbiddenException e) {
            consecutiveRateLimitHits++;
            successfulFetchesSinceRateLimit = 0;
            int basePause = config.rate429PauseMinutesOrDefault();
            int pauseMinutes = Math.min(basePause * (1 << (consecutiveRateLimitHits - 1)), 120);
            pauseReason = "HTTP 403 — access forbidden, IP may be blocked (hit #" + consecutiveRateLimitHits + ")";
            pauseUntil = Instant.now().plus(pauseMinutes, ChronoUnit.MINUTES);
            pauseLoggedThisCycle = false;
            log.warn("javdb: *** FORBIDDEN (403) *** IP likely blocked — hit #{}, pausing {}m — switch VPN to resume",
                    consecutiveRateLimitHits, pauseMinutes);
            queue.releaseToRetry(job.id());
        } catch (JavdbNotFoundException e) {
            log.warn("javdb: not found for job {} ({})", job.id(), job.jobType());
            queue.markPermanentlyFailed(job.id(), "not_found");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("javdb: failed job {} ({}): {}", job.id(), job.jobType(), msg);
            queue.markAttemptFailed(job.id(), msg);
        }
    }

    private void executeFetchTitle(EnrichmentJob job) {
        long titleId = job.targetId();
        long actressId = job.actressId();

        Optional<Title> maybeTitle = titleRepo.findById(titleId);
        if (maybeTitle.isEmpty()) {
            log.warn("javdb: title {} not found in DB — skipping", titleId);
            queue.markPermanentlyFailed(job.id(), "title_not_in_db");
            return;
        }
        Title title = maybeTitle.get();
        log.info("javdb: fetching {}", title.getCode());

        // 1D — Sentinel short-circuit: skip HTTP fetch entirely for sentinel actresses
        // (Various, Unknown, Amateur). Sentinels are never real performers; fetching title
        // pages for them produces unreliable cast-validation signals.
        if (actressId > 0 && isSentinelActress(actressId)) {
            log.info("javdb: actress {} is sentinel — routing {} to ambiguous queue without fetch",
                    actressId, title.getCode());
            if (reviewQueueRepo != null) {
                reviewQueueRepo.enqueue(titleId, null, "ambiguous", "sentinel_short_circuit");
            }
            queue.markPermanentlyFailed(job.id(), "sentinel_actress");
            return;
        }

        // Slug resolution: anchored on the linked actress's javdb slug when available.
        // Fixes the code-reuse mismatch bug — see spec/PROPOSAL_JAVDB_SLUG_VERIFICATION.md.
        String actressJavdbSlug = null;
        if (actressId > 0) {
            actressJavdbSlug = stagingRepo.findActressStaging(actressId)
                    .map(JavdbActressStagingRow::javdbSlug)
                    .orElse(null);
        }
        JavdbSlugResolver.Resolution resolution = slugResolver.resolve(
                lookupCode(title.getCode()), actressJavdbSlug);
        String slug;
        JavdbSlugResolver.Source resolverSource;
        if (resolution instanceof JavdbSlugResolver.Success success) {
            slug = success.slug();
            resolverSource = success.source();
        } else if (resolution instanceof JavdbSlugResolver.NoMatchInFilmography nm) {
            log.warn("javdb: {} not in filmography of actress slug {} — marking no_match",
                    title.getCode(), nm.actressSlug());
            queue.markPermanentlyFailed(job.id(), "no_match_in_filmography");
            return;
        } else {
            // CodeNotFound — preserve the pre-fix terminal status so callers don't see new behavior.
            log.warn("javdb: no results for {} — marking not_found", title.getCode());
            queue.markPermanentlyFailed(job.id(), "not_found");
            return;
        }

        String html = client.fetchTitlePage(slug);
        TitleExtract extract = extractor.extractTitle(html, title.getCode(), slug);

        // 1C — Write-time gate: decide confidence/cast_validated, or skip write for certain outcomes.
        GateResult gateResult = applyWriteGate(titleId, actressId, resolverSource, extract, slug,
                title.getCode());
        if (!gateResult.write()) {
            log.info("javdb: gate blocked write for {} (reason={}) — marking {}",
                    title.getCode(), gateResult.queueReason(), gateResult.queueReason());
            if ("fetch_failed".equals(gateResult.queueReason())) {
                queue.markAttemptFailed(job.id(), "cast_parse_failed");
            } else {
                queue.markPermanentlyFailed(job.id(), gateResult.queueReason());
            }
            return;
        }

        String rawPath = stagingRepo.saveTitleRaw(slug, extract);
        enrichmentRepo.upsertEnrichment(titleId, slug, rawPath, extract,
                resolverSourceLabel(resolverSource), gateResult.confidence(), gateResult.castValidated());
        if (revalidationPendingRepo != null) {
            revalidationPendingRepo.enqueue(titleId, "queue_drain");
        }

        // Per-title grade stamp using cached curve (no-op if curve not yet computed)
        if (gradeStamper != null) {
            gradeStamper.stampIfRated(titleId, extract.ratingAvg(), extract.ratingCount());
        }
        processedThisBatch++;

        boolean isCollectionJob = EnrichmentJob.SOURCE_COLLECTION.equals(job.source());

        if (isCollectionJob) {
            // Multi-cast title: match a slug for every DB-known credited actress (no single-cast
            // fallback — see matchAndRecordActressSlug Javadoc) and gate-and-chain per cast member.
            List<Long> castIds = titleActressRepo == null
                    ? List.of()
                    : titleActressRepo.findActressIdsByTitle(titleId);
            for (Long castActressId : castIds) {
                matchAndRecordActressSlug(castActressId, title.getCode(), extract.cast(), /*allowSingleCastFallback*/ false);
            }
            queue.markDone(job.id());
            // No autoPromoter on collection — the title has no single owning actress.
            for (Long castActressId : castIds) {
                if (profileChainGate.shouldChainProfile(castActressId)) {
                    triggerActressProfileIfNeeded(castActressId);
                }
            }
            return;
        }

        // Single-actress path (actress-driven, recent, pool): look for the owning actress in the cast list.
        matchAndRecordActressSlug(actressId, title.getCode(), extract.cast());

        queue.markDone(job.id());
        autoPromoter.promoteFromTitle(titleId, actressId);

        // Completion hook: enqueue fetch_actress_profile if we now have a slug but no profile.
        // For title-driven flows, gate against sentinel/threshold/existence checks first.
        if (job.isActressDriven() || profileChainGate.shouldChainProfile(actressId)) {
            triggerActressProfileIfNeeded(actressId);
        }
    }

    private void executeFetchActressProfile(EnrichmentJob job) {
        long actressId = job.actressId();

        Optional<JavdbActressStagingRow> maybeStagingRow = stagingRepo.findActressStaging(actressId);
        if (maybeStagingRow.isEmpty() || maybeStagingRow.get().javdbSlug() == null) {
            log.warn("javdb: actress {} has no slug yet — cannot fetch profile", actressId);
            queue.markPermanentlyFailed(job.id(), "no_slug");
            return;
        }
        String slug = maybeStagingRow.get().javdbSlug();
        log.info("javdb: fetching actress profile {}", slug);

        String html = client.fetchActressPage(slug);
        ActressExtract extract = extractor.extractActress(html, slug);

        String rawPath = stagingRepo.saveActressRaw(slug, extract);
        JavdbActressStagingRow row = projector.projectActress(actressId, extract, rawPath);
        // Preserve source_title_code from the existing slug_only row
        String sourceTitleCode = maybeStagingRow.get().sourceTitleCode();
        row = new JavdbActressStagingRow(
                row.actressId(), row.javdbSlug(), sourceTitleCode, row.status(),
                row.rawPath(), row.rawFetchedAt(), row.nameVariantsJson(), row.avatarUrl(),
                row.twitterHandle(), row.instagramHandle(), row.titleCount(),
                row.localAvatarPath()
        );
        stagingRepo.upsertActress(row);

        if (avatarStore != null && row.avatarUrl() != null) {
            String relPath = avatarStore.download(slug, row.avatarUrl());
            if (relPath != null) {
                stagingRepo.updateLocalAvatarPath(actressId, relPath);
            }
        }

        queue.markDone(job.id());
        autoPromoter.promoteActressStageName(actressId);
        autoPromoter.promoteFromActressProfile(actressId);
    }

    /** Default-true wrapper preserving the legacy single-cast fallback behavior. */
    private void matchAndRecordActressSlug(long actressId, String titleCode, List<TitleExtract.CastEntry> cast) {
        matchAndRecordActressSlug(actressId, titleCode, cast, /*allowSingleCastFallback*/ true);
    }

    /**
     * Resolves the javdb slug for one credited actress by matching against the title's cast list.
     *
     * @param allowSingleCastFallback when true, a 1-entry cast list assigns its only slug to the
     *        target actress regardless of name match (legacy behavior — safe for single-actress jobs).
     *        Collection-source jobs MUST pass {@code false}, because iterating over multiple credited
     *        actresses would otherwise stamp the same lone slug onto every one of them.
     */
    private void matchAndRecordActressSlug(long actressId, String titleCode,
                                           List<TitleExtract.CastEntry> cast,
                                           boolean allowSingleCastFallback) {
        if (cast.isEmpty()) return;

        Optional<Actress> maybeActress = actressRepo.findById(actressId);
        if (maybeActress.isEmpty()) return;
        Actress actress = maybeActress.get();

        // Try name matching first — works when actress has a Japanese stage name or alias,
        // or a romanized canonical name that matches javdb's roman cast entry (e.g. "AIKA").
        Set<String> knownNames = buildKnownNames(actress, actressRepo.findAliases(actressId));
        for (TitleExtract.CastEntry entry : cast) {
            String n = CastMatcher.normalize(entry.name());
            if (n != null && knownNames.contains(n)) {
                if (stagingRepo.upsertActressSlugOnly(actressId, entry.slug(), titleCode)) {
                    log.info("javdb: resolved slug {} for actress {} via name match ({})", entry.slug(), actressId, titleCode);
                }
                return;
            }
        }

        // Fallback: if the title has exactly one cast entry we can still assign the slug.
        // Most JAV titles are single-actress, and name matching commonly fails because our
        // DB stores romanized names while javdb uses Japanese.
        if (allowSingleCastFallback && cast.size() == 1) {
            if (stagingRepo.upsertActressSlugOnly(actressId, cast.get(0).slug(), titleCode)) {
                log.info("javdb: assigned slug {} to actress {} by single-cast assumption ({})", cast.get(0).slug(), actressId, titleCode);
            }
        }
    }

    /**
     * On startup, scan existing {@code title_javdb_enrichment} cast data to write slug_only rows
     * for actresses who were missed because their romanized DB name didn't match javdb's Japanese
     * cast names. Only processes actresses where all enriched single-cast titles agree on the same
     * slug (unambiguous). Enqueues a profile fetch for each newly written slug.
     */
    void backfillActressSlugsFromEnrichment() {
        List<JavdbStagingRepository.BackfillEntry> entries = stagingRepo.findBackfillableActressSlugs();
        if (entries.isEmpty()) return;
        log.info("javdb: backfilling slugs for {} actresses from existing enrichment cast data", entries.size());
        for (JavdbStagingRepository.BackfillEntry entry : entries) {
            if (stagingRepo.upsertActressSlugOnly(entry.actressId(), entry.javdbSlug(), entry.sourceTitleCode())) {
                queue.enqueueActressProfile(entry.actressId());
                log.info("javdb: backfilled slug {} for actress {} (via {})", entry.javdbSlug(), entry.actressId(), entry.sourceTitleCode());
            }
        }
    }

    /**
     * Re-evaluates open {@code cast_anomaly} review rows against the current matcher.
     * Older runs flagged titles whose cast_json now matches under the upgraded
     * normalization (e.g. romanized canonical name "Aika" vs cast entry "AIKA"), so
     * we re-run the match using stored cast_json — no network — and discharge rows
     * that now resolve cleanly. Idempotent: a second pass finds nothing to do.
     */
    public int recoverCastAnomaliesAfterMatcherFix() {
        if (castMatcher == null || titleActressRepo == null || reviewQueueRepo == null) return 0;
        List<RecoveryCandidate> candidates = jdbi.withHandle(h -> h.createQuery("""
                SELECT q.id AS queue_id, q.title_id, t.code AS title_code, e.cast_json
                FROM enrichment_review_queue q
                JOIN title_javdb_enrichment e ON e.title_id = q.title_id
                JOIN titles t ON t.id = q.title_id
                WHERE q.reason = 'cast_anomaly' AND q.resolved_at IS NULL
                """)
                .map((rs, ctx) -> new RecoveryCandidate(
                        rs.getLong("queue_id"),
                        rs.getLong("title_id"),
                        rs.getString("title_code"),
                        rs.getString("cast_json")))
                .list());
        if (candidates.isEmpty()) return 0;

        int recovered = 0;
        for (RecoveryCandidate c : candidates) {
            List<TitleExtract.CastEntry> cast = parseCastJson(c.castJson);
            if (cast.isEmpty()) continue;
            List<Long> linked = titleActressRepo.findActressIdsByTitle(c.titleId).stream()
                    .filter(id -> !isSentinelActress(id))
                    .toList();
            for (long actressId : linked) {
                CastMatcher.MatchResult m = castMatcher.match(actressId, cast);
                if (!m.matched()) continue;
                stagingRepo.upsertActressSlugOnly(actressId, m.matchedSlug(), c.titleCode);
                jdbi.useTransaction(h -> {
                    h.createUpdate("""
                            UPDATE title_javdb_enrichment
                            SET confidence = 'HIGH', cast_validated = 1
                            WHERE title_id = :titleId
                            """)
                            .bind("titleId", c.titleId)
                            .execute();
                    reviewQueueRepo.resolveOne(c.queueId, "cast_recovered", h);
                });
                queue.enqueueActressProfile(actressId);
                recovered++;
                log.info("javdb: cast_anomaly recovered for title {} ({}) — actress {} matched cast '{}' (slug={})",
                        c.titleId, c.titleCode, actressId, m.matchedName(), m.matchedSlug());
                break;
            }
        }
        if (recovered > 0) {
            log.info("javdb: cast_anomaly recovery discharged {} of {} open rows after matcher upgrade",
                    recovered, candidates.size());
        }
        return recovered;
    }

    private static List<TitleExtract.CastEntry> parseCastJson(String castJson) {
        if (castJson == null || castJson.isBlank()) return List.of();
        try {
            JsonNode arr = RECOVERY_JSON.readTree(castJson);
            if (!arr.isArray()) return List.of();
            List<TitleExtract.CastEntry> out = new java.util.ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                String slug = n.path("slug").asText(null);
                String name = n.path("name").asText(null);
                String gender = n.path("gender").asText(null);
                if (slug != null && name != null) {
                    out.add(new TitleExtract.CastEntry(slug, name, gender));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("javdb: failed to parse cast_json during recovery: {}", e.toString());
            return List.of();
        }
    }

    private record RecoveryCandidate(long queueId, long titleId, String titleCode, String castJson) {}

    private Set<String> buildKnownNames(Actress actress, List<ActressAlias> aliases) {
        Set<String> names = new java.util.HashSet<>();
        addNormalized(names, actress.getCanonicalName());
        addNormalized(names, actress.getStageName());
        for (ActressAlias alias : aliases) {
            addNormalized(names, alias.aliasName());
        }
        return names;
    }

    private static void addNormalized(Set<String> sink, String value) {
        String n = CastMatcher.normalize(value);
        if (n != null) sink.add(n);
    }

    private void triggerActressProfileIfNeeded(long actressId) {
        Optional<JavdbActressStagingRow> row = stagingRepo.findActressStaging(actressId);
        if (row.isEmpty() || row.get().javdbSlug() == null) return;
        if (row.get().rawPath() != null) return; // already fetched

        // enqueueActressProfile's SQL is already idempotent (checks for existing pending/done jobs).
        // Do NOT gate on countPendingForActress — that would delay the profile fetch until all
        // title jobs finish. Fire on first slug discovery.
        queue.enqueueActressProfile(actressId);
        log.info("javdb: enqueued fetch_actress_profile for actress {}", actressId);
    }

    /**
     * Strips variant suffixes from a title code before searching javdb.
     * e.g. "SONE-038_4K" → "SONE-038", "SONE-038-4K" → "SONE-038", "DV-948" → "DV-948".
     */
    static String lookupCode(String code) {
        return code.replaceFirst("(?i)(^[A-Za-z]+-\\d+)[-_].+$", "$1");
    }

    /** Outcome of the write-time gate. */
    record GateResult(boolean write, String confidence, boolean castValidated, String queueReason) {
        static GateResult write(String confidence, boolean castValidated) {
            return new GateResult(true, confidence, castValidated, null);
        }
        static GateResult skip(String queueReason) {
            return new GateResult(false, null, false, queueReason);
        }
    }

    /**
     * 1C write-time gate — decides confidence tier, cast_validated, and whether to proceed.
     *
     * <p>Decision table (7 rows):
     * <ol>
     *   <li>castParseFailed → skip, route to fetch_failed (retryable)</li>
     *   <li>ACTRESS_FILMOGRAPHY + castEmpty → write HIGH, cast_validated=1</li>
     *   <li>ACTRESS_FILMOGRAPHY + !castEmpty + actress in cast → write HIGH, cast_validated=1</li>
     *   <li>ACTRESS_FILMOGRAPHY + !castEmpty + actress NOT in cast → write LOW, cast_validated=0, cast_anomaly queue</li>
     *   <li>CODE_SEARCH_FALLBACK + no real linked actress → write MEDIUM, cast_validated=0</li>
     *   <li>CODE_SEARCH_FALLBACK + real linked actress in cast → write MEDIUM, cast_validated=0</li>
     *   <li>CODE_SEARCH_FALLBACK + real linked actress NOT in cast → skip, route to ambiguous</li>
     * </ol>
     *
     * <p>Sentinel actresses are short-circuited before this method is called (see 1D).
     */
    private GateResult applyWriteGate(long titleId, long actressId,
                                      JavdbSlugResolver.Source resolverSource,
                                      TitleExtract extract, String slug,
                                      String titleCode) {
        // Row 1: parse failure — retryable
        if (extract.castParseFailed()) {
            if (reviewQueueRepo != null) {
                reviewQueueRepo.enqueue(titleId, slug, "fetch_failed", resolverSourceLabel(resolverSource));
            }
            return GateResult.skip("fetch_failed");
        }

        if (resolverSource == JavdbSlugResolver.Source.ACTRESS_FILMOGRAPHY) {
            // Row 2: genuine empty cast with actress anchor → highest confidence
            if (extract.castEmpty()) {
                return GateResult.write("HIGH", true);
            }
            // Rows 3/4: non-empty cast — check for actress presence
            if (castMatcher != null && actressId > 0) {
                CastMatcher.MatchResult match = castMatcher.match(actressId, extract.cast());
                if (match.matched()) {
                    return GateResult.write("HIGH", true);
                } else {
                    if (reviewQueueRepo != null) {
                        reviewQueueRepo.enqueue(titleId, slug, "cast_anomaly", resolverSourceLabel(resolverSource));
                    }
                    return GateResult.write("LOW", false);
                }
            }
            // castMatcher null (test) or actressId=0 edge-case: still write but unvalidated
            return GateResult.write("MEDIUM", false);
        }

        // CODE_SEARCH_FALLBACK path
        if (titleActressRepo != null && castMatcher != null) {
            List<Long> linkedIds = titleActressRepo.findActressIdsByTitle(titleId).stream()
                    .filter(id -> !isSentinelActress(id))
                    .toList();
            if (!linkedIds.isEmpty()) {
                for (long linkedId : linkedIds) {
                    if (castMatcher.match(linkedId, extract.cast()).matched()) {
                        // Row 6: linked actress found in cast → MEDIUM
                        return GateResult.write("MEDIUM", false);
                    }
                }
                // Row 7: real linked actress exists but none in cast → ambiguous.
                // Build candidate snapshot (fetches N extra pages for multi-slug searches;
                // cost is acceptable because this path is rare).
                log.info("javdb: code-search fallback cast mismatch for title {} — routing to ambiguous", titleId);
                if (reviewQueueRepo != null) {
                    String detailJson = disambiguationSnapshotter != null
                            ? disambiguationSnapshotter.buildSnapshot(titleId, titleCode, slug, extract)
                            : null;
                    if (detailJson != null) {
                        reviewQueueRepo.enqueueWithDetail(titleId, slug, "ambiguous",
                                resolverSourceLabel(resolverSource), detailJson);
                    } else {
                        reviewQueueRepo.enqueue(titleId, slug, "ambiguous", resolverSourceLabel(resolverSource));
                    }
                }
                return GateResult.skip("ambiguous");
            }
        }

        // Row 5: no non-sentinel linked actress to validate against → MEDIUM, unvalidated
        return GateResult.write("MEDIUM", false);
    }

    /** Returns {@code true} if the given actress has {@code is_sentinel = 1}. */
    private boolean isSentinelActress(long actressId) {
        if (actressId <= 0) return false;
        return jdbi.withHandle(h ->
                h.createQuery("SELECT is_sentinel FROM actresses WHERE id = :id")
                        .bind("id", actressId)
                        .mapTo(Integer.class)
                        .findOne()
                        .map(v -> v != 0)
                        .orElse(false));
    }

    private static String resolverSourceLabel(JavdbSlugResolver.Source source) {
        if (source == null) return "unknown";
        return switch (source) {
            case ACTRESS_FILMOGRAPHY   -> "actress_filmography";
            case CODE_SEARCH_FALLBACK  -> "code_search_fallback";
        };
    }

    private int randomBurstSize() {
        int base = config.burstSizeOrDefault();
        int lo = Math.max(2, (int)(base * 0.6));
        int hi = (int)(base * 1.5) + 1;
        return ThreadLocalRandom.current().nextInt(lo, hi);
    }

    private int randomBurstBreak() {
        int base = config.burstBreakMinutesOrDefault();
        int lo = Math.max(10, (int)(base * 0.6));
        int hi = (int)(base * 1.5) + 1;
        return ThreadLocalRandom.current().nextInt(lo, hi);
    }

    private void sleepInterruptibly(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
