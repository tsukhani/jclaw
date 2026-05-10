package jobs;

import mcp.McpConnectionManager;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

/**
 * Boot all enabled MCP servers (JCLAW-31).
 *
 * <p>Runs once on application start, AFTER {@link ToolRegistrationJob} so
 * native tools are already published — MCP-discovered tools merge into the
 * registry on top. {@code @NoTransaction} because
 * {@link McpConnectionManager#startAll} opens its own short-lived
 * {@code Tx.run} to read the {@code mcp_server} rows; it doesn't want a
 * caller-supplied tx held open across the per-server connector spawn.
 */
@OnApplicationStart
@NoTransaction
public class McpStartupJob extends Job<Void> {

    @Override
    public void doJob() {
        try {
            McpConnectionManager.startAll();
            EventLogger.info("system",
                    "MCP startup complete (%d connections)".formatted(McpConnectionManager.connectionCount()));
        } catch (RuntimeException e) {
            EventLogger.error("system", "MCP startup failed: " + e.getMessage());
        }
    }
}
