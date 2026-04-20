package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SchemaInitializer using an in-memory SQLite database.
 */
class SchemaInitializerTest {

    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void createsAllTables() {
        new SchemaInitializer(jdbi).initialize();

        List<String> tables = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT name FROM sqlite_master
                        WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                        ORDER BY name""")
                        .mapTo(String.class)
                        .list()
        );

        assertEquals(
                List.of("actress_aliases", "actress_companies", "actresses",
                        "av_actresses", "av_tag_definitions", "av_video_screenshots", "av_video_tags", "av_videos",
                        "label_tags", "labels", "tags",
                        "title_actresses", "title_effective_tags", "title_locations", "title_tags",
                        "titles", "videos", "volumes", "watch_history"),
                tables
        );
    }

    @Test
    void createsExpectedIndexes() {
        new SchemaInitializer(jdbi).initialize();

        List<String> indexes = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT name FROM sqlite_master
                        WHERE type = 'index' AND name LIKE 'idx_%'
                        ORDER BY name""")
                        .mapTo(String.class)
                        .list()
        );

        assertEquals(
                List.of("idx_actress_aliases_name", "idx_actress_companies_company",
                        "idx_actresses_name_nocase",
                        "idx_av_actresses_iafd_id", "idx_av_actresses_volume",
                        "idx_av_video_screenshots_video",
                        "idx_av_video_tags_tag", "idx_av_video_tags_video",
                        "idx_av_videos_actress", "idx_av_videos_bucket",
                        "idx_av_videos_studio", "idx_av_videos_volume",
                        "idx_label_tags_tag",
                        "idx_title_actresses_actress", "idx_title_actresses_title",
                        "idx_title_effective_tags_tag",
                        "idx_title_locations_title", "idx_title_locations_volume", "idx_title_locations_volume_partition",
                        "idx_title_tags_tag",
                        "idx_titles_actress", "idx_titles_code", "idx_titles_label",
                        "idx_videos_title", "idx_videos_volume",
                        "idx_watch_history_title_code", "idx_watch_history_unique_entry", "idx_watch_history_watched_at"),
                indexes
        );
    }

    @Test
    void isIdempotent() {
        SchemaInitializer init = new SchemaInitializer(jdbi);
        init.initialize();
        assertDoesNotThrow(init::initialize, "Running initialize twice should not fail");
    }

    @Test
    void titlesTableHasLabelAndSeqNum() {
        new SchemaInitializer(jdbi).initialize();

        // Insert a title with label and seq_num to verify columns exist
        jdbi.useHandle(h ->
            h.execute("""
                    INSERT INTO titles (code, base_code, label, seq_num)
                    VALUES ('ABP-123', 'ABP-00123', 'ABP', 123)""")
        );

        var row = jdbi.withHandle(h ->
                h.createQuery("SELECT label, seq_num FROM titles WHERE code = 'ABP-123'")
                        .mapToMap()
                        .one()
        );

        assertEquals("ABP", row.get("label"));
        assertEquals(123, row.get("seq_num"));
    }

    @Test
    void actressesTableHasFavoriteColumn() {
        new SchemaInitializer(jdbi).initialize();

        jdbi.useHandle(h ->
                h.execute("""
                        INSERT INTO actresses (canonical_name, tier, favorite, first_seen_at)
                        VALUES ('Test Actress', 'regular', 1, '2026-01-01')""")
        );

        int favorite = jdbi.withHandle(h ->
                h.createQuery("SELECT favorite FROM actresses WHERE canonical_name = 'Test Actress'")
                        .mapTo(Integer.class)
                        .one()
        );

        assertEquals(1, favorite);
    }

    @Test
    void actressesTableHasBookmarkedAtColumn() {
        new SchemaInitializer(jdbi).initialize();

        boolean hasCol = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='bookmarked_at'")
                        .mapTo(Integer.class)
                        .one() > 0);
        assertTrue(hasCol);
    }

    @Test
    void freshSchemaIsStampedAtCurrentVersion() {
        new SchemaInitializer(jdbi).initialize();

        int version = jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
        assertEquals(20, version);
    }

    @Test
    void labelsTableAcceptsData() {
        new SchemaInitializer(jdbi).initialize();

        jdbi.useHandle(h ->
                h.execute("""
                        INSERT INTO labels (code, label_name, company, description)
                        VALUES ('TEST', 'Test Label', 'Test Company', 'A test label')""")
        );

        var row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM labels WHERE code = 'TEST'")
                        .mapToMap()
                        .one()
        );

        assertEquals("Test Label", row.get("label_name"));
        assertEquals("Test Company", row.get("company"));
    }
}
