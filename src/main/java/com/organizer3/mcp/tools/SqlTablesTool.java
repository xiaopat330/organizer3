package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.ReadOnlyDb;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * List every user table in the SQLite database with an approximate row count.
 *
 * <p>Uses {@code sqlite_schema} (the modern alias for {@code sqlite_master}) filtered
 * to {@code type = 'table'}, excluding the internal {@code sqlite_%} tables. Row counts
 * are computed with one {@code SELECT COUNT(*)} per table — cheap on SQLite, but if
 * the DB ever grows huge we can switch to {@code sqlite_stat1} estimates.
 */
public class SqlTablesTool implements Tool {

    private final ReadOnlyDb db;

    public SqlTablesTool(ReadOnlyDb db) { this.db = db; }

    @Override public String name()        { return "sql_tables"; }
    @Override public String description() { return "List all user tables with row counts."; }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        try (Statement stmt = db.connection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_schema WHERE type = 'table' "
                   + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long count = countRows(name);
                tables.add(new TableInfo(name, count));
            }
        }
        return new Result(tables.size(), tables);
    }

    private long countRows(String tableName) throws SQLException {
        // tableName comes from sqlite_schema, not user input — still quote it for safety.
        String quoted = "\"" + tableName.replace("\"", "\"\"") + "\"";
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM " + quoted);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public record Result(int total, List<TableInfo> tables) {}
    public record TableInfo(String name, long rowCount) {}
}
