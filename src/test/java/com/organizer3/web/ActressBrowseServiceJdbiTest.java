package com.organizer3.web;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiLabelRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for ActressBrowseService methods that require real Jdbi (i.e., inline SQL).
 */
class ActressBrowseServiceJdbiTest {

    private Connection connection;
    private Jdbi jdbi;
    private ActressBrowseService service;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));

        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo   = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiLabelRepository labelRepo = new JdbiLabelRepository(jdbi);

        service = new ActressBrowseService(actressRepo, titleRepo, mock(CoverPath.class),
                Map.of(), labelRepo, mock(ActressNameLookup.class), null, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── findEnrichmentTagsForActress ──────────────────────────────────────

    @Test
    void findEnrichmentTagsForActressReturnsActressScopedTagsOnly() {
        long ayaId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Aya', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long hibikiId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Hibiki', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        long t1 = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES ('ABP-001','ABP-00001','ABP',1,:a)")
                .bind("a", ayaId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long t2 = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES ('SSIS-001','SSIS-00001','SSIS',1,:a)")
                .bind("a", hibikiId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO enrichment_tag_definitions (id, name, title_count, surface) VALUES (1, 'big-tits', 2, 1)");
            h.execute("INSERT INTO enrichment_tag_definitions (id, name, title_count, surface) VALUES (2, 'cosplay', 1, 1)");
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t1 + ", " + ayaId + ")");
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t2 + ", " + hibikiId + ")");
            // Aya's title has tag 1; Hibiki's title has tags 1 and 2
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t1 + ", 1)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2 + ", 1)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2 + ", 2)");
        });

        List<ActressBrowseService.ActressEnrichmentTag> ayaTags = service.findEnrichmentTagsForActress(ayaId);
        assertEquals(1, ayaTags.size(), "Aya should have only tag 1");
        assertEquals(1L, ayaTags.get(0).id());
        assertEquals("big-tits", ayaTags.get(0).name());
        assertEquals(1, ayaTags.get(0).titleCount(), "count should be scoped to Aya's titles");

        List<ActressBrowseService.ActressEnrichmentTag> hibikiTags = service.findEnrichmentTagsForActress(hibikiId);
        assertEquals(2, hibikiTags.size(), "Hibiki should have tags 1 and 2");
        assertEquals(1, hibikiTags.get(0).titleCount());
        assertEquals(1, hibikiTags.get(1).titleCount());
    }

    @Test
    void findEnrichmentTagsForActressReturnsEmptyWhenNone() {
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Aya', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        List<ActressBrowseService.ActressEnrichmentTag> result = service.findEnrichmentTagsForActress(actressId);
        assertTrue(result.isEmpty());
    }
}
