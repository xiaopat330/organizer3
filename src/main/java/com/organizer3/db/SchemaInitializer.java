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
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        canonical_name  TEXT NOT NULL UNIQUE,
                        stage_name      TEXT,
                        tier            TEXT NOT NULL,
                        favorite        INTEGER NOT NULL DEFAULT 0,
                        bookmark        INTEGER NOT NULL DEFAULT 0,
                        grade           TEXT,
                        rejected        INTEGER NOT NULL DEFAULT 0,
                        first_seen_at   TEXT NOT NULL,
                        date_of_birth   TEXT,
                        birthplace      TEXT,
                        blood_type      TEXT,
                        height_cm       INTEGER,
                        bust            INTEGER,
                        waist           INTEGER,
                        hip             INTEGER,
                        cup             TEXT,
                        active_from     TEXT,
                        active_to       TEXT,
                        biography       TEXT,
                        legacy          TEXT
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
                        grade           TEXT,
                        rejected        INTEGER NOT NULL DEFAULT 0,
                        title_original  TEXT,
                        title_english   TEXT,
                        release_date    TEXT,
                        notes           TEXT
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
                        last_seen_at  TEXT NOT NULL
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS labels (
                        code        TEXT PRIMARY KEY,
                        label_name  TEXT,
                        company     TEXT,
                        description TEXT
                    )""");

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

            // Stamp version so SchemaUpgrader skips migrations already baked into CREATE TABLE
            h.execute("PRAGMA user_version = 4");
        });
        log.info("Schema initialization complete");
    }
}
