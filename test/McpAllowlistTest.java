import com.google.gson.JsonObject;
import mcp.McpAllowlist;
import mcp.McpToolDef;
import models.Agent;
import models.AgentSkillAllowedTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;

import java.util.List;

/**
 * Direct unit coverage of {@link McpAllowlist}: row writes/deletes against
 * the agent_skill_allowed_tool table and the isAllowed() gate. Wraps every
 * call in {@link Tx#run} since McpAllowlist is intentionally tx-agnostic.
 */
class McpAllowlistTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
    }

    // ==================== registerForAllAgents ====================

    @Test
    void registerForAllAgentsWritesOneRowPerAgentPerTool() {
        var agentIds = Tx.run(() -> {
            var a1 = newAgent("alpha");
            var a2 = newAgent("beta");
            return List.of(a1.id, a2.id);
        });
        var tools = List.of(toolDef("create_issue"), toolDef("close_issue"));

        var written = Tx.run(() -> McpAllowlist.registerForAllAgents("github", tools));
        assertEquals(2 * 2, written, "2 agents x 2 tools = 4 rows");

        Tx.run(() -> {
            var rowsByName = countRows("mcp:github");
            assertEquals(4, rowsByName);
            for (var aid : agentIds) {
                assertTrue(allowed(aid, "github", "create_issue"));
                assertTrue(allowed(aid, "github", "close_issue"));
            }
        });
    }

    @Test
    void registerIsIdempotentAndRefreshesShrinkingToolList() {
        Tx.run(() -> newAgent("alpha"));
        // First publish: 3 tools.
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc",
                List.of(toolDef("a"), toolDef("b"), toolDef("c"))));
        // Second publish: shrink to 1 tool — old rows must be gone.
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));
        Tx.run(() -> {
            assertEquals(1, countRows("mcp:svc"),
                    "shrinking the tool list must remove rows for tools no longer advertised");
        });
    }

    @Test
    void registerWithEmptyListClearsRows() {
        Tx.run(() -> newAgent("alpha"));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc",
                List.of(toolDef("x"), toolDef("y"))));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of()));
        Tx.run(() -> assertEquals(0, countRows("mcp:svc")));
    }

    // ==================== unregister ====================

    @Test
    void unregisterRemovesAllRowsForServerOnly() {
        Tx.run(() -> newAgent("alpha"));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc1", List.of(toolDef("a"))));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc2", List.of(toolDef("b"))));

        var deleted = Tx.run(() -> McpAllowlist.unregister("svc1"));
        assertEquals(1, deleted);
        Tx.run(() -> {
            assertEquals(0, countRows("mcp:svc1"));
            assertEquals(1, countRows("mcp:svc2"), "unrelated server's rows must be untouched");
        });
    }

    // ==================== isAllowed ====================

    @Test
    void isAllowedReturnsFalseForUnknownAgent() {
        Tx.run(() -> {
            var agent = newAgent("alpha");
            McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a")));
            // Synthetic agent with id never seen.
            var ghost = new Agent();
            ghost.id = 999_999L;
            assertFalse(McpAllowlist.isAllowed(ghost, "svc", "a"));
            assertTrue(McpAllowlist.isAllowed(agent, "svc", "a"));
        });
    }

    @Test
    void isAllowedReturnsFalseAfterUnregister() {
        var agentId = Tx.run(() -> newAgent("alpha").id);
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));
        assertTrue(Tx.run(() -> allowed(agentId, "svc", "a")));
        Tx.run(() -> McpAllowlist.unregister("svc"));
        assertFalse(Tx.run(() -> allowed(agentId, "svc", "a")));
    }

    @Test
    void isAllowedFalseForToolNotAdvertised() {
        var agentId = Tx.run(() -> newAgent("alpha").id);
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));
        assertFalse(Tx.run(() -> allowed(agentId, "svc", "not_advertised")));
    }

    // ==================== backfillForAgent ====================

    @Test
    void backfillForAgentSkippedWhenNoConnectedServers() {
        var agent = Tx.run(() -> newAgent("late"));
        var written = Tx.run(() -> McpAllowlist.backfillForAgent(agent));
        assertEquals(0, written);
    }

    // ==================== helpers ====================

    private Agent newAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = true;
        a.save();
        return a;
    }

    private static McpToolDef toolDef(String name) {
        return new McpToolDef(name, name + " desc", new JsonObject());
    }

    private static long countRows(String skillName) {
        return AgentSkillAllowedTool.count("skillName = ?1", skillName);
    }

    private static boolean allowed(Long agentId, String server, String tool) {
        Agent agent = Agent.findById(agentId);
        return McpAllowlist.isAllowed(agent, server, tool);
    }
}
