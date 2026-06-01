package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiStageNameSuggestionRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JdbiStageNameSuggestionRepository#findLatestUsableSuggestion}
 * using real in-memory SQLite.
 */
class JdbiStageNameSuggestionRepositoryTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JdbiStageNameSuggestionRepository repo;
    private Jdbi jdbi;
    private Connection connection;
    private String now;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiStageNameSuggestionRepository(jdbi);
        now = ISO_UTC.format(Instant.now());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Basic empty/miss ──────────────────────────────────────────────────────

    @Test
    void findLatestUsableSuggestion_emptyWhenNoRows() {
        assertTrue(repo.findLatestUsableSuggestion("麻美ゆま").isEmpty());
    }

    // ── Accepted with final_romaji wins over suggested_romaji ─────────────────

    @Test
    void findLatestUsableSuggestion_returnsFinalRomajiWhenAcceptedAndNonNull() {
        repo.recordSuggestion("麻美ゆま", "Yuma Asami", now);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision='accepted', final_romaji='Yuma Asami (corrected)' WHERE kanji_form=?",
                "麻美ゆま"));

        Optional<String> result = repo.findLatestUsableSuggestion("麻美ゆま");
        assertTrue(result.isPresent());
        assertEquals("Yuma Asami (corrected)", result.get());
    }

    // ── Accepted without final_romaji returns suggested_romaji ────────────────

    @Test
    void findLatestUsableSuggestion_returnsAcceptedSuggestedRomajiWhenNoFinalRomaji() {
        repo.recordSuggestion("麻美ゆま", "Yuma Asami", now);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision='accepted' WHERE kanji_form=?",
                "麻美ゆま"));

        Optional<String> result = repo.findLatestUsableSuggestion("麻美ゆま");
        assertTrue(result.isPresent());
        assertEquals("Yuma Asami", result.get());
    }

    // ── Unreviewed returns suggested_romaji ───────────────────────────────────

    @Test
    void findLatestUsableSuggestion_returnsUnreviewedSuggestedRomaji() {
        repo.recordSuggestion("麻美ゆま", "Yuma Asami", now);

        Optional<String> result = repo.findLatestUsableSuggestion("麻美ゆま");
        assertTrue(result.isPresent());
        assertEquals("Yuma Asami", result.get());
    }

    // ── Rejected returns empty ────────────────────────────────────────────────

    @Test
    void findLatestUsableSuggestion_emptyWhenAllRejected() {
        repo.recordSuggestion("麻美ゆま", "Yuma Asami", now);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision='rejected' WHERE kanji_form=?",
                "麻美ゆま"));

        assertTrue(repo.findLatestUsableSuggestion("麻美ゆま").isEmpty());
    }

    // ── Ordering: newest usable wins; rejected rows are skipped ──────────────

    @Test
    void findLatestUsableSuggestion_newestUnreviewedWinsOverOlderAccepted() {
        // Insert older row (accepted) then newer row (unreviewed)
        String t1 = "2026-01-01T00:00:00.000Z";
        String t2 = "2026-01-02T00:00:00.000Z";
        repo.recordSuggestion("蒼井そら", "Sora Aoi (old)", t1);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision='accepted' WHERE kanji_form=? AND suggested_romaji=?",
                "蒼井そら", "Sora Aoi (old)"));
        repo.recordSuggestion("蒼井そら", "Sora Aoi (new)", t2);

        Optional<String> result = repo.findLatestUsableSuggestion("蒼井そら");
        assertTrue(result.isPresent());
        assertEquals("Sora Aoi (new)", result.get());
    }

    @Test
    void findLatestUsableSuggestion_skipsNewestRejectedAndReturnsOlderAccepted() {
        // Insert older accepted row, then newer rejected row
        String t1 = "2026-01-01T00:00:00.000Z";
        String t2 = "2026-01-02T00:00:00.000Z";
        repo.recordSuggestion("蒼井そら", "Sora Aoi (old)", t1);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision='accepted' WHERE kanji_form=? AND suggested_romaji=?",
                "蒼井そら", "Sora Aoi (old)"));
        repo.recordSuggestion("蒼井そら", "Sora Aoi (wrong)", t2);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision='rejected' WHERE kanji_form=? AND suggested_romaji=?",
                "蒼井そら", "Sora Aoi (wrong)"));

        Optional<String> result = repo.findLatestUsableSuggestion("蒼井そら");
        assertTrue(result.isPresent());
        assertEquals("Sora Aoi (old)", result.get());
    }

    // ── FIX 3a: recordFinalRomaji ─────────────────────────────────────────────

    /**
     * FIX 3a: recordFinalRomaji updates final_romaji on the most recent suggestion row
     * for the given kanji form. Subsequent findLatestUsableSuggestion should return the
     * corrected (given-first) value.
     */
    @Test
    void recordFinalRomaji_updatesLatestSuggestionFinalRomaji() {
        String kanji = "渚あいり";
        String normalizedKanji = com.organizer3.translation.TranslationNormalization.normalize(kanji);
        String ts = "2026-06-01T00:00:00.000Z";

        // Insert a suggestion with LLM-produced surname-first order.
        repo.recordSuggestion(normalizedKanji, "Nagisa Airi", ts);

        // FIX 3a: record the canonical (given-first) correction.
        repo.recordFinalRomaji(normalizedKanji, "Airi Nagisa");

        // findLatestUsableSuggestion should now return the corrected value
        // (COALESCE(final_romaji, suggested_romaji) picks final_romaji first).
        Optional<String> result = repo.findLatestUsableSuggestion(normalizedKanji);
        assertTrue(result.isPresent(), "suggestion must still be found after recordFinalRomaji");
        assertEquals("Airi Nagisa", result.get(), "FIX 3a: corrected order must win via final_romaji");
    }

    /**
     * FIX 3a: recordFinalRomaji is a no-op when no suggestion row exists for the kanji form.
     * Must not throw.
     */
    @Test
    void recordFinalRomaji_noRow_noOp() {
        assertDoesNotThrow(() ->
                repo.recordFinalRomaji("存在しない", "Should Not Throw"));
    }

    /**
     * FIX 3a: When multiple suggestion rows exist, recordFinalRomaji updates the most
     * recent one (highest id) and leaves the older rows untouched.
     */
    @Test
    void recordFinalRomaji_multipleRows_updatesLatestOnly() {
        String kanji = "田中みく";
        String normalized = com.organizer3.translation.TranslationNormalization.normalize(kanji);
        repo.recordSuggestion(normalized, "Tanaka Miku (old)", "2026-01-01T00:00:00.000Z");
        repo.recordSuggestion(normalized, "Miku Tanaka (new)", "2026-01-02T00:00:00.000Z");

        repo.recordFinalRomaji(normalized, "Miku Tanaka (corrected)");

        // The most recent row should have the corrected value.
        Optional<String> result = repo.findLatestUsableSuggestion(normalized);
        assertTrue(result.isPresent());
        assertEquals("Miku Tanaka (corrected)", result.get(),
                "FIX 3a: only the latest row's final_romaji should be updated");

        // Directly check that the older row is unchanged (via findByKanji order).
        var rows = repo.findByKanji(normalized);
        assertEquals(2, rows.size());
        // findByKanji orders by suggested_at DESC, so rows.get(0) is the newer row.
        assertNotNull(rows.get(0).finalRomaji(), "newer row must have final_romaji set");
        assertNull(rows.get(1).finalRomaji(), "older row must still have null final_romaji");
    }
}
