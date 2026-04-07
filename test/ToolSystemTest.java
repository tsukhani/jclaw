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
        agent = AgentService.create("tool-test-agent", "openrouter", "gpt-4.1", true);
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

    // --- SkillsTool ---

    @Test
    public void skillsToolListEmpty() {
        var result = ToolRegistry.execute("skills",
                """
                {"action": "listSkills"}
                """, agent);
        assertTrue(result.contains("No skills"));
    }

    @Test
    public void skillsToolListAndRead() {
        var skill = new models.Skill();
        skill.name = "test-skill";
        skill.description = "A test skill";
        skill.content = "# Test Skill Instructions\nDo the test thing.";
        skill.isGlobal = false;
        skill.save();

        var assignment = new models.AgentSkill();
        assignment.agent = agent;
        assignment.skill = skill;
        assignment.save();

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
