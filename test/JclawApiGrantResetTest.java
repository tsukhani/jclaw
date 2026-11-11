import agents.ToolRegistry;
import com.google.gson.JsonParser;
import jobs.JclawApiGrantResetJob;
import models.Agent;
import models.AgentToolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The step that removes every {@code jclaw_api} row of an agent other than main, once per install (JF-293). A row
 * saved while the tool was main's alone was ignored, and must not switch the tool on now that a row does.
 *
 * <p>Main's half is read off {@link JclawApiGrantResetJob#removes}, the choice the step makes for each row, on an
 * unsaved agent: {@code main} is provisioned by code and no test stores or deletes it.
 */
class JclawApiGrantResetTest extends FunctionalTest {

    private static final String JCLAW_API = "jclaw_api";
    private static final String EXEC = "exec";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        clearMarker();
        ToolRegistry.clearDisabledToolsCache();
    }

    // deleteDatabase empties the config table and leaves ConfigService's cache, which would still answer "applied".
    @AfterEach
    void clearMarker() {
        commitInFreshTx(() -> {
            ConfigService.delete(JclawApiGrantResetJob.APPLIED_KEY);
            return null;
        });
    }

    /** Runs {@code block} in its own committed transaction: the body is already in one, which {@code Tx.run} joins. */
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static Long createAgent(String name) {
        return commitInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1").id);
    }

    /** A row written behind the route, as a grant from before the opt-in was. */
    private static void saveRow(Long agentId, String tool, boolean enabled) {
        commitInFreshTx(() -> {
            var row = mcp.McpGrants.newRow(Agent.findById(agentId), tool);
            row.enabled = enabled;
            return row.save();
        });
    }

    /** The step as a start runs it, with the events it logged. */
    private static List<EventLogger.Captured> runStep() {
        return commitInFreshTx(() -> EventLogger.captureForTest(JclawApiGrantResetJob::removeOnce));
    }

    private static String marker() {
        return commitInFreshTx(() -> ConfigService.get(JclawApiGrantResetJob.APPLIED_KEY));
    }

    /** Every stored tool row as {@code agent/tool=enabled}, sorted. */
    private static List<String> storedRows() {
        return commitInFreshTx(() -> {
            List<AgentToolConfig> rows = AgentToolConfig.findAll();
            return rows.stream().map(r -> r.agent.name + "/" + r.handle() + "=" + r.enabled).sorted().toList();
        });
    }

    private static Set<String> disabledFor(Long agentId) {
        return commitInFreshTx(() -> new TreeSet<>(ToolRegistry.loadDisabledTools(Agent.findById(agentId))));
    }

    /** The tools a turn of the agent may be offered. */
    private static Set<String> offeredTo(Long agentId) {
        return commitInFreshTx(() -> {
            var names = new TreeSet<String>();
            for (var def : ToolRegistry.getToolDefsForAgent(Agent.findById(agentId))) names.add(def.function().name());
            return names;
        });
    }

    /** Whether the agent's own tool list shows {@code tool} switched on; null when it is not listed. */
    private Boolean enabledInList(Long agentId, String tool) {
        for (var row : JsonParser.parseString(getContent(GET("/api/agents/" + agentId + "/tools"))).getAsJsonArray()) {
            var o = row.getAsJsonObject();
            if (tool.equals(o.get("name").getAsString())) return o.get("enabled").getAsBoolean();
        }
        return null;
    }

    private static Set<String> with(Set<String> names, String name) {
        var out = new TreeSet<>(names);
        out.add(name);
        return out;
    }

    private static Set<String> without(Set<String> names, String name) {
        var out = new TreeSet<>(names);
        out.remove(name);
        return out;
    }

    private static List<String> without(List<String> rows, String... gone) {
        return rows.stream().filter(r -> !List.of(gone).contains(r)).toList();
    }

    @Test
    void aGrantSavedBeforeTheOptInIsRemovedAndThatAgentIsNotOfferedJclawApi() {
        login();
        var id = createAgent("grant-reset-old");
        var neverGrantedDisabled = disabledFor(id);
        var neverGrantedOffered = offeredTo(id);
        assertEquals(true, neverGrantedDisabled.contains(JCLAW_API), "premise: off for an agent never granted it");
        saveRow(id, JCLAW_API, true);
        var before = storedRows();
        ToolRegistry.clearDisabledToolsCache();
        // Read here, so the answer the step must not leave behind is the cached one.
        assertEquals(without(neverGrantedDisabled, JCLAW_API), disabledFor(id), "premise: an enabled row switches it on");
        assertEquals(with(neverGrantedOffered, JCLAW_API), offeredTo(id));

        var events = runStep();

        assertEquals(without(before, "grant-reset-old/jclaw_api=true"), storedRows());
        assertEquals(neverGrantedDisabled, disabledFor(id));
        assertEquals(neverGrantedOffered, offeredTo(id));
        assertEquals(false, enabledInList(id, JCLAW_API));
        assertEquals("true", marker());
        assertEquals(List.of("INFO|system|Removed the jclaw_api grant of 1 agent(s) (grant-reset-old), saved before "
                        + "the tool became opt-in: switch it on again under Tools on the agent's page"),
                events.stream().map(e -> e.level() + "|" + e.category() + "|" + e.message()).toList());
    }

    @Test
    void theStepRemovesEveryJclawApiRowSwitchedOnOrOffAndNoOtherToolsRow() {
        var parentId = createAgent("grant-reset-parent");
        var otherId = createAgent("grant-reset-other");
        var childId = commitInFreshTx(() -> AgentService.create("grant-reset-child", "openrouter", "gpt-4.1",
                null, null, false, Agent.findById(parentId)).id);
        saveRow(parentId, JCLAW_API, true);
        saveRow(parentId, EXEC, true);
        saveRow(otherId, JCLAW_API, false);
        saveRow(otherId, EXEC, false);
        saveRow(childId, JCLAW_API, true);
        var before = storedRows();
        assertEquals(true, before.containsAll(List.of("grant-reset-parent/jclaw_api=true", "grant-reset-parent/exec=true",
                "grant-reset-other/jclaw_api=false", "grant-reset-other/exec=false", "grant-reset-child/jclaw_api=true")),
                "premise: the five rows are stored: " + before);

        var events = runStep();

        assertEquals(without(before, "grant-reset-parent/jclaw_api=true", "grant-reset-other/jclaw_api=false",
                "grant-reset-child/jclaw_api=true"), storedRows());
        // A row that was switched off changed nothing, so the line names only the agents that lost the tool.
        assertEquals(List.of("Removed the jclaw_api grant of 2 agent(s) (grant-reset-child, grant-reset-parent), saved "
                        + "before the tool became opt-in: switch it on again under Tools on the agent's page"),
                events.stream().map(EventLogger.Captured::message).toList());
    }

    @Test
    void aGrantTheOperatorMakesAfterTheStepSurvivesEveryLaterStart() {
        login();
        var id = createAgent("grant-reset-new");
        var neverGrantedDisabled = disabledFor(id);
        runStep();
        assertEquals("true", marker(), "premise: the first start ran the step");

        assertIsOk(PUT("/api/agents/" + id + "/tools/jclaw_api", "application/json", "{\"enabled\":true}"));
        var granted = storedRows();
        assertEquals(true, granted.contains("grant-reset-new/jclaw_api=true"), "premise: the operator's grant is stored");

        var secondStart = runStep();
        var thirdStart = runStep();

        assertEquals(granted, storedRows());
        assertEquals(without(neverGrantedDisabled, JCLAW_API), disabledFor(id));
        assertEquals(true, enabledInList(id, JCLAW_API));
        assertEquals(List.of(), secondStart);
        assertEquals(List.of(), thirdStart);
    }

    @Test
    void aStartWithNoJclawApiRowsWritesTheMarkerRemovesNothingAndLogsNothing() {
        var id = createAgent("grant-reset-none");
        saveRow(id, EXEC, true);
        var before = storedRows();
        assertNull(marker(), "premise: no start has run the step");

        var events = runStep();

        assertEquals(before, storedRows());
        assertEquals("true", marker());
        assertEquals(List.of(), events);
    }

    @Test
    void theStepKeepsMainsRowAndRemovesEveryOtherAgents() {
        assertEquals(false, JclawApiGrantResetJob.removes(row(Agent.MAIN_AGENT_NAME, JCLAW_API, true)));
        assertEquals(false, JclawApiGrantResetJob.removes(row(Agent.MAIN_AGENT_NAME, JCLAW_API, false)));
        // Agent.isMain compares the name without regard to case.
        assertEquals(false, JclawApiGrantResetJob.removes(row("Main", JCLAW_API, true)));
        assertEquals(true, JclawApiGrantResetJob.removes(row("grant-reset-unsaved", JCLAW_API, true)));
        assertEquals(true, JclawApiGrantResetJob.removes(row("grant-reset-unsaved", JCLAW_API, false)));
        assertEquals(true, JclawApiGrantResetJob.removes(row(Agent.EVALTEST_AGENT_NAME, JCLAW_API, true)));
        assertEquals(true, JclawApiGrantResetJob.removes(row("mainly", JCLAW_API, true)));
        assertEquals(false, JclawApiGrantResetJob.removes(row("grant-reset-unsaved", EXEC, true)));
        // An MCP row names its server and holds no tool name.
        assertEquals(false, JclawApiGrantResetJob.removes(row("grant-reset-unsaved", null, true)));
    }

    private static AgentToolConfig row(String agentName, String tool, boolean enabled) {
        var agent = new Agent();
        agent.name = agentName;
        var row = new AgentToolConfig();
        row.agent = agent;
        row.toolName = tool;
        row.enabled = enabled;
        return row;
    }
}
