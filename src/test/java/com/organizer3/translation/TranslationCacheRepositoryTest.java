package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiTranslationCacheRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
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
 * Repository tests for {@link JdbiTranslationCacheRepository} using real in-memory SQLite.
 * Covers: insert, lookup by hash+strategy, human-corrected precedence, transient failure with retry_after.
 */
class TranslationCacheRepositoryTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JdbiTranslationCacheRepository cacheRepo;
    private JdbiTranslationStrategyRepository strategyRepo;
    private long strategyId;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        cacheRepo = new JdbiTranslationCacheRepository(jdbi);

        strategyId = strategyRepo.insert(new TranslationStrategy(
                0, "label_basic", "gemma4:e4b", "Translate: {jp}", null, true, null));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void findByHashAndStrategy_missingReturnsEmpty() {
        Optional<TranslationCacheRow> found = cacheRepo.findByHashAndStrategy("deadbeef", strategyId);
        assertTrue(found.isEmpty());
    }

    @Test
    void insertAndLookupByHashAndStrategy() {
        String hash = "abc123";
        String now = ISO_UTC.format(Instant.now());
        TranslationCacheRow row = new TranslationCacheRow(
                0, hash, "ソーステキスト", strategyId,
                "Source text", null, null, null, null,
                150, 10, 5, 1000L, now);

        long id = cacheRepo.insert(row);
        assertTrue(id > 0);

        Optional<TranslationCacheRow> found = cacheRepo.findByHashAndStrategy(hash, strategyId);
        assertTrue(found.isPresent());
        assertEquals("ソーステキスト", found.get().sourceText());
        assertEquals("Source text", found.get().englishText());
        assertNull(found.get().failureReason());
        assertEquals(id, found.get().id());
    }

    @Test
    void humanCorrectedTextTakesPrecedenceOverEnglishText() {
        String hash = TranslationNormalization.hashOf("テスト");
        String now = ISO_UTC.format(Instant.now());
        TranslationCacheRow row = new TranslationCacheRow(
                0, hash, "テスト", strategyId,
                "Test (LLM output)", "Test (human corrected)", "2026-01-01T00:00:00.000Z",
                null, null, null, null, null, null, now);

        cacheRepo.insert(row);

        TranslationCacheRow found = cacheRepo.findByHashAndStrategy(hash, strategyId).orElseThrow();
        assertEquals("Test (LLM output)", found.englishText());
        assertEquals("Test (human corrected)", found.humanCorrectedText());
        assertEquals("Test (human corrected)", found.bestTranslation());
    }

    @Test
    void bestTranslation_returnsEnglishTextWhenNoCorrectedText() {
        String hash = "hash1";
        String now = ISO_UTC.format(Instant.now());
        TranslationCacheRow row = new TranslationCacheRow(
                0, hash, "テスト", strategyId,
                "English output", null, null, null, null,
                null, null, null, null, now);

        cacheRepo.insert(row);
        TranslationCacheRow found = cacheRepo.findByHashAndStrategy(hash, strategyId).orElseThrow();
        assertEquals("English output", found.bestTranslation());
    }

    @Test
    void bestTranslation_returnsNullWhenBothNull() {
        String hash = "hash2";
        String now = ISO_UTC.format(Instant.now());
        TranslationCacheRow row = new TranslationCacheRow(
                0, hash, "テスト", strategyId,
                null, null, null, "refused", null,
                null, null, null, null, now);

        cacheRepo.insert(row);
        TranslationCacheRow found = cacheRepo.findByHashAndStrategy(hash, strategyId).orElseThrow();
        assertNull(found.bestTranslation());
    }

    @Test
    void transientFailureWithRetryAfter() {
        String hash = "hash3";
        String futureTime = ISO_UTC.format(Instant.now().plusSeconds(300));
        String now = ISO_UTC.format(Instant.now());
        TranslationCacheRow row = new TranslationCacheRow(
                0, hash, "テスト", strategyId,
                null, null, null, "unreachable", futureTime,
                null, null, null, null, now);

        cacheRepo.insert(row);
        TranslationCacheRow found = cacheRepo.findByHashAndStrategy(hash, strategyId).orElseThrow();
        assertEquals("unreachable", found.failureReason());
        assertNotNull(found.retryAfter());
        assertTrue(Instant.parse(found.retryAfter()).isAfter(Instant.now()));
    }

    @Test
    void updateOutcome_updatesFieldsInPlace() {
        String hash = "hash4";
        String now = ISO_UTC.format(Instant.now());
        TranslationCacheRow row = new TranslationCacheRow(
                0, hash, "テスト", strategyId,
                null, null, null, "unreachable", now,
                null, null, null, null, now);

        long id = cacheRepo.insert(row);
        cacheRepo.updateOutcome(id, "Updated English", null, null, 200, 12, 8, 500L);

        TranslationCacheRow updated = cacheRepo.findByHashAndStrategy(hash, strategyId).orElseThrow();
        assertEquals("Updated English", updated.englishText());
        assertNull(updated.failureReason());
        assertEquals(200, updated.latencyMs());
        assertEquals(12, updated.promptTokens());
        assertEquals(8, updated.evalTokens());
        assertEquals(500L, updated.evalDurationNs());
    }

    @Test
    void countTotal_reflectsInsertedRows() {
        assertEquals(0, cacheRepo.countTotal());

        cacheRepo.insert(makeRow("h1", strategyId, "English 1"));
        assertEquals(1, cacheRepo.countTotal());

        cacheRepo.insert(makeRow("h2", strategyId, "English 2"));
        assertEquals(2, cacheRepo.countTotal());
    }

    @Test
    void countSuccessful_onlyCountsNonNullEnglishText() {
        cacheRepo.insert(makeRow("h1", strategyId, "English"));
        cacheRepo.insert(makeFailedRow("h2", strategyId));

        assertEquals(2, cacheRepo.countTotal());
        assertEquals(1, cacheRepo.countSuccessful());
        assertEquals(1, cacheRepo.countFailed());
    }

    // --- helpers ---

    private TranslationCacheRow makeRow(String hash, long stratId, String english) {
        return new TranslationCacheRow(0, hash, "text", stratId, english,
                null, null, null, null, null, null, null, null,
                ISO_UTC.format(Instant.now()));
    }

    private TranslationCacheRow makeFailedRow(String hash, long stratId) {
        return new TranslationCacheRow(0, hash, "text", stratId, null,
                null, null, "refused", null, null, null, null, null,
                ISO_UTC.format(Instant.now()));
    }
}
