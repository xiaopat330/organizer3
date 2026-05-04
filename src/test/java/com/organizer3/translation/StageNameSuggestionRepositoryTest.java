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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository tests for {@link JdbiStageNameSuggestionRepository} using real in-memory SQLite.
 * Covers: idempotence, find-by-kanji, find-accepted, find-unreviewed, countUnreviewed.
 */
class StageNameSuggestionRepositoryTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JdbiStageNameSuggestionRepository repo;
    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiStageNameSuggestionRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void countUnreviewed_emptyTableReturnsZero() {
        assertEquals(0L, repo.countUnreviewed());
    }

    @Test
    void recordSuggestion_andCountUnreviewed() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        assertEquals(1L, repo.countUnreviewed());
    }

    @Test
    void recordSuggestion_isIdempotent() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now); // exact duplicate
        assertEquals(1L, repo.countUnreviewed());
    }

    @Test
    void recordSuggestion_differentRomajiForSameKanjiAllowed() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        repo.recordSuggestion("あいだゆあ", "Yua Aida (alternate)", now);
        assertEquals(2L, repo.countUnreviewed());
    }

    @Test
    void findByKanji_returnsAllMatchingRows() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        repo.recordSuggestion("愛佳", "Aika", now);

        List<StageNameSuggestionRow> results = repo.findByKanji("あいだゆあ");
        assertEquals(1, results.size());
        assertEquals("あいだゆあ", results.get(0).kanjiForm());
        assertEquals("Yua Aida", results.get(0).suggestedRomaji());
        assertNull(results.get(0).reviewDecision());
    }

    @Test
    void findByKanji_emptyWhenNoMatch() {
        List<StageNameSuggestionRow> results = repo.findByKanji("あいだゆあ");
        assertTrue(results.isEmpty());
    }

    @Test
    void findAcceptedRomaji_emptyWhenNoAcceptedDecision() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        Optional<String> accepted = repo.findAcceptedRomaji("あいだゆあ");
        assertTrue(accepted.isEmpty());
    }

    @Test
    void findAcceptedRomaji_returnsAcceptedSuggestion() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        // Manually set review_decision = 'accepted' via raw SQL
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision = 'accepted', reviewed_at = ? WHERE kanji_form = ?",
                now, "あいだゆあ"));

        Optional<String> accepted = repo.findAcceptedRomaji("あいだゆあ");
        assertTrue(accepted.isPresent());
        assertEquals("Yua Aida", accepted.get());
    }

    @Test
    void findAcceptedRomaji_prefersFinalRomajiOverSuggested() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        // Set final_romaji to a corrected value
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision = 'accepted', reviewed_at = ?, final_romaji = ? WHERE kanji_form = ?",
                now, "Yua Aida (corrected)", "あいだゆあ"));

        Optional<String> accepted = repo.findAcceptedRomaji("あいだゆあ");
        assertTrue(accepted.isPresent());
        assertEquals("Yua Aida (corrected)", accepted.get());
    }

    @Test
    void findUnreviewed_returnsOnlyUnreviewed() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        repo.recordSuggestion("愛佳", "Aika", now);
        // Mark one as reviewed
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision = 'rejected', reviewed_at = ? WHERE kanji_form = ?",
                now, "愛佳"));

        List<StageNameSuggestionRow> unreviewed = repo.findUnreviewed(10);
        assertEquals(1, unreviewed.size());
        assertEquals("あいだゆあ", unreviewed.get(0).kanjiForm());
    }

    @Test
    void findUnreviewed_respectsLimit() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        repo.recordSuggestion("愛佳", "Aika", now);
        repo.recordSuggestion("蒼井そら", "Sora Aoi", now);

        List<StageNameSuggestionRow> unreviewed = repo.findUnreviewed(2);
        assertEquals(2, unreviewed.size());
    }

    @Test
    void countUnreviewed_doesNotCountReviewed() {
        String now = ISO_UTC.format(Instant.now());
        repo.recordSuggestion("あいだゆあ", "Yua Aida", now);
        repo.recordSuggestion("愛佳", "Aika", now);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision = 'accepted', reviewed_at = ? WHERE kanji_form = ?",
                now, "愛佳"));

        assertEquals(1L, repo.countUnreviewed());
    }
}
