package jclaw.mcp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the JClaw MCP server (JCLAW-282).
 *
 * <p>Three-step boot:
 * <ol>
 *   <li>Parse CLI / env config.</li>
 *   <li>Fetch + parse the OpenAPI spec from the configured JClaw
 *       instance, generate the tool catalog.</li>
 *   <li>Run the JSON-RPC server over stdio until the host disconnects.</li>
 * </ol>
 *
 * <p>Any pre-boot failure prints a usage-style message on stderr and
 * exits non-zero so the operator's MCP host (Claude Desktop, Cursor)
 * surfaces it visibly rather than just hanging the connection.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] argv) {
        Config config;
        try {
            config = Config.parse(argv);
        }
        catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        try {
            run(config);
        }
        catch (Exception e) {
            log.error("JClaw MCP server failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    static void run(Config config) throws Exception {
        var http = new JClawHttp(config);
        log.info("JClaw MCP server starting; base-url={} scope={}",
                config.baseUrl(), config.scope());

        var spec = new OpenApiCatalog(http).fetch();
        var tools = new ToolGenerator(config).generate(spec);
        if (tools.isEmpty()) {
            // Cleaner to fail loud here than to accept an MCP connection
            // that advertises zero tools — the host will assume the
            // server is broken and the operator's first symptom will be
            // "Claude Desktop shows no tools" with no log to pin it on.
            throw new IllegalStateException(
                    "No tools generated — check OpenAPI spec at "
                            + config.baseUrl() + " and the --scope / --exclude flags");
        }

        try (var transport = new StdioTransport()) {
            var invoker = new ToolInvoker(http);
            var server = new McpServer(transport, tools, invoker);

            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "mcp-shutdown"));
            server.run();
        }
    }
}
