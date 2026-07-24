package com.organizer3.db;

import com.organizer3.javdb.JavdbCode;
import com.organizer3.repository.StageNameNormalizer;
import com.organizer3.sync.TitleCodeParser;
import com.organizer3.translation.TranslationNormalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

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
    private static final int CURRENT_VERSION = 74;

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

        if (version < 42) {
            applyV42();
            setVersion(42);
        }

        if (version < 43) {
            applyV43();
            setVersion(43);
        }

        if (version < 44) {
            applyV44();
            setVersion(44);
        }

        if (version < 45) {
            applyV45();
            setVersion(45);
        }

        if (version < 46) {
            applyV46();
            setVersion(46);
        }

        if (version < 47) {
            applyV47();
            setVersion(47);
        }

        if (version < 48) {
            applyV48();
            setVersion(48);
        }

        if (version < 49) {
            applyV49();
            setVersion(49);
        }

        if (version < 50) {
            applyV50();
            setVersion(50);
        }

        if (version < 51) {
            applyV51();
            setVersion(51);
        }

        if (version < 52) {
            applyV52();
            setVersion(52);
        }

        if (version < 53) {
            applyV53();
            setVersion(53);
        }

        if (version < 54) {
            applyV54();
            setVersion(54);
        }

        if (version < 55) {
            applyV55();
            setVersion(55);
        }

        if (version < 56) {
            applyV56();
            setVersion(56);
        }

        if (version < 57) {
            applyV57();
            setVersion(57);
        }

        if (version < 58) {
            applyV58();
            setVersion(58);
        }

        if (version < 59) {
            applyV59();
            setVersion(59);
        }

        if (version < 60) {
            applyV60();
            setVersion(60);
        }

        if (version < 61) {
            applyV61();
            setVersion(61);
        }

        if (version < 62) {
            applyV62();
            setVersion(62);
        }

        if (version < 63) {
            applyV63();
            setVersion(63);
        }

        if (version < 64) {
            applyV64();
            setVersion(64);
        }

        if (version < 65) {
            applyV65();
            setVersion(65);
        }

        if (version < 66) {
            applyV66();
            setVersion(66);
        }

        if (version < 67) {
            applyV67();
            setVersion(67);
        }

        if (version < 68) {
            applyV68();
            setVersion(68);
        }

        if (version < 69) {
            applyV69();
            setVersion(69);
        }

        if (version < 70) {
            applyV70();
            setVersion(70);
        }

        if (version < 71) {
            applyV71();
            setVersion(71);
        }

        if (version < 72) {
            applyV72();
            setVersion(72);
        }

        if (version < 73) {
            applyV73();
            setVersion(73);
        }

        if (version < 74) {
            applyV74();
            setVersion(74);
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
     * v42: {@code av_screenshot_queue} — persistent FIFO queue for background AV screenshot
     * generation. Each row tracks one video; UNIQUE on {@code av_video_id} makes enqueue
     * idempotent. {@code ON DELETE CASCADE} removes rows automatically when the video is deleted.
     * Status values: PENDING | IN_PROGRESS | PAUSED | DONE | FAILED.
     *
     * <p>See spec/PROPOSAL_AV_SCREENSHOT_QUEUE.md.
     */
    private void applyV42() {
        log.info("Applying migration v42: av_screenshot_queue table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS av_screenshot_queue (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        av_video_id   INTEGER NOT NULL UNIQUE REFERENCES av_videos(id) ON DELETE CASCADE,
                        av_actress_id INTEGER NOT NULL REFERENCES av_actresses(id),
                        enqueued_at   TEXT NOT NULL,
                        started_at    TEXT,
                        completed_at  TEXT,
                        status        TEXT NOT NULL DEFAULT 'PENDING',
                        error         TEXT
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_asq_status_enqueued
                        ON av_screenshot_queue(status, enqueued_at)""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_asq_actress
                        ON av_screenshot_queue(av_actress_id)""");
        });
    }

    /**
     * v43: {@code custom_avatar_path} column on {@code actresses}.
     *
     * <p>Stores the relative path to a user-curated avatar image (e.g.
     * {@code actress-custom-avatars/42.jpg}). NULL means no custom avatar; resolution
     * falls back to {@code javdb_actress_staging.local_avatar_path} via COALESCE at
     * query time. No backfill needed — purely additive.
     *
     * <p>See spec/PROPOSAL_CUSTOM_PROFILE_IMAGES.md.
     */
    private void applyV43() {
        log.info("Applying migration v43: custom_avatar_path on actresses");
        jdbi.useHandle(h -> addColumnIfMissing(h, "actresses", "custom_avatar_path", "TEXT"));
    }

    /**
     * v44: Draft Mode schema — four draft tables + two new columns on {@code actresses}.
     *
     * <p>Creates:
     * <ul>
     *   <li>{@code draft_titles} — per-title WIP row; one active draft per title enforced by
     *       unique index on {@code title_id}.</li>
     *   <li>{@code draft_actresses} — keyed by javdb slug; ref-counted by
     *       {@code draft_title_actresses}; orphans reaped by GC sweep.</li>
     *   <li>{@code draft_title_actresses} — cast-slot resolution table; cascades on title delete.</li>
     *   <li>{@code draft_title_javdb_enrichment} — mirror of {@code title_javdb_enrichment};
     *       one row per draft; cascades on title delete.</li>
     * </ul>
     *
     * <p>Also adds {@code created_via} and {@code created_at} to {@code actresses} via
     * {@code addColumnIfMissing} (idempotent). {@code NULL} values on existing rows are
     * intentional — pre-Draft-Mode actresses have unknown provenance.
     *
     * <p>See spec/PROPOSAL_DRAFT_MODE.md §2 and §13 Phase 1.
     */
    private void applyV44() {
        log.info("Applying migration v44: Draft Mode schema (draft_titles, draft_actresses, " +
                 "draft_title_actresses, draft_title_javdb_enrichment) + actresses.created_via/created_at");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS draft_titles (
                        id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id              INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
                        code                  TEXT NOT NULL,
                        title_original        TEXT,
                        title_english         TEXT,
                        release_date          TEXT,
                        notes                 TEXT,
                        grade                 TEXT,
                        grade_source          TEXT,
                        upstream_changed      INTEGER NOT NULL DEFAULT 0,
                        last_validation_error TEXT,
                        created_at            TEXT NOT NULL,
                        updated_at            TEXT NOT NULL
                    )""");
            h.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_draft_titles_title_id
                        ON draft_titles(title_id)""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS draft_actresses (
                        javdb_slug            TEXT PRIMARY KEY,
                        stage_name            TEXT,
                        english_first_name    TEXT,
                        english_last_name     TEXT,
                        link_to_existing_id   INTEGER REFERENCES actresses(id),
                        created_at            TEXT NOT NULL,
                        updated_at            TEXT NOT NULL,
                        last_validation_error TEXT
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS draft_title_actresses (
                        draft_title_id INTEGER NOT NULL REFERENCES draft_titles(id) ON DELETE CASCADE,
                        javdb_slug     TEXT NOT NULL REFERENCES draft_actresses(javdb_slug),
                        resolution     TEXT NOT NULL,
                        PRIMARY KEY (draft_title_id, javdb_slug)
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS draft_title_javdb_enrichment (
                        draft_title_id  INTEGER PRIMARY KEY REFERENCES draft_titles(id) ON DELETE CASCADE,
                        javdb_slug      TEXT,
                        cast_json       TEXT,
                        maker           TEXT,
                        series          TEXT,
                        cover_url       TEXT,
                        tags_json       TEXT,
                        rating_avg      REAL,
                        rating_count    INTEGER,
                        resolver_source TEXT,
                        updated_at      TEXT NOT NULL
                    )""");

            addColumnIfMissing(h, "actresses", "created_via", "TEXT");
            addColumnIfMissing(h, "actresses", "created_at",  "TEXT");
        });
    }

    /**
     * v45: Draft Mode Phase 3 — add {@code new_payload} and {@code promotion_metadata} columns
     * to {@code title_javdb_enrichment_history}.
     *
     * <p>{@code new_payload} captures the post-promotion canonical enrichment state as JSON.
     * {@code promotion_metadata} captures the promotion event details as JSON
     * ({@code resolutions}, {@code skip_count}, {@code sentinel_chosen}, {@code cover_fetched}).
     *
     * <p>NULL on existing rows is intentional — pre-Phase-3 history rows were written by the
     * background enrichment runner (not Draft Mode), so they have no promotion context.
     *
     * <p>Idempotent via {@code addColumnIfMissing}.
     *
     * <p>See spec/PROPOSAL_DRAFT_MODE.md §9.3.
     */
    private void applyV45() {
        log.info("Applying migration v45: new_payload + promotion_metadata on title_javdb_enrichment_history");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "title_javdb_enrichment_history", "new_payload",         "TEXT");
            addColumnIfMissing(h, "title_javdb_enrichment_history", "promotion_metadata",  "TEXT");
        });
    }

    /**
     * v46: {@code title_path_history} — forensic log of volume-relative paths a title has
     * ever occupied. Used by {@code SyncIdentityMatcher} as a last-resort match layer: if a
     * folder reappears at a historically-known path and code-based matching misses, the
     * history row can surface it as a {@code path_history_match} review-queue candidate.
     *
     * <p>No FK on {@code title_id} — mirrors {@code title_javdb_enrichment_history}. Rows must
     * survive title deletion so a re-add can recover the prior identity.
     *
     * <p>Identity is {@code (volume_id, partition_id, path)} — unique across all locations in the
     * library. Cross-volume path matches are intentionally excluded; see spec §Phase 4.
     *
     * <p>See spec/PROPOSAL_SYNC_METADATA_PRESERVATION.md §5 Option D and §6 Phase 4.
     */
    private void applyV46() {
        log.info("Applying migration v46: title_path_history table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_path_history (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id      INTEGER NOT NULL,
                        volume_id     TEXT    NOT NULL,
                        partition_id  TEXT    NOT NULL,
                        path          TEXT    NOT NULL,
                        first_seen_at TEXT    NOT NULL,
                        last_seen_at  TEXT    NOT NULL,
                        UNIQUE (volume_id, partition_id, path)
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_title_path_history_lookup
                        ON title_path_history (volume_id, partition_id, path)""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_title_path_history_title_id
                        ON title_path_history (title_id)""");
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

    /**
     * v47: {@code translation_strategy} + {@code translation_cache} tables for the local
     * LLM translation service.
     *
     * <p>{@code translation_strategy} holds versioned (model + prompt-template) pairs.
     * {@code translation_cache} is the pile of work the service has already done, keyed
     * by {@code (source_hash, strategy_id)} where {@code source_hash} is the SHA-256 of
     * the NFKC-normalised source text.
     *
     * <p>Both tables are created via {@code CREATE TABLE IF NOT EXISTS} — idempotent.
     *
     * <p>See spec/PROPOSAL_TRANSLATION_SERVICE.md §5.1.
     */
    private void applyV47() {
        log.info("Applying migration v47: translation_strategy + translation_cache tables");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS translation_strategy (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        name            TEXT NOT NULL UNIQUE,
                        model_id        TEXT NOT NULL,
                        prompt_template TEXT NOT NULL,
                        options_json    TEXT,
                        is_active       INTEGER NOT NULL DEFAULT 1
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS translation_cache (
                        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_hash          TEXT NOT NULL,
                        source_text          TEXT NOT NULL,
                        strategy_id          INTEGER NOT NULL REFERENCES translation_strategy(id),
                        english_text         TEXT,
                        human_corrected_text TEXT,
                        human_corrected_at   TEXT,
                        failure_reason       TEXT,
                        retry_after          TEXT,
                        latency_ms           INTEGER,
                        prompt_tokens        INTEGER,
                        eval_tokens          INTEGER,
                        eval_duration_ns     INTEGER,
                        cached_at            TEXT NOT NULL,
                        UNIQUE(source_hash, strategy_id)
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_tc_strategy
                        ON translation_cache(strategy_id)""");
        });
    }

    private void applyV48() {
        log.info("Applying migration v48: translation_queue table + *_en columns on title_javdb_enrichment");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS translation_queue (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_text     TEXT NOT NULL,
                        strategy_id     INTEGER NOT NULL,
                        submitted_at    TEXT NOT NULL,
                        started_at      TEXT,
                        completed_at    TEXT,
                        status          TEXT NOT NULL,
                        callback_kind   TEXT,
                        callback_id     INTEGER,
                        attempt_count   INTEGER NOT NULL DEFAULT 0,
                        last_error      TEXT
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_tq_status
                        ON translation_queue(status, submitted_at)""");

            // Idempotent ALTER TABLE: add *_en columns to title_javdb_enrichment
            // SQLite does not support IF NOT EXISTS on ALTER TABLE ADD COLUMN, so we catch
            // errors if the column already exists and continue.
            for (String col : new String[]{"title_original_en", "series_en", "maker_en", "publisher_en"}) {
                try {
                    h.execute("ALTER TABLE title_javdb_enrichment ADD COLUMN " + col + " TEXT");
                } catch (Exception e) {
                    if (!e.getMessage().contains("duplicate column name")) {
                        throw e;
                    }
                }
            }
        });
    }

    /**
     * v49: Add {@code tier2_strategy_id} column to {@code translation_strategy} for tier-2 fallback pairing.
     *
     * <p>A nullable self-referential FK: each tier-1 strategy points to its tier-2 fallback.
     * Tier-2 strategies have NULL in this column.
     *
     * <p>See spec/PROPOSAL_TRANSLATION_SERVICE.md §Phase 3.
     */
    private void applyV49() {
        log.info("Applying migration v49: tier2_strategy_id column on translation_strategy");
        jdbi.useHandle(h -> {
            try {
                h.execute("ALTER TABLE translation_strategy ADD COLUMN tier2_strategy_id INTEGER REFERENCES translation_strategy(id)");
            } catch (Exception e) {
                if (!e.getMessage().contains("duplicate column name")) {
                    throw e;
                }
            }
        });
    }

    /**
     * v50: Stage-name shortcut tables for actress kanji→romaji resolution.
     *
     * <p>{@code stage_name_lookup} is the curated seed table populated at startup from actress YAMLs.
     * {@code stage_name_suggestion} records LLM-produced suggestions for human review.
     *
     * <p>See spec/PROPOSAL_TRANSLATION_SERVICE.md §6.1.
     */
    private void applyV50() {
        log.info("Applying migration v50: stage_name_lookup + stage_name_suggestion tables");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS stage_name_lookup (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        kanji_form      TEXT NOT NULL UNIQUE,
                        romanized_form  TEXT NOT NULL,
                        actress_slug    TEXT,
                        source          TEXT NOT NULL DEFAULT 'yaml_seed',
                        seeded_at       TEXT NOT NULL
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_snl_kanji
                        ON stage_name_lookup(kanji_form)""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS stage_name_suggestion (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        kanji_form       TEXT NOT NULL,
                        suggested_romaji TEXT NOT NULL,
                        suggested_at     TEXT NOT NULL,
                        reviewed_at      TEXT,
                        review_decision  TEXT,
                        final_romaji     TEXT,
                        UNIQUE(kanji_form, suggested_romaji)
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_sns_kanji
                        ON stage_name_suggestion(kanji_form)""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_sns_unreviewed
                        ON stage_name_suggestion(review_decision)
                        WHERE review_decision IS NULL""");
        });
    }

    private void applyV51() {
        log.info("Applying migration v51: NFKC-normalize draft_actresses.stage_name");
        jdbi.useTransaction(h -> {
            List<Object[]> rows = h.createQuery("SELECT javdb_slug, stage_name FROM draft_actresses")
                    .map((rs, ctx) -> new Object[]{rs.getString(1), rs.getString(2)})
                    .list();
            int changed = 0;
            for (Object[] row : rows) {
                String slug = (String) row[0];
                String stageName = (String) row[1];
                if (stageName == null) continue;
                String normalized = TranslationNormalization.normalize(stageName);
                if (!normalized.equals(stageName)) {
                    h.createUpdate("UPDATE draft_actresses SET stage_name = :n WHERE javdb_slug = :s")
                            .bind("n", normalized)
                            .bind("s", slug)
                            .execute();
                    changed++;
                }
            }
            log.info("Migration v51: NFKC-normalized {} draft_actresses rows", changed);
        });
    }

    /**
     * v52: {@code link_to_draft_slug} column on {@code draft_actresses}.
     *
     * <p>Supports the Near-Miss curation "new canonical" outcome: when multiple drafts share
     * the same kanji stage name, one is elected primary ({@code link_to_draft_slug IS NULL})
     * and the rest point at the primary via this column. At promotion all siblings resolve to
     * the primary's newly-allocated {@code actresses.id}.
     *
     * <p>FK is {@code ON DELETE SET NULL}: if the primary is deleted before promotion, siblings
     * become orphans. Re-election logic in {@link com.organizer3.javdb.draft.DraftPromotionService}
     * handles that case at promotion time.
     *
     * <p>Idempotent via {@code addColumnIfMissing}.
     *
     * <p>See spec/PROPOSAL_NEAR_MISS_RESOLVER.md §6 and §4.4 outcome B.
     */
    private void applyV52() {
        log.info("Applying migration v52: link_to_draft_slug column on draft_actresses");
        jdbi.useHandle(h -> addColumnIfMissing(h, "draft_actresses", "link_to_draft_slug",
                "TEXT REFERENCES draft_actresses(javdb_slug) ON DELETE SET NULL"));
    }

    /**
     * v53: {@code priority} column on {@code translation_queue}.
     *
     * <p>Adds {@code priority INTEGER NOT NULL DEFAULT 0}. Higher values are claimed first
     * by {@code claimNext} (ORDER BY priority DESC, submitted_at ASC). Stage-name enqueues
     * use priority=10 so they jump the queue ahead of bulk title translations (priority=0).
     *
     * <p>Idempotent via {@code addColumnIfMissing}. Existing rows take the default value of 0.
     */
    private void applyV53() {
        log.info("Applying migration v53: priority column on translation_queue");
        jdbi.useHandle(h -> addColumnIfMissing(h, "translation_queue", "priority",
                "INTEGER NOT NULL DEFAULT 0"));
    }

    /**
     * v54: {@code stale_since} column on {@code title_locations} for grace-period orphan tracking.
     *
     * <p>{@code NULL} means the row is live (observed on the most recent sync of its scope).
     * A non-null ISO-8601 timestamp is when the row was first marked absent. Rows with
     * {@code stale_since} older than {@code sync.staleGraceDays} days are swept on next sync.
     *
     * <p>No backfill: {@code NULL} is correct for all existing rows (they are all "live").
     */
    private void applyV54() {
        log.info("Applying migration v54: stale_since column on title_locations for grace-period orphan tracking");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "title_locations", "stale_since", "TEXT");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_stale_since ON title_locations(stale_since)");
        });
    }

    /**
     * v55: {@code reconcile_reports} table for persisted reconcile pass results.
     *
     * <p>Stores one row per reconcile run (manual or coherent-sync-triggered) so the
     * admin can see overnight results the next morning. The {@code detail_json} column
     * is nullable — it is only populated when the run was in verbose mode.
     *
     * <p>See spec/PROPOSAL_SYNC_RECONCILIATION.md §4.2 and Phase 3.
     */
    private void applyV55() {
        log.info("Applying migration v55: reconcile_reports table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS reconcile_reports (
                        id                       INTEGER PRIMARY KEY AUTOINCREMENT,
                        generated_at             TEXT NOT NULL,
                        duplicate_live_locations INTEGER NOT NULL,
                        pending_grace            INTEGER NOT NULL,
                        oldest_pending_grace_days INTEGER NOT NULL,
                        past_grace_stragglers    INTEGER NOT NULL,
                        actress_folder_mismatches INTEGER NOT NULL,
                        triggered_by             TEXT NOT NULL,
                        detail_json              TEXT
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_reconcile_reports_generated_at
                        ON reconcile_reports(generated_at DESC)""");
        });
    }

    /**
     * v56: NFC-normalize + space-strip existing {@code actresses.stage_name} values.
     *
     * <p>Applies the same normalization as {@link StageNameNormalizer#normalize(String)} to
     * every non-null {@code stage_name} row, updating only rows where the value actually
     * changes. This is a one-shot backfill to clean rows that were written before the
     * normalizer was applied at write paths (Option 1 from
     * spec/PROPOSAL_ACTRESS_PROFILE_HARDENING.md).
     *
     * <p>Idempotent: re-running leaves already-normalized rows untouched.
     */
    private void applyV56() {
        jdbi.useTransaction(h -> {
            List<Object[]> rows = h.createQuery(
                            "SELECT id, stage_name FROM actresses WHERE stage_name IS NOT NULL")
                    .map((rs, ctx) -> new Object[]{rs.getLong(1), rs.getString(2)})
                    .list();
            int changed = 0;
            int skipped = 0;
            for (Object[] row : rows) {
                long id = (Long) row[0];
                String stageName = (String) row[1];
                String normalized = StageNameNormalizer.normalize(stageName);
                if (normalized == null || normalized.equals(stageName)) {
                    skipped++;
                } else {
                    h.createUpdate("UPDATE actresses SET stage_name = :n WHERE id = :id")
                            .bind("n", normalized)
                            .bind("id", id)
                            .execute();
                    changed++;
                }
            }
            log.info("Schema v56: normalized {} stage_names ({} rows scanned, {} skipped as already-normal)",
                    changed, rows.size(), skipped);
        });
    }

    /**
     * v57: {@code alias_capture_events} — durable persistence for Phase 6d Slice C
     * measurement events (alias-capture modal triggers/dismissals and draft-editor opens).
     *
     * <p>Logback rolls at 10MB×4 files (~2h retention), so log-only measurement loses
     * data before the 7-day window closes. This table captures the same events as the
     * existing {@code alias-capture:} / {@code draft-editor:} log lines so the measurement
     * window can be restarted. The log lines remain as a redundant signal.
     *
     * <p>{@code kind} ∈ {trigger, dismissed, editor_open}. For {@code trigger}, {@code needs}
     * holds the comma-joined needs list. For {@code dismissed}, {@code via} records the
     * dismissal path. For {@code editor_open}, only {@code title_id} is populated.
     *
     * <p>See spec/PROPOSAL_TRANSLATION_PHASE_6.md §5.4.
     */
    private void applyV57() {
        log.info("Applying migration v57: alias_capture_events table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS alias_capture_events (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts          TEXT NOT NULL,
                        kind        TEXT NOT NULL,
                        actress_id  INTEGER,
                        title_id    INTEGER,
                        via         TEXT,
                        needs       TEXT
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_alias_capture_events_ts
                        ON alias_capture_events(ts)""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_alias_capture_events_kind
                        ON alias_capture_events(kind)""");
        });
    }

    /**
     * v58: {@code category} column on {@code enrichment_tag_definitions}, mirroring the
     * curated {@code tags.category} taxonomy so the Library filter panel can group
     * enrichment tags by category (format, production_style, setting, role, theme, act, body).
     *
     * <p>Free-text column (no CHECK constraint) — matches the existing curated
     * {@code tags.category} design. The 41 rows with a {@code curated_alias} mapping
     * are backfilled in-place via JOIN-UPDATE against the curated {@code tags} table;
     * the ~188 unmapped rows are left NULL for a later seed slice.
     *
     * <p>Idempotent via {@code addColumnIfMissing}; the backfill is filtered on
     * {@code category IS NULL} so re-running is a no-op.
     */
    private void applyV58() {
        log.info("Applying migration v58: category column on enrichment_tag_definitions");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "enrichment_tag_definitions", "category", "TEXT");
            int backfilled = h.createUpdate("""
                    UPDATE enrichment_tag_definitions
                       SET category = (SELECT t.category FROM tags t
                                        WHERE t.name = enrichment_tag_definitions.curated_alias)
                     WHERE curated_alias IS NOT NULL
                       AND category IS NULL
                    """).execute();
            log.info("Schema v58: backfilled category on {} enrichment_tag_definitions rows", backfilled);
        });
    }

    /**
     * v59: re-parse titles whose label is NULL — fixes the prior {@link TitleCodeParser} bug
     * that rejected codes with a letter-prefixed sequence (e.g., {@code MKBD-S119},
     * {@code CPZ69-H005}). Those titles were synced with the raw folder name as both
     * {@code code} and {@code base_code} and a NULL {@code label}/{@code seq_num}.
     *
     * <p>For each {@code label IS NULL} row, the migration loads the leaf folder name of
     * any associated {@code title_locations.path} and re-runs the (now-fixed) parser. If
     * it now matches, the row's {@code code}/{@code base_code}/{@code label}/{@code seq_num}
     * are updated. Rows without a path, or whose leaf still doesn't parse, are left alone
     * (a separate triage problem). Collisions on the unique {@code code} index are skipped
     * defensively and logged.
     *
     * <p>Idempotent: re-runs find no remaining {@code label IS NULL} rows that newly parse.
     */
    private void applyV59() {
        log.info("Applying migration v59: re-parse titles with NULL label via fixed TitleCodeParser");
        TitleCodeParser parser = new TitleCodeParser();
        jdbi.useTransaction(h -> {
            List<TitleToReparse> rows = h.createQuery("""
                    SELECT t.id AS id,
                           (SELECT tl.path FROM title_locations tl
                             WHERE tl.title_id = t.id
                             LIMIT 1) AS path
                      FROM titles t
                     WHERE t.label IS NULL
                    """).map((rs, ctx) -> new TitleToReparse(
                            rs.getLong("id"),
                            rs.getString("path")))
                    .list();

            int reparsed = 0;
            int skipped = 0;
            for (TitleToReparse row : rows) {
                if (row.path == null || row.path.isBlank()) {
                    skipped++;
                    continue;
                }
                String leaf = leafOf(row.path);
                TitleCodeParser.ParsedCode parsed = parser.parse(leaf);
                if (parsed.label() == null) {
                    skipped++;
                    continue;
                }
                // Collision check: skip if another title already owns the parsed code.
                boolean collision = h.createQuery(
                                "SELECT COUNT(*) FROM titles WHERE code = :code AND id != :id")
                        .bind("code", parsed.code())
                        .bind("id", row.id)
                        .mapTo(Integer.class)
                        .one() > 0;
                if (collision) {
                    log.warn("Schema v59: skipping title id={} — another title already has code={}",
                            row.id, parsed.code());
                    skipped++;
                    continue;
                }
                int updated = h.createUpdate("""
                        UPDATE titles
                           SET code = :code,
                               base_code = :baseCode,
                               label = :label,
                               seq_num = :seqNum
                         WHERE id = :id
                           AND label IS NULL
                        """)
                        .bind("code", parsed.code())
                        .bind("baseCode", parsed.baseCode())
                        .bind("label", parsed.label())
                        .bind("seqNum", parsed.seqNum())
                        .bind("id", row.id)
                        .execute();
                if (updated > 0) {
                    reparsed++;
                } else {
                    skipped++;
                }
            }
            log.info("Schema v59: re-parsed {} titles (skipped {} with no matching code)",
                    reparsed, skipped);
        });
    }

    private void applyV60() {
        log.info("Applying migration v60: create notes table + idx_notes_entity_type index");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS notes (
                        entity_type TEXT    NOT NULL CHECK (entity_type IN ('actress','title')),
                        entity_id   TEXT    NOT NULL,
                        body        TEXT    NOT NULL,
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        PRIMARY KEY (entity_type, entity_id)
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_notes_entity_type
                        ON notes(entity_type)""");
        });
    }

    /**
     * v61: one-shot cleanup of accumulated FK-orphan rows across 7 child tables.
     *
     * <p>SQLite's foreign-key enforcement requires {@code PRAGMA foreign_keys = ON} per
     * connection, which the JDBI pool does not currently set. As a result, deletes on
     * parent tables (actresses, titles, draft_titles, title_javdb_enrichment) have
     * historically left orphan rows in the corresponding child tables despite their
     * declared {@code ON DELETE CASCADE}. Production databases were cleaned manually;
     * this migration replays that cleanup on any other DB copy so it converges to a
     * clean state on startup.
     *
     * <p>Each DELETE is idempotent — re-running on a clean DB is a no-op (0 deletions).
     * This migration does NOT change the foreign_keys pragma or add ON DELETE CASCADE;
     * those structural fixes are deferred. Until then, future orphans may still
     * accumulate; this migration only addresses the existing backlog.
     *
     * <p>Intentionally excluded: {@code title_path_history}. That table has no FK on
     * {@code title_id} by design (V46) — orphan rows survive title deletion and serve
     * as the re-add recovery mechanism for the sync matcher. Deleting them would
     * destroy a feature's data store, not clean up an FK leak.
     */
    private void applyV61() {
        log.info("Applying migration v61: delete accumulated FK-orphan rows across 7 child tables");
        jdbi.useHandle(h -> {
            int n;
            n = h.createUpdate("""
                    DELETE FROM actress_companies
                     WHERE actress_id NOT IN (SELECT id FROM actresses)
                    """).execute();
            log.info("Schema v61: deleted {} orphan actress_companies rows", n);

            n = h.createUpdate("""
                    DELETE FROM title_tags
                     WHERE title_id NOT IN (SELECT id FROM titles)
                    """).execute();
            log.info("Schema v61: deleted {} orphan title_tags rows", n);

            n = h.createUpdate("""
                    DELETE FROM title_effective_tags
                     WHERE title_id NOT IN (SELECT id FROM titles)
                    """).execute();
            log.info("Schema v61: deleted {} orphan title_effective_tags rows", n);

            n = h.createUpdate("""
                    DELETE FROM title_enrichment_tags
                     WHERE title_id NOT IN (SELECT title_id FROM title_javdb_enrichment)
                    """).execute();
            log.info("Schema v61: deleted {} orphan title_enrichment_tags rows", n);

            n = h.createUpdate("""
                    DELETE FROM draft_title_actresses
                     WHERE draft_title_id NOT IN (SELECT id FROM draft_titles)
                    """).execute();
            log.info("Schema v61: deleted {} orphan draft_title_actresses rows", n);

            n = h.createUpdate("""
                    DELETE FROM draft_title_javdb_enrichment
                     WHERE draft_title_id NOT IN (SELECT id FROM draft_titles)
                    """).execute();
            log.info("Schema v61: deleted {} orphan draft_title_javdb_enrichment rows", n);

            n = h.createUpdate("""
                    DELETE FROM revalidation_pending
                     WHERE title_id NOT IN (SELECT id FROM titles)
                    """).execute();
            log.info("Schema v61: deleted {} orphan revalidation_pending rows", n);
        });
    }

    /**
     * v62: adds AI picker assist columns to {@code enrichment_review_queue}.
     *
     * <p>Shadow-mode infrastructure for Phase 1 of the AI Picker Assist proposal
     * ({@code spec/PROPOSAL_AI_PICKER_ASSIST.md}). The new columns capture the
     * dual-model suggestion ({@code ai_suggestion_slug}), its confidence bucket
     * ({@code ai_suggestion_confidence} — one of {@code agreed}, {@code phi4_only},
     * {@code gemma_only}, {@code conflict}, {@code both_abstain}), a free-form
     * {@code ai_suggestion_reason}, the {@code ai_suggestion_at} timestamp, and an
     * {@code ai_auto_applied} flag toggled when the orchestrator auto-applies the
     * suggestion.
     *
     * <p>All five columns are additive and nullable (the flag defaults to 0).
     * Idempotent via {@link #addColumnIfMissing}.
     */
    private void applyV62() {
        log.info("Applying migration v62: AI picker assist columns on enrichment_review_queue");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "enrichment_review_queue", "ai_suggestion_slug", "TEXT");
            addColumnIfMissing(h, "enrichment_review_queue", "ai_suggestion_confidence", "TEXT");
            addColumnIfMissing(h, "enrichment_review_queue", "ai_suggestion_reason", "TEXT");
            addColumnIfMissing(h, "enrichment_review_queue", "ai_suggestion_at", "TEXT");
            addColumnIfMissing(h, "enrichment_review_queue", "ai_auto_applied", "INTEGER DEFAULT 0");
        });
    }

    /**
     * v63: {@code ai_auto_apply_attempts} column on {@code enrichment_review_queue}.
     *
     * <p>Tracks how many times the auto-apply sweeper has failed to apply a row's AI
     * suggestion. The sweeper increments this counter on each failure; once it reaches
     * {@code EnrichmentAssistConfig.maxAutoApplyAttempts} the row is excluded from
     * {@code listAutoApplyReady} (it stays open for human picker resolution).
     *
     * <p>Idempotent via {@code addColumnIfMissing}.
     */
    private void applyV63() {
        log.info("Applying migration v63: ai_auto_apply_attempts on enrichment_review_queue");
        jdbi.useHandle(h ->
                addColumnIfMissing(h, "enrichment_review_queue",
                        "ai_auto_apply_attempts", "INTEGER DEFAULT 0"));
    }

    private void applyV64() {
        log.info("Applying migration v64: ai_phi4_slug + ai_gemma_slug on enrichment_review_queue");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "enrichment_review_queue", "ai_phi4_slug",  "TEXT");
            addColumnIfMissing(h, "enrichment_review_queue", "ai_gemma_slug", "TEXT");
        });
    }

    /**
     * v65: {@code curated_at} column on {@code title_locations}.
     *
     * <p>Nullable ISO-8601 timestamp set when a human explicitly curates a title via the
     * draft-promote or no-draft-save path. {@code NULL} means "not yet curated" (the correct
     * initial state for all existing rows). The scope guard
     * {@code WHERE volume_id = :unsortedVolumeId AND stale_since IS NULL} ensures only the
     * live staging-volume copy is ever stamped.
     *
     * <p>Idempotent via {@link #addColumnIfMissing}.
     */
    private void applyV65() {
        log.info("Applying migration v65: curated_at on title_locations");
        jdbi.useHandle(h -> addColumnIfMissing(h, "title_locations", "curated_at", "TEXT"));
    }

    /**
     * v66: {@code bookmark_on_promote} column on {@code draft_titles}.
     *
     * <p>Durable per-draft flag. When set, promoting the draft additively bookmarks the
     * resulting canonical title (sets {@code bookmark = 1, bookmarked_at}). Defaults to
     * {@code 0} so existing drafts retain current behavior.
     *
     * <p>Idempotent via {@link #addColumnIfMissing}.
     */
    private void applyV66() {
        log.info("Applying migration v66: bookmark_on_promote on draft_titles");
        jdbi.useHandle(h -> addColumnIfMissing(h, "draft_titles", "bookmark_on_promote", "INTEGER DEFAULT 0"));
    }

    /**
     * v67: no-op placeholder migration — keeps version numbering contiguous.
     * Reserved for future use if a migration is needed between v66 and v68.
     */
    private void applyV67() {
        log.info("Applying migration v67: no-op placeholder");
        // intentionally empty
    }

    /**
     * v68: {@code attribution_findings} table — actress-level attribution audit aggregate findings.
     *
     * <p>Stores one row per (actress_id, finding_class). The {@code metric} is an aggregate
     * fraction (e.g. mismatch titles / total enriched titles). No per-title rows.
     * Status values: {@code open} | {@code suppressed} | {@code resolved}.
     *
     * <p>See spec/PROPOSAL_KANJI_MISATTRIBUTION_GUARDS.md.
     */
    private void applyV68() {
        log.info("Applying migration v68: attribution_findings table");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS attribution_findings (
                        actress_id             INTEGER NOT NULL,
                        finding_class          TEXT NOT NULL,
                        metric                 REAL,
                        sample_json            TEXT,
                        first_seen_at          TEXT,
                        last_seen_at           TEXT,
                        status                 TEXT NOT NULL DEFAULT 'open',
                        note                   TEXT,
                        stage_name_at_suppress TEXT,
                        slug_at_suppress       TEXT,
                        UNIQUE(actress_id, finding_class)
                    )""");
            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_attribution_findings_status
                        ON attribution_findings(status)""");
        });
    }

    /**
     * v69: {@code age_at_release} column on {@code title_actresses}.
     *
     * <p>Adds a nullable INTEGER column holding the credited actress's age in whole years
     * on the title's release date (birthday-aware: uses the %Y%m%d integer subtraction
     * formula so the birthday boundary is exact).
     *
     * <p>Release-date precedence: {@code title_javdb_enrichment.release_date} (canonical)
     * then {@code titles.release_date} (fallback); empty-string values are treated as NULL.
     * Rows that are not computable are explicitly set to NULL.
     *
     * <p>Idempotent via {@link #addColumnIfMissing}; the seed UPDATE is also idempotent.
     */
    private void applyV69() {
        log.info("Applying migration v69: age_at_release column on title_actresses");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "title_actresses", "age_at_release", "INTEGER");
            h.execute("""
                    UPDATE title_actresses SET age_at_release = (
                        SELECT CASE
                            WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                            WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                            ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                                - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                        END
                        FROM titles t
                        JOIN actresses a ON a.id = title_actresses.actress_id
                        LEFT JOIN title_javdb_enrichment e ON e.title_id = t.id
                        WHERE t.id = title_actresses.title_id
                    )""");
        });
    }

    /**
     * v70: {@code product_code_norm} column + index on {@code javdb_actress_filmography_entry}.
     *
     * <p>javdb stores minimally-padded codes ({@code SEND-02}) but the no-match triage lookup
     * receives the 5-digit-padded {@code base_code} ({@code SEND-00002}). SQLite cannot run the
     * Java normalization, so we materialize a normalized column ({@link JavdbCode#normalizeForMatch})
     * and query against it instead.
     *
     * <p>The backfill computes the normalized form in Java for every entry missing it and applies
     * the UPDATEs in batched prepared statements (flushing every 1000 rows) so the ~132k-row table
     * doesn't accumulate a single oversized batch.
     *
     * <p>Idempotent: {@link #addColumnIfMissing}, backfill only {@code WHERE product_code_norm IS NULL},
     * and {@code CREATE INDEX IF NOT EXISTS}.
     */
    private void applyV70() {
        log.info("Applying migration v70: product_code_norm on javdb_actress_filmography_entry");
        jdbi.useHandle(h -> {
            addColumnIfMissing(h, "javdb_actress_filmography_entry", "product_code_norm", "TEXT");

            List<FilmographyEntryKey> toBackfill = h.createQuery("""
                    SELECT actress_slug, product_code
                    FROM javdb_actress_filmography_entry
                    WHERE product_code_norm IS NULL
                    """)
                    .map((rs, ctx) -> new FilmographyEntryKey(
                            rs.getString("actress_slug"), rs.getString("product_code")))
                    .list();

            var batch = h.prepareBatch("""
                    UPDATE javdb_actress_filmography_entry
                    SET product_code_norm = :norm
                    WHERE actress_slug = :slug AND product_code = :code
                    """);
            int pending = 0;
            for (FilmographyEntryKey key : toBackfill) {
                batch.bind("norm", JavdbCode.normalizeForMatch(key.productCode()))
                        .bind("slug", key.actressSlug())
                        .bind("code", key.productCode())
                        .add();
                if (++pending == 1000) {
                    batch.execute();
                    pending = 0;
                }
            }
            if (pending > 0) {
                batch.execute();
            }

            h.execute("""
                    CREATE INDEX IF NOT EXISTS idx_filmography_entry_code_norm
                        ON javdb_actress_filmography_entry(product_code_norm)""");

            log.info("Migration v70: backfilled product_code_norm for {} filmography entries",
                    toBackfill.size());
        });
    }

    /**
     * v71: {@code resolved_via} column on {@code draft_title_actresses} — repair for the
     * dropped v67 migration.
     *
     * <p>The column exists in fresh installs ({@link SchemaInitializer}'s CREATE TABLE) but was
     * never added to existing DBs: the original v67 migration body that added it was accidentally
     * stubbed to a no-op during a later rebase, so DBs already at version ≥67 permanently lacked
     * the column (causing {@code SQLITE_ERROR: ... has no column named resolved_via} at runtime).
     * This migration re-adds it for those DBs.
     *
     * <p>Valid values: {@code canonical} / {@code alias} / {@code stage_name} / {@code slug} /
     * {@code fuzzy} / {@code manual} / {@code prefill}. {@code NULL} means a legacy row written
     * before the column existed; there is no backfill.
     *
     * <p>Idempotent via {@link #addColumnIfMissing}.
     */
    private void applyV71() {
        log.info("Applying migration v71: resolved_via on draft_title_actresses (repair for dropped v67)");
        jdbi.useHandle(h -> addColumnIfMissing(h, "draft_title_actresses", "resolved_via", "TEXT"));
    }

    /**
     * v72: {@code cover_pending_since} on {@code title_locations} (cover-write confirmation).
     *
     * <p>Nullable {@code TEXT} (ISO-8601 µs Z). {@code NULL} = confirmed / not-applicable, which
     * is correct for every existing row (including already-healed history — the first reconciler
     * pass must not re-stat them). Only the promote path ever sets it non-NULL: it is stamped
     * pessimistically before the async NAS cover write and cleared when that write completes
     * without throwing; a dropped/crashed/failed write leaves the row pending for
     * {@link com.organizer3.javdb.draft.PromotionCoverReconciler} to heal.
     *
     * <p>Idempotent via {@link #addColumnIfMissing}.
     */
    private void applyV72() {
        log.info("Applying migration v72: cover_pending_since on title_locations (cover-write confirmation)");
        jdbi.useHandle(h -> addColumnIfMissing(h, "title_locations", "cover_pending_since", "TEXT"));
    }

    /**
     * v73: fixes the draft-promotion enrichment path dropping release_date/duration_minutes/
     * publisher, and normalizes the two-spelling {@code resolver_source} bug — both from
     * reference/audits/unsorted_enrichment_audit_20260722.md findings 1 and 2.
     *
     * <ol>
     *   <li>Adds {@code duration_minutes} / {@code publisher} columns to
     *       {@code draft_title_javdb_enrichment} — the draft tables never had a home for these,
     *       so {@code DraftPopulator}/{@code DraftPromotionService} could not carry them through.
     *       (Idempotent via {@link #addColumnIfMissing}.)
     *   <li>Backfills {@code title_javdb_enrichment.release_date} from {@code titles.release_date}
     *       for rows where the enrichment copy is NULL — {@code titles.release_date} was always
     *       populated correctly by the promotion transaction's step 3 UPDATE even when the
     *       enrichment row's own copy was wrongly left NULL, so it is the recoverable source.
     *       {@code duration_minutes} and {@code publisher} are NOT recoverable this way — the
     *       draft tables never stored them at all prior to this version — so those columns are
     *       left NULL for existing rows; only a javdb re-fetch can populate them.
     *   <li>Normalizes the two hyphenated {@code resolver_source} spellings emitted by the old
     *       (buggy) {@code DraftPopulator.resolverSourceLabel} to the canonical underscore form
     *       used everywhere else, in both {@code title_javdb_enrichment} (promoted rows) and
     *       {@code draft_title_javdb_enrichment} (any draft still in flight). Only the two
     *       enum-derived values are touched — other resolver_source values (e.g.
     *       {@code manual_picker}, {@code auto_enriched}) are legitimately underscore/word forms
     *       and must not be touched by a blanket replace.
     * </ol>
     *
     * <p>Every statement is a targeted, re-runnable UPDATE/ALTER — safe to apply twice.
     */
    private void applyV73() {
        log.info("Applying migration v73: draft-promotion enrichment fields + resolver_source normalization");
        jdbi.useHandle(h -> {
            // (1) New columns on the draft enrichment table.
            addColumnIfMissing(h, "draft_title_javdb_enrichment", "duration_minutes", "INTEGER");
            addColumnIfMissing(h, "draft_title_javdb_enrichment", "publisher", "TEXT");

            // (2) release_date backfill: recover from titles.release_date where present and the
            // enrichment row's own copy is NULL. duration_minutes/publisher are intentionally left
            // alone here — see class comment above, not recoverable from the DB.
            h.execute("""
                    UPDATE title_javdb_enrichment
                    SET release_date = (
                        SELECT t.release_date FROM titles t WHERE t.id = title_javdb_enrichment.title_id
                    )
                    WHERE release_date IS NULL
                      AND EXISTS (
                          SELECT 1 FROM titles t
                          WHERE t.id = title_javdb_enrichment.title_id
                            AND t.release_date IS NOT NULL
                      )
                    """);

            // (3) resolver_source normalization — targeted per-value, both tables.
            for (String table : List.of("title_javdb_enrichment", "draft_title_javdb_enrichment")) {
                h.createUpdate(
                        "UPDATE " + table + " SET resolver_source = 'code_search_fallback' "
                                + "WHERE resolver_source = 'code-search-fallback'")
                        .execute();
                h.createUpdate(
                        "UPDATE " + table + " SET resolver_source = 'actress_filmography' "
                                + "WHERE resolver_source = 'actress-filmography'")
                        .execute();
            }
        });
    }

    /**
     * v74: clears {@code stage_name} on sentinel actresses ({@code is_sentinel = 1}). Sentinel
     * rows (Amateur/Various/Unknown) are catch-all buckets, not real people, and must never carry
     * a {@code stage_name} — but the automatic promotion paths had no sentinel guard, so the
     * {@code Amateur} sentinel got stamped with a stage_name from an amateur-label title's
     * cast_json. The runtime write paths now guard {@code is_sentinel = 0} (see
     * {@code AutoPromoter}, {@code DraftPromotionService}, {@code JdbiActressRepository}); this
     * migration is the one-time cleanup of the bad data already written.
     *
     * <p>Idempotent — a second run matches zero rows once cleared.
     */
    private void applyV74() {
        log.info("Applying migration v74: clear stage_name on sentinel actresses");
        jdbi.useHandle(h -> h.execute(
                "UPDATE actresses SET stage_name = NULL WHERE is_sentinel = 1 AND stage_name IS NOT NULL"));
    }

    private record FilmographyEntryKey(String actressSlug, String productCode) {}

    private static String leafOf(String path) {
        if (path == null) return null;
        String trimmed = path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    private record TitleToReparse(long id, String path) {}

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
