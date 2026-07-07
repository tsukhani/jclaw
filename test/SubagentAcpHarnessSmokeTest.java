import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-658 smoke test: the {@code runtime:"acp"} external-harness path end to
 * end. Tier 1 (Pi / Claude Code / Codex CLI) already ships via the existing acp
 * runtime; this exercises the operator-facing enablement flow — configure
 * {@link SubagentSpawnTool#ACP_COMMAND_KEY}, grant the agent {@code acpAllowed},
 * spawn with {@code runtime:"acp"} — against a real on-disk fake harness and
 * asserts a persisted {@code COMPLETED} {@link SubagentRun}.
 *
 * <p>The harness stand-in is a tiny POSIX shell script that copies its stdin
 * (the delivered task) to stdout behind a fixed marker, so the assertion proves
 * both directions of the seam: the task reaches the process on stdin, and the
 * process's stdout is captured back as the child reply. It is a genuine external
 * subprocess (distinct from {@code SubagentSpawnToolTest}'s {@code cat} unit
 * case, which asserts only the returned JSON) — the crux here is the DB row.
 */
class SubagentAcpHarnessSmokeTest extends UnitTest {

    private static final String MARKER = "HARNESS_SAW:";
    private Path harness;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        new jobs.ToolRegistrationJob().doJob();

        // Fake harness: emit a fixed marker, then echo stdin (the task) through.
        harness = Files.createTempFile("jclaw-acp-harness-", ".sh");
        Files.writeString(harness, "#!/bin/sh\nprintf '" + MARKER + "'\ncat\n");
        harness.toFile().setExecutable(true, false);
    }

    @AfterEach
    void teardown() throws Exception {
        EventLogger.clear();
        if (harness != null) Files.deleteIfExists(harness);
    }

    @Test
    void acpHarnessSpawnPersistsCompletedRun() throws Exception {
        // Operator setup: point the acp runtime at the harness and grant the
        // capability to this (non-main) agent.
        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        var parent = createAgent("p-acp-smoke");
        parent.acpAllowed = true;
        parent.save();
        ConversationService.create(parent, "web", "u-acp-smoke");
        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"smoke-check\",\"runtime\":\"acp\"}");
        var json = JsonParser.parseString(reply).getAsJsonObject();

        assertEquals("COMPLETED", json.get("status").getAsString(),
                "runtime=acp against a working harness must report COMPLETED");
        assertEquals(MARKER + "smoke-check", json.get("reply").getAsString(),
                "the harness stdout (marker + task-from-stdin) must be the child reply");

        // The crux: a terminal SubagentRun is persisted COMPLETED with the
        // harness output as its outcome.
        long runId = Long.parseLong(json.get("run_id").getAsString());
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run, "the acp spawn must persist a SubagentRun row");
        assertEquals(SubagentRun.Status.COMPLETED, run.status,
                "the persisted acp run must be COMPLETED");
        assertEquals(MARKER + "smoke-check", run.outcome,
                "COMPLETED acp run's outcome is the harness reply");
    }

    private Agent createAgent(String name) {
        var agent = AgentService.create(name, "test-provider", "test-model");
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** Commit pending setup rows so the VT-dispatched child run sees them. */
    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    /** Drive SubagentSpawnTool.execute on a VT so its child run observes
     *  committed rows through its own persistence context. */
    private String invokeOnVirtualThread(Long parentAgentId, String argsJson) throws Exception {
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentAgentId));
                var tool = ToolRegistry.lookupTool(SubagentSpawnTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, parent));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "acp spawn must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
