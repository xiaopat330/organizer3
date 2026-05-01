package com.organizer3.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

/**
 * Applies incremental schema migrations to an existing database using SQLite's
 * {@code PRAGMA user_version} as the version counter.
 *
 * <p>Fresh installs go through {@link SchemaInitializer}, which stamps the current
 * version so this upgrader skips all migrations.
 *
 * <p>Migrations are additive and safe to run multiple times only if version tracking
 * is correct — each migration increments the version immediately after executing.
 */
@Slf4j
@RequiredArgsConstructor
public class SchemaUpgrader {

    /** Must match the version stamped by {@link SchemaInitializer}. */
    private static final int CURRENT_VERSION = 41;

    private final Jdbi jdbi;

    public void upgrade() {
        int version = getVersion();
        if (version >= CURRENT_VERSION) {
            log.debug("Schema is current (version {})", version);
            return;
        }
        log.info("Upgrading schema from version {} to {}", version, CURRENT_VERSION);

        if (version < 2) {
            applyV2();
            setVersion(2);
        }

        if (version < 3) {
            applyV3();
            setVersion(3);
        }

        if (version < 4) {
            applyV4();
            setVersion(4);
        }

        if (version < 5) {
            applyV5();
            setVersion(5);
        }

        if (version < 6) {
            applyV6();
            setVersion(6);
        }

        if (version < 7) {
            applyV7();
            setVersion(7);
        }

        if (version < 8) {
            applyV8();
            setVersion(8);
        }

        if (version < 9) {
            applyV9();
            setVersion(9);
        }

        if (version < 10) {
            applyV10();
            setVersion(10);
        }

        // v11 is reserved — slot was held for AV Stars but tables land in v14 for existing DBs.

        if (version < 12) {
            applyV12();
            setVersion(12);
        }

        if (version < 13) {
            applyV13();
            setVersion(13);
        }

        if (version < 14) {
            applyV14();
            setVersion(14);
        }

        if (version < 15) {
            applyV15();
            setVersion(15);
        }

        if (version < 16) {
            applyV16();
            setVersion(16);
        }

        if (version < 17) {
            applyV17();
            setVersion(17);
        }

        if (version < 18) {
            applyV18();
            setVersion(18);
        }

        if (version < 19) {
            applyV19();
            setVersion(19);
        }

        if (version < 20) {
            applyV20();
            setVersion(20);
        }

        if (version < 21) {
            applyV21();
            setVersion(21);
        }

        if (version < 22) {
            applyV22();
            setVersion(22);
        }

        if (version < 23) {
            applyV23();
            setVersion(23);
        }

        if (version < 24) {
            applyV24();
            setVersion(24);
        }

        if (version < 25) {
            applyV25();
            setVersion(25);
        }

        if (version < 26) {
            applyV26();
            setVersion(26);
        }

        if (version < 27) {
            applyV27();
            setVersion(27);
        }

        if (version < 28) {
            applyV28();
            setVersion(28);
        }

        if (version < 29) {
            applyV29();
            setVersion(29);
        }

        if (version < 30) {
            applyV30();
            setVersion(30);
        }

        if (version < 31) {
            applyV31();
            setVersion(31);
        }

        if (version < 32) {
            applyV32();
            setVersion(32);
        }

        if (version < 33) {
            applyV33();
            setVersion(33);
        }

        if (version < 34) {
            applyV34();
            setVersion(34);
        }

        if (version < 35) {
            applyV35();
            setVersion(35);
        }

        if (version < 36) {
            applyV36();
            setVersion(36);
        }

        if (version < 37) {
            applyV37();
            setVersion(37);
        }

        if (version < 38) {
            applyV38();
            setVersion(38);
        }

        if (version < 39) {
            applyV39();
            setVersion(39);
        }

        if (version < 40) {
            applyV40();
            setVersion(40);
        }

        if (version < 41) {
            applyV41();
            setVersion(41);
        }

        log.info("Schema upgrade complete");
    }

    /**
     * v40: {@code priority} column on {@code javdb_enrichment_queue}.
     *
     * <p>Adds {@code priority TEXT NOT NULL DEFAULT 'NORMAL'} with a CHECK constraint
     * limiting values to LOW/NORMAL/HIGH/URGENT. Existing rows take the default NORMAL.
     * Adds a partial index on {@code (priority, sort_order, id) WHERE status = 'pending'}
     * to support priority-aware claim ordering in step 2.
     */
    private void applyV40() {
        log.info("Applying migration v40: priority column on javdb_enrichment_queue");
        jdbi.useHandle(h -> {
            h.execute("""
                    ALTER TABLE javdb_enrichment_queue
                      ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'
                      CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT'))
                    """);
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_jeq_claim_priority
                      ON javdb_enrichment_queue(priority, sort_order, id)
                      WHERE status = 'pending'
                    """);
        });
    }

    /**
     * v41: backfill — discharge stale {@code javdb_enrichment_queue} rows whose review has
     * already been manually resolved.
     *
     * <p>Marks {@code status='done'} for any {@code fetch_title} row that is still {@code failed}
     * but whose title has a closed {@code enrichment_review_queue} row with an operator-resolution
     * value (manual_picker, manual_override, accepted_gap, marked_resolved, dismissed).
     * These rows were left orphaned by the missing reconciliation path now fixed by this release.
     *
     * <p>Idempotent: re-running finds zero matching rows.
     */
    private void applyV41() {
        log.info("Applying migration v41: discharge stale failed enrichment queue rows with closed review rows");
        jdbi.useHandle(h -> h.execute("""
                UPDATE javdb_enrichment_queue
                SET status = 'done',
                    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                    last_error = COALESCE(last_error, '') || ' [resolved: backfill_cleanup]'
                WHERE job_type = 'fetch_title'
                  AND status = 'failed'
                  AND target_id IN (
                    SELECT title_id FROM enrichment_review_queue
                    WHERE resolved_at IS NOT NULL
                      AND resolution IN (
                        'manual_picker', 'manual_override',
                        'accepted_gap', 'marked_resolved', 'dismissed'
                      )
                  )
                """));
    }

    /**
     * v39: triage columns on {@code enrichment_review_queue}.
     *
     * <p>Adds {@code last_seen_at} (nullable TEXT) — stamped on resolve in future waves,
     * backfilled from {@code created_at} for all existing rows in this migration.
     * Adds {@code detail} (nullable TEXT) — reserved for per-category JSON in future waves.
     */
    private void applyV39() {
        log.info("Applying migration v39: last_seen_at + detail columns on enrichment_review_queue");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "enrichment_review_queue", "last_seen_at", "TEXT");
            addColumnIfMissing(h, "enrichment_review_queue", "detail",       "TEXT");
            h.execute("UPDATE enrichment_review_queue SET last_seen_at = created_at WHERE last_seen_at IS NULL");
        });
    }

    /**
     * v38: revalidation queue and {@code last_revalidated_at} column.
     *
     * <p>Adds {@code last_revalidated_at} to {@code title_javdb_enrichment} so the safety-net
     * sweep can efficiently find rows that have never been revalidated or are stale (>30 days).
     * Creates {@code revalidation_pending} as the dirty queue: title IDs enqueued on drift
     * detection (actress filmography slug change or entry vanish) for priority revalidation.
     */
    private void applyV38() {
        log.info("Applying migration v38: revalidation_pending table + last_revalidated_at column");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "title_javdb_enrichment", "last_revalidated_at", "TEXT");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tje_revalidated ON title_javdb_enrichment(last_revalidated_at)");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS revalidation_pending (
                        title_id    INTEGER PRIMARY KEY REFERENCES titles(id) ON DELETE CASCADE,
                        enqueued_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                        reason      TEXT    NOT NULL
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_reval_enqueued ON revalidation_pending(enqueued_at)");
        });
    }

    /**
     * v37: {@code title_javdb_enrichment_history} — append-only audit log.
     *
     * <p>Snapshots the prior enrichment row on every overwrite ({@code enrichment_runner}),
     * manual clear ({@code cleanup}), and title delete ({@code title_deleted}). Intentionally
     * has no FK on {@code title_id} so history outlives its title row.
     */
    private void applyV37() {
        log.info("Applying migration v37: title_javdb_enrichment_history audit log");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_javdb_enrichment_history (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id        INTEGER NOT NULL,
                        title_code      TEXT    NOT NULL,
                        changed_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                        reason          TEXT,
                        prior_slug      TEXT,
                        prior_payload   TEXT
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tjeh_title ON title_javdb_enrichment_history(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tjeh_code  ON title_javdb_enrichment_history(title_code)");
        });
    }

    /**
     * v36: {@code enrichment_review_queue} table for write-time gate outcomes.
     *
     * <p>Rows are written when the gate cannot proceed with a normal enrichment write:
     * sentinel short-circuit → {@code ambiguous}, cast mismatch → {@code cast_anomaly},
     * cast parse failure → {@code fetch_failed}.
     * A partial unique index on {@code (title_id, reason) WHERE resolved_at IS NULL}
     * makes enqueue idempotent.
     */
    private void applyV36() {
        log.info("Applying migration v36: enrichment_review_queue table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS enrichment_review_queue (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id        INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
                        slug            TEXT,
                        reason          TEXT    NOT NULL,
                        resolver_source TEXT,
                        created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                        resolved_at     TEXT,
                        resolution      TEXT
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_erq_title ON enrichment_review_queue(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_erq_open  ON enrichment_review_queue(reason) WHERE resolved_at IS NULL");
            h.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_erq_open_unique
                        ON enrichment_review_queue(title_id, reason) WHERE resolved_at IS NULL""");
        });
    }

    /**
     * v35: provenance columns on {@code title_javdb_enrichment}.
     *
     * <p>Adds {@code resolver_source} (how the slug was resolved), {@code confidence}
     * (HIGH/MEDIUM/LOW/UNKNOWN tiering), and {@code cast_validated} (write-time gate result).
     * No backfill here — the one-shot {@code EnrichmentProvenanceBackfillTask} stamps
     * existing rows at startup.
     */
    private void applyV35() {
        log.info("Applying migration v35: provenance columns on title_javdb_enrichment");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "title_javdb_enrichment", "resolver_source", "TEXT");
            addColumnIfMissing(h, "title_javdb_enrichment", "confidence",      "TEXT");
            addColumnIfMissing(h, "title_javdb_enrichment", "cast_validated",  "INTEGER");
        });
    }

    /**
     * v34: drift-detection columns for the filmography cache.
     *
     * <p>Adds {@code stale} on entries (set to 1 when the entry vanishes from a re-fetch
     * but is still referenced by enriched titles), and {@code last_drift_count} +
     * {@code last_fetch_status} on the metadata row (telemetry + 404 pin signal).
     */
    private void applyV34() {
        log.info("Applying migration v34: drift-detection columns on filmography tables");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "javdb_actress_filmography_entry",
                    "stale", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(h, "javdb_actress_filmography",
                    "last_drift_count", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(h, "javdb_actress_filmography",
                    "last_fetch_status", "TEXT NOT NULL DEFAULT 'ok'");
        });
    }

    /**
     * v33: per-actress javdb filmography cache tables.
     *
     * <p>{@code javdb_actress_filmography} — one metadata row per actress (slug, fetch time,
     * page count, last release date, source). {@code javdb_actress_filmography_entry} — one
     * (actress_slug, product_code, title_slug) row per filmography entry. These tables back
     * the L2 disk cache used by {@code JavdbSlugResolver} to avoid repeated HTTP fetches
     * of the same actress's filmography. See spec/PROPOSAL_ENRICHMENT_HARDENING.md §1A.
     */
    private void applyV33() {
        log.info("Applying migration v33: javdb_actress_filmography + entry tables");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_actress_filmography (
                        actress_slug      TEXT PRIMARY KEY,
                        fetched_at        TEXT NOT NULL,
                        page_count        INTEGER NOT NULL,
                        last_release_date TEXT,
                        source            TEXT NOT NULL
                    )""");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_actress_filmography_entry (
                        actress_slug TEXT NOT NULL,
                        product_code TEXT NOT NULL,
                        title_slug   TEXT NOT NULL,
                        PRIMARY KEY (actress_slug, product_code),
                        FOREIGN KEY (actress_slug) REFERENCES javdb_actress_filmography(actress_slug)
                            ON DELETE CASCADE
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_filmography_entry_code
                        ON javdb_actress_filmography_entry(product_code)""");
        });
    }

    /**
     * v32: corrects the v31 sentinel backfill, which matched {@code stage_name} but the
     * sentinel rows ({@code Various}, {@code Unknown}, {@code Amateur}) live under
     * {@code canonical_name} with {@code stage_name = NULL}. Re-runs the backfill against
     * {@code canonical_name}. Idempotent.
     */
    private void applyV32() {
        log.info("Applying migration v32: fix sentinel backfill (match canonical_name)");
        jdbi.useHandle(h -> h.execute("""
                UPDATE actresses SET is_sentinel = 1
                WHERE LOWER(canonical_name) IN ('various', 'unknown', 'amateur')
                """));
    }

    /**
     * v31: title-driven enrichment schema.
     *
     * <p>(a) Rebuilds {@code javdb_enrichment_queue}: makes {@code actress_id} nullable
     * and adds {@code source TEXT NOT NULL DEFAULT 'actress'}. All existing rows are
     * preserved with {@code source='actress'}. Indexes are recreated.
     *
     * <p>(b) Adds {@code actresses.is_sentinel} and backfills: Various / Unknown / Amateur
     * by {@code canonical_name} (aliases are not matched). Note: an earlier revision matched
     * {@code stage_name}, which missed real-world rows where {@code stage_name IS NULL};
     * v32 re-applies the corrected backfill for DBs that already advanced to v31.
     */
    private void applyV31() {
        log.info("Applying migration v31: title-driven enrichment (nullable actress_id + source, is_sentinel)");
        jdbi.useHandle(h -> {
            // ── (a) Rebuild javdb_enrichment_queue ────────────────────────────────
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_enrichment_queue_v31 (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        job_type        TEXT NOT NULL,
                        target_id       INTEGER NOT NULL,
                        actress_id      INTEGER,
                        source          TEXT NOT NULL DEFAULT 'actress',
                        status          TEXT NOT NULL,
                        attempts        INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at TEXT NOT NULL,
                        last_error      TEXT,
                        created_at      TEXT NOT NULL,
                        updated_at      TEXT NOT NULL,
                        sort_order      INTEGER
                    )""");
            h.execute("""
                    INSERT INTO javdb_enrichment_queue_v31
                        (id, job_type, target_id, actress_id, source, status, attempts,
                         next_attempt_at, last_error, created_at, updated_at, sort_order)
                    SELECT id, job_type, target_id, actress_id, 'actress', status, attempts,
                           next_attempt_at, last_error, created_at, updated_at, sort_order
                    FROM javdb_enrichment_queue
                    """);
            h.execute("DROP TABLE javdb_enrichment_queue");
            h.execute("ALTER TABLE javdb_enrichment_queue_v31 RENAME TO javdb_enrichment_queue");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_claim   ON javdb_enrichment_queue(status, next_attempt_at)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_actress ON javdb_enrichment_queue(actress_id, status)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_source  ON javdb_enrichment_queue(source, status)");

            // ── (b) Add is_sentinel + backfill ────────────────────────────────────
            addColumnIfMissing(h, "actresses", "is_sentinel", "INTEGER NOT NULL DEFAULT 0");
            h.execute("""
                    UPDATE actresses SET is_sentinel = 1
                    WHERE LOWER(canonical_name) IN ('various', 'unknown', 'amateur')
                    """);
        });
    }

    /**
     * v30: remove duplicate javdb_enrichment_queue rows caused by the pre-fix enqueue bug
     * where re-enqueueing a failed title inserted a new pending row instead of replacing it.
     * Keeps the most recent row per (job_type, target_id, actress_id) group, preferring
     * active statuses (pending, in_flight) over terminal ones (done, failed, cancelled).
     */
    private void applyV30() {
        log.info("Applying migration v30: dedup javdb_enrichment_queue");
        jdbi.useHandle(h -> h.execute("""
                DELETE FROM javdb_enrichment_queue
                WHERE id NOT IN (
                    SELECT id FROM (
                        SELECT id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY job_type, target_id, actress_id
                                   ORDER BY
                                     CASE status
                                       WHEN 'in_flight' THEN 0
                                       WHEN 'pending'   THEN 1
                                       WHEN 'paused'    THEN 2
                                       WHEN 'done'      THEN 3
                                       WHEN 'failed'    THEN 4
                                       ELSE 5
                                     END ASC,
                                     updated_at DESC
                               ) AS rn
                        FROM javdb_enrichment_queue
                    ) ranked
                    WHERE rn = 1
                )
                """));
    }

    /**
     * v29: javdb_enrichment_queue.sort_order — explicit queue ordering for promote/demote
     * and item-level pause support. Backfills pending rows to preserve FIFO order.
     */
    private void applyV29() {
        log.info("Applying migration v29: javdb_enrichment_queue.sort_order");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "javdb_enrichment_queue", "sort_order", "INTEGER");
            h.execute("""
                    UPDATE javdb_enrichment_queue
                    SET sort_order = id
                    WHERE status IN ('pending', 'paused') AND sort_order IS NULL
                    """);
        });
    }

    /**
     * v28: titles.grade_source provenance column + rating_curve single-row config table.
     *
     * <p>grade_source tracks whether a title's grade was set by enrichment, AI-derived YAML
     * loading, or manual user override. Existing non-null grade rows are backfilled to 'ai'.
     * rating_curve persists the Bayesian cutoffs computed from the javdb-enriched population.
     */
    private void applyV28() {
        log.info("Applying migration v28: titles.grade_source + rating_curve table");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "titles", "grade_source", "TEXT");
            h.execute("UPDATE titles SET grade_source = 'ai' WHERE grade IS NOT NULL AND grade_source IS NULL");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS rating_curve (
                        id                  INTEGER PRIMARY KEY CHECK (id = 1),
                        global_mean         REAL    NOT NULL,
                        global_count        INTEGER NOT NULL,
                        min_credible_votes  INTEGER NOT NULL,
                        cutoffs_json        TEXT    NOT NULL,
                        computed_at         TEXT    NOT NULL
                    )""");
        });
    }

    /**
     * v27: javdb_actress_staging.local_avatar_path — stores the relative path
     * (under dataDir) to a locally cached actress avatar. See spec/PROPOSAL_ACTRESS_AVATARS.md.
     */
    private void applyV27() {
        log.info("Applying migration v27: javdb_actress_staging.local_avatar_path");
        jdbi.useHandle(h -> addColumnIfMissing(h, "javdb_actress_staging", "local_avatar_path", "TEXT"));
    }

    /**
     * v26: relax title_effective_tags.source CHECK to include 'enrichment', and backfill
     * enrichment-derived rows from title_enrichment_tags via curated_alias. Phase 4
     * integration — see spec/PROPOSAL_ENRICHMENT_TAG_INTEGRATION.md.
     */
    private void applyV26() {
        log.info("Applying migration v26: title_effective_tags accepts 'enrichment' source + backfill");
        jdbi.useHandle(h -> {
            // SQLite cannot ALTER an existing CHECK; rebuild the table preserving rows.
            h.execute("DROP INDEX IF EXISTS idx_title_effective_tags_tag");
            h.execute("ALTER TABLE title_effective_tags RENAME TO title_effective_tags_old");
            h.execute("""
                    CREATE TABLE title_effective_tags (
                        title_id  INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
                        tag       TEXT NOT NULL,
                        source    TEXT NOT NULL CHECK(source IN ('direct', 'label', 'enrichment')),
                        PRIMARY KEY (title_id, tag)
                    )""");
            h.execute("""
                    INSERT INTO title_effective_tags (title_id, tag, source)
                    SELECT title_id, tag, source FROM title_effective_tags_old
                    """);
            h.execute("DROP TABLE title_effective_tags_old");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_effective_tags_tag ON title_effective_tags(tag)");

            // Backfill enrichment-derived rows. Skipped silently if the v25 enrichment tables
            // aren't present (defensive — they should be after applyV25, but apply guards anyway).
            int hasEtags = h.createQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='title_enrichment_tags'")
                    .mapTo(Integer.class).one();
            if (hasEtags == 0) {
                log.info("v26 backfill skipped: title_enrichment_tags not present");
                return;
            }
            int inserted = h.execute("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT tet.title_id, etd.curated_alias, 'enrichment'
                    FROM title_enrichment_tags tet
                    JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                    WHERE etd.curated_alias IS NOT NULL
                      AND etd.curated_alias IN (SELECT name FROM tags)
                    """);
            log.info("v26 backfill: inserted {} enrichment-derived title_effective_tags rows", inserted);
        });
    }

    /**
     * v25: title_javdb_enrichment + enrichment tag tables. Backfills from javdb_title_staging
     * so existing enriched titles immediately have queryable enrichment rows + normalized tags.
     * The staging table is left in place — Phase 2 will retire it.
     */
    private void applyV25() {
        log.info("Applying migration v25: title_javdb_enrichment + enrichment tag tables (with backfill)");
        jdbi.useHandle(h -> {
            // Schema
            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_javdb_enrichment (
                        title_id            INTEGER PRIMARY KEY REFERENCES titles(id) ON DELETE CASCADE,
                        javdb_slug          TEXT NOT NULL,
                        fetched_at          TEXT NOT NULL,
                        release_date        TEXT,
                        rating_avg          REAL,
                        rating_count        INTEGER,
                        maker               TEXT,
                        publisher           TEXT,
                        series              TEXT,
                        title_original      TEXT,
                        duration_minutes    INTEGER,
                        cover_url           TEXT,
                        thumbnail_urls_json TEXT,
                        cast_json           TEXT,
                        raw_path            TEXT
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tje_rating_avg   ON title_javdb_enrichment(rating_avg)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tje_release_date ON title_javdb_enrichment(release_date)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tje_maker        ON title_javdb_enrichment(maker)");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS enrichment_tag_definitions (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        name            TEXT NOT NULL UNIQUE,
                        curated_alias   TEXT REFERENCES tags(name),
                        title_count     INTEGER NOT NULL DEFAULT 0,
                        surface         INTEGER NOT NULL DEFAULT 1
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_etd_title_count ON enrichment_tag_definitions(title_count)");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_enrichment_tags (
                        title_id  INTEGER NOT NULL REFERENCES title_javdb_enrichment(title_id) ON DELETE CASCADE,
                        tag_id    INTEGER NOT NULL REFERENCES enrichment_tag_definitions(id),
                        PRIMARY KEY (title_id, tag_id)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tet_tag ON title_enrichment_tags(tag_id)");

            // Backfill — only run if the staging table exists (it does post-v24, but be defensive).
            int hasStaging = h.createQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='javdb_title_staging'")
                    .mapTo(Integer.class).one();
            if (hasStaging == 0) {
                log.info("v25 backfill skipped: javdb_title_staging not present");
                return;
            }

            // Step A: enrichment rows from fetched staging rows.
            int enrichmentRows = h.execute("""
                    INSERT OR IGNORE INTO title_javdb_enrichment (
                        title_id, javdb_slug, fetched_at, release_date, rating_avg, rating_count,
                        maker, publisher, series, title_original, duration_minutes,
                        cover_url, thumbnail_urls_json, cast_json, raw_path
                    )
                    SELECT title_id, javdb_slug, raw_fetched_at, release_date, rating_avg, rating_count,
                           maker, publisher, series, title_original, duration_minutes,
                           cover_url, thumbnail_urls_json, cast_json, raw_path
                    FROM javdb_title_staging
                    WHERE status = 'fetched' AND javdb_slug IS NOT NULL
                    """);
            log.info("v25 backfill: inserted {} title_javdb_enrichment rows", enrichmentRows);

            // Step B: distinct tag definitions from all fetched staging tags_json.
            // Guard with json_valid() so a single malformed row can't tank the migration.
            int tagDefs = h.execute("""
                    INSERT OR IGNORE INTO enrichment_tag_definitions (name)
                    SELECT DISTINCT TRIM(je.value)
                    FROM javdb_title_staging s, json_each(s.tags_json) je
                    WHERE s.status = 'fetched'
                      AND s.tags_json IS NOT NULL
                      AND json_valid(s.tags_json)
                      AND TRIM(je.value) != ''
                    """);
            log.info("v25 backfill: inserted {} enrichment_tag_definitions rows", tagDefs);

            // Step C: title→tag assignments.
            int assignments = h.execute("""
                    INSERT OR IGNORE INTO title_enrichment_tags (title_id, tag_id)
                    SELECT s.title_id, etd.id
                    FROM javdb_title_staging s, json_each(s.tags_json) je
                    JOIN enrichment_tag_definitions etd ON etd.name = TRIM(je.value)
                    WHERE s.status = 'fetched'
                      AND s.tags_json IS NOT NULL
                      AND json_valid(s.tags_json)
                      AND TRIM(je.value) != ''
                    """);
            log.info("v25 backfill: inserted {} title_enrichment_tags rows", assignments);

            // Step D: refresh title_count on definitions. Same code path the future
            // service-managed maintenance will use; bulk-applied here on first run.
            h.execute("""
                    UPDATE enrichment_tag_definitions
                    SET title_count = (
                        SELECT COUNT(*) FROM title_enrichment_tags
                        WHERE tag_id = enrichment_tag_definitions.id
                    )
                    """);
            log.info("v25 backfill: title_count refresh complete");
        });
    }

    /** v24: javdb enrichment queue + staging tables. */
    private void applyV24() {
        log.info("Applying migration v24: javdb enrichment queue and staging tables");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_enrichment_queue (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        job_type        TEXT NOT NULL,
                        target_id       INTEGER NOT NULL,
                        actress_id      INTEGER NOT NULL,
                        status          TEXT NOT NULL,
                        attempts        INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at TEXT NOT NULL,
                        last_error      TEXT,
                        created_at      TEXT NOT NULL,
                        updated_at      TEXT NOT NULL
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_claim   ON javdb_enrichment_queue(status, next_attempt_at)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_actress ON javdb_enrichment_queue(actress_id, status)");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_title_staging (
                        title_id            INTEGER PRIMARY KEY REFERENCES titles(id),
                        status              TEXT NOT NULL,
                        javdb_slug          TEXT,
                        raw_path            TEXT,
                        raw_fetched_at      TEXT,
                        title_original      TEXT,
                        release_date        TEXT,
                        duration_minutes    INTEGER,
                        maker               TEXT,
                        publisher           TEXT,
                        series              TEXT,
                        rating_avg          REAL,
                        rating_count        INTEGER,
                        tags_json           TEXT,
                        cast_json           TEXT,
                        cover_url           TEXT,
                        thumbnail_urls_json TEXT
                    )""");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_actress_staging (
                        actress_id          INTEGER PRIMARY KEY REFERENCES actresses(id),
                        javdb_slug          TEXT NOT NULL,
                        source_title_code   TEXT,
                        status              TEXT NOT NULL,
                        raw_path            TEXT,
                        raw_fetched_at      TEXT,
                        name_variants_json  TEXT,
                        avatar_url          TEXT,
                        twitter_handle      TEXT,
                        instagram_handle    TEXT,
                        title_count         INTEGER
                    )""");
            h.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_javdb_actress_slug ON javdb_actress_staging(javdb_slug)");
        });
    }

    /**
     * v23: {@code merge_candidates} table for Duplicate Triage Phase 3A.
     * Stores cross-row duplicate pairs detected by code normalization.
     */
    private void applyV23() {
        log.info("Applying migration v23: merge_candidates table");
        jdbi.useHandle(h -> h.execute("""
                CREATE TABLE IF NOT EXISTS merge_candidates (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    title_code_a  TEXT NOT NULL,
                    title_code_b  TEXT NOT NULL,
                    confidence    TEXT NOT NULL CHECK(confidence IN ('code-normalization','variant-suffix')),
                    detected_at   TEXT NOT NULL,
                    decision      TEXT CHECK(decision IN ('MERGE','DISMISS')),
                    decided_at    TEXT,
                    winner_code   TEXT,
                    executed_at   TEXT,
                    UNIQUE(title_code_a, title_code_b)
                )"""));
    }

    /**
     * v22: {@code duplicate_decisions} table for Duplicate Triage MVP (Phase 2A).
     * Stores per-location KEEP/TRASH/VARIANT decisions; {@code executed_at} remains
     * NULL until Phase 2C when decisions are acted on.
     */
    private void applyV22() {
        log.info("Applying migration v22: duplicate_decisions table");
        jdbi.useHandle(h -> h.execute("""
                CREATE TABLE IF NOT EXISTS duplicate_decisions (
                    title_code   TEXT NOT NULL,
                    volume_id    TEXT NOT NULL,
                    nas_path     TEXT NOT NULL,
                    decision     TEXT NOT NULL CHECK(decision IN ('KEEP','TRASH','VARIANT')),
                    created_at   TEXT NOT NULL,
                    executed_at  TEXT,
                    PRIMARY KEY (title_code, volume_id, nas_path)
                )"""));
    }

    /**
     * v21: {@code favorite_cleared_at} on titles + actresses, with triggers that
     * auto-maintain it on any UPDATE of {@code favorite}.
     *
     * <p>Supports the 30-day retention grace window used by the background thumbnail
     * evictor — see {@code spec/PROPOSAL_BACKGROUND_THUMBNAILS.md}.
     *
     * <p>Backfill: existing rows left with {@code favorite_cleared_at = NULL}. Pre-migration
     * un-favorites have unknown timestamp and are treated as "never favorited" (no grace).
     * This is the right default — the only risk is users losing grace they weren't tracking
     * yet, not getting surprise retention.
     */
    private void applyV21() {
        log.info("Applying migration v21: favorite_cleared_at + triggers");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "titles",    "favorite_cleared_at", "TEXT");
            addColumnIfMissing(h, "actresses", "favorite_cleared_at", "TEXT");
            SchemaInitializer.createFavoriteClearedAtTriggers(h);
        });
    }

    /**
     * v20: {@code needs_profiling} flag on actresses. Set to 1 for actresses created via the
     * Title Editor's inline-create flow so MCP / AI tooling can surface them as profiling
     * candidates. Existing actresses remain at the default (0).
     */
    private void applyV20() {
        log.info("Applying migration v20: actresses.needs_profiling");
        jdbi.useHandle(h -> addColumnIfMissing(h, "actresses", "needs_profiling",
                "INTEGER NOT NULL DEFAULT 0"));
    }

    /**
     * v19: {@code size_bytes} column on videos. Enables size-based duplicate detection
     * (MCP Phase 2 §4.1) and multi-file title dedup. Nullable — backfilled by sync
     * re-scan or the probe job, which now widens its "needs probing" predicate to
     * include rows where size is missing.
     */
    private void applyV19() {
        log.info("Applying migration v19: videos.size_bytes");
        jdbi.useHandle(h -> addColumnIfMissing(h, "videos", "size_bytes", "INTEGER"));
    }

    /**
     * v18: Video metadata columns for duplicate/normalization tooling.
     * All fields are nullable; {@code duration_sec IS NULL} is the "needs backfill" signal.
     */
    private void applyV18() {
        log.info("Applying migration v18: video metadata columns");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "videos", "duration_sec", "INTEGER");
            addColumnIfMissing(h, "videos", "width",        "INTEGER");
            addColumnIfMissing(h, "videos", "height",       "INTEGER");
            addColumnIfMissing(h, "videos", "video_codec",  "TEXT");
            addColumnIfMissing(h, "videos", "audio_codec",  "TEXT");
            addColumnIfMissing(h, "videos", "container",    "TEXT");
        });
    }

    /**
     * v17: AV Stars — creates {@code av_tag_definitions} and {@code av_video_tags} tables
     * for the tag taxonomy + filter feature (Phase 5e).
     */
    private void applyV17() {
        log.info("Applying migration v17: av_tag_definitions and av_video_tags tables");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS av_tag_definitions (
                        slug         TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        category     TEXT,
                        aliases_json TEXT
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS av_video_tags (
                        av_video_id  INTEGER NOT NULL REFERENCES av_videos(id) ON DELETE CASCADE,
                        tag_slug     TEXT NOT NULL REFERENCES av_tag_definitions(slug),
                        source       TEXT NOT NULL DEFAULT 'apply',
                        PRIMARY KEY (av_video_id, tag_slug)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_video_tags_tag ON av_video_tags(tag_slug)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_video_tags_video ON av_video_tags(av_video_id)");
        });
    }

    /**
     * v16: AV Stars — creates {@code av_video_screenshots} table for storing
     * locally-generated screenshot frames (Phase 5d).
     */
    private void applyV16() {
        log.info("Applying migration v16: av_video_screenshots table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS av_video_screenshots (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        av_video_id INTEGER NOT NULL REFERENCES av_videos(id) ON DELETE CASCADE,
                        seq         INTEGER NOT NULL,
                        path        TEXT NOT NULL,
                        UNIQUE(av_video_id, seq)
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_av_video_screenshots_video
                        ON av_video_screenshots(av_video_id)""");
        });
    }

    /**
     * v15: AV Stars — adds visit tracking to av_actresses and watch/bookmark/curation
     * fields to av_videos needed by the Phase 5 web UI.
     */
    private void applyV15() {
        log.info("Applying migration v15: visit/watch tracking on av_actresses and av_videos");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "av_actresses", "last_visited_at",  "TEXT");
            addColumnIfMissing(h, "av_actresses", "visit_count",      "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(h, "av_videos",    "bookmark",         "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(h, "av_videos",    "watched",          "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(h, "av_videos",    "last_watched_at",  "TEXT");
            addColumnIfMissing(h, "av_videos",    "watch_count",      "INTEGER NOT NULL DEFAULT 0");
        });
    }

    /**
     * v14: AV Stars — creates {@code av_actresses} and {@code av_videos} tables for the
     * Western performer library. Fully independent of the JAV actress/title schema.
     */
    private void applyV14() {
        log.info("Applying migration v14: av_actresses and av_videos tables");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS av_actresses (
                        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                        volume_id            TEXT NOT NULL REFERENCES volumes(id),
                        folder_name          TEXT NOT NULL,
                        stage_name           TEXT NOT NULL,

                        iafd_id              TEXT,
                        headshot_path        TEXT,
                        aka_names_json       TEXT,

                        gender               TEXT,
                        date_of_birth        TEXT,
                        date_of_death        TEXT,
                        birthplace           TEXT,
                        nationality          TEXT,
                        ethnicity            TEXT,

                        hair_color           TEXT,
                        eye_color            TEXT,
                        height_cm            INTEGER,
                        weight_kg            INTEGER,
                        measurements         TEXT,
                        cup                  TEXT,
                        shoe_size            TEXT,
                        tattoos              TEXT,
                        piercings            TEXT,

                        active_from          INTEGER,
                        active_to            INTEGER,
                        director_from        INTEGER,
                        director_to          INTEGER,
                        iafd_title_count     INTEGER,

                        website_url          TEXT,
                        social_json          TEXT,
                        platforms_json       TEXT,
                        external_refs_json   TEXT,

                        iafd_comments_json   TEXT,
                        awards_json          TEXT,

                        favorite             INTEGER NOT NULL DEFAULT 0,
                        bookmark             INTEGER NOT NULL DEFAULT 0,
                        rejected             INTEGER NOT NULL DEFAULT 0,
                        grade                TEXT,
                        notes                TEXT,

                        first_seen_at        TEXT NOT NULL,
                        last_scanned_at      TEXT,
                        last_iafd_synced_at  TEXT,
                        video_count          INTEGER NOT NULL DEFAULT 0,
                        total_size_bytes     INTEGER NOT NULL DEFAULT 0,

                        UNIQUE(volume_id, folder_name)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_actresses_volume ON av_actresses(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_actresses_iafd_id ON av_actresses(iafd_id)");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS av_videos (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        av_actress_id   INTEGER NOT NULL REFERENCES av_actresses(id),
                        volume_id       TEXT NOT NULL REFERENCES volumes(id),
                        relative_path   TEXT NOT NULL,
                        filename        TEXT NOT NULL,
                        extension       TEXT,
                        size_bytes      INTEGER,
                        mtime           TEXT,
                        last_seen_at    TEXT NOT NULL,
                        added_date      TEXT,
                        bucket          TEXT,

                        studio          TEXT,
                        release_date    TEXT,
                        parsed_title    TEXT,
                        resolution      TEXT,
                        codec           TEXT,
                        tags_json       TEXT,

                        favorite        INTEGER NOT NULL DEFAULT 0,
                        rejected        INTEGER NOT NULL DEFAULT 0,

                        UNIQUE(av_actress_id, relative_path)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_actress ON av_videos(av_actress_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_volume ON av_videos(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_studio ON av_videos(studio)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_bucket ON av_videos(bucket)");
        });
    }

    /**
     * v12: adds denormalization tables for tag and company filtering, plus a COLLATE NOCASE
     * index on actress names. v11 is reserved for AV Stars.
     */
    private void applyV12() {
        log.info("Applying migration v12: title_effective_tags, actress_companies, name nocase index");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_effective_tags (
                        title_id  INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
                        tag       TEXT NOT NULL,
                        source    TEXT NOT NULL CHECK(source IN ('direct', 'label')),
                        PRIMARY KEY (title_id, tag)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_effective_tags_tag ON title_effective_tags(tag)");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS actress_companies (
                        actress_id  INTEGER NOT NULL REFERENCES actresses(id) ON DELETE CASCADE,
                        company     TEXT NOT NULL,
                        PRIMARY KEY (actress_id, company)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_actress_companies_company ON actress_companies(company)");

            h.execute("CREATE INDEX IF NOT EXISTS idx_actresses_name_nocase ON actresses(canonical_name COLLATE NOCASE)");

            // Backfill title_effective_tags
            h.execute("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT title_id, tag, 'direct' FROM title_tags
                    """);
            h.execute("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT t.id, lt.tag, 'label'
                    FROM titles t
                    JOIN label_tags lt ON lt.label_code = t.label
                    WHERE t.label IS NOT NULL AND t.label != ''
                    """);

            // Backfill actress_companies
            h.execute("""
                    INSERT OR IGNORE INTO actress_companies (actress_id, company)
                    SELECT DISTINCT ta.actress_id, l.company
                    FROM title_actresses ta
                    JOIN titles t ON t.id = ta.title_id
                    JOIN labels l ON l.code = t.label
                    WHERE t.label IS NOT NULL AND t.label != ''
                      AND l.company IS NOT NULL
                    """);
        });
    }

    /**
     * v10: adds rich-profile columns to actresses — hiragana reading, retirement
     * announcement date, and JSON-serialized lists for alternate names with notes,
     * studio tenures, and awards. All additive; idempotent via column existence check.
     */
    private void applyV10() {
        log.info("Applying migration v10: adding rich profile columns to actresses");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "actresses", "name_reading", "TEXT");
            addColumnIfMissing(h, "actresses", "retirement_announced", "TEXT");
            addColumnIfMissing(h, "actresses", "alternate_names_json", "TEXT");
            addColumnIfMissing(h, "actresses", "primary_studios_json", "TEXT");
            addColumnIfMissing(h, "actresses", "awards_json", "TEXT");
        });
    }

    private static void addColumnIfMissing(org.jdbi.v3.core.Handle h, String table, String column, String type) {
        boolean hasCol = h.createQuery(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'")
                .mapTo(Integer.class).one() > 0;
        if (!hasCol) {
            h.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /** v9: adds bookmarked_at timestamp to actresses for Actresses dashboard ordering. */
    private void applyV9() {
        log.info("Applying migration v9: adding bookmarked_at to actresses");
        jdbi.useHandle(h -> {
            boolean hasCol = h.createQuery(
                    "SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='bookmarked_at'")
                    .mapTo(Integer.class).one() > 0;
            if (!hasCol) {
                h.execute("ALTER TABLE actresses ADD COLUMN bookmarked_at TEXT");
                // Backfill: stamp existing bookmarks with now so they don't all appear as epoch.
                h.createUpdate("UPDATE actresses SET bookmarked_at = :now WHERE bookmark = 1")
                        .bind("now", java.time.LocalDateTime.now().toString())
                        .execute();
            }
        });
    }

    /** v8: adds bookmarked_at timestamp to titles for Titles dashboard ordering. */
    private void applyV8() {
        log.info("Applying migration v8: adding bookmarked_at to titles");
        jdbi.useHandle(h -> {
            boolean hasCol = h.createQuery(
                    "SELECT COUNT(*) FROM pragma_table_info('titles') WHERE name='bookmarked_at'")
                    .mapTo(Integer.class).one() > 0;
            if (!hasCol) {
                h.execute("ALTER TABLE titles ADD COLUMN bookmarked_at TEXT");
                // Backfill: stamp existing bookmarks with now so they don't all appear as epoch.
                h.createUpdate("UPDATE titles SET bookmarked_at = :now WHERE bookmark = 1")
                        .bind("now", java.time.LocalDateTime.now().toString())
                        .execute();
            }
        });
    }

    /** v2: adds stage_name column to actresses for Japanese kanji/kana stage name storage. */
    private void applyV2() {
        log.info("Applying migration v2: adding stage_name to actresses");
        jdbi.useHandle(h -> h.execute("ALTER TABLE actresses ADD COLUMN stage_name TEXT"));
    }

    /** v4: adds company_description column to labels table. */
    private void applyV4() {
        log.info("Applying migration v4: adding company_description to labels");
        jdbi.useHandle(h -> h.execute("ALTER TABLE labels ADD COLUMN company_description TEXT"));
    }

    /** v5: adds company profile columns to labels, tags reference table, and label_tags join table. */
    private void applyV5() {
        log.info("Applying migration v5: label profile columns, tags table, label_tags table");
        jdbi.useHandle(h -> {
            h.execute("ALTER TABLE labels ADD COLUMN company_specialty TEXT");
            h.execute("ALTER TABLE labels ADD COLUMN company_founded TEXT");
            h.execute("ALTER TABLE labels ADD COLUMN company_status TEXT");
            h.execute("ALTER TABLE labels ADD COLUMN company_parent TEXT");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS tags (
                        name        TEXT PRIMARY KEY,
                        category    TEXT NOT NULL,
                        description TEXT
                    )""");
            h.execute("""
                    CREATE TABLE IF NOT EXISTS label_tags (
                        label_code  TEXT NOT NULL REFERENCES labels(code),
                        tag         TEXT NOT NULL REFERENCES tags(name),
                        PRIMARY KEY (label_code, tag)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_label_tags_tag ON label_tags(tag)");
        });
    }

    /** v3: adds actress profile fields, title metadata fields, and title_tags table. */
    private void applyV3() {
        log.info("Applying migration v3: actress profile fields, title metadata, title_tags");
        jdbi.useHandle(h -> {
            h.execute("ALTER TABLE actresses ADD COLUMN date_of_birth TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN birthplace TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN blood_type TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN height_cm INTEGER");
            h.execute("ALTER TABLE actresses ADD COLUMN bust INTEGER");
            h.execute("ALTER TABLE actresses ADD COLUMN waist INTEGER");
            h.execute("ALTER TABLE actresses ADD COLUMN hip INTEGER");
            h.execute("ALTER TABLE actresses ADD COLUMN cup TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN active_from TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN active_to TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN biography TEXT");
            h.execute("ALTER TABLE actresses ADD COLUMN legacy TEXT");

            h.execute("ALTER TABLE titles ADD COLUMN title_original TEXT");
            h.execute("ALTER TABLE titles ADD COLUMN title_english TEXT");
            h.execute("ALTER TABLE titles ADD COLUMN release_date TEXT");
            h.execute("ALTER TABLE titles ADD COLUMN notes TEXT");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_tags (
                        title_id  INTEGER NOT NULL REFERENCES titles(id),
                        tag       TEXT NOT NULL,
                        PRIMARY KEY (title_id, tag)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_tags_tag ON title_tags(tag)");
        });
    }

    /**
     * v7: recovery migration for visit tracking columns.
     *
     * <p>A prior bug in SchemaInitializer stamped user_version=6 on existing DBs without
     * running the v6 migration, leaving some DBs at version 6 but missing the columns.
     * This migration checks for column existence before adding so it is safe to run
     * regardless of whether the columns are already present.
     */
    private void applyV7() {
        log.info("Applying migration v7: ensure visit tracking columns exist on actresses and titles");
        jdbi.useHandle(h -> {
            boolean hasActressVisit = h.createQuery(
                    "SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='visit_count'")
                    .mapTo(Integer.class).one() > 0;
            if (!hasActressVisit) {
                h.execute("ALTER TABLE actresses ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 0");
                h.execute("ALTER TABLE actresses ADD COLUMN last_visited_at TEXT");
            }
            boolean hasTitleVisit = h.createQuery(
                    "SELECT COUNT(*) FROM pragma_table_info('titles') WHERE name='visit_count'")
                    .mapTo(Integer.class).one() > 0;
            if (!hasTitleVisit) {
                h.execute("ALTER TABLE titles ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 0");
                h.execute("ALTER TABLE titles ADD COLUMN last_visited_at TEXT");
            }
        });
    }

    /** v6: adds visit tracking columns (visit_count, last_visited_at) to actresses and titles. */
    private void applyV6() {
        log.info("Applying migration v6: visit tracking columns on actresses and titles");
        jdbi.useHandle(h -> {
            h.execute("ALTER TABLE actresses ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 0");
            h.execute("ALTER TABLE actresses ADD COLUMN last_visited_at TEXT");
            h.execute("ALTER TABLE titles ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 0");
            h.execute("ALTER TABLE titles ADD COLUMN last_visited_at TEXT");
        });
    }

    /**
     * v13: adds a UNIQUE index on (title_code, watched_at) in watch_history so that
     * {@code INSERT OR IGNORE} in the restore path can deduplicate entries idempotently.
     * Deduplicates any existing rows first (keeps the lowest rowid per pair).
     */
    private void applyV13() {
        log.info("Applying migration v13: unique index on watch_history(title_code, watched_at)");
        jdbi.useHandle(h -> {
            // Remove any pre-existing duplicate (title_code, watched_at) pairs before creating
            // the unique index — otherwise the index creation would fail.
            h.execute("""
                    DELETE FROM watch_history
                    WHERE rowid NOT IN (
                        SELECT MIN(rowid) FROM watch_history GROUP BY title_code, watched_at
                    )""");
            h.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_history_unique_entry
                        ON watch_history(title_code, watched_at)""");
        });
    }

    private int getVersion() {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version")
                        .mapTo(Integer.class)
                        .one());
    }

    private void setVersion(int version) {
        // PRAGMA user_version does not support bound parameters
        jdbi.useHandle(h -> h.execute("PRAGMA user_version = " + version));
    }
}
