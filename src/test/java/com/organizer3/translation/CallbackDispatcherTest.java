package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CallbackDispatcher}.
 *
 * <p>Verifies: registered kind writes to the right column; unknown kind is logged but doesn't
 * throw; null callback is a no-op; existing EN value is not clobbered.
 */
class CallbackDispatcherTest {

    private CallbackDispatcher dispatcher;
    private Jdbi jdbi;
    private Connection connection;
    private long titleId;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        // Insert prerequisite title + enrichment row
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles (code, label) VALUES ('TEST-001','TEST')");
        });
        titleId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM titles WHERE code='TEST-001'")
                        .mapTo(Long.class).one());
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) " +
                        "VALUES (?, 'TEST-001', '2026-01-01T00:00:00.000Z')", titleId));

        dispatcher = new CallbackDispatcher(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // -------------------------------------------------------------------------
    // Null / no-op cases
    // -------------------------------------------------------------------------

    @Test
    void dispatch_nullKind_isNoOp() {
        assertDoesNotThrow(() -> dispatcher.dispatch(null, 1L, "English text"));
    }

    @Test
    void dispatch_nullId_isNoOp() {
        assertDoesNotThrow(() -> dispatcher.dispatch("title_javdb_enrichment.maker_en", null, "English text"));
    }

    // -------------------------------------------------------------------------
    // Unknown callback kind
    // -------------------------------------------------------------------------

    @Test
    void dispatch_unknownKind_logsAndDoesNotThrow() {
        // Should not throw — just log a warning
        assertDoesNotThrow(() -> dispatcher.dispatch("unknown.field_en", 1L, "English text"));
    }

    // -------------------------------------------------------------------------
    // Built-in title_javdb_enrichment callbacks
    // -------------------------------------------------------------------------

    @Test
    void dispatch_titleOriginalEn_writesToCorrectColumn() {
        dispatcher.dispatch("title_javdb_enrichment.title_original_en", titleId, "My English Title");
        assertEquals("My English Title", readColumn("title_original_en"));
    }

    @Test
    void dispatch_seriesEn_writesToCorrectColumn() {
        dispatcher.dispatch("title_javdb_enrichment.series_en", titleId, "My Series");
        assertEquals("My Series", readColumn("series_en"));
    }

    @Test
    void dispatch_makerEn_writesToCorrectColumn() {
        dispatcher.dispatch("title_javdb_enrichment.maker_en", titleId, "My Studio");
        assertEquals("My Studio", readColumn("maker_en"));
    }

    @Test
    void dispatch_publisherEn_writesToCorrectColumn() {
        dispatcher.dispatch("title_javdb_enrichment.publisher_en", titleId, "My Publisher");
        assertEquals("My Publisher", readColumn("publisher_en"));
    }

    // -------------------------------------------------------------------------
    // Does not clobber existing EN value
    // -------------------------------------------------------------------------

    @Test
    void dispatch_existingEnValue_isNotClobbered() {
        // Set an existing EN value
        jdbi.useHandle(h ->
                h.execute("UPDATE title_javdb_enrichment SET maker_en='Existing Value' WHERE title_id=?", titleId));

        dispatcher.dispatch("title_javdb_enrichment.maker_en", titleId, "New Value");

        // Should remain "Existing Value" — not clobbered
        assertEquals("Existing Value", readColumn("maker_en"));
    }

    // -------------------------------------------------------------------------
    // Callback target row deleted (0 rows updated — no error)
    // -------------------------------------------------------------------------

    @Test
    void dispatch_deletedRow_doesNotThrow() {
        long nonExistentId = 99999L;
        assertDoesNotThrow(() ->
                dispatcher.dispatch("title_javdb_enrichment.maker_en", nonExistentId, "English text"));
    }

    // -------------------------------------------------------------------------
    // Custom callback registration
    // -------------------------------------------------------------------------

    @Test
    void dispatch_customHandler_isInvoked() {
        AtomicReference<String> received = new AtomicReference<>();
        dispatcher.register("my.custom_kind", (id, en) -> received.set(en));

        dispatcher.dispatch("my.custom_kind", 1L, "Custom Result");
        assertEquals("Custom Result", received.get());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String readColumn(String column) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT " + column + " FROM title_javdb_enrichment WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(String.class)
                        .findFirst()
                        .orElse(null));
    }
}
