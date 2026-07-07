import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * JCLAW-533: renaming an agent must migrate its name-keyed side-data — the
 * on-disk workspace directory and {@code agent.<name>.*} config keys — so a
 * rename doesn't strand them and a reused name doesn't inherit them. Sibling to
 * {@code MemoryAgentIdentityTest} (JCLAW-531), which covers the memory side.
 */
class AgentRenameMigrationTest extends UnitTest {

    private static final String[] NAMES = {"ren-src", "ren-dst", "reuse-ws", "reuse-ws-old"};

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        // Clear any workspace dirs left by a prior run so the move can't collide.
        for (var n : NAMES) rmWorkspace(n);
    }

    @AfterEach
    void cleanup() {
        for (var n : NAMES) rmWorkspace(n);
    }

    private static void rmWorkspace(String name) {
        var dir = AgentService.workspacePath(name);
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) { /* best-effort */ }
            });
        } catch (IOException _) { /* best-effort */ }
    }

    @Test
    void renameMovesWorkspaceAndReKeysConfig() throws Exception {
        var agent = AgentService.create("ren-src", "openrouter", "gpt-4.1");
        Files.writeString(AgentService.workspacePath("ren-src").resolve("MARKER.txt"), "hi");
        ConfigService.set("agent.ren-src.queue.mode", "collect");

        AgentService.update(agent, "ren-dst", "openrouter", "gpt-4.1", true);

        assertTrue(Files.exists(AgentService.workspacePath("ren-dst").resolve("MARKER.txt")),
                "workspace contents must follow the rename");
        assertFalse(Files.exists(AgentService.workspacePath("ren-src")),
                "old workspace directory must be gone after the rename");
        assertEquals("collect", ConfigService.get("agent.ren-dst.queue.mode"),
                "config key must be re-keyed to the new name");
        assertNull(ConfigService.get("agent.ren-src.queue.mode"),
                "old config key must no longer resolve");
    }

    @Test
    void reusedNameDoesNotInheritWorkspace() throws Exception {
        var first = AgentService.create("reuse-ws", "openrouter", "gpt-4.1");
        Files.writeString(AgentService.workspacePath("reuse-ws").resolve("SECRET.txt"), "pii");

        // Free the name by renaming away, then a brand-new agent takes it.
        AgentService.update(first, "reuse-ws-old", "openrouter", "gpt-4.1", true);
        AgentService.create("reuse-ws", "openrouter", "gpt-4.1");

        assertFalse(Files.exists(AgentService.workspacePath("reuse-ws").resolve("SECRET.txt")),
                "a reused name must not inherit the prior agent's workspace files");
        assertTrue(Files.exists(AgentService.workspacePath("reuse-ws-old").resolve("SECRET.txt")),
                "the renamed-away agent keeps its workspace under the new name");
    }
}
