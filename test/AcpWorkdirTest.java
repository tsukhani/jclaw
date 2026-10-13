import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Message;
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-657 (finding A): the {@code runtime:"acp"} harness runs in a confined
 * working directory, not the backend's CWD (the repo root): its own folder under
 * a configured {@link SubagentSpawnTool#ACP_WORKDIR_KEY}, or under the child
 * agent's own workspace tree when that is unset.
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
    void aConfiguredWorkdirIsTheParentOfEachSessionFolder() throws Exception {
        Path workdir = Files.createTempDirectory("jclaw-acp-wd-");
        try {
            ConfigService.set(SubagentSpawnTool.ACP_WORKDIR_KEY, workdir.toString());

            var reply = spawnAcp("workdir-override");

            var leaf = workdir.getFileName().toString();
            assertTrue(reply.contains(leaf + File.separator + "where-are-you"),
                    "the harness pwd (%s) must be the session folder under the configured parent (…/%s/where-are-you)"
                            .formatted(reply, leaf));
        } finally {
            deleteTree(workdir);
        }
    }

    @Test
    void aNamedFolderIsCreatedThenContinuedIn() throws Exception {
        Path workdir = Files.createTempDirectory("jclaw-acp-wd-");
        try {
            ConfigService.set(SubagentSpawnTool.ACP_WORKDIR_KEY, workdir.toString());

            var first = spawn("folder-new", "{\"task\":\"where-are-you\",\"runtime\":\"acp\",\"folder\":\"fibonacci\"}");
            var second = spawn("folder-again", "{\"task\":\"a later task\",\"runtime\":\"acp\",\"folder\":\"fibonacci\"}");

            var firstDir = first.get("reply").getAsString().strip();
            assertTrue(firstDir.endsWith(File.separator + "fibonacci"), "the run works in the named folder: " + firstDir);
            assertEquals(firstDir, second.get("reply").getAsString().strip(),
                    "the second run continues in the first run's folder");
            assertTrue(stepsOf(first).stream().anyMatch(s -> s.startsWith("new folder ")), stepsOf(first).toString());
            assertTrue(stepsOf(second).stream().anyMatch(s -> s.startsWith("continuing in ")), stepsOf(second).toString());
        } finally {
            deleteTree(workdir);
        }
    }

    @Test
    void anUnnamedRunFailsWhenItsFolderCannotBeCreated() throws Exception {
        // A parent under a file: the setting's shape is valid, and creating the folder still fails.
        Path blocker = Files.createTempFile("jclaw-acp-not-a-dir-", ".txt");
        try {
            ConfigService.set(SubagentSpawnTool.ACP_WORKDIR_KEY, blocker.resolve("coding").toString());

            var run = JsonParser.parseString(
                    spawnRaw("workdir-unusable", "{\"task\":\"where-are-you\",\"runtime\":\"acp\"}")).getAsJsonObject();

            assertEquals("FAILED", run.get("status").getAsString(), run.toString());
            assertTrue(run.get("error").getAsString().contains("no folder could be created"), run.toString());
            assertFalse(run.toString().contains(System.getProperty("user.dir")),
                    "the harness must not have started in the server's own directory: " + run);
        } finally {
            Files.deleteIfExists(blocker);
        }
    }

    @Test
    void aFolderMustBeOneNameAndNeedsTheAcpRuntime() throws Exception {
        var asPath = spawnRaw("folder-path", "{\"task\":\"x\",\"runtime\":\"acp\",\"folder\":\"../outside\"}");
        assertTrue(asPath.startsWith("Error:") && asPath.contains("one folder name"), asPath);

        var nativeRuntime = spawnRaw("folder-native", "{\"task\":\"x\",\"folder\":\"fibonacci\"}");
        assertTrue(nativeRuntime.startsWith("Error:") && nativeRuntime.contains("runtime=\"acp\""), nativeRuntime);
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
        return spawn(parentName, "{\"task\":\"where-are-you\",\"runtime\":\"acp\"}").get("reply").getAsString();
    }

    /** Spawn with {@code argsJson} and return the parsed result of a run that must complete. */
    private JsonObject spawn(String parentName, String argsJson) throws Exception {
        var json = JsonParser.parseString(spawnRaw(parentName, argsJson)).getAsJsonObject();
        assertEquals("COMPLETED", json.get("status").getAsString(),
                "the confined acp run must report COMPLETED");
        return json;
    }

    /** The persisted transcript lines of the run {@code result} describes. */
    private static List<String> stepsOf(JsonObject result) {
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(Long.parseLong(result.get("run_id").getAsString()));
        List<Message> steps = Message.find("conversation = ?1 AND messageKind = ?2",
                run.childConversation, SubagentSpawnTool.MESSAGE_KIND_CODINGRUN_STEP).fetch();
        return steps.stream().map(m -> m.content).toList();
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** Spawn with {@code argsJson} under a fresh acp-granted agent and return the tool's raw result. */
    private String spawnRaw(String parentName, String argsJson) throws Exception {
        var parent = AgentService.create(parentName, "test-provider", "test-model");
        parent.enabled = true;
        parent.acpAllowed = true;
        parent.save();
        ConversationService.create(parent, "web", "u-" + parentName);
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var parentId = parent.id;
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var p = Tx.run(() -> (Agent) Agent.findById(parentId));
                var tool = new SubagentSpawnTool();
                resultRef.set(tool.execute(argsJson, p));
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
