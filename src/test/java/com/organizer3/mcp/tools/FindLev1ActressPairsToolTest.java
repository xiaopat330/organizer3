package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FindLev1ActressPairsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private FindLev1ActressPairsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        tool = new FindLev1ActressPairsTool(actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Core distance scenarios ─────────────────────────────────────────────

    @Test
    void detectsSubstitutionPair() {
        Actress a = save("Reimi Hoshisaki", Actress.Tier.LIBRARY); addTitles(a, 2);
        Actress b = save("Reimi Hoshizaki", Actress.Tier.LIBRARY); addTitles(b, 3);

        var r = call(defaults());
        assertEquals(1, r.count());
        assertEquals(1, r.pairs().get(0).distance());
    }

    @Test
    void detectsInsertionPair() {
        // Kashi (5) vs Kashii (6) — insertion at end. Length differs by 1.
        Actress popular = save("Ria Kashi",  Actress.Tier.POPULAR);  addTitles(popular, 49);
        Actress minor   = save("Ria Kashii", Actress.Tier.MINOR);    addTitles(minor, 7);

        var r = call(defaults());
        assertEquals(1, r.count());
        var p = r.pairs().get(0);
        assertEquals(1, p.distance());
        // High-titles side should be present.
        int maxTc = Math.max(p.a().titleCount(), p.b().titleCount());
        assertEquals(49, maxTc);
    }

    @Test
    void detectsDeletionPair() {
        // Lev=1 deletion: "Saki Aobaa" → "Saki Aoba" drops trailing 'a'.
        Actress a = save("Saki Aobaa", Actress.Tier.LIBRARY); addTitles(a, 4);
        Actress b = save("Saki Aoba",  Actress.Tier.LIBRARY); addTitles(b, 2);

        var r = call(defaults());
        assertEquals(1, r.count());
        assertEquals(1, r.pairs().get(0).distance());
    }

    // ── Filters ─────────────────────────────────────────────────────────────

    @Test
    void firstTokenMustMatchExcludesDifferentFirstTokens() {
        // Different first tokens: "Ria" vs "Mia" — Lev=1 on full name,
        // but should be excluded with first_token_must_match=true (default).
        Actress a = save("Ria Kashi", Actress.Tier.LIBRARY); addTitles(a, 2);
        Actress b = save("Mia Kashi", Actress.Tier.LIBRARY); addTitles(b, 2);

        var r = call(defaults());
        assertEquals(0, r.count(), "different first tokens must be excluded when first_token_must_match=true");

        // With first_token_must_match=false, pair must surface.
        ObjectNode n = defaults();
        n.put("first_token_must_match", false);
        var r2 = call(n);
        assertEquals(1, r2.count());
    }

    @Test
    void tierMismatchOnlyFiltersSameTierPairs() {
        // Same-tier pair: both LIBRARY
        Actress aSame = save("Aiko Tanaka", Actress.Tier.LIBRARY); addTitles(aSame, 2);
        Actress bSame = save("Aiko Tanake", Actress.Tier.LIBRARY); addTitles(bSame, 2);
        // Cross-tier pair: SUPERSTAR vs LIBRARY
        Actress aDiff = save("Aimi Yoshikawa", Actress.Tier.GODDESS);  addTitles(aDiff, 100);
        Actress bDiff = save("Aimi Yoshizawa", Actress.Tier.LIBRARY);  addTitles(bDiff, 2);

        // Default tier_mismatch_only=false: should see 2 pairs
        var rAll = call(defaults());
        assertEquals(2, rAll.count());

        // tier_mismatch_only=true: only the cross-tier pair survives
        ObjectNode n = defaults();
        n.put("tier_mismatch_only", true);
        var rMismatch = call(n);
        assertEquals(1, rMismatch.count());
        var p = rMismatch.pairs().get(0);
        assertNotEquals(p.a().tier(), p.b().tier());
    }

    @Test
    void maxDistanceTwoReturnsLev1AndLev2() {
        // Lev=1 pair
        Actress a1 = save("Momo Fukada", Actress.Tier.LIBRARY); addTitles(a1, 2);
        Actress b1 = save("Momo Fukuda", Actress.Tier.LIBRARY); addTitles(b1, 2);
        // Lev=2 pair: Fukada vs Fukuta (a↔u, d↔t)
        Actress a2 = save("Momo Fukuta", Actress.Tier.LIBRARY); addTitles(a2, 2);
        // a1 and a2: Fukada vs Fukuta — Lev=2

        // Default max_distance=1: only the Lev=1 pairs should appear.
        var r1 = call(defaults());
        for (var p : r1.pairs()) assertEquals(1, p.distance());
        long lev1Count = r1.pairs().stream().filter(p -> p.distance() == 1).count();
        assertTrue(lev1Count >= 1);

        // max_distance=2: must include Lev=2 pair as well.
        ObjectNode n = defaults();
        n.put("max_distance", 2);
        var r2 = call(n);
        long lev2Count = r2.pairs().stream().filter(p -> p.distance() == 2).count();
        assertTrue(lev2Count >= 1, "max_distance=2 must surface Lev-2 pairs");
        // And the Lev=1 pairs must still be there.
        long lev1AlsoIncluded = r2.pairs().stream().filter(p -> p.distance() == 1).count();
        assertTrue(lev1AlsoIncluded >= 1, "Lev-1 pairs must still be present when max_distance=2");
    }

    @Test
    void excludesSentinelActressesByDefault() {
        // Two sentinel-named actresses with first token "Various" — would pair otherwise.
        Actress s1 = save("Various Performers", Actress.Tier.LIBRARY);
        Actress s2 = save("Various Performars", Actress.Tier.LIBRARY); // Lev=1
        // Mark them as sentinel
        jdbi.useHandle(h -> h.execute("UPDATE actresses SET is_sentinel = 1 WHERE id IN (?, ?)",
                s1.getId(), s2.getId()));
        // give them title counts so min_titles filter doesn't drop them on its own
        addTitles(s1, 2); addTitles(s2, 2);

        var r = call(defaults());
        assertEquals(0, r.count(), "sentinel actresses must be excluded by default");

        ObjectNode n = defaults();
        n.put("exclude_sentinel", false);
        var r2 = call(n);
        assertEquals(1, r2.count(), "with exclude_sentinel=false the pair must surface");
    }

    @Test
    void minTitlesEitherFiltersPhantomVsPhantomPairs() {
        // Both have 0 titles
        save("Aya Sazanami", Actress.Tier.LIBRARY);
        save("Aya Sazanamx", Actress.Tier.LIBRARY); // Lev=1

        // default min_titles_either=1: must filter (neither has titles)
        var r = call(defaults());
        assertEquals(0, r.count(), "phantom-vs-phantom pair must be filtered by min_titles_either=1");

        // min_titles_either=0: pair surfaces
        ObjectNode n = defaults();
        n.put("min_titles_either", 0);
        var r2 = call(n);
        assertEquals(1, r2.count());
    }

    @Test
    void minTitlesEitherKeepsPairWhenOneSideHasTitles() {
        Actress a = save("Asami Nagase", Actress.Tier.SUPERSTAR); addTitles(a, 60);
        save("Asami Nanase", Actress.Tier.LIBRARY); // 0 titles

        // default min_titles_either=1: one side qualifies → pair stays
        var r = call(defaults());
        assertEquals(1, r.count());
    }

    // ── Levenshtein unit ────────────────────────────────────────────────────

    @Test
    void levenshteinSupportsInsertDeleteSubstitute() {
        assertEquals(1, FindLev1ActressPairsTool.levenshtein("kashi", "kashii", 2));   // insertion
        assertEquals(1, FindLev1ActressPairsTool.levenshtein("aoba", "aobaa", 2));     // deletion
        assertEquals(1, FindLev1ActressPairsTool.levenshtein("hoshisaki", "hoshizaki", 2));
        assertEquals(-1, FindLev1ActressPairsTool.levenshtein("abc", "xyz", 1));
        assertEquals(0,  FindLev1ActressPairsTool.levenshtein("same", "same", 1));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Actress save(String name, Actress.Tier tier) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(tier)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build());
    }

    private void addTitles(Actress a, int n) {
        for (int i = 0; i < n; i++) {
            String code = "T" + a.getId() + "-" + String.format("%03d", i);
            Title t = Title.builder()
                    .code(code)
                    .baseCode(code)
                    .label(code.split("-")[0])
                    .seqNum(1)
                    .actressId(a.getId())
                    .build();
            long id = titleRepo.save(t).getId();
            titleActressRepo.link(id, a.getId());
        }
    }

    private ObjectNode defaults() {
        return M.createObjectNode();
    }

    private FindLev1ActressPairsTool.Result call(ObjectNode args) {
        return (FindLev1ActressPairsTool.Result) tool.call(args);
    }
}
