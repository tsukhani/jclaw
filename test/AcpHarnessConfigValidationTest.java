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
 * JCLAW-659: the operator-facing config-key validation surface for the
 * {@code runtime:"acp"} external-harness path. A misconfigured harness id
 * ({@link SubagentSpawnTool#ACP_HARNESS_KEY}) or mode
 * ({@link SubagentSpawnTool#ACP_MODE_KEY}) must be rejected up front with a
 * clear, key-naming error rather than silently falling back to a default — and
 * crucially without spawning a run. {@code acpRuntimeError} performs this check
 * before any DB work, so the returned error string is itself proof no
 * {@link SubagentRun}, child Agent, or child Conversation was written; the
 * package-private validator isn't reachable from the default package, so the
 * checks are exercised through the public {@link SubagentSpawnTool#execute}
 * entry point.
 *
 * <p>The third case pins the no-regression contract: with mode unset (default
 * {@code batch}) a well-formed acp spawn still COMPLETES exactly as the existing
 * one-shot capture path, proving the new validation didn't perturb the default.
 * The harness stand-in is the same tiny POSIX marker+echo script the acp smoke
 * test uses, so a completed batch run's outcome is {@code MARKER + task}.
 */
class AcpHarnessConfigValidationTest extends UnitTest {

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
        harness = Files.createTempFile("jclaw-acp-cfg-harness-", ".sh");
        Files.writeString(harness, "#!/bin/sh\nprintf '" + MARKER + "'\ncat\n");
        harness.toFile().setExecutable(true, false);
    }

    @AfterEach
    void teardown() throws Exception {
        EventLogger.clear();
        if (harness != null) Files.deleteIfExists(harness);
    }

    @Test
    void rejectsUnknownHarness() throws Exception {
        // A working command (so the harness-command gate passes) but a bogus
        // harness id — validation must stop here, before the mode check.
        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        ConfigService.set(SubagentSpawnTool.ACP_HARNESS_KEY, "nope");
        var parent = grantedAgent("p-acp-bad-harness");
        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"reject-check\",\"runtime\":\"acp\"}");

        // acpRuntimeError returns a plain-text error before any DB work — a
        // returned error string (not a JSON status object) is proof no run,
        // child Agent, or child Conversation was written.
        assertTrue(reply.startsWith("Error:"),
                "an unknown harness id must yield a plain-text error, not a spawned run: " + reply);
        assertTrue(reply.contains(SubagentSpawnTool.ACP_HARNESS_KEY),
                "the error must name the misconfigured key subagent.acp.harness: " + reply);
        assertTrue(reply.contains("pi") && reply.contains("claude")
                        && reply.contains("codex") && reply.contains("gemini")
                        && reply.contains("opencode") && reply.contains("generic"),
                "the error must enumerate the allowed harness ids: " + reply);
    }

    @Test
    void rejectsUnknownMode() throws Exception {
        // Harness id left unset (defaults to generic, so the harness gate passes)
        // and a bogus mode — validation must reject on the mode check.
        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        ConfigService.set(SubagentSpawnTool.ACP_MODE_KEY, "nope");
        var parent = grantedAgent("p-acp-bad-mode");
        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"reject-check\",\"runtime\":\"acp\"}");

        assertTrue(reply.startsWith("Error:"),
                "an unknown mode must yield a plain-text error, not a spawned run: " + reply);
        assertTrue(reply.contains(SubagentSpawnTool.ACP_MODE_KEY),
                "the error must name the misconfigured key subagent.acp.mode: " + reply);
        assertTrue(reply.contains("batch") && reply.contains("json") && reply.contains("rpc"),
                "the error must enumerate the allowed modes: " + reply);
    }

    @Test
    void batchModeDefaultUnchanged() throws Exception {
        // No mode configured => default batch: a well-formed acp spawn must
        // COMPLETE via the one-shot capture path, unchanged by the new validation.
        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        var parent = grantedAgent("p-acp-default-batch");
        ConversationService.create(parent, "web", "u-acp-default-batch");
        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"batch-default\",\"runtime\":\"acp\"}");
        var json = JsonParser.parseString(reply).getAsJsonObject();

        assertEquals("COMPLETED", json.get("status").getAsString(),
                "default-mode (batch) acp spawn against a working harness must report COMPLETED");
        assertEquals(MARKER + "batch-default", json.get("reply").getAsString(),
                "batch is one-shot capture: the harness stdout (marker + task-from-stdin) is the child reply");

        long runId = Long.parseLong(json.get("run_id").getAsString());
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run, "the default-batch acp spawn must persist a SubagentRun row");
        assertEquals(SubagentRun.Status.COMPLETED, run.status,
                "the persisted default-batch acp run must be COMPLETED");
        assertEquals(MARKER + "batch-default", run.outcome,
                "COMPLETED batch run's outcome is the harness reply");
    }

    /** A non-main agent explicitly granted the acp capability. */
    private Agent grantedAgent(String name) {
        var agent = AgentService.create(name, "test-provider", "test-model");
        agent.enabled = true;
        agent.acpAllowed = true;
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
