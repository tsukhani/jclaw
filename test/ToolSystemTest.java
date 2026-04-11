import org.junit.jupiter.api.*;
import play.test.*;
import agents.ToolRegistry;
import models.Agent;
import models.Task;
import services.AgentService;
import tools.*;

import java.io.IOException;
import java.nio.file.Path;

import java.io.IOException;
import java.nio.file.Files;

public class ToolSystemTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        ToolRegistry.clear();
        ToolRegistry.register(new TaskTool());
        ToolRegistry.register(new CheckListTool());
        ToolRegistry.register(new FileSystemTools());
        ToolRegistry.register(new WebFetchTool());
        ToolRegistry.register(new SkillsTool());
        ToolRegistry.publish();
        agent = AgentService.create("tool-test-agent", "openrouter", "gpt-4.1", null);
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("tool-test-agent"));
    }

    // --- ToolRegistry ---

    @Test
    public void registryListsAllTools() {
        assertEquals(5, ToolRegistry.listTools().size());
        assertEquals(5, ToolRegistry.getToolDefs().size());
    }

    @Test
    public void executeUnknownToolReturnsError() {
        var result = ToolRegistry.execute("nonexistent_tool", "{}", agent);
        assertTrue(result.startsWith("Error: Unknown tool"));
    }

    @Test
    public void executeToolCatchesExceptions() {
        ToolRegistry.register(new ToolRegistry.Tool() {
            public String name() { return "throwing_tool"; }
            public String description() { return "Throws"; }
            public java.util.Map<String, Object> parameters() { return java.util.Map.of(); }
            public String execute(String args, Agent a) { throw new RuntimeException("boom"); }
        });
        ToolRegistry.publish();
        var result = ToolRegistry.execute("throwing_tool", "{}", agent);
        assertTrue(result.contains("Error executing tool"));
        assertTrue(result.contains("boom"));
    }

    // --- TaskTool ---

    @Test
    public void taskToolCreateTask() {
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "createTask", "name": "test-task", "description": "Do something"}
                """, agent);
        assertTrue(result.contains("created and queued"));
        var tasks = Task.findPendingDue();
        assertEquals(1, tasks.size());
        assertEquals("test-task", tasks.getFirst().name);
    }

    @Test
    public void taskToolScheduleRecurring() {
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "scheduleRecurringTask", "name": "daily-report", "description": "Generate report", "cronExpression": "0 9 * * *"}
                """, agent);
        assertTrue(result.contains("Recurring task"));
        var recurring = Task.findRecurring();
        assertEquals(1, recurring.size());
    }

    @Test
    public void taskToolListRecurring() {
        ToolRegistry.execute("task_manager",
                """
                {"action": "scheduleRecurringTask", "name": "task-1", "description": "First task", "cronExpression": "0 9 * * *"}
                """, agent);
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "listRecurringTasks"}
                """, agent);
        assertTrue(result.contains("task-1"));
    }

    // --- CheckListTool ---

    @Test
    public void checklistValidInput() {
        var result = ToolRegistry.execute("checklist",
                """
                {"items": [
                    {"content": "Step 1", "status": "completed", "activeForm": "Completing step 1"},
                    {"content": "Step 2", "status": "in_progress", "activeForm": "Working on step 2"},
                    {"content": "Step 3", "status": "pending", "activeForm": "Will do step 3"}
                ]}
                """, agent);
        assertTrue(result.contains("successfully"));
    }

    @Test
    public void checklistRejectsMultipleInProgress() {
        var result = ToolRegistry.execute("checklist",
                """
                {"items": [
                    {"content": "Step 1", "status": "in_progress", "activeForm": "Doing 1"},
                    {"content": "Step 2", "status": "in_progress", "activeForm": "Doing 2"}
                ]}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("Exactly one"));
    }

    // --- FileSystemTools ---

    @Test
    public void fileSystemReadFile() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "AGENT.md"}
                """, agent);
        assertTrue(result.contains("Agent Instructions"));
    }

    @Test
    public void fileSystemWriteAndRead() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "notes.txt", "content": "Hello from tool"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "notes.txt"}
                """, agent);
        assertEquals("Hello from tool", result);
    }

    @Test
    public void fileSystemListFiles() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "listFiles", "path": "."}
                """, agent);
        assertTrue(result.contains("AGENT.md"));
        assertTrue(result.contains("skills/"));
    }

    @Test
    public void fileSystemPathTraversalBlocked() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "../../etc/passwd"}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("escapes"));
    }

    // --- Symlink and obfuscated-traversal coverage for the canonical
    //     containment helpers in AgentService. The lexical-only validator
    //     used to allow a workspace-internal symlink whose target was
    //     outside the workspace; the canonical layer (toRealPath) catches
    //     it now. ---

    @Test
    public void fileSystemSymlinkEscapeBlocked() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace);
        var outside = Files.createTempDirectory("jclaw-symlink-test-");
        Files.writeString(outside.resolve("secret.txt"), "should not be readable");
        var link = workspace.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
            var result = ToolRegistry.execute("filesystem",
                    "{\"action\": \"readFile\", \"path\": \"escape/secret.txt\"}",
                    agent);
            assertTrue(result.contains("Error"), "symlink escape must be rejected");
            assertTrue(result.contains("escapes"), "error must mention escape");
        } finally {
            Files.deleteIfExists(link);
            Files.walk(outside).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    public void resolveContainedRejectsObfuscatedTraversal() {
        // The old serveWorkspaceFile substring check let "./../../etc/passwd"
        // through because it does contain ".." but the normalized path was
        // never compared against the workspace root. Pin that down.
        assertNull(AgentService.resolveWorkspacePath(agent.name, "./../../etc/passwd"));
        assertNull(AgentService.resolveWorkspacePath(agent.name, "../../etc/passwd"));
        assertNull(AgentService.resolveWorkspacePath(agent.name, "/etc/passwd"));
    }

    @Test
    public void acquireWorkspacePathThrowsOnEscape() {
        assertThrows(SecurityException.class,
                () -> AgentService.acquireWorkspacePath(agent.name, "../../etc/passwd"));
        assertThrows(SecurityException.class,
                () -> AgentService.acquireWorkspacePath(agent.name, "/etc/passwd"));
    }

    @Test
    public void acquireWorkspacePathAcceptsLegitimateRelativePath() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace);
        var hello = workspace.resolve("hello.txt");
        Files.writeString(hello, "world");
        try {
            var path = AgentService.acquireWorkspacePath(agent.name, "hello.txt");
            assertNotNull(path);
            assertEquals("world", Files.readString(path));
        } finally {
            Files.deleteIfExists(hello);
        }
    }

    @Test
    public void fileSystemHardlinkAliasingBlocked() throws Exception {
        // Hardlinks bypass the symlink check because there's no "link" to
        // follow — both names point to the same inode at the FS level. The
        // sandbox detects this via Files.getAttribute("unix:nlink") and
        // rejects any in-workspace path whose inode has more than one link.
        //
        // Setup: a "secret" file lives in a sibling-of-workspace directory
        // (same filesystem, so Files.createLink works — hardlinks can't cross
        // mount points). The attacker hardlinks it into the workspace and
        // tries to read it via FileSystemTools.
        var workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace);
        var sibling = workspace.getParent().resolve("hardlink-test-outside");
        Files.createDirectories(sibling);
        var outsideSecret = sibling.resolve("secret.txt");
        Files.writeString(outsideSecret, "should not leak");
        var insideLink = workspace.resolve("escape.txt");
        try {
            Files.createLink(insideLink, outsideSecret);
            var result = ToolRegistry.execute("filesystem",
                    "{\"action\": \"readFile\", \"path\": \"escape.txt\"}",
                    agent);
            assertTrue(result.contains("Error"), "hardlink read must be rejected");
            assertTrue(result.contains("hardlink") || result.contains("nlink"),
                    "error must mention hardlink/nlink so the cause is obvious");
        } finally {
            Files.deleteIfExists(insideLink);
            Files.deleteIfExists(outsideSecret);
            Files.deleteIfExists(sibling);
        }
    }

    // --- SkillsTool ---

    @Test
    public void skillsToolListEmpty() {
        agents.SkillLoader.clearCache();
        var result = ToolRegistry.execute("skills",
                """
                {"action": "listSkills"}
                """, agent);
        assertTrue(result.contains("No skills"));
    }

    @Test
    public void skillsToolListAndRead() {
        // Create a skill file in the agent's workspace
        var skillDir = AgentService.workspacePath("tool-test-agent").resolve("skills").resolve("test-skill");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: test-skill
                    description: A test skill
                    ---
                    # Test Skill Instructions
                    Do the test thing.
                    """);
        } catch (IOException e) { fail(e); }

        agents.SkillLoader.clearCache();

        var listResult = ToolRegistry.execute("skills",
                """
                {"action": "listSkills"}
                """, agent);
        assertTrue(listResult.contains("test-skill"));

        var readResult = ToolRegistry.execute("skills",
                """
                {"action": "readSkill", "name": "test-skill"}
                """, agent);
        assertTrue(readResult.contains("Test Skill Instructions"));
    }

    // --- Helpers ---

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
