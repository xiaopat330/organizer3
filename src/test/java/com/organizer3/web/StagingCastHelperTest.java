package com.organizer3.web;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StagingCastHelper} — ordered cast-name derivation from canonical DB state.
 *
 * <p>Uses real in-memory SQLite (via {@link SchemaInitializer}) per repo-test conventions.
 */
class StagingCastHelperTest {

    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long insertActress(long id, String canonicalName, boolean isSentinel) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) "
                + "VALUES (?,?,'LIBRARY','2024-01-01',?)",
                id, canonicalName, isSentinel ? 1 : 0));
        return id;
    }

    private long insertTitle(long id, Long actressId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num, actress_id) "
                + "VALUES (?,?,?,?,?,?)",
                id, "TST-" + id, "TST-" + id, "TST", id, actressId));
        return id;
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses(title_id, actress_id) VALUES (?,?)",
                titleId, actressId));
    }

    // ── NULL filing actress → empty list ────────────────────────────────────

    @Test
    void nullFilingActress_returnsEmptyList() {
        insertTitle(1L, null);
        List<String> names = StagingCastHelper.orderedNamesForTitle(jdbi, 1L);
        assertTrue(names.isEmpty(), "NULL actress_id must produce an empty list");
    }

    // ── Single cast ──────────────────────────────────────────────────────────

    @Test
    void singleActress_returnsSingletonList() {
        insertActress(10L, "Mana Sakura", false);
        insertTitle(1L, 10L);
        linkActress(1L, 10L);

        List<String> names = StagingCastHelper.orderedNamesForTitle(jdbi, 1L);
        assertEquals(List.of("Mana Sakura"), names);
    }

    // ── Filing actress first even when not lowest rowid (DAZD-287 mirror) ──

    @Test
    void filingActressFirst_evenWhenNotLowestRowid() {
        // Insert actress B first (lower rowid), then actress A.
        insertActress(20L, "Miyu Aizawa", false);  // lower rowid in title_actresses
        insertActress(10L, "Waka Misono", false);   // higher rowid in title_actresses
        insertTitle(1L, 10L);  // filing actress = Waka Misono (id=10)

        // Link in reverse order so Miyu Aizawa has the lower rowid.
        linkActress(1L, 20L);  // rowid 1 → Miyu Aizawa
        linkActress(1L, 10L);  // rowid 2 → Waka Misono

        List<String> names = StagingCastHelper.orderedNamesForTitle(jdbi, 1L);

        assertEquals(2, names.size());
        assertEquals("Waka Misono", names.get(0),
                "filing actress must come first regardless of rowid");
        assertEquals("Miyu Aizawa", names.get(1),
                "co-credit must follow in rowid order");
    }

    // ── Sentinel excluded ────────────────────────────────────────────────────

    @Test
    void sentinel_coCredit_excluded() {
        insertActress(10L, "Mana Sakura", false);
        insertActress(99L, "Amateur", true);   // sentinel
        insertTitle(1L, 10L);
        linkActress(1L, 10L);
        linkActress(1L, 99L);  // sentinel co-credit

        List<String> names = StagingCastHelper.orderedNamesForTitle(jdbi, 1L);

        assertEquals(List.of("Mana Sakura"), names,
                "sentinel co-credits must be excluded from the list");
    }

    // ── Three-cast rowid order ────────────────────────────────────────────────

    @Test
    void threeCast_rowIdOrder_respected() {
        insertActress(1L, "A Filing", false);
        insertActress(2L, "B Second", false);
        insertActress(3L, "C Third", false);
        insertTitle(10L, 1L);
        // Link in rowid order (filing last to confirm we override with actress_id)
        linkActress(10L, 2L);  // rowid 1
        linkActress(10L, 3L);  // rowid 2
        linkActress(10L, 1L);  // rowid 3 — filing actress (out of rowid order intentionally)

        List<String> names = StagingCastHelper.orderedNamesForTitle(jdbi, 10L);

        assertEquals(3, names.size());
        assertEquals("A Filing", names.get(0),   "filing actress always first");
        assertEquals("B Second", names.get(1),   "first co-credit by rowid");
        assertEquals("C Third", names.get(2),    "second co-credit by rowid");
    }

    // ── Handle overload (inside transaction) ────────────────────────────────

    @Test
    void handleOverload_worksInsideTransaction() {
        insertActress(10L, "Mana Sakura", false);
        insertTitle(1L, 10L);
        linkActress(1L, 10L);

        List<String> names = jdbi.withHandle(h ->
                StagingCastHelper.orderedNamesForTitle(h, 1L));
        assertEquals(List.of("Mana Sakura"), names);
    }
}
