import org.junit.jupiter.api.*;
import play.test.*;
import agents.SkillLoader;
import agents.SystemPromptAssembler;
import models.Agent;
import services.AgentService;
import services.ConversationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AgentSystemTest extends UnitTest {

    private static final String[] TEST_AGENTS = {
            "test-agent", "ws-agent", "missing-agent", "enabled-1", "disabled-1",
            "skill-agent", "xml-agent", "convo-agent", "msg-agent", "prompt-agent",
            "minimal-agent", "no-skills"
    };

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgents();
    }

    @AfterAll
    static void cleanup() {
        cleanupTestAgents();
    }

    // --- AgentService tests ---

    @Test
    public void createAgentCreatesWorkspace() {
        var agent = AgentService.create("test-agent", "openrouter", "gpt-4.1", true);
        assertNotNull(agent);
        assertEquals("test-agent", agent.name);
        assertTrue(agent.isDefault);

        var workspace = AgentService.workspacePath("test-agent");
        assertTrue(Files.exists(workspace));
        assertTrue(Files.exists(workspace.resolve("AGENT.md")));
        assertTrue(Files.exists(workspace.resolve("IDENTITY.md")));
        assertTrue(Files.exists(workspace.resolve("USER.md")));
        assertTrue(Files.isDirectory(workspace.resolve("skills")));
    }

    @Test
    public void readAndWriteWorkspaceFile() {
        AgentService.create("ws-agent", "openrouter", "gpt-4.1", false);

        AgentService.writeWorkspaceFile("ws-agent", "AGENT.md", "# Custom Instructions\nBe helpful.");
        var content = AgentService.readWorkspaceFile("ws-agent", "AGENT.md");
        assertNotNull(content);
        assertTrue(content.contains("Custom Instructions"));
    }

    @Test
    public void readMissingWorkspaceFileReturnsNull() {
        AgentService.create("missing-agent", "openrouter", "gpt-4.1", false);
        var content = AgentService.readWorkspaceFile("missing-agent", "NONEXISTENT.md");
        assertNull(content);
    }

    @Test
    public void listEnabledFiltersDisabled() {
        var agent1 = AgentService.create("enabled-1", "openrouter", "gpt-4.1", false);
        agent1.enabled = true;
        agent1.save();
        var agent2 = AgentService.create("disabled-1", "openrouter", "gpt-4.1", false);
        agent2.enabled = false;
        agent2.save();

        var enabled = AgentService.listEnabled();
        assertEquals(1, enabled.size());
        assertEquals("enabled-1", enabled.getFirst().name);
    }

    // --- SkillLoader tests ---

    @Test
    public void loadSkillsFromDatabase() {
        var agent = AgentService.create("skill-agent", "openrouter", "gpt-4.1", false);

        var skill = new models.Skill();
        skill.name = "coding";
        skill.description = "Help with writing and reviewing code";
        skill.content = "# Coding Skill\nFollow best practices when writing code.";
        skill.isGlobal = false;
        skill.save();

        var assignment = new models.AgentSkill();
        assignment.agent = agent;
        assignment.skill = skill;
        assignment.save();

        SkillLoader.clearCache();
        var skills = SkillLoader.loadSkills("skill-agent");
        assertEquals(1, skills.size());
        assertEquals("coding", skills.getFirst().name());
        assertEquals("Help with writing and reviewing code", skills.getFirst().description());
    }

    @Test
    public void loadSkillsReturnsEmptyForNoSkills() {
        AgentService.create("no-skills", "openrouter", "gpt-4.1", false);
        var skills = SkillLoader.loadSkills("no-skills");
        assertTrue(skills.isEmpty());
    }

    @Test
    public void formatSkillsXmlContainsSkillData() {
        var agent = AgentService.create("xml-agent", "openrouter", "gpt-4.1", false);

        var skill = new models.Skill();
        skill.name = "research";
        skill.description = "Deep web research with citations";
        skill.content = "Research instructions here.";
        skill.isGlobal = false;
        skill.save();

        var assignment = new models.AgentSkill();
        assignment.agent = agent;
        assignment.skill = skill;
        assignment.save();

        SkillLoader.clearCache();
        var skills = SkillLoader.loadSkills("xml-agent");
        var xml = SkillLoader.formatSkillsXml(skills);
        assertTrue(xml.contains("<available_skills>"));
        assertTrue(xml.contains("<name>research</name>"));
        assertTrue(xml.contains("Deep web research with citations"));
    }

    @Test
    public void yamlFrontmatterParsing() {
        assertEquals("my-skill", SkillLoader.extractYamlValue("name: my-skill", "name"));
        assertEquals("A description", SkillLoader.extractYamlValue("description: A description", "description"));
        assertNull(SkillLoader.extractYamlValue("other: value", "name"));
        assertEquals("quoted", SkillLoader.extractYamlValue("name: \"quoted\"", "name"));
    }

    // --- ConversationService tests ---

    @Test
    public void findOrCreateConversation() {
        var agent = AgentService.create("convo-agent", "openrouter", "gpt-4.1", true);
        var convo1 = ConversationService.findOrCreate(agent, "web", "admin");
        assertNotNull(convo1);

        var convo2 = ConversationService.findOrCreate(agent, "web", "admin");
        assertEquals(convo1.id, convo2.id);
    }

    @Test
    public void appendAndLoadMessages() {
        var agent = AgentService.create("msg-agent", "openrouter", "gpt-4.1", true);
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        ConversationService.appendUserMessage(convo, "Hello");
        ConversationService.appendAssistantMessage(convo, "Hi there!", null);
        ConversationService.appendUserMessage(convo, "How are you?");

        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role);
        assertEquals("Hello", messages.get(0).content);
        assertEquals("assistant", messages.get(1).role);
        assertEquals("user", messages.get(2).role);
    }

    // --- SystemPromptAssembler tests ---

    @Test
    public void assembleIncludesWorkspaceFiles() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1", true);
        AgentService.writeWorkspaceFile("prompt-agent", "AGENT.md", "# Be helpful and concise");

        var assembled = SystemPromptAssembler.assemble(agent, "test query");
        assertNotNull(assembled.systemPrompt());
        assertTrue(assembled.systemPrompt().contains("Be helpful and concise"));
        assertTrue(assembled.systemPrompt().contains("Current time:"));
        assertTrue(assembled.systemPrompt().contains("Platform:"));
    }

    @Test
    public void assembleSkipsOptionalFiles() {
        var agent = AgentService.create("minimal-agent", "openrouter", "gpt-4.1", true);
        // Delete optional files
        try {
            Files.deleteIfExists(AgentService.workspacePath("minimal-agent").resolve("IDENTITY.md"));
            Files.deleteIfExists(AgentService.workspacePath("minimal-agent").resolve("USER.md"));
        } catch (IOException _) {}

        var assembled = SystemPromptAssembler.assemble(agent, "test");
        assertNotNull(assembled.systemPrompt());
        // Should still have AGENT.md content and environment info
        assertTrue(assembled.systemPrompt().contains("Environment"));
    }

    // --- Helpers ---

    private static void cleanupTestAgents() {
        for (var name : TEST_AGENTS) {
            deleteDir(AgentService.workspacePath(name));
        }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException _) {}
                    });
        } catch (IOException _) {}
    }
}
