package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/**
 * Creates the database schema from scratch.
 *
 * <p>No incremental migrations — just drop and recreate as needed during development.
 */
@Slf4j
@RequiredArgsConstructor
public class SchemaInitializer {

    private final Jdbi jdbi;

    public void initialize() {
        log.info("Initializing database schema");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS volumes (
                        id              TEXT PRIMARY KEY,
                        structure_type  TEXT NOT NULL,
                        last_synced_at  TEXT
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS actresses (
                        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                        canonical_name       TEXT NOT NULL UNIQUE,
                        stage_name           TEXT,
                        name_reading         TEXT,
                        tier                 TEXT NOT NULL,
                        favorite             INTEGER NOT NULL DEFAULT 0,
                        bookmark             INTEGER NOT NULL DEFAULT 0,
                        bookmarked_at        TEXT,
                        grade                TEXT,
                        rejected             INTEGER NOT NULL DEFAULT 0,
                        first_seen_at        TEXT NOT NULL,
                        date_of_birth        TEXT,
                        birthplace           TEXT,
                        blood_type           TEXT,
                        height_cm            INTEGER,
                        bust                 INTEGER,
                        waist                INTEGER,
                        hip                  INTEGER,
                        cup                  TEXT,
                        active_from          TEXT,
                        active_to            TEXT,
                        retirement_announced TEXT,
                        biography            TEXT,
                        legacy               TEXT,
                        alternate_names_json TEXT,
                        primary_studios_json TEXT,
                        awards_json          TEXT,
                        visit_count          INTEGER NOT NULL DEFAULT 0,
                        last_visited_at      TEXT,
                        needs_profiling      INTEGER NOT NULL DEFAULT 0,
                        favorite_cleared_at  TEXT,
                        is_sentinel          INTEGER NOT NULL DEFAULT 0
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS actress_aliases (
                        actress_id  INTEGER NOT NULL REFERENCES actresses(id),
                        alias_name  TEXT NOT NULL,
                        PRIMARY KEY (actress_id, alias_name)
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS titles (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        code            TEXT NOT NULL UNIQUE,
                        base_code       TEXT,
                        label           TEXT,
                        seq_num         INTEGER,
                        actress_id      INTEGER REFERENCES actresses(id),
                        favorite        INTEGER NOT NULL DEFAULT 0,
                        bookmark        INTEGER NOT NULL DEFAULT 0,
                        bookmarked_at   TEXT,
                        grade           TEXT,
                        grade_source    TEXT,
                        rejected        INTEGER NOT NULL DEFAULT 0,
                        title_original  TEXT,
                        title_english   TEXT,
                        release_date    TEXT,
                        notes           TEXT,
                        visit_count     INTEGER NOT NULL DEFAULT 0,
                        last_visited_at TEXT,
                        favorite_cleared_at TEXT
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_tags (
                        title_id  INTEGER NOT NULL REFERENCES titles(id),
                        tag       TEXT NOT NULL,
                        PRIMARY KEY (title_id, tag)
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_locations (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id      INTEGER NOT NULL REFERENCES titles(id),
                        volume_id     TEXT NOT NULL REFERENCES volumes(id),
                        partition_id  TEXT NOT NULL,
                        path          TEXT NOT NULL,
                        last_seen_at  TEXT NOT NULL,
                        added_date    TEXT,
                        UNIQUE(title_id, volume_id, path)
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS videos (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id      INTEGER NOT NULL REFERENCES titles(id),
                        volume_id     TEXT NOT NULL,
                        filename      TEXT NOT NULL,
                        path          TEXT NOT NULL,
                        last_seen_at  TEXT NOT NULL,
                        duration_sec  INTEGER,
                        width         INTEGER,
                        height        INTEGER,
                        video_codec   TEXT,
                        audio_codec   TEXT,
                        container     TEXT,
                        size_bytes    INTEGER
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS labels (
                        code                TEXT PRIMARY KEY,
                        label_name          TEXT,
                        company             TEXT,
                        description         TEXT,
                        company_description TEXT,
                        company_specialty   TEXT,
                        company_founded     TEXT,
                        company_status      TEXT,
                        company_parent      TEXT
                    )""");

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

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_actresses (
                        title_id    INTEGER NOT NULL REFERENCES titles(id),
                        actress_id  INTEGER NOT NULL REFERENCES actresses(id),
                        PRIMARY KEY (title_id, actress_id)
                    )""");

            h.execute("CREATE INDEX IF NOT EXISTS idx_actress_aliases_name ON actress_aliases(alias_name)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_code ON titles(code)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_label ON titles(label)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_actress ON titles(actress_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_tags_tag ON title_tags(tag)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_title ON title_locations(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_volume ON title_locations(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_volume_partition ON title_locations(volume_id, partition_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_videos_title ON videos(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_videos_volume ON videos(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_actresses_title ON title_actresses(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_actresses_actress ON title_actresses(actress_id)");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_effective_tags (
                        title_id  INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
                        tag       TEXT NOT NULL,
                        source    TEXT NOT NULL CHECK(source IN ('direct', 'label', 'enrichment')),
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

            h.execute("""
                    CREATE TABLE IF NOT EXISTS watch_history (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_code  TEXT NOT NULL,
                        watched_at  TEXT NOT NULL
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_watch_history_title_code ON watch_history(title_code)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_watch_history_watched_at ON watch_history(watched_at)");
            h.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_history_unique_entry ON watch_history(title_code, watched_at)");

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

                        -- Visit tracking (v15)
                        last_visited_at      TEXT,
                        visit_count          INTEGER NOT NULL DEFAULT 0,

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

                        -- Watch/curation tracking (v15)
                        bookmark        INTEGER NOT NULL DEFAULT 0,
                        watched         INTEGER NOT NULL DEFAULT 0,
                        last_watched_at TEXT,
                        watch_count     INTEGER NOT NULL DEFAULT 0,

                        UNIQUE(av_actress_id, relative_path)
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_actress ON av_videos(av_actress_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_volume ON av_videos(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_studio ON av_videos(studio)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_av_videos_bucket ON av_videos(bucket)");

            // av_tag_definitions + av_video_tags (v17)
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

            // av_video_screenshots (v16)
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

            // Triggers that auto-maintain favorite_cleared_at on titles + actresses.
            // On favorite 1→0: stamp NOW. On 0→1: clear. Fires only on UPDATE OF favorite,
            // so the inner UPDATE of favorite_cleared_at does not recurse.
            createFavoriteClearedAtTriggers(h);

            // duplicate_decisions (v22): triage decisions for duplicate title locations.
            h.execute("""
                    CREATE TABLE IF NOT EXISTS duplicate_decisions (
                        title_code   TEXT NOT NULL,
                        volume_id    TEXT NOT NULL,
                        nas_path     TEXT NOT NULL,
                        decision     TEXT NOT NULL CHECK(decision IN ('KEEP','TRASH','VARIANT')),
                        created_at   TEXT NOT NULL,
                        executed_at  TEXT,
                        PRIMARY KEY (title_code, volume_id, nas_path)
                    )""");

            // merge_candidates (v23): cross-row duplicate pairs detected by code normalization.
            h.execute("""
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
                    )""");

            // javdb_enrichment_queue (v24, updated v31: nullable actress_id + source)
            h.execute("""
                    CREATE TABLE IF NOT EXISTS javdb_enrichment_queue (
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
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_claim   ON javdb_enrichment_queue(status, next_attempt_at)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_actress ON javdb_enrichment_queue(actress_id, status)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_jeq_source  ON javdb_enrichment_queue(source, status)");

            // javdb_title_staging (v24)
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

            // javdb_actress_staging (v24)
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
                        title_count         INTEGER,
                        local_avatar_path   TEXT
                    )""");
            h.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_javdb_actress_slug ON javdb_actress_staging(javdb_slug)");

            // title_javdb_enrichment + enrichment tag tables (v25).
            // Canonical home for javdb-derived per-title metadata. Replaces javdb_title_staging
            // semantically; staging is retained until the Phase 2 cutover.
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
            h.execute("CREATE INDEX IF NOT EXISTS idx_tet_tag ON title_enrichment_tags(tag_id);");

            // rating_curve: single-row config persisting Bayesian grade cutoffs (v28).
            h.execute("""
                    CREATE TABLE IF NOT EXISTS rating_curve (
                        id                  INTEGER PRIMARY KEY CHECK (id = 1),
                        global_mean         REAL    NOT NULL,
                        global_count        INTEGER NOT NULL,
                        min_credible_votes  INTEGER NOT NULL,
                        cutoffs_json        TEXT    NOT NULL,
                        computed_at         TEXT    NOT NULL
                    )""");

            // Only stamp version on fresh installs (user_version = 0).
            // On an existing DB the CREATE TABLE statements above are all no-ops, so we must
            // leave the version alone and let SchemaUpgrader apply any missing migrations.
            int currentVersion = h.createQuery("PRAGMA user_version").mapTo(Integer.class).one();
            if (currentVersion == 0) {
                h.execute("PRAGMA user_version = 32");
            }
        });
        log.info("Schema initialization complete");
    }

    /**
     * Installs (or re-installs) the four triggers that auto-maintain
     * {@code favorite_cleared_at} on {@code titles} and {@code actresses}.
     *
     * <p>Each table has two triggers:
     * <ul>
     *   <li><b>on unfavorite</b> (1→0): stamp {@code favorite_cleared_at = now()}</li>
     *   <li><b>on refavorite</b> (0→1): clear {@code favorite_cleared_at = NULL}</li>
     * </ul>
     *
     * <p>Safe to run on a DB where the columns already exist; triggers are dropped
     * and recreated so both fresh-install and migration paths converge on the same
     * definition.
     */
    static void createFavoriteClearedAtTriggers(org.jdbi.v3.core.Handle h) {
        String[] stmts = new String[] {
            "DROP TRIGGER IF EXISTS trg_titles_favorite_cleared_on_unfav",
            "DROP TRIGGER IF EXISTS trg_titles_favorite_cleared_on_refav",
            "DROP TRIGGER IF EXISTS trg_actresses_favorite_cleared_on_unfav",
            "DROP TRIGGER IF EXISTS trg_actresses_favorite_cleared_on_refav",
            """
            CREATE TRIGGER trg_titles_favorite_cleared_on_unfav
            AFTER UPDATE OF favorite ON titles
            FOR EACH ROW
            WHEN OLD.favorite = 1 AND NEW.favorite = 0
            BEGIN
                UPDATE titles SET favorite_cleared_at = datetime('now') WHERE id = NEW.id;
            END
            """,
            """
            CREATE TRIGGER trg_titles_favorite_cleared_on_refav
            AFTER UPDATE OF favorite ON titles
            FOR EACH ROW
            WHEN OLD.favorite = 0 AND NEW.favorite = 1
            BEGIN
                UPDATE titles SET favorite_cleared_at = NULL WHERE id = NEW.id;
            END
            """,
            """
            CREATE TRIGGER trg_actresses_favorite_cleared_on_unfav
            AFTER UPDATE OF favorite ON actresses
            FOR EACH ROW
            WHEN OLD.favorite = 1 AND NEW.favorite = 0
            BEGIN
                UPDATE actresses SET favorite_cleared_at = datetime('now') WHERE id = NEW.id;
            END
            """,
            """
            CREATE TRIGGER trg_actresses_favorite_cleared_on_refav
            AFTER UPDATE OF favorite ON actresses
            FOR EACH ROW
            WHEN OLD.favorite = 0 AND NEW.favorite = 1
            BEGIN
                UPDATE actresses SET favorite_cleared_at = NULL WHERE id = NEW.id;
            END
            """
        };
        for (String s : stmts) h.execute(s);
    }
}
