package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.ReadOnlyDb;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Return the raw CREATE statement and column metadata for a single table or view.
 *
 * <p>Two ways to get schema information:
 * <ul>
 *   <li>{@code createSql} — the original CREATE TABLE/VIEW text from {@code sqlite_schema}</li>
 *   <li>{@code columns}   — driver-reported column list via {@code PRAGMA table_info}</li>
 * </ul>
 *
 * <p>Both are returned so the agent can pick whichever is easier to parse for its use case.
 */
public class SqlSchemaTool implements Tool {

    private final ReadOnlyDb db;

    public SqlSchemaTool(ReadOnlyDb db) { this.db = db; }

    @Override public String name()        { return "sql_schema"; }
    @Override public String description() { return "Return the CREATE statement and column list for a single table or view."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("table", "string", "Table or view name.")
                .require("table")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws SQLException {
        String table = Schemas.requireString(args, "table");
        validateIdent(table);

        String createSql;
        String type;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT type, sql FROM sqlite_schema WHERE name = ? AND type IN ('table', 'view')")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("No such table or view: " + table);
                type = rs.getString("type");
                createSql = rs.getString("sql");
            }
        }

        String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement("PRAGMA table_info(" + quoted + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                columns.add(new Column(
                        rs.getInt("cid"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getInt("notnull") != 0,
                        rs.getString("dflt_value"),
                        rs.getInt("pk") != 0
                ));
            }
        }

        return new SchemaResult(table, type, createSql, columns);
    }

    /** Reject anything that isn't a sane SQL identifier. */
    private static void validateIdent(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Empty table name");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                throw new IllegalArgumentException("Invalid character in table name: " + s);
            }
        }
    }

    public record SchemaResult(String name, String type, String createSql, List<Column> columns) {}

    public record Column(
            int cid,
            String name,
            String type,
            boolean notNull,
            String defaultValue,
            boolean primaryKey
    ) {}
}
