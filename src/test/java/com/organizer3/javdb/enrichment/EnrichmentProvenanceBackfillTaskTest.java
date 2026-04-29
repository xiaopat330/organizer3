package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentProvenanceBackfillTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentProvenanceBackfillTask task;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        task = new EnrichmentProvenanceBackfillTask(jdbi);

        // Seed non-sentinel actresses with known stage_names for cast matching.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, stage_name, tier, first_seen_at, is_sentinel) VALUES (1, 'Mana Sakura', '紗倉まな', 'LIBRARY', '2024-01-01', 0)");
            h.execute("INSERT INTO actresses(id, canonical_name, stage_name, tier, first_seen_at, is_sentinel) VALUES (2, 'Sora Aoi', '蒼井そら', 'LIBRARY', '2024-01-01', 0)");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    /** Insert a title row, link actress, and insert enrichment with the given cast_json. */
    private void insertEnrichmentRow(int titleId, String code, int actressId, String castJson) {
        jdbi.useHandle(h -> {
            h.createUpdate("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (:id, :code, :code, 'TST', :id)")
                    .bind("id", titleId).bind("code", code).execute();
            h.createUpdate("INSERT INTO title_actresses(title_id, actress_id) VALUES (:t, :a)")
                    .bind("t", titleId).bind("a", actressId).execute();
            h.createUpdate("""
                    INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at, cast_json)
                    VALUES (:id, :slug, '2024-01-01T00:00:00Z', :cast)
                    """)
                    .bind("id", titleId).bind("slug", "s" + titleId).bind("cast", castJson).execute();
        });
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void backfill_happyPath_threeUnknownTwoLow() {
        // Titles 1-3: cast_json contains the actress → no mismatch → stay UNKNOWN
        insertEnrichmentRow(1, "TST-1", 1, "[{\"name\":\"紗倉まな\"}]");
        insertEnrichmentRow(2, "TST-2", 1, "[{\"name\":\"紗倉まな\"},{\"name\":\"other\"}]");
        insertEnrichmentRow(3, "TST-3", 2, "[{\"name\":\"蒼井そら\"}]");
        // Titles 4-5: cast_json does NOT contain the actress → mismatch → LOW
        insertEnrichmentRow(4, "TST-4", 1, "[{\"name\":\"someone else\"}]");
        insertEnrichmentRow(5, "TST-5", 2, "[{\"name\":\"nobody\"}]");

        task.run();

        // 3 rows: cast matches → UNKNOWN
        assertEquals(3, countByConfidence("UNKNOWN"));
        // 2 rows: cast mismatch → LOW
        assertEquals(2, countByConfidence("LOW"));
        // All rows have resolver_source stamped
        assertEquals(5, countBySource("unknown"));
    }

    // ── empty-cast exception ────────────────────────────────────────────────────

    @Test
    void backfill_emptyCastRows_stayUnknown() {
        // cast_json = [] → empty array, excluded from LOW scan
        insertEnrichmentRow(1, "TST-1", 1, "[]");
        // cast_json IS NULL → excluded from LOW scan (MISMATCH_WHERE requires cast IS NOT NULL)
        insertEnrichmentRow(2, "TST-2", 1, null);

        task.run();

        assertEquals(2, countByConfidence("UNKNOWN"), "empty/null cast rows must stay UNKNOWN");
        assertEquals(0, countByConfidence("LOW"));
    }

    // ── malformed cast_json → LOW ───────────────────────────────────────────────

    @Test
    void backfill_malformedCastJson_stampsLow() {
        // Malformed: valid JSON but not an array — json_array_length returns NULL → not excluded
        insertEnrichmentRow(1, "TST-1", 1, "{\"not\":\"an array\"}");

        task.run();

        assertEquals("LOW", getConfidence(1), "malformed cast_json is a suspicious signal → LOW");
    }

    // ── idempotency ─────────────────────────────────────────────────────────────

    @Test
    void backfill_alreadyStampedRow_isNotTouched() {
        // Pre-stamp one row with HIGH confidence — simulates a row that already has provenance.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST-1', 'TST', 1)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 1)");
            h.execute("""
                    INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at, cast_json,
                        resolver_source, confidence, cast_validated)
                    VALUES (1, 's1', '2024-01-01T00:00:00Z', '[{\"name\":\"other\"}]',
                        'actress_filmography', 'HIGH', 1)
                    """);
        });
        // Add a second, unprovenanced row so the backfill actually runs.
        insertEnrichmentRow(2, "TST-2", 1, "[{\"name\":\"紗倉まな\"}]");

        task.run();

        // Sentinel row must be untouched.
        assertEquals("HIGH", getConfidence(1), "pre-stamped HIGH row must not be modified");
        assertEquals("actress_filmography", getSource(1));
        // Second row gets stamped.
        assertEquals("UNKNOWN", getConfidence(2));
    }

    @Test
    void backfill_alreadyFullyStamped_isNoOp() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST-1', 'TST', 1)");
            h.execute("""
                    INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at,
                        resolver_source, confidence, cast_validated)
                    VALUES (1, 's1', '2024-01-01T00:00:00Z', 'unknown', 'UNKNOWN', 0)
                    """);
        });

        // Running twice must not throw and must leave the row unchanged.
        task.run();
        task.run();

        assertEquals("UNKNOWN", getConfidence(1));
        assertEquals("unknown", getSource(1));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private int countByConfidence(String confidence) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE confidence = :c")
                        .bind("c", confidence).mapTo(Integer.class).one());
    }

    private int countBySource(String source) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE resolver_source = :s")
                        .bind("s", source).mapTo(Integer.class).one());
    }

    private String getConfidence(int titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT confidence FROM title_javdb_enrichment WHERE title_id = :id")
                        .bind("id", titleId).mapTo(String.class).one());
    }

    private String getSource(int titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT resolver_source FROM title_javdb_enrichment WHERE title_id = :id")
                        .bind("id", titleId).mapTo(String.class).one());
    }
}
