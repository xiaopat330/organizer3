package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the three-source merge in {@link TitleEffectiveTagsService}: direct, label,
 * and (Phase 4) enrichment-derived via {@code curated_alias}.
 */
class TitleEffectiveTagsServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private TitleEffectiveTagsService svc;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        svc = new TitleEffectiveTagsService(jdbi);
        // Seed curated tag catalog so the IN (SELECT name FROM tags) guard passes.
        jdbi.useHandle(h -> {
            for (String tag : List.of("busty", "solo-actress", "cowgirl", "compilation", "pov")) {
                h.execute("INSERT INTO tags(name, category) VALUES (?, 'test')", tag);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private long mkTitle(String code, String label) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num) VALUES (?, ?, ?, 1)")
                .bind(0, code).bind(1, code).bind(2, label)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void addDirectTag(long titleId, String tag) {
        jdbi.useHandle(h -> h.execute("INSERT OR IGNORE INTO title_tags(title_id, tag) VALUES (?,?)", titleId, tag));
    }

    private void addLabelTag(String label, String tag) {
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO labels(code) VALUES (?)", label);
            h.execute("INSERT OR IGNORE INTO label_tags(label_code, tag) VALUES (?, ?)", label, tag);
        });
    }

    private void addEnrichment(long titleId, String enrichmentTag, String curatedAlias) {
        jdbi.useHandle(h -> {
            h.execute("""
                    INSERT OR IGNORE INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at)
                    VALUES (?, 'slug-' || ?, '2026-04-26T00:00:00Z')
                    """, titleId, titleId);
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions(name, curated_alias) VALUES (?, ?)",
                    enrichmentTag, curatedAlias);
            h.execute("""
                    INSERT OR IGNORE INTO title_enrichment_tags(title_id, tag_id)
                    SELECT ?, id FROM enrichment_tag_definitions WHERE name = ?
                    """, titleId, enrichmentTag);
        });
    }

    private List<Map<String, Object>> effectiveRows(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT tag, source FROM title_effective_tags WHERE title_id = :id ORDER BY tag")
                .bind("id", titleId).mapToMap().list());
    }

    @Test
    void recomputeForTitle_aliasedEnrichmentTag_appearsAsEnrichmentSource() {
        long t = mkTitle("E-1", "TST");
        addEnrichment(t, "Big Tits", "busty");

        svc.recomputeForTitle(t);

        var rows = effectiveRows(t);
        assertEquals(1, rows.size());
        assertEquals("busty", rows.get(0).get("tag"));
        assertEquals("enrichment", rows.get(0).get("source"));
    }

    @Test
    void recomputeForTitle_unaliasedEnrichmentTag_isSkipped() {
        long t = mkTitle("E-2", "TST");
        addEnrichment(t, "4K", null);  // no alias — must not appear

        svc.recomputeForTitle(t);

        assertTrue(effectiveRows(t).isEmpty());
    }

    @Test
    void recomputeForTitle_aliasToNonexistentCuratedTag_isSkipped() {
        long t = mkTitle("E-3", "TST");
        addEnrichment(t, "Risky Mosaic", "not-a-real-curated-tag");  // alias points nowhere

        svc.recomputeForTitle(t);

        assertTrue(effectiveRows(t).isEmpty(), "alias to a tag absent from `tags` table must not flow through");
    }

    @Test
    void recomputeForTitle_directAndEnrichmentSameTag_dedupesToOneRow() {
        // User has manually tagged the title with `busty`; enrichment also implies `busty`.
        long t = mkTitle("E-4", "TST");
        addDirectTag(t, "busty");
        addEnrichment(t, "Big Tits", "busty");

        svc.recomputeForTitle(t);

        var rows = effectiveRows(t);
        assertEquals(1, rows.size(), "primary key (title_id, tag) prevents the duplicate");
        // Either source is acceptable per INSERT OR IGNORE order; current order inserts direct first.
        assertEquals("busty", rows.get(0).get("tag"));
        assertEquals("direct", rows.get(0).get("source"),
                "direct tag wins on conflict because it's inserted first");
    }

    @Test
    void recomputeForTitle_multipleEnrichmentTags_mappingToSameAlias_dedupes() {
        // Big Tits → busty AND Busty Fetish → busty. Title carries both. Only one busty row.
        long t = mkTitle("E-5", "TST");
        addEnrichment(t, "Big Tits", "busty");
        addEnrichment(t, "Busty Fetish", "busty");

        svc.recomputeForTitle(t);

        var rows = effectiveRows(t);
        assertEquals(1, rows.size());
        assertEquals("busty", rows.get(0).get("tag"));
    }

    @Test
    void recomputeForTitle_threeSources_allFlowIn() {
        long t = mkTitle("E-6", "S1");
        addDirectTag(t, "compilation");
        addLabelTag("S1", "premium-production");
        // premium-production is not in our test tags table; insert it so it can flow through.
        jdbi.useHandle(h -> h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES ('premium-production', 'test')"));
        addEnrichment(t, "Cowgirl", "cowgirl");

        svc.recomputeForTitle(t);

        var rows = effectiveRows(t);
        assertEquals(3, rows.size());
        // Verify each source appears
        var sources = rows.stream().map(r -> r.get("source")).toList();
        assertTrue(sources.contains("direct"));
        assertTrue(sources.contains("label"));
        assertTrue(sources.contains("enrichment"));
    }

    @Test
    void recomputeForTitle_replacesPreviousState() {
        long t = mkTitle("E-7", "TST");
        addEnrichment(t, "Big Tits", "busty");
        svc.recomputeForTitle(t);
        assertEquals(1, effectiveRows(t).size());

        // Now clear enrichment by deleting the assignment + repointing alias to null
        jdbi.useHandle(h -> h.execute("DELETE FROM title_enrichment_tags WHERE title_id = ?", t));
        svc.recomputeForTitle(t);

        assertTrue(effectiveRows(t).isEmpty(), "removing the enrichment cleared the derived row");
    }

    @Test
    void recomputeAll_enrichmentSourceIncluded() {
        long t1 = mkTitle("R-1", "TST");
        long t2 = mkTitle("R-2", "TST");
        addEnrichment(t1, "Big Tits", "busty");
        addEnrichment(t2, "Cowgirl", "cowgirl");

        svc.recomputeAll();

        assertEquals(1, effectiveRows(t1).size());
        assertEquals(1, effectiveRows(t2).size());
        assertEquals("busty", effectiveRows(t1).get(0).get("tag"));
        assertEquals("cowgirl", effectiveRows(t2).get(0).get("tag"));
    }
}
