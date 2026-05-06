package com.organizer3.curation;

import com.organizer3.curation.NearMissResolveService.Outcome;
import com.organizer3.curation.NearMissResolveService.ResolveRequest;
import com.organizer3.curation.NearMissResolveService.ResolveResult;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftActress;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NearMissResolveServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private DraftActressRepository draftActressRepo;
    private NearMissResolveService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        actressRepo      = new JdbiActressRepository(jdbi);
        draftActressRepo = new DraftActressRepository(jdbi);
        service          = new NearMissResolveService(actressRepo, draftActressRepo);

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) " +
                      "VALUES (10, 'Existing Star', 'LIBRARY', '2024-01-01')");
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) " +
                      "VALUES (11, 'Yuma Asami', 'LIBRARY', '2024-01-01')");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void insertDraft(String slug, String kanji, Long linkToExisting, String linkToDraft,
                              String last) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO draft_actresses
                    (javdb_slug, stage_name, english_first_name, english_last_name,
                     link_to_existing_id, link_to_draft_slug, created_at, updated_at)
                VALUES (:slug, :kanji, NULL, :last, :xid, :linkDraft, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')
                """)
                .bind("slug",      slug)
                .bind("kanji",     kanji)
                .bind("last",      last)
                .bind("xid",       linkToExisting)
                .bind("linkDraft", linkToDraft)
                .execute());
    }

    private List<String> aliasesFor(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT alias_name FROM actress_aliases WHERE actress_id = :id ORDER BY alias_name")
                        .bind("id", actressId)
                        .mapTo(String.class)
                        .list());
    }

    // ── ALIAS outcome ─────────────────────────────────────────────────────────

    @Test
    void alias_insertsThreeAliasesWhenAllDistinct() {
        insertDraft("da1", "夏目彩春", null, null, null);

        ResolveResult result = service.resolve(new ResolveRequest(
                "夏目彩春", null, Outcome.ALIAS, 10L,
                "Ayaharu", "Natsume", "Natsume Iroha"));

        // kanji + llmRomaji + composedUserEdit = 3 aliases (all distinct, none = canonical "Existing Star")
        assertEquals(3, result.insertedAliases());
        List<String> aliases = aliasesFor(10L);
        assertTrue(aliases.contains("夏目彩春"));
        assertTrue(aliases.contains("Natsume Iroha"));
        assertTrue(aliases.contains("Ayaharu Natsume"));
    }

    @Test
    void alias_skipsUserEditWhenEqualsLlmRomaji() {
        insertDraft("da1", "夏目彩春", null, null, null);

        ResolveResult result = service.resolve(new ResolveRequest(
                "夏目彩春", null, Outcome.ALIAS, 10L,
                "Natsume", "Iroha", "Natsume Iroha"));

        // composedName == llmRomaji → only 2 aliases: kanji + llmRomaji
        assertEquals(2, result.insertedAliases());
    }

    @Test
    void alias_skipsAliasMatchingExistingCanonicalName() {
        // actress 11 has canonical "Yuma Asami"
        insertDraft("da1", "麻美ゆま", null, null, null);

        ResolveResult result = service.resolve(new ResolveRequest(
                "麻美ゆま", null, Outcome.ALIAS, 11L,
                "Yuma", "Asami", "Yuma Asami"));  // composedName == canonicalName

        // kanji inserted; llmRomaji "Yuma Asami" == canonical → skipped; composedName same → skipped
        assertEquals(1, result.insertedAliases());
        List<String> aliases = aliasesFor(11L);
        assertTrue(aliases.contains("麻美ゆま"));
        assertFalse(aliases.contains("Yuma Asami"));
    }

    @Test
    void alias_idempotent_reInsertingSameAliasDoesNotIncrement() {
        insertDraft("da1", "夏目彩春", null, null, null);

        service.resolve(new ResolveRequest("夏目彩春", null, Outcome.ALIAS, 10L,
                "Ayaharu", "Natsume", "Natsume Iroha"));
        // Second resolve: all aliases already exist → insertedAliases=0
        ResolveResult second = service.resolve(new ResolveRequest("夏目彩春", null, Outcome.ALIAS, 10L,
                "Ayaharu", "Natsume", "Natsume Iroha"));
        assertEquals(0, second.insertedAliases());
    }

    @Test
    void alias_cascadeUpdatesUnresolvedDrafts() {
        insertDraft("da1", "夏目彩春", null, null, null);
        insertDraft("da2", "夏目彩春", null, null, null);

        ResolveResult result = service.resolve(new ResolveRequest(
                "夏目彩春", null, Outcome.ALIAS, 10L, "Ayaharu", "Natsume", null));

        assertEquals(2, result.updatedDrafts());
        assertEquals(10L, draftActressRepo.findBySlug("da1").orElseThrow().getLinkToExistingId());
        assertEquals(10L, draftActressRepo.findBySlug("da2").orElseThrow().getLinkToExistingId());
    }

    @Test
    void alias_cascadeSkipsPreResolvedDrafts() {
        insertDraft("da1", "夏目彩春", null, null, null);
        insertDraft("da_resolved", "夏目彩春", 10L, null, "Resolved");  // already linked

        ResolveResult result = service.resolve(new ResolveRequest(
                "夏目彩春", null, Outcome.ALIAS, 10L, null, "Natsume", null));

        assertEquals(1, result.updatedDrafts());  // only da1
    }

    @Test
    void alias_mononym_singleAliasWritten() {
        insertDraft("da1", "玉", null, null, null);

        ResolveResult result = service.resolve(new ResolveRequest(
                "玉", null, Outcome.ALIAS, 10L, null, "Tama", null));

        assertTrue(result.insertedAliases() >= 1);
        List<String> aliases = aliasesFor(10L);
        assertTrue(aliases.contains("Tama"));
    }

    @Test
    void alias_validation_requiresAliasOfActressId() {
        assertThrows(IllegalArgumentException.class, () ->
                service.resolve(new ResolveRequest("夏目彩春", null, Outcome.ALIAS, null,
                        null, "Natsume", null)));
    }

    // ── CANONICAL outcome ────────────────────────────────────────────────────

    @Test
    void canonical_updatesPrimaryAndCascadesToSiblings() {
        insertDraft("primary1", "夏目彩春", null, null, null);
        insertDraft("sibling1", "夏目彩春", null, null, null);
        insertDraft("sibling2", "夏目彩春", null, null, null);

        ResolveResult result = service.resolve(new ResolveRequest(
                "夏目彩春", "primary1", Outcome.CANONICAL, null, "Ayaharu", "Natsume", null));

        assertEquals(3, result.updatedDrafts());
        assertEquals(0, result.insertedAliases());

        DraftActress primary = draftActressRepo.findBySlug("primary1").orElseThrow();
        assertNull(primary.getLinkToDraftSlug());

        DraftActress sib1 = draftActressRepo.findBySlug("sibling1").orElseThrow();
        assertEquals("primary1", sib1.getLinkToDraftSlug());

        DraftActress sib2 = draftActressRepo.findBySlug("sibling2").orElseThrow();
        assertEquals("primary1", sib2.getLinkToDraftSlug());
    }

    @Test
    void canonical_preResolvedDraftsNotTouched() {
        insertDraft("primary1", "夏目彩春", null, null, null);
        insertDraft("already_linked", "夏目彩春", 10L, null, "Star");  // already resolved

        ResolveResult result = service.resolve(new ResolveRequest(
                "夏目彩春", "primary1", Outcome.CANONICAL, null, null, "Natsume", null));

        // Only primary updated; already_linked skipped
        assertEquals(1, result.updatedDrafts());
        assertEquals("Star", draftActressRepo.findBySlug("already_linked").orElseThrow().getEnglishLastName());
    }

    @Test
    void canonical_throwsWhenPrimarySlugNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                service.resolve(new ResolveRequest("夏目彩春", "no-such-slug",
                        Outcome.CANONICAL, null, null, "Natsume", null)));
    }

    @Test
    void canonical_throwsWhenPrimaryAlreadyResolved() {
        insertDraft("already", "夏目彩春", 10L, null, "Resolved");

        assertThrows(IllegalArgumentException.class, () ->
                service.resolve(new ResolveRequest("夏目彩春", "already",
                        Outcome.CANONICAL, null, null, "Natsume", null)));
    }

    @Test
    void canonical_validation_requiresPrimarySlug() {
        assertThrows(IllegalArgumentException.class, () ->
                service.resolve(new ResolveRequest("夏目彩春", null,
                        Outcome.CANONICAL, null, null, "Natsume", null)));
    }

    // ── NFKC normalization ────────────────────────────────────────────────────

    @Test
    void nfkc_fullWidthInputNormalizedBeforeCascade() {
        // DB stores the NFKC-normalized form "ab" (full-width ａｂ → normalized to "ab")
        // The service must normalize the input before the cascade WHERE clause.
        insertDraft("da1", "ab", null, null, null);

        // User passes full-width form; NFKC normalize produces "ab" → matches stored row
        ResolveResult result = service.resolve(new ResolveRequest(
                "ａｂ", null, Outcome.ALIAS, 10L, null, "Ab", null));
        assertEquals(1, result.updatedDrafts());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void validation_blankKanjiThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                service.resolve(new ResolveRequest("", null, Outcome.ALIAS, 10L, null, "Last", null)));
    }

    @Test
    void validation_blankEnglishLastThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                service.resolve(new ResolveRequest("夏目彩春", null, Outcome.ALIAS, 10L, null, "", null)));
    }
}
