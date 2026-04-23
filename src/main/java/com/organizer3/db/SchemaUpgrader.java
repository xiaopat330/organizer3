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
    private static final int CURRENT_VERSION = 23;

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

        log.info("Schema upgrade complete");
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
