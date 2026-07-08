import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-657 (finding A): the {@code runtime:"acp"} harness runs in a confined
 * working directory, not the backend's CWD (the repo root). A configured
 * {@link SubagentSpawnTool#ACP_WORKDIR_KEY} wins; when unset the harness runs
 * under the child agent's own workspace tree.
 *
 * <p>The harness stand-in is a POSIX shell script that discards the delivered
 * task on stdin and prints its own working directory with {@code pwd}. With the
 * generic adapter in streaming mode each line is a step, so the run's reply is
 * the harness CWD — which the assertions match against the expected directory.
 */
class AcpWorkdirTest extends UnitTest {

    private Path harness;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        new jobs.ToolRegistrationJob().doJob();

        harness = Files.createTempFile("jclaw-acp-pwd-", ".sh");
        Files.writeString(harness, "#!/bin/sh\ncat >/dev/null 2>&1\npwd\n");
        harness.toFile().setExecutable(true, false);

        // Stream the generic adapter (one step per line) so the reply is the pwd.
        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        ConfigService.set(SubagentSpawnTool.ACP_HARNESS_KEY, "generic");
        ConfigService.set(SubagentSpawnTool.ACP_MODE_KEY, "json");
    }

    @AfterEach
    void teardown() throws Exception {
        EventLogger.clear();
        if (harness != null) Files.deleteIfExists(harness);
    }

    @Test
    void configuredWorkdirConfinesTheHarness() throws Exception {
        Path workdir = Files.createTempDirectory("jclaw-acp-wd-");
        ConfigService.set(SubagentSpawnTool.ACP_WORKDIR_KEY, workdir.toString());

        var reply = spawnAcp("workdir-override");

        var leaf = workdir.getFileName().toString();
        assertTrue(reply.contains(leaf),
                "the harness pwd (%s) must be the configured workdir (…/%s)".formatted(reply, leaf));
        Files.deleteIfExists(workdir);
    }

    @Test
    void defaultWorkdirIsUnderTheAgentWorkspace() throws Exception {
        // No ACP_WORKDIR_KEY set — the harness must run under the workspace tree,
        // NOT the backend CWD (the repo root), so a real harness's writes are scoped.
        var reply = spawnAcp("workdir-default");

        var rootLeaf = AgentService.workspaceRoot().getFileName().toString();
        assertTrue(reply.contains(File.separator + rootLeaf + File.separator),
                "the harness pwd (%s) must sit under the agent workspace tree (…/%s/…)"
                        .formatted(reply, rootLeaf));
        // JCLAW-666: sessions land in a per-task directory under coding/.
        assertTrue(reply.contains(File.separator + "coding" + File.separator + "where-are-you"),
                "the harness pwd (%s) must be the per-session coding/<slug> directory"
                        .formatted(reply));
    }

    @Test
    void codingSlugIsDeterministicFilenameSafeAndBounded() {
        assertEquals("create-fibonacci-program",
                SubagentSpawnTool.codingSlug("Create Fibonacci program!"));
        assertEquals("session", SubagentSpawnTool.codingSlug("!!!"));
        assertEquals("session", SubagentSpawnTool.codingSlug(null));
        var longSlug = SubagentSpawnTool.codingSlug("a".repeat(80) + " tail");
        assertTrue(longSlug.length() <= 40, "bounded: " + longSlug);
        assertEquals(SubagentSpawnTool.codingSlug("same  task"),
                SubagentSpawnTool.codingSlug("same-task"));
    }

    @Test
    void collidingSessionsGetSuffixedDirectories() throws Exception {
        // Two runs with the same task must NOT share a directory: the second
        // resolves to <slug>-2 (an existing session dir is never reused).
        var first = spawnAcp("workdir-collide-a");
        var second = spawnAcp("workdir-collide-b");
        assertTrue(first.contains("where-are-you"), "first run in the slug dir: " + first);
        assertTrue(second.contains("where-are-you"),
                "second run still under the slug family: " + second);
        assertNotEquals(first.strip(), second.strip(),
                "colliding sessions must get distinct directories");
    }

    /** Spawn a synchronous acp run and return the child reply (the harness pwd). */
    private String spawnAcp(String parentName) throws Exception {
        var parent = AgentService.create(parentName, "test-provider", "test-model");
        parent.enabled = true;
        parent.acpAllowed = true;
        parent.save();
        ConversationService.create(parent, "web", "u-" + parentName);
        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var parentId = parent.id;
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var p = Tx.run(() -> (Agent) Agent.findById(parentId));
                var tool = ToolRegistry.lookupTool(SubagentSpawnTool.TOOL_NAME);
                resultRef.set(tool.execute("{\"task\":\"where-are-you\",\"runtime\":\"acp\"}", p));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "acp spawn must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();

        var json = JsonParser.parseString(resultRef.get()).getAsJsonObject();
        assertEquals("COMPLETED", json.get("status").getAsString(),
                "the confined acp run must report COMPLETED");
        return json.get("reply").getAsString();
    }
}
