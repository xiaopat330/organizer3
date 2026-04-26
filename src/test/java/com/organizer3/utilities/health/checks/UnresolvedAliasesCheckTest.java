package com.organizer3.utilities.health.checks;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class UnresolvedAliasesCheckTest {

    private Connection connection;
    private Jdbi jdbi;
    private UnresolvedAliasesCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        check = new UnresolvedAliasesCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void reportsZeroWhenAllAliasesResolve() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses (id, canonical_name, tier, first_seen_at) VALUES (1, 'Alice', 'C', '2024-01-01')");
            h.execute("INSERT INTO actress_aliases (actress_id, alias_name) VALUES (1, 'ali')");
        });

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
    }

    /**
     * Dangling alias: alias row points to actress_id=99 but no actress with id=99 exists.
     * SQLite doesn't enforce FKs by default, so this happens in practice.
     */
    @Test
    void detectsDanglingAlias() {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO actress_aliases (actress_id, alias_name) VALUES (99, 'ghost')"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        assertEquals("ghost", result.rows().get(0).label());
        assertTrue(result.rows().get(0).detail().contains("99"));
    }

    /**
     * Rule 3 false-positive guard: an alias that does resolve must NOT be flagged,
     * even if other aliases in the table are dangling.
     */
    @Test
    void resolvedAliasIsNotFlaggedWhenOthersDangle() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses (id, canonical_name, tier, first_seen_at) VALUES (1, 'Alice', 'C', '2024-01-01')");
            h.execute("INSERT INTO actress_aliases (actress_id, alias_name) VALUES (1, 'ali')");
            h.execute("INSERT INTO actress_aliases (actress_id, alias_name) VALUES (99, 'ghost')");
        });

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total(), "only the dangling one should be flagged");
        assertEquals("ghost", result.rows().get(0).label());
    }
}
