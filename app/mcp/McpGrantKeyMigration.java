package mcp;

import models.AgentToolConfig;
import models.McpServer;
import play.db.DB;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One-time migration of per-agent MCP grants from name keying to id keying (JCLAW-983).
 *
 * <p>Grant rows used to address an MCP tool by its name, which is built from its server's
 * name. {@link AgentToolConfig} now addresses one by {@code (mcp_server_id, mcp_action)}
 * instead, so this resolves each existing {@code mcp_*} name back to the server it denotes
 * and rewrites the row. A name denoting no configured server is removed, not backfilled to
 * a null server: it granted nothing before this ran and would grant nothing after — it is
 * the residue of deletes and renames that predate JCLAW-982 (49,018 rows where this was
 * first measured, out of 139,687).
 *
 * <p>Idempotent: a backfilled row no longer carries a name to match, so a second run finds
 * nothing. Near-instant on a fresh install, where there is nothing to convert.
 */
public final class McpGrantKeyMigration {

    private McpGrantKeyMigration() {}

    /** @param backfilled rows re-keyed to a server id, {@code removed} rows naming no server */
    public record Result(int backfilled, int removed) {}

    /**
     * Startup entry point: relax the {@code tool_name} NOT NULL an existing database still
     * carries, then re-key every MCP grant. The DDL runs on its own committed connection
     * because the backfill's {@code tool_name = null} writes depend on it.
     *
     * @throws SQLException on any catalog read or ALTER failure
     */
    public static void run() throws SQLException {
        try (Connection conn = DB.getDataSource().getConnection()) {
            relaxNotNull(conn, "AGENT_TOOL_CONFIG", "TOOL_NAME");
            conn.commit();
        }
        var result = Tx.run(McpGrantKeyMigration::backfill);
        if (result.backfilled() > 0 || result.removed() > 0) {
            EventLogger.info("system",
                    "Re-keyed %d MCP grant row(s) by server id; removed %d naming no server (JCLAW-983)"
                            .formatted(result.backfilled(), result.removed()));
        }
    }

    /**
     * Re-key every grant row whose {@code tool_name} names an MCP tool, and drop those whose
     * name matches no configured server. Does not commit — the caller owns the transaction.
     */
    // Public because Play's tests live in the default package: a test drives the conversion
    // against a seeded fixture rather than against a boot.
    public static Result backfill() {
        // Longest name first: a server named `a` and one named `a_b` both claim `mcp_a_b_x`
        // by prefix, and the longer name is the more specific reading.
        var servers = McpServer.<McpServer>findAll().stream()
                .sorted(Comparator.comparingInt((McpServer s) -> s.name.length()).reversed())
                .toList();

        var orphans = new ArrayList<String>();
        int backfilled = 0;
        for (var name : mcpToolNames()) {
            var server = servers.stream().filter(s -> belongsTo(name, s.name)).findFirst().orElse(null);
            if (server == null) {
                orphans.add(name);
                continue;
            }
            backfilled += JPA.em().createQuery(
                            "update AgentToolConfig c set c.mcpServer = :server, c.mcpAction = :action, "
                                    + "c.toolName = null where c.toolName = :name")
                    .setParameter("server", server)
                    .setParameter("action", McpGrants.actionOf(name, server.name))
                    .setParameter("name", name)
                    .executeUpdate();
        }
        return new Result(backfilled, deleteByToolName(orphans));
    }

    /** True when {@code toolName} is {@code serverName}'s handle or one of its actions. */
    private static boolean belongsTo(String toolName, String serverName) {
        var handle = McpServer.toolName(serverName, "");
        return toolName.equals(handle) || toolName.startsWith(handle + "_");
    }

    private static List<String> mcpToolNames() {
        return JPA.em().createQuery(
                        "select distinct c.toolName from AgentToolConfig c "
                                + "where c.toolName like 'mcp!_%' escape '!'",
                        String.class)
                .getResultList();
    }

    /** Chunked because a single server can span every agent — 489 here, 280 actions each. */
    private static int deleteByToolName(List<String> toolNames) {
        int n = 0;
        for (int i = 0; i < toolNames.size(); i += 200) {
            var chunk = toolNames.subList(i, Math.min(i + 200, toolNames.size()));
            n += JPA.em().createQuery("delete from AgentToolConfig c where c.toolName in :names")
                    .setParameter("names", chunk)
                    .executeUpdate();
        }
        return n;
    }

    /**
     * Drop {@code table.column}'s NOT NULL when the catalog still reports it, so a row that
     * addresses its tool by server id can leave the name empty. No-op on a database whose
     * column is already nullable — a fresh install, where Hibernate created it from the
     * entity. Does not commit.
     *
     * @return whether the ALTER ran
     */
    // Public because Play's tests live in the default package: a test drives it against its
    // own throwaway schema rather than against the live one.
    public static boolean relaxNotNull(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement lookup = conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE UPPER(table_name) = ? AND UPPER(column_name) = ?")) {
            lookup.setString(1, table.toUpperCase(Locale.ROOT));
            lookup.setString(2, column.toUpperCase(Locale.ROOT));
            try (ResultSet rs = lookup.executeQuery()) {
                if (!rs.next() || "YES".equalsIgnoreCase(rs.getString(1))) return false;
            }
        }
        // H2 and PostgreSQL share the catalog views but not this ALTER.
        boolean postgres = conn.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("postgresql");
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE " + table + " ALTER COLUMN " + column
                    + (postgres ? " DROP NOT NULL" : " SET NULL"));
        }
        return true;
    }
}
