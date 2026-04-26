package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavdbEnrichmentRepositoryTest {

    private Jdbi jdbi;
    private Connection connection;
    private JavdbEnrichmentRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JavdbEnrichmentRepository(jdbi, new ObjectMapper(), new com.organizer3.db.TitleEffectiveTagsService(jdbi));
        // Foreign key from title_javdb_enrichment.title_id requires a titles row.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST-1', 'TST', 1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'TST-2', 'TST-2', 'TST', 2)");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private static TitleExtract sampleExtract(String code, String slug, List<String> tags, Double ratingAvg) {
        return new TitleExtract(
                code, slug, "原題-" + code, "2024-06-01", 120,
                "S1 NO.1 STYLE", null, "Test Series", ratingAvg, 100,
                tags,
                List.of(new TitleExtract.CastEntry("c1", "Cast One", "F")),
                "https://example/cover.jpg",
                List.of("https://example/t1.jpg"),
                "2026-04-25T00:00:00Z"
        );
    }

    @Test
    void upsertEnrichment_writesEnrichmentRowAndTags() {
        repo.upsertEnrichment(1L, "slug1", "javdb_raw/title/slug1.json",
                sampleExtract("TST-1", "slug1", List.of("Big Tits", "Solowork", "Cowgirl"), 4.5));

        var row = jdbi.withHandle(h -> h.createQuery(
                "SELECT javdb_slug, rating_avg, maker FROM title_javdb_enrichment WHERE title_id = 1")
                .mapToMap().one());
        assertEquals("slug1", row.get("javdb_slug"));
        assertEquals(4.5, ((Number) row.get("rating_avg")).doubleValue());
        assertEquals("S1 NO.1 STYLE", row.get("maker"));

        int assignmentCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_enrichment_tags WHERE title_id = 1")
                .mapTo(Integer.class).one());
        assertEquals(3, assignmentCount);

        // title_count maintained across all definitions
        int mismatched = jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM enrichment_tag_definitions etd
                WHERE etd.title_count != (SELECT COUNT(*) FROM title_enrichment_tags WHERE tag_id = etd.id)
                """).mapTo(Integer.class).one());
        assertEquals(0, mismatched);
    }

    @Test
    void upsertEnrichment_isAtomicReplace() {
        // Initial write: 3 tags
        repo.upsertEnrichment(1L, "slug1", "javdb_raw/title/slug1.json",
                sampleExtract("TST-1", "slug1", List.of("Big Tits", "Solowork", "Cowgirl"), 4.0));

        // Re-enrichment: completely different tag set + different slug + different rating
        repo.upsertEnrichment(1L, "slug1-v2", "javdb_raw/title/slug1-v2.json",
                sampleExtract("TST-1", "slug1-v2", List.of("Slender", "POV"), 4.8));

        // Enrichment row reflects the latest data (no merge)
        var row = jdbi.withHandle(h -> h.createQuery(
                "SELECT javdb_slug, rating_avg FROM title_javdb_enrichment WHERE title_id = 1")
                .mapToMap().one());
        assertEquals("slug1-v2", row.get("javdb_slug"));
        assertEquals(4.8, ((Number) row.get("rating_avg")).doubleValue());

        // Tag assignments reflect only the new tag set
        List<String> tags = jdbi.withHandle(h -> h.createQuery("""
                SELECT etd.name FROM title_enrichment_tags tet
                JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                WHERE tet.title_id = 1
                ORDER BY etd.name
                """).mapTo(String.class).list());
        assertEquals(List.of("POV", "Slender"), tags);

        // Old tag definitions still exist (the vocabulary doesn't shrink) but title_count is now 0 for them
        Integer soloworkCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT title_count FROM enrichment_tag_definitions WHERE name = 'Solowork'")
                .mapTo(Integer.class).findOne().orElse(null));
        assertEquals(0, soloworkCount, "obsolete tag's title_count should drop to zero after replacement");
    }

    @Test
    void upsertEnrichment_sharesTagDefinitionsAcrossTitles() {
        repo.upsertEnrichment(1L, "slug1", "javdb_raw/title/slug1.json",
                sampleExtract("TST-1", "slug1", List.of("Big Tits", "Solowork"), 4.0));
        repo.upsertEnrichment(2L, "slug2", "javdb_raw/title/slug2.json",
                sampleExtract("TST-2", "slug2", List.of("Big Tits", "Cowgirl"), 4.2));

        // Big Tits is a single definition shared across both titles
        Integer bigTitsCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT title_count FROM enrichment_tag_definitions WHERE name = 'Big Tits'")
                .mapTo(Integer.class).one());
        assertEquals(2, bigTitsCount);

        Integer defCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM enrichment_tag_definitions WHERE name IN ('Big Tits','Solowork','Cowgirl')")
                .mapTo(Integer.class).one());
        assertEquals(3, defCount, "three distinct definitions across the two titles");
    }

    @Test
    void upsertEnrichment_handlesNullAndEmptyTagsGracefully() {
        repo.upsertEnrichment(1L, "slug1", "rp", sampleExtract("TST-1", "slug1", null, null));
        int rows = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id = 1")
                .mapTo(Integer.class).one());
        assertEquals(1, rows);

        repo.upsertEnrichment(2L, "slug2", "rp", sampleExtract("TST-2", "slug2", List.of("", "  "), null));
        int defCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM enrichment_tag_definitions").mapTo(Integer.class).one());
        assertEquals(0, defCount, "blank tag strings should be filtered, no definitions inserted");
    }

    @Test
    void deleteEnrichment_removesRowAndTagsAndRefreshesCounts() {
        repo.upsertEnrichment(1L, "slug1", "rp", sampleExtract("TST-1", "slug1", List.of("Big Tits"), 4.0));
        repo.upsertEnrichment(2L, "slug2", "rp", sampleExtract("TST-2", "slug2", List.of("Big Tits"), 4.0));

        repo.deleteEnrichment(1L);

        int enrichRows = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id = 1").mapTo(Integer.class).one());
        assertEquals(0, enrichRows);

        Integer bigTitsCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT title_count FROM enrichment_tag_definitions WHERE name = 'Big Tits'")
                .mapTo(Integer.class).one());
        assertEquals(1, bigTitsCount, "title_count drops to 1 after deleting the other holder");
    }
}
