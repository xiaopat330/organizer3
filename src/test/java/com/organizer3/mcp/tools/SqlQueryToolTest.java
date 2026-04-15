package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.mcp.ReadOnlyDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SqlQueryTool}. Uses an on-disk (temp) SQLite file because the RO handle
 * is opened via a {@code mode=ro} URL against a file path — there's no meaningful
 * "read-only memory" analog.
 */
class SqlQueryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path dbFile;
    private ReadOnlyDb roDb;
    private SqlQueryTool tool;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("mcp-sqltest-", ".db");
        // Seed with a writable connection, then hand RO access to the tool.
        try (Connection rw = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = rw.createStatement()) {
            st.executeUpdate("CREATE TABLE widgets (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("INSERT INTO widgets (name) VALUES ('alpha'), ('bravo'), ('charlie')");
        }
        roDb = new ReadOnlyDb(dbFile);
        tool = new SqlQueryTool(roDb);
    }

    @AfterEach
    void tearDown() throws Exception {
        roDb.close();
        Files.deleteIfExists(dbFile);
    }

    @Test
    void selectReturnsRows() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("SELECT id, name FROM widgets ORDER BY id"));
        assertEquals(3, r.rowCount());
        assertEquals(java.util.List.of("id", "name"), r.columns());
        assertEquals("alpha",   r.rows().get(0).get(1));
        assertEquals("charlie", r.rows().get(2).get(1));
        assertFalse(r.truncated());
    }

    @Test
    void autoAppendsLimitWhenMissing() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("SELECT * FROM widgets", 2));
        assertEquals(2, r.rowCount());
        assertTrue(r.executedSql().toUpperCase().contains("LIMIT 2"));
    }

    @Test
    void preservesUserProvidedLimit() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("SELECT * FROM widgets LIMIT 1", 500));
        assertEquals(1, r.rowCount());
        // Should not inject another LIMIT
        assertEquals(1, countOccurrences(r.executedSql().toUpperCase(), "LIMIT"));
    }

    @Test
    void rejectsUpdateStatement() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("UPDATE widgets SET name = 'x' WHERE id = 1")));
    }

    @Test
    void rejectsInsertStatement() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("INSERT INTO widgets (name) VALUES ('x')")));
    }

    @Test
    void rejectsDropTable() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("DROP TABLE widgets")));
    }

    @Test
    void rejectsMultipleStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("SELECT 1; SELECT 2")));
    }

    @Test
    void allowsTrailingSemicolon() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("SELECT 1;"));
        assertEquals(1, r.rowCount());
    }

    @Test
    void allowsWithCte() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("WITH x AS (SELECT 1 AS n) SELECT n FROM x"));
        assertEquals(1, r.rowCount());
    }

    @Test
    void allowsAllowlistedPragma() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("PRAGMA table_info(widgets)"));
        assertTrue(r.rowCount() >= 2);
    }

    @Test
    void allowsLowercasePragma() throws Exception {
        SqlQueryTool.QueryResult r = (SqlQueryTool.QueryResult)
                tool.call(args("pragma table_info(widgets)"));
        assertTrue(r.rowCount() >= 2);
    }

    @Test
    void rejectsDisallowedPragma() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("PRAGMA writable_schema = 1")));
    }

    /**
     * Belt-and-braces check that the read-only connection itself blocks writes even if
     * the statement filter were bypassed. Uses the raw JDBC handle directly.
     */
    @Test
    void readOnlyHandleBlocksWritesAtDriverLayer() {
        try (Statement s = roDb.connection().createStatement()) {
            assertThrows(java.sql.SQLException.class,
                    () -> s.executeUpdate("INSERT INTO widgets (name) VALUES ('x')"));
        } catch (java.sql.SQLException e) {
            fail("createStatement itself shouldn't fail: " + e.getMessage());
        }
    }

    private static ObjectNode args(String sql) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("sql", sql);
        return n;
    }

    private static ObjectNode args(String sql, int limit) {
        ObjectNode n = args(sql);
        n.put("limit", limit);
        return n;
    }

    private static int countOccurrences(String s, String needle) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }
}
