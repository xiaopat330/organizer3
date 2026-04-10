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
    private static final int CURRENT_VERSION = 8;

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

        log.info("Schema upgrade complete");
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
