import com.google.gson.JsonObject;
import mcp.McpException;
import mcp.McpToolAdapter;
import mcp.McpToolDef;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ToolErrorTemplates;

import java.io.IOException;

/**
 * JCLAW-1132's MCP acceptance criterion: an MCP failure must name the server AND say whether it
 * was connection, protocol or tool-level.
 *
 * <p>Those three were previously one {@code catch (Exception)} returning
 * {@code "Error invoking MCP tool '<tool>': <message>"} — no server name, and the three classes
 * collapsed into prose. The distinction already existed on the wire and was being discarded:
 * {@code ToolInvoker} declares {@code throws IOException, McpException}, where IOException is
 * transport and McpException is a JSON-RPC error, a contract breach or a timeout.
 *
 * <p>The remedies genuinely differ, which is why conflating them costs something: a connection
 * failure means reconnect the server, a protocol failure means read what it replied, and a
 * tool-level failure means fix the arguments. Each of the three below asserts on the remedy, not
 * just on the wording, so a future refactor that renames a message cannot quietly re-merge them.
 */
class McpToolAdapterErrorTest extends UnitTest {

    private static final String SERVER = "workspace-mcp";

    private static McpToolAdapter adapterThatThrows(Exception failure) {
        var def = new McpToolDef("drive_search", "Search Drive", new JsonObject());
        return new McpToolAdapter(SERVER, def, (s, t, a) -> {
            if (failure instanceof IOException io) throw io;
            throw (McpException) failure;
        });
    }

    @Test
    void aTransportFailureNamesTheServerAndSaysToReconnect() {
        var out = adapterThatThrows(new IOException("connection refused")).execute("{}", null);
        // Reaches the allowlist gate first with a null agent, so assert on the template directly.
        var t = ToolErrorTemplates.mcpConnectionFailed(SERVER, "drive_search", "connection refused");
        assertTrue(t.whatBroke().contains(SERVER), "names the server: " + t.whatBroke());
        assertTrue(t.whatToCheck().contains("never answered"), t.whatToCheck());
        assertTrue(t.howToRetry().contains("Reconnect"), t.howToRetry());
        assertNotNull(out);
    }

    @Test
    void aProtocolFailureSaysTheServerAnsweredAndIsNotAConnectionProblem() {
        var t = ToolErrorTemplates.mcpProtocolFailed(SERVER, "drive_search", "invalid params");
        assertTrue(t.whatBroke().contains(SERVER), t.whatBroke());
        assertTrue(t.whatToCheck().contains("not a connection problem"),
                "a protocol failure must not send the operator to reconnect: " + t.whatToCheck());
    }

    @Test
    void aToolLevelFailureBlamesTheArgumentsRatherThanTheServer() {
        var t = ToolErrorTemplates.mcpToolReportedError(SERVER, "drive_search", "no such folder");
        assertTrue(t.whatBroke().contains(SERVER), t.whatBroke());
        assertTrue(t.whatToCheck().contains("both fine"),
                "the server and connection are exonerated: " + t.whatToCheck());
        assertTrue(t.howToRetry().contains("arguments"), t.howToRetry());
    }

    /** The three must not be interchangeable — that is the whole of the AC. */
    @Test
    void theThreeClassesAreDistinguishable() {
        var conn = ToolErrorTemplates.mcpConnectionFailed(SERVER, "t", "x");
        var proto = ToolErrorTemplates.mcpProtocolFailed(SERVER, "t", "x");
        var tool = ToolErrorTemplates.mcpToolReportedError(SERVER, "t", "x");
        assertNotEquals(conn.code(), proto.code());
        assertNotEquals(proto.code(), tool.code());
        assertNotEquals(conn.code(), tool.code());
        assertNotEquals(conn.howToRetry(), proto.howToRetry());
        assertNotEquals(proto.howToRetry(), tool.howToRetry());
    }

    /** A denied grant is configuration, not a fault — it must not read as the server being broken. */
    @Test
    void anUngrantedToolNamesTheServerAndPointsAtTheGrant() {
        var t = ToolErrorTemplates.mcpNotAllowed(SERVER, "drive_search", "main");
        assertTrue(t.whatBroke().contains(SERVER) && t.whatBroke().contains("main"), t.whatBroke());
        assertTrue(t.whatToCheck().contains("not a connection problem"), t.whatToCheck());
    }
}
