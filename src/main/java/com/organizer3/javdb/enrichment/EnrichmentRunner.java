package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
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

    private static final int STALL_MINUTES = 5;
    private static final long LOOP_SLEEP_MS = 1_000;

    private final JavdbConfig config;
    private final JavdbClient client;
    private final JavdbSearchParser searchParser;
    private final JavdbExtractor extractor;
    private final JavdbProjector projector;
    private final JavdbStagingRepository stagingRepo;
    private final EnrichmentQueue queue;
    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final AutoPromoter autoPromoter;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    // When > now, the runner is in a 429-triggered global pause.
    private volatile Instant pauseUntil = Instant.EPOCH;

    private Thread thread;

    public EnrichmentRunner(
            JavdbConfig config,
            JavdbClient client,
            JavdbExtractor extractor,
            JavdbProjector projector,
            JavdbStagingRepository stagingRepo,
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
        this.queue = queue;
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.autoPromoter = autoPromoter;
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stopRequested.set(false);
        queue.resetStuckInFlightJobs(STALL_MINUTES);
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
            long waitMs = ChronoUnit.MILLIS.between(now, pauseUntil);
            log.info("javdb: rate-limit pause active, sleeping {}s", waitMs / 1000);
            sleepInterruptibly(Math.min(waitMs, 30_000));
            return;
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
        } catch (JavdbRateLimitException e) {
            log.warn("javdb: rate-limited — pausing {}m", config.rate429PauseMinutesOrDefault());
            pauseUntil = Instant.now().plus(config.rate429PauseMinutesOrDefault(), ChronoUnit.MINUTES);
            queue.releaseToRetry(job.id());
        } catch (JavdbNotFoundException e) {
            log.warn("javdb: not found for job {} ({})", job.id(), job.jobType());
            queue.markPermanentlyFailed(job.id(), "not_found");
            if (EnrichmentJob.FETCH_TITLE.equals(job.jobType())) {
                stagingRepo.upsertTitleNotFound(job.targetId());
            }
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

        Optional<String> maybeSlug = searchParser.parseFirstSlug(client.searchByCode(title.getCode()));
        if (maybeSlug.isEmpty()) {
            log.warn("javdb: no results for {} — marking not_found", title.getCode());
            queue.markPermanentlyFailed(job.id(), "not_found");
            stagingRepo.upsertTitleNotFound(titleId);
            return;
        }
        String slug = maybeSlug.get();

        String html = client.fetchTitlePage(slug);
        TitleExtract extract = extractor.extractTitle(html, title.getCode(), slug);

        String rawPath = stagingRepo.saveTitleRaw(slug, extract);
        JavdbTitleStagingRow row = projector.projectTitle(titleId, extract, rawPath);
        stagingRepo.upsertTitle(row);

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

        // Collect all known names for this actress (stage name + aliases)
        Set<String> knownNames = buildKnownNames(actress, actressRepo.findAliases(actressId));

        for (TitleExtract.CastEntry entry : cast) {
            if (knownNames.contains(entry.name())) {
                stagingRepo.upsertActressSlugOnly(actressId, entry.slug(), titleCode);
                log.info("javdb: resolved slug {} for actress {} (via {})", entry.slug(), actressId, titleCode);
                return;
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

    private void sleepInterruptibly(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
