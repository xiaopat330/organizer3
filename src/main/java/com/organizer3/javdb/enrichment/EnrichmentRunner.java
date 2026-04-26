package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.JavdbForbiddenException;
import com.organizer3.javdb.JavdbNotFoundException;
import com.organizer3.javdb.JavdbRateLimitException;
import com.organizer3.javdb.JavdbSearchParser;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background thread that processes {@code javdb_enrichment_queue} jobs one at a time.
 *
 * <p>Lifecycle mirrors {@code BackgroundThumbnailWorker}: start on app boot,
 * stop on shutdown. Independent of {@code TaskRunner}.
 *
 * <p>On boot, stuck {@code in_flight} rows older than 5 min are reset to {@code pending}
 * before the loop begins.
 */
@Slf4j
public class EnrichmentRunner {

    private static final long LOOP_SLEEP_MS = 1_000;

    private final JavdbConfig config;
    private final JavdbClient client;
    private final JavdbSearchParser searchParser;
    private final JavdbExtractor extractor;
    private final JavdbProjector projector;
    private final JavdbStagingRepository stagingRepo;
    private final JavdbEnrichmentRepository enrichmentRepo;
    private final EnrichmentQueue queue;
    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final AutoPromoter autoPromoter;

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

    private Thread thread;

    public EnrichmentRunner(
            JavdbConfig config,
            JavdbClient client,
            JavdbExtractor extractor,
            JavdbProjector projector,
            JavdbStagingRepository stagingRepo,
            JavdbEnrichmentRepository enrichmentRepo,
            EnrichmentQueue queue,
            TitleRepository titleRepo,
            ActressRepository actressRepo,
            AutoPromoter autoPromoter) {
        this.config = config;
        this.client = client;
        this.searchParser = new JavdbSearchParser();
        this.extractor = extractor;
        this.projector = projector;
        this.stagingRepo = stagingRepo;
        this.enrichmentRepo = enrichmentRepo;
        this.queue = queue;
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.autoPromoter = autoPromoter;
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stopRequested.set(false);
        queue.resetOrphanedInFlightJobs();
        backfillActressSlugsFromEnrichment();
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

    public void setPaused(boolean on) {
        paused.set(on);
        log.info("javdb enrichment runner paused={}", on);
    }

    public boolean isPaused() {
        return paused.get();
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
        if (paused.get()) {
            sleepInterruptibly(30_000);
            return;
        }

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

        Optional<EnrichmentJob> maybeJob = queue.claimNextJob();
        if (maybeJob.isEmpty()) {
            sleepInterruptibly(LOOP_SLEEP_MS);
            return;
        }

        EnrichmentJob job = maybeJob.get();
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

        sleepInterruptibly(LOOP_SLEEP_MS);
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

        Optional<String> maybeSlug = searchParser.parseFirstSlug(client.searchByCode(lookupCode(title.getCode())));
        if (maybeSlug.isEmpty()) {
            log.warn("javdb: no results for {} — marking not_found", title.getCode());
            queue.markPermanentlyFailed(job.id(), "not_found");
            return;
        }
        String slug = maybeSlug.get();

        String html = client.fetchTitlePage(slug);
        TitleExtract extract = extractor.extractTitle(html, title.getCode(), slug);

        String rawPath = stagingRepo.saveTitleRaw(slug, extract);
        enrichmentRepo.upsertEnrichment(titleId, slug, rawPath, extract);

        // Cast matching: look for the owning actress in the cast list
        matchAndRecordActressSlug(actressId, title.getCode(), extract.cast());

        queue.markDone(job.id());
        autoPromoter.promoteFromTitle(titleId, actressId);

        // Completion hook: enqueue fetch_actress_profile if we now have a slug but no profile
        triggerActressProfileIfNeeded(actressId);
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
                row.twitterHandle(), row.instagramHandle(), row.titleCount()
        );
        stagingRepo.upsertActress(row);

        queue.markDone(job.id());
        autoPromoter.promoteActressStageName(actressId);
    }

    private void matchAndRecordActressSlug(long actressId, String titleCode, List<TitleExtract.CastEntry> cast) {
        if (cast.isEmpty()) return;

        Optional<Actress> maybeActress = actressRepo.findById(actressId);
        if (maybeActress.isEmpty()) return;
        Actress actress = maybeActress.get();

        // Try name matching first — works when actress has a Japanese stage name or alias.
        Set<String> knownNames = buildKnownNames(actress, actressRepo.findAliases(actressId));
        for (TitleExtract.CastEntry entry : cast) {
            if (knownNames.contains(entry.name())) {
                if (stagingRepo.upsertActressSlugOnly(actressId, entry.slug(), titleCode)) {
                    log.info("javdb: resolved slug {} for actress {} via name match ({})", entry.slug(), actressId, titleCode);
                }
                return;
            }
        }

        // Fallback: if the title has exactly one cast entry we can still assign the slug.
        // Most JAV titles are single-actress, and name matching commonly fails because our
        // DB stores romanized names while javdb uses Japanese.
        if (cast.size() == 1) {
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

    private Set<String> buildKnownNames(Actress actress, List<ActressAlias> aliases) {
        Set<String> names = new java.util.HashSet<>();
        if (actress.getStageName() != null) names.add(actress.getStageName());
        for (ActressAlias alias : aliases) {
            if (alias.aliasName() != null) names.add(alias.aliasName());
        }
        return names;
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

    private void sleepInterruptibly(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
