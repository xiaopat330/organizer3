package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.organizer3.translation.ActressFuzzyMatcher.Rule.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Corpus-based tests for {@link ActressFuzzyMatcher} using real in-memory SQLite.
 *
 * <p>Fixture:
 * <ul>
 *   <li>Sarasa Hara — canonical "Sarasa Hara"; aliases: "Natsume Iroha", "夏目彩春", "原 更紗"
 *   <li>Asami Yuma — canonical "Asami Yuma" (reversal control — query "Yuma Asami" forces Rule 2)
 *   <li>Meisa Kuroki — punctuation control: input "Mei-Sa Kuroki" must hit Rule 3
 *   <li>Akira Tanaka + Hiro Tanaka — last-name ambiguity control
 * </ul>
 */
class ActressFuzzyMatcherTest {

    private JdbiActressRepository actressRepo;
    private ActressFuzzyMatcher matcher;
    private Connection connection;

    private Actress sarasa;
    private Actress asamiYuma;
    private Actress meisaKuroki;
    private Actress akiraTanaka;
    private Actress hiroTanaka;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        matcher = new ActressFuzzyMatcher(actressRepo);

        sarasa = actressRepo.save(actress("Sarasa Hara"));
        actressRepo.saveAlias(new ActressAlias(sarasa.getId(), "Natsume Iroha"));
        actressRepo.saveAlias(new ActressAlias(sarasa.getId(), "夏目彩春"));
        actressRepo.saveAlias(new ActressAlias(sarasa.getId(), "原 更紗"));

        // Canonical is "Asami Yuma" so that input "Yuma Asami" fails Rule 1 and hits Rule 2.
        asamiYuma = actressRepo.save(actress("Asami Yuma"));

        meisaKuroki = actressRepo.save(actress("Meisa Kuroki"));

        akiraTanaka = actressRepo.save(actress("Akira Tanaka"));
        hiroTanaka = actressRepo.save(actress("Hiro Tanaka"));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    @Test
    void splitTokensHandlesMultipleSpaces() {
        assertArrayEquals(new String[]{"Yuma", "Asami"}, ActressFuzzyMatcher.splitTokens("Yuma  Asami"));
    }

    @Test
    void reverseTokensReversesTwoTokens() {
        assertArrayEquals(new String[]{"Asami", "Yuma"}, ActressFuzzyMatcher.reverseTokens(new String[]{"Yuma", "Asami"}));
    }

    @Test
    void stripPunctuationRemovesDashAndComma() {
        assertEquals("MeiSa Kuroki", ActressFuzzyMatcher.stripPunctuation("Mei-Sa Kuroki"));
        assertEquals("Yuma Asami", ActressFuzzyMatcher.stripPunctuation("Yuma, Asami"));
    }

    @Test
    void isSingleTokenReturnsTrueForOneToken() {
        assertTrue(ActressFuzzyMatcher.isSingleToken("Asami"));
        assertFalse(ActressFuzzyMatcher.isSingleToken("Yuma Asami"));
    }

    // ── Rule 1: EXACT ─────────────────────────────────────────────────────────

    @Test
    void matchExactCanonicalName() {
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Sarasa Hara");
        assertTrue(result.isPresent());
        assertEquals(sarasa.getId(), result.get().actressId());
        assertEquals(EXACT, result.get().rule());
    }

    @Test
    void matchExactAliasHit_NatsumeIroha() {
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Natsume Iroha");
        assertTrue(result.isPresent());
        assertEquals(sarasa.getId(), result.get().actressId());
        assertEquals(EXACT, result.get().rule());
    }

    @Test
    void matchExactAliasHit_kanjiStageName() {
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("夏目彩春");
        assertTrue(result.isPresent());
        assertEquals(sarasa.getId(), result.get().actressId());
        assertEquals(EXACT, result.get().rule());
    }

    // ── Rule 2: REVERSAL ──────────────────────────────────────────────────────

    @Test
    void matchReversalFiresWhenCanonicalIsReversedForm() {
        // "Asami Yuma" is NOT the canonical name; reversing it → "Yuma Asami"... wait,
        // canonical is "Asami Yuma". Input "Yuma Asami" reverses to "Asami Yuma" → EXACT alias
        // would be checked, but it's the canonical — so Rule 1 fails on "Yuma Asami" (not canonical),
        // then Rule 2 reverses to "Asami Yuma" which IS the canonical → REVERSAL.
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Yuma Asami");
        assertTrue(result.isPresent());
        assertEquals(asamiYuma.getId(), result.get().actressId());
        assertEquals(REVERSAL, result.get().rule());
    }

    @Test
    void matchReversalHitsAliasViaReversal() {
        // "Iroha Natsume" reversed → "Natsume Iroha" which is an alias of Sarasa Hara.
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Iroha Natsume");
        assertTrue(result.isPresent());
        assertEquals(sarasa.getId(), result.get().actressId());
        assertEquals(REVERSAL, result.get().rule());
    }

    // ── Rule 3: PUNCT_NORM ────────────────────────────────────────────────────

    @Test
    void matchPunctNormStripsHyphen() {
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Mei-Sa Kuroki");
        assertTrue(result.isPresent());
        assertEquals(meisaKuroki.getId(), result.get().actressId());
        assertEquals(PUNCT_NORM, result.get().rule());
    }

    // ── Rule 4: LAST_NAME_ONLY ────────────────────────────────────────────────

    @Test
    void matchLastNameOnlySingleUnambiguousHit() {
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Hara");
        assertTrue(result.isPresent());
        assertEquals(sarasa.getId(), result.get().actressId());
        assertEquals(LAST_NAME_ONLY, result.get().rule());
    }

    @Test
    void matchLastNameOnlyAmbigiousReturnsEmpty() {
        // Both Akira Tanaka and Hiro Tanaka share last token "Tanaka" — must under-link.
        Optional<ActressFuzzyMatcher.MatchResult> result = matcher.match("Tanaka");
        assertTrue(result.isEmpty());
    }

    // ── No match ──────────────────────────────────────────────────────────────

    @Test
    void matchReturnsEmptyForUnknownName() {
        assertTrue(matcher.match("Nobody Known").isEmpty());
    }

    @Test
    void matchReturnsEmptyForNullInput() {
        assertTrue(matcher.match(null).isEmpty());
    }

    @Test
    void matchReturnsEmptyForBlankInput() {
        assertTrue(matcher.match("  ").isEmpty());
    }

    // ── findCandidates ────────────────────────────────────────────────────────

    @Test
    void findCandidatesReturnsBothTanakasWithLastNameOnlyRule() {
        List<ActressFuzzyMatcher.MatchResult> candidates = matcher.findCandidates("Tanaka");
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(r -> r.rule() == LAST_NAME_ONLY));
        assertTrue(candidates.stream().anyMatch(r -> r.actressId() == akiraTanaka.getId()));
        assertTrue(candidates.stream().anyMatch(r -> r.actressId() == hiroTanaka.getId()));
    }

    @Test
    void findCandidatesDeduplicatesActressAcrossRules() {
        // "Sarasa Hara" matches Rule 1 (exact canonical). It should NOT also appear
        // under Rule 4 even though "Hara" is its last token.
        // Input must be a single token to trigger Rule 4, so test with "Hara".
        // But "Hara" only triggers Rule 4 (no exact canonical named "Hara"), so
        // test dedup with a multi-rule scenario: seed an actress "Hara" to get
        // Rule 1 hit, then verify "Sarasa Hara" is NOT also in the results under Rule 4.
        Actress haraOnly = actressRepo.save(actress("Hara"));
        List<ActressFuzzyMatcher.MatchResult> candidates = matcher.findCandidates("Hara");
        // Rule 1: "Hara" exact → haraOnly
        // Rule 4: last-token "Hara" → both haraOnly and sarasa match
        // Dedup: haraOnly already in seen → sarasa gets Rule 4, haraOnly appears only once
        long haraOnlyCount = candidates.stream().filter(r -> r.actressId() == haraOnly.getId()).count();
        assertEquals(1, haraOnlyCount, "haraOnly should appear exactly once despite matching both Rule 1 and Rule 4");
        // Sarasa Hara should appear exactly once under Rule 4
        long sarasaCount = candidates.stream().filter(r -> r.actressId() == sarasa.getId()).count();
        assertEquals(1, sarasaCount, "Sarasa Hara should appear exactly once");
        // haraOnly wins Rule 1 (EXACT), sarasa gets Rule 4
        assertEquals(EXACT, candidates.stream().filter(r -> r.actressId() == haraOnly.getId()).findFirst().get().rule());
        assertEquals(LAST_NAME_ONLY, candidates.stream().filter(r -> r.actressId() == sarasa.getId()).findFirst().get().rule());
    }

    @Test
    void findCandidatesReturnsEmptyForNoMatch() {
        assertTrue(matcher.findCandidates("Nobody Known").isEmpty());
    }

    @Test
    void findCandidatesExactHitNotDuplicatedUnderLaterRules() {
        // "Meisa Kuroki" matches Rule 1 exactly. Must not also appear under Rule 4 for "Kuroki"
        // — but input "Meisa Kuroki" is multi-token so Rule 4 doesn't fire.
        List<ActressFuzzyMatcher.MatchResult> candidates = matcher.findCandidates("Meisa Kuroki");
        assertEquals(1, candidates.size());
        assertEquals(EXACT, candidates.get(0).rule());
        assertEquals(meisaKuroki.getId(), candidates.get(0).actressId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Actress actress(String canonicalName) {
        return Actress.builder()
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }
}
