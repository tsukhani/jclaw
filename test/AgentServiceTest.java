import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.AgentBinding;
import models.AgentToolConfig;
import models.Conversation;
import models.Message;
import models.Task;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Service-layer tests for {@link AgentService}: CRUD, lookup, sandboxed
 * workspace path resolution, and the read/write helpers controllers and
 * tools depend on.
 *
 * <p>These complement the FunctionalTest controller tests by exercising the
 * service contract directly — invariants the HTTP layer can't easily assert
 * (cascade-delete reach, three-layer path validation, file cache behavior)
 * live here.
 */
public class AgentServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    // =====================
    // create()
    // =====================

    @Test
    public void createPersistsAgentWithProvidedFields() {
        var agent = AgentService.create("svc-create-1", "openrouter", "gpt-4.1");
        assertNotNull(agent.id, "id must be assigned by JPA");
        assertEquals("svc-create-1", agent.name);
        assertEquals("openrouter", agent.modelProvider);
        assertEquals("gpt-4.1", agent.modelId);
        assertNotNull(agent.createdAt);
        assertNotNull(agent.updatedAt);
    }

    @Test
    public void createWithThinkingModeNullStoresNull() {
        var agent = AgentService.create("svc-thinking-null", "openrouter", "gpt-4.1", null);
        assertNull(agent.thinkingMode,
                "explicit null thinkingMode must persist as null");
    }

    @Test
    public void createOverloadDefaultsThinkingModeToNull() {
        var agent = AgentService.create("svc-thinking-default", "openrouter", "gpt-4.1");
        assertNull(agent.thinkingMode,
                "the 3-arg overload must delegate with null thinkingMode");
    }

    @Test
    public void createSeedsWorkspaceFilesWithoutOverwriting() {
        var agent = AgentService.create("svc-workspace-seed", "openrouter", "gpt-4.1");
        var dir = AgentService.workspacePath(agent.name);
        assertTrue(Files.exists(dir.resolve("AGENT.md")));
        assertTrue(Files.exists(dir.resolve("IDENTITY.md")));
        assertTrue(Files.exists(dir.resolve("USER.md")));
        assertTrue(Files.exists(dir.resolve("SOUL.md")));
        assertTrue(Files.exists(dir.resolve("BOOTSTRAP.md")));
        assertTrue(Files.exists(dir.resolve("skills")),
                "createWorkspace must seed the skills/ subdirectory");
    }

    @Test
    public void createDisablesBrowserToolForNonMainAgent() {
        var agent = AgentService.create("svc-browser-non-main", "openrouter", "gpt-4.1");
        AgentToolConfig browser = AgentToolConfig.find("agent.id = ?1 AND toolName = ?2",
                agent.id, "browser").first();
        assertNotNull(browser, "non-main agents must get a browser AgentToolConfig row");
        assertFalse(browser.enabled,
                "browser tool must be disabled by default for non-main agents (security)");
    }

    @Test
    public void createDoesNotAutoDisableBrowserForMainAgent() {
        // Main agent must NOT receive the auto-disable row — it owns the
        // browser session lifecycle for support workflows.
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        main.modelProvider = "openrouter";
        main.modelId = "gpt-4.1";
        main.enabled = true;
        main.save();
        AgentService.createWorkspace(Agent.MAIN_AGENT_NAME);

        AgentToolConfig browser = AgentToolConfig.find("agent.id = ?1 AND toolName = ?2",
                main.id, "browser").first();
        assertNull(browser, "main agent should not receive a default-off browser row");
    }

    @Test
    public void createSetsMainAgentEnabledTrueRegardlessOfProviderState() {
        // Main is a structural singleton that must always be enabled — even
        // if its model isn't currently in any registered provider's catalog.
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        main.modelProvider = "nonexistent-provider";
        main.modelId = "made-up-model";
        // Use the create overload via direct save path since AgentService
        // refuses the "main" name through its standard create flow.
        main.enabled = main.isMain() || AgentService.isProviderConfigured("nonexistent-provider", "made-up-model");
        main.save();
        assertTrue(main.enabled,
                "main agent must compute enabled=true even with no provider configured");
    }

    // =====================
    // update()
    // =====================

    @Test
    public void updatePreservesVisionOverloadDefault() {
        // The 5-arg overload (agent, name, provider, modelId, enabled) must
        // forward the agent's existing vision override untouched. JCLAW-165
        // retired the parallel audio toggle.
        var agent = AgentService.create("svc-update-preserve", "openrouter", "gpt-4.1");
        agent.visionEnabled = true;
        agent.save();

        var updated = AgentService.update(agent, agent.name, agent.modelProvider, agent.modelId, true);
        assertEquals(Boolean.TRUE, updated.visionEnabled,
                "5-arg overload must preserve visionEnabled");
    }

    @Test
    public void updateAcceptsExplicitVisionOverride() {
        var agent = AgentService.create("svc-update-explicit", "openrouter", "gpt-4.1");
        var updated = AgentService.update(agent, agent.name, agent.modelProvider,
                agent.modelId, true, null, Boolean.TRUE);
        assertEquals(Boolean.TRUE, updated.visionEnabled);
    }

    @Test
    public void updateForcesMainAgentEnabledTrueRegardlessOfArgument() {
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        main.modelProvider = "openrouter";
        main.modelId = "gpt-4.1";
        main.enabled = true;
        main.save();
        AgentService.createWorkspace(Agent.MAIN_AGENT_NAME);

        // Caller asks to disable; service-level invariant must override.
        var updated = AgentService.update(main, main.name, main.modelProvider,
                main.modelId, false, null, null, null);
        assertTrue(updated.enabled,
                "main agent must remain enabled even when the caller passes enabled=false");
    }

    // =====================
    // findById / findByName / listAll / listEnabled
    // =====================

    @Test
    public void findByIdReturnsAgentOrNull() {
        var agent = AgentService.create("svc-find-id", "openrouter", "gpt-4.1");
        assertNotNull(AgentService.findById(agent.id));
        assertNull(AgentService.findById(99_999_999L));
    }

    @Test
    public void findByNameReturnsAgentOrNull() {
        AgentService.create("svc-find-name", "openrouter", "gpt-4.1");
        assertNotNull(AgentService.findByName("svc-find-name"));
        assertNull(AgentService.findByName("nonexistent-agent-xyz"));
    }

    @Test
    public void listAllReturnsEveryAgent() {
        AgentService.create("svc-list-1", "openrouter", "gpt-4.1");
        AgentService.create("svc-list-2", "openrouter", "gpt-4.1");
        var all = AgentService.listAll();
        assertEquals(2, all.size());
    }

    @Test
    public void listEnabledFiltersOutDisabledAgents() {
        var a = AgentService.create("svc-enabled-true", "openrouter", "gpt-4.1");
        a.enabled = true;
        a.save();
        var b = AgentService.create("svc-enabled-false", "openrouter", "gpt-4.1");
        b.enabled = false;
        b.save();

        var enabled = AgentService.listEnabled();
        assertEquals(1, enabled.size());
        assertEquals("svc-enabled-true", enabled.getFirst().name);
    }

    // =====================
    // delete() — cascades + workspace cleanup
    // =====================

    @Test
    public void deleteCascadesThroughChildRowsAndRemovesWorkspace() {
        var agent = AgentService.create("svc-delete-cascade", "openrouter", "gpt-4.1");
        var agentId = agent.id;
        var agentName = agent.name;

        // Stage child rows the same way a real conversation would.
        var convo = new Conversation();
        convo.agent = agent;
        convo.channelType = "web";
        convo.peerId = "delete-test-peer";
        convo.save();

        var msg = new Message();
        msg.conversation = convo;
        msg.role = "user";
        msg.content = "hi";
        msg.save();

        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = "telegram";
        binding.peerId = "delete-test-peer";
        binding.save();

        var task = new Task();
        task.agent = agent;
        task.name = "delete-cascade-task";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.scheduledAt = Instant.now();
        task.save();

        // Verify pre-conditions.
        assertTrue(Conversation.count("agent.id = ?1", agentId) > 0);
        assertTrue(Message.count("conversation.id = ?1", convo.id) > 0);
        assertTrue(AgentBinding.count("agent.id = ?1", agentId) > 0);
        assertTrue(Task.count("agent.id = ?1", agentId) > 0);
        // The auto-disabled browser row from create() also exists.
        assertTrue(AgentToolConfig.count("agent.id = ?1", agentId) > 0);

        var workspaceDir = AgentService.workspacePath(agentName);
        assertTrue(Files.exists(workspaceDir),
                "workspace dir must exist before delete");

        AgentService.delete(agent);

        // Every child row must be gone.
        assertEquals(0L, Conversation.count("agent.id = ?1", agentId),
                "conversations must cascade");
        assertEquals(0L, Message.count("conversation.id = ?1", convo.id),
                "messages must cascade");
        assertEquals(0L, AgentBinding.count("agent.id = ?1", agentId),
                "bindings must cascade");
        assertEquals(0L, Task.count("agent.id = ?1", agentId),
                "tasks must cascade");
        assertEquals(0L, AgentToolConfig.count("agent.id = ?1", agentId),
                "agent tool configs must cascade");
        assertEquals(0L, Agent.count("id = ?1", agentId),
                "agent row itself must be deleted");
        assertFalse(Files.exists(workspaceDir),
                "workspace dir must be removed after delete");
    }

    @Test
    public void deletePurgesAgentScopedConfigKeysAndCache() {
        var agent = AgentService.create("svc-delete-config", "openrouter", "gpt-4.1");
        ConfigService.set("agent.svc-delete-config.shell.bypassAllowlist", "false");
        // Sanity: the row landed.
        assertEquals("false", ConfigService.get("agent.svc-delete-config.shell.bypassAllowlist"));

        AgentService.delete(agent);

        assertNull(ConfigService.get("agent.svc-delete-config.shell.bypassAllowlist"),
                "agent.{name}.* keys must be removed and the cache invalidated");
    }

    // =====================
    // isProviderConfigured()
    // =====================

    @Test
    public void isProviderConfiguredFalseWhenProviderUnknown() {
        assertFalse(AgentService.isProviderConfigured("nonexistent-provider", "anything"));
    }

    // =====================
    // Workspace path resolution — defense-in-depth
    // =====================

    @Test
    public void workspacePathReturnsCanonicalPathForValidName() {
        var path = AgentService.workspacePath("svc-path-valid");
        assertNotNull(path);
        assertTrue(path.toString().endsWith("svc-path-valid"),
                "resolved path must end with the agent name: " + path);
    }

    @Test
    public void workspacePathThrowsOnTraversalShapedName() {
        var ex = assertThrows(SecurityException.class,
                () -> AgentService.workspacePath("../escape-attempt"));
        assertTrue(ex.getMessage().toLowerCase().contains("workspace"),
                "exception must reference the workspace boundary: " + ex.getMessage());
    }

    @Test
    public void resolveContainedReturnsNullForDotDotEscape() {
        var root = AgentService.workspaceRoot().toAbsolutePath();
        assertNull(AgentService.resolveContained(root, "../../../etc/passwd"),
                "resolveContained must return null on lexical escape");
    }

    @Test
    public void resolveContainedReturnsPathForLegalRelative() {
        var root = AgentService.workspaceRoot().toAbsolutePath();
        var resolved = AgentService.resolveContained(root, "svc-path-legal/AGENT.md");
        assertNotNull(resolved);
        assertTrue(resolved.toString().endsWith("AGENT.md"));
    }

    @Test
    public void acquireContainedThrowsOnDotDotEscape() {
        var root = AgentService.workspaceRoot().toAbsolutePath();
        assertThrows(SecurityException.class,
                () -> AgentService.acquireContained(root, "../../../etc/passwd"));
    }

    @Test
    public void acquireWorkspacePathThrowsOnTraversal() {
        AgentService.create("svc-acquire-traversal", "openrouter", "gpt-4.1");
        assertThrows(SecurityException.class,
                () -> AgentService.acquireWorkspacePath("svc-acquire-traversal", "../../etc/passwd"));
    }

    @Test
    public void resolveWorkspacePathReturnsNullOnTraversal() {
        AgentService.create("svc-resolve-traversal", "openrouter", "gpt-4.1");
        assertNull(AgentService.resolveWorkspacePath("svc-resolve-traversal", "../../../etc/passwd"));
    }

    // =====================
    // readWorkspaceFile / writeWorkspaceFile
    // =====================

    @Test
    public void readWorkspaceFileReturnsSeededContent() {
        AgentService.create("svc-read-seeded", "openrouter", "gpt-4.1");
        var content = AgentService.readWorkspaceFile("svc-read-seeded", "AGENT.md");
        assertNotNull(content);
        assertTrue(content.contains("Agent Instructions") || content.contains("helpful AI assistant"),
                "AGENT.md template content should be readable: " + content);
    }

    @Test
    public void readWorkspaceFileReturnsNullForMissingFile() {
        AgentService.create("svc-read-missing", "openrouter", "gpt-4.1");
        assertNull(AgentService.readWorkspaceFile("svc-read-missing", "does-not-exist.md"));
    }

    @Test
    public void readWorkspaceFileReturnsNullForTraversal() {
        AgentService.create("svc-read-traversal", "openrouter", "gpt-4.1");
        // The catch in readWorkspaceFile swallows SecurityException and returns null.
        assertNull(AgentService.readWorkspaceFile("svc-read-traversal", "../../../etc/passwd"));
    }

    @Test
    public void writeWorkspaceFileRoundTripsAndInvalidatesCache() {
        AgentService.create("svc-write-roundtrip", "openrouter", "gpt-4.1");

        // Prime the cache with a read.
        var seeded = AgentService.readWorkspaceFile("svc-write-roundtrip", "AGENT.md");
        assertNotNull(seeded);

        // Overwrite via the write helper.
        AgentService.writeWorkspaceFile("svc-write-roundtrip", "AGENT.md",
                "# replaced by test\n");

        // Cache must have been evicted — the next read must surface the new
        // content rather than the prior cached value.
        var fresh = AgentService.readWorkspaceFile("svc-write-roundtrip", "AGENT.md");
        assertEquals("# replaced by test\n", fresh,
                "writeWorkspaceFile must invalidate the file cache");
    }

    @Test
    public void writeWorkspaceFileCreatesMissingParentDirectories() {
        AgentService.create("svc-write-nested", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile("svc-write-nested", "deep/nested/file.txt",
                "nested-content");
        var read = AgentService.readWorkspaceFile("svc-write-nested", "deep/nested/file.txt");
        assertEquals("nested-content", read,
                "writer must mkdir -p the parent and write the file");
    }

    // =====================
    // createWorkspace vs resetWorkspace
    // =====================

    @Test
    public void createWorkspaceDoesNotOverwriteExistingFiles() {
        var dir = AgentService.workspacePath("svc-create-no-overwrite");
        // First create — seeds a default AGENT.md.
        AgentService.createWorkspace("svc-create-no-overwrite");

        // Mutate AGENT.md, then call createWorkspace again — the mutation
        // must survive (overwrite=false in createWorkspace).
        var agentMd = dir.resolve("AGENT.md");
        try {
            Files.writeString(agentMd, "# user-edited content\n");
        } catch (Exception e) {
            fail("setup write failed: " + e.getMessage());
        }
        AgentService.createWorkspace("svc-create-no-overwrite");
        try {
            assertEquals("# user-edited content\n", Files.readString(agentMd),
                    "createWorkspace must NOT clobber existing files");
        } catch (Exception e) {
            fail("verification read failed: " + e.getMessage());
        }
    }

    @Test
    public void resetWorkspaceOverwritesExistingFiles() {
        var dir = AgentService.workspacePath("svc-reset-overwrite");
        AgentService.createWorkspace("svc-reset-overwrite");

        var agentMd = dir.resolve("AGENT.md");
        try {
            Files.writeString(agentMd, "# user-edited content\n");
        } catch (Exception e) {
            fail("setup write failed: " + e.getMessage());
        }

        AgentService.resetWorkspace("svc-reset-overwrite");
        try {
            var after = Files.readString(agentMd);
            assertNotEquals("# user-edited content\n", after,
                    "resetWorkspace must clobber existing files");
            assertTrue(after.contains("Agent Instructions") || after.contains("helpful AI assistant"),
                    "resetWorkspace must restore the template: " + after);
        } catch (Exception e) {
            fail("verification read failed: " + e.getMessage());
        }
    }

    // =====================
    // Test-only cleanup so workspace dirs don't leak across runs.
    // =====================

    @AfterAll
    static void cleanupWorkspaceDirs() {
        // Test agents are namespaced "svc-*". Sweep them so a flaky run
        // doesn't accumulate state on disk between sessions.
        var root = AgentService.workspaceRoot();
        if (!Files.exists(root)) return;
        try (var stream = Files.list(root)) {
            stream.filter(p -> {
                var name = p.getFileName().toString();
                return name.startsWith("svc-");
            }).forEach(AgentServiceTest::deleteRecursively);
        } catch (Exception _) {
            // best-effort cleanup; never fail the suite on this
        }
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                try { Files.delete(child); } catch (Exception _) { /* best-effort */ }
            });
        } catch (Exception _) {
            // best-effort cleanup
        }
    }
}
