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
    private static final int CURRENT_VERSION = 3;

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

        log.info("Schema upgrade complete");
    }

    /** v2: adds stage_name column to actresses for Japanese kanji/kana stage name storage. */
    private void applyV2() {
        log.info("Applying migration v2: adding stage_name to actresses");
        jdbi.useHandle(h -> h.execute("ALTER TABLE actresses ADD COLUMN stage_name TEXT"));
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
