package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.ReadOnlyDb;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Execute a read-only SQL query against the SQLite database.
 *
 * <p>Safety model (layered):
 * <ol>
 *   <li>Physical: executes on a connection opened with {@code mode=ro}; writes fail at
 *       the driver layer regardless of what the statement says.</li>
 *   <li>Single statement only — no {@code ;}-chaining.</li>
 *   <li>Leading keyword must be {@code SELECT}, {@code WITH}, {@code EXPLAIN}, or {@code PRAGMA}.
 *       {@code PRAGMA} is further restricted to an allowlist of introspection pragmas.</li>
 *   <li>LIMIT auto-appended when absent, clamped to a maximum.</li>
 *   <li>Query timeout (seconds) applied via {@link Statement#setQueryTimeout}.</li>
 *   <li>Response size cap — query stops emitting rows past a byte budget and sets
 *       {@code truncated: true}.</li>
 * </ol>
 */
public class SqlQueryTool implements Tool {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT     = 5000;
    private static final int QUERY_TIMEOUT_SEC = 10;
    private static final int MAX_RESPONSE_BYTES = 1_000_000;

    private static final Set<String> ALLOWED_LEADING = Set.of(
            "SELECT", "WITH", "EXPLAIN", "PRAGMA"
    );

    /** Pragmas that are pure introspection — no side effects, no mutation. */
    private static final Set<String> ALLOWED_PRAGMAS = Set.of(
            "table_info",
            "table_list",
            "index_list",
            "index_info",
            "foreign_key_list",
            "database_list"
    );

    private final ReadOnlyDb db;

    public SqlQueryTool(ReadOnlyDb db) { this.db = db; }

    @Override public String name()        { return "sql_query"; }
    @Override public String description() { return "Run a read-only SQL query against the SQLite database. Allowed leading keywords: SELECT, WITH, EXPLAIN, PRAGMA (introspection only). Single statement. LIMIT auto-applied."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("sql",   "string",  "SQL to execute. Single statement. Must start with SELECT/WITH/EXPLAIN/PRAGMA.")
                .prop("limit", "integer", "Max rows to return (default 500, max 5000). Applied via injected LIMIT when the SQL doesn't already have one.", DEFAULT_LIMIT)
                .require("sql")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws SQLException {
        String sql = Schemas.requireString(args, "sql").trim();
        int requestedLimit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        sql = stripTrailingSemicolon(sql);
        rejectMultipleStatements(sql);
        String leading = leadingKeyword(sql);
        enforceLeadingKeyword(leading, sql);
        String finalSql = applyLimitIfMissing(sql, leading, requestedLimit);

        try (Statement stmt = db.connection().createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SEC);
            long startNanos = System.nanoTime();
            try (ResultSet rs = stmt.executeQuery(finalSql)) {
                return readResult(rs, finalSql, requestedLimit, startNanos);
            }
        }
    }

    // ── SQL sanitization ────────────────────────────────────────────────────

    private static String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).stripTrailing();
        return s;
    }

    /** Reject any semicolon that's not inside a quoted string. */
    private static void rejectMultipleStatements(String sql) {
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') { quote = c; }
                else if (c == ';') {
                    throw new IllegalArgumentException("Multiple statements are not allowed");
                }
            } else {
                if (c == quote) {
                    // handle doubled quote escape
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { i++; }
                    else { quote = 0; }
                }
            }
        }
    }

    private static String leadingKeyword(String sql) {
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) i++;
        int start = i;
        while (i < sql.length() && !Character.isWhitespace(sql.charAt(i))) i++;
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static void enforceLeadingKeyword(String leading, String sql) {
        if (!ALLOWED_LEADING.contains(leading)) {
            throw new IllegalArgumentException(
                    "Statement must start with one of " + ALLOWED_LEADING + " (got: " + leading + ")");
        }
        if ("PRAGMA".equals(leading)) {
            // Use the already-matched leading length rather than re-indexing with a literal —
            // the user's `sql` may have been written in lowercase.
            int afterLeading = sql.toUpperCase(Locale.ROOT).indexOf(leading) + leading.length();
            String rest = sql.substring(afterLeading).stripLeading();
            int end = 0;
            while (end < rest.length()) {
                char c = rest.charAt(end);
                if (Character.isLetterOrDigit(c) || c == '_') end++;
                else break;
            }
            String pragmaName = rest.substring(0, end).toLowerCase(Locale.ROOT);
            if (!ALLOWED_PRAGMAS.contains(pragmaName)) {
                throw new IllegalArgumentException(
                        "PRAGMA '" + pragmaName + "' not allowed. Allowed: " + ALLOWED_PRAGMAS);
            }
        }
    }

    private static String applyLimitIfMissing(String sql, String leading, int limit) {
        if (!"SELECT".equals(leading) && !"WITH".equals(leading)) return sql;
        String upper = sql.toUpperCase(Locale.ROOT);
        // Naive but sufficient: if " LIMIT " appears anywhere outside a quoted region, assume the user provided one.
        if (containsOutsideQuotes(upper, " LIMIT ")) return sql;
        return sql + " LIMIT " + limit;
    }

    private static boolean containsOutsideQuotes(String sql, String needle) {
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') { quote = c; continue; }
                if (i + needle.length() <= sql.length()
                        && sql.regionMatches(i, needle, 0, needle.length())) {
                    return true;
                }
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { i++; }
                else { quote = 0; }
            }
        }
        return false;
    }

    // ── Result reading ──────────────────────────────────────────────────────

    private QueryResult readResult(ResultSet rs, String finalSql, int requestedLimit, long startNanos) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<String> columnNames = new ArrayList<>(cols);
        for (int i = 1; i <= cols; i++) columnNames.add(md.getColumnLabel(i));

        List<List<Object>> rows = new ArrayList<>();
        long approxBytes = 0;
        boolean truncatedBySize = false;
        while (rs.next()) {
            List<Object> row = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
                Object v = rs.getObject(i);
                row.add(normalize(v));
                approxBytes += (v == null ? 4 : v.toString().length() + 2);
            }
            rows.add(row);
            if (approxBytes > MAX_RESPONSE_BYTES) {
                truncatedBySize = true;
                break;
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new QueryResult(
                columnNames,
                rows,
                rows.size(),
                truncatedBySize,
                elapsedMs,
                finalSql
        );
    }

    /**
     * Normalize JDBC values into JSON-friendly types. {@code BigDecimal} for example
     * serializes to a string in Jackson by default with some configs — force to double.
     */
    private static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        if (v instanceof byte[] bytes)  return "<" + bytes.length + " bytes>";
        return v;
    }

    public record QueryResult(
            List<String> columns,
            List<List<Object>> rows,
            int rowCount,
            boolean truncated,
            long elapsedMs,
            String executedSql
    ) {}
}
