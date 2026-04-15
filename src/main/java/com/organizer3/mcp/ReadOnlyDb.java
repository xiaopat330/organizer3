package com.organizer3.mcp;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Holds a single dedicated read-only JDBC handle to the SQLite database.
 *
 * <p>The handle is opened with {@code mode=ro} in the JDBC URL, which causes the
 * SQLite driver to open the underlying file as read-only. Any attempt to execute
 * an INSERT/UPDATE/DELETE/DDL through this connection fails at the driver layer —
 * this is the primary safety net for the {@code sql_query} tool. The other filters
 * (leading-keyword allowlist, single-statement, LIMIT clamp) are ergonomic rather
 * than security-critical.
 *
 * <p>SQLite WAL mode permits a read-only connection to coexist with the app's
 * writer connection (the shared {@code Jdbi}) without blocking.
 *
 * <p>One connection is reused across all MCP requests. Connections are thread-safe
 * for concurrent reads via the driver's serialized access mode (the default on
 * macOS SQLite builds). If that assumption changes, wrap query execution in a
 * synchronized block.
 */
@Slf4j
public class ReadOnlyDb implements AutoCloseable {

    private final Connection connection;

    public ReadOnlyDb(java.nio.file.Path dbPath) {
        String url = "jdbc:sqlite:file:" + dbPath.toAbsolutePath() + "?mode=ro";
        try {
            this.connection = DriverManager.getConnection(url);
            log.info("MCP read-only DB handle opened: {}", url);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open read-only SQLite connection at " + dbPath, e);
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close MCP read-only DB handle: {}", e.getMessage());
        }
    }
}
