import org.junit.jupiter.api.*;
import play.test.*;
import agents.SkillLoader;
import agents.SystemPromptAssembler;
import jobs.DefaultConfigJob;
import models.Agent;
import models.Config;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AgentSystemTest extends UnitTest {

    private static final String[] TEST_AGENTS = {
            "test-agent", "ws-agent", "missing-agent", "enabled-1", "disabled-1",
            "skill-agent", "xml-agent", "convo-agent", "msg-agent", "prompt-agent",
            "minimal-agent", "no-skills", "main", "window-agent", "delete-agent"
    };

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        cleanupTestAgents();
    }

    @AfterAll
    static void cleanup() {
        cleanupTestAgents();
    }

    // --- AgentService tests ---

    @Test
    public void createAgentCreatesWorkspace() {
        var agent = AgentService.create("test-agent", "openrouter", "gpt-4.1", null);
        assertNotNull(agent);
        assertEquals("test-agent", agent.name);
        assertFalse(agent.isMain());

        var workspace = AgentService.workspacePath("test-agent");
        assertTrue(Files.exists(workspace));
        assertTrue(Files.exists(workspace.resolve("AGENT.md")));
        assertTrue(Files.exists(workspace.resolve("IDENTITY.md")));
        assertTrue(Files.exists(workspace.resolve("USER.md")));
        assertTrue(Files.isDirectory(workspace.resolve("skills")));
    }

    @Test
    public void readAndWriteWorkspaceFile() {
        AgentService.create("ws-agent", "openrouter", "gpt-4.1", null);

        AgentService.writeWorkspaceFile("ws-agent", "AGENT.md", "# Custom Instructions\nBe helpful.");
        var content = AgentService.readWorkspaceFile("ws-agent", "AGENT.md");
        assertNotNull(content);
        assertTrue(content.contains("Custom Instructions"));
    }

    @Test
    public void readMissingWorkspaceFileReturnsNull() {
        AgentService.create("missing-agent", "openrouter", "gpt-4.1", null);
        var content = AgentService.readWorkspaceFile("missing-agent", "NONEXISTENT.md");
        assertNull(content);
    }

    @Test
    public void listEnabledFiltersDisabled() {
        var agent1 = AgentService.create("enabled-1", "openrouter", "gpt-4.1", null);
        agent1.enabled = true;
        agent1.save();
        var agent2 = AgentService.create("disabled-1", "openrouter", "gpt-4.1", null);
        agent2.enabled = false;
        agent2.save();

        var enabled = AgentService.listEnabled();
        assertEquals(1, enabled.size());
        assertEquals("enabled-1", enabled.getFirst().name);
    }

    // --- Main-agent invariants ---

    @Test
    public void mainAgentIsCreatedEnabledEvenWithUnconfiguredProvider() {
        // Use a provider that's definitely not configured in tests — main should
        // still be created as enabled because the invariant is "main is always enabled."
        var main = AgentService.create("main", "nonexistent-provider", "nonexistent-model", null);
        assertTrue(main.isMain());
        assertTrue(main.enabled,
                "Main agent must be enabled at creation regardless of provider config");
    }

    @Test
    public void mainAgentUpdateForcesEnabledTrue() {
        var main = AgentService.create("main", "openrouter", "gpt-4.1", null);
        // Simulate a caller passing enabled=false — service layer must override it.
        var updated = AgentService.update(main, "main", "openrouter", "gpt-4.1", false, null);
        assertTrue(updated.enabled,
                "AgentService.update must ignore enabled=false for the main agent");
    }

    @Test
    public void mainAgentSurvivesSyncEnabledStatesWithNoProvider() {
        var main = AgentService.create("main", "nonexistent-provider", "nonexistent-model", null);
        assertTrue(main.enabled); // created enabled per the invariant above

        // syncEnabledStates would normally disable agents whose provider isn't
        // configured — it must exempt main.
        AgentService.syncEnabledStates();

        var refreshed = Agent.findByName("main");
        assertNotNull(refreshed);
        assertTrue(refreshed.enabled,
                "syncEnabledStates must never disable the main agent");
    }

    @Test
    public void mainAgentSyncHealsADisabledMainRow() {
        // If main was ever persisted as disabled (e.g. by a pre-fix boot), sync
        // must heal it on the next pass rather than leave it broken.
        var main = AgentService.create("main", "openrouter", "gpt-4.1", null);
        main.enabled = false;
        main.save();

        AgentService.syncEnabledStates();

        var refreshed = Agent.findByName("main");
        assertTrue(refreshed.enabled, "sync must re-enable a rogue disabled main row");
    }

    // --- SkillLoader tests ---

    @Test
    public void loadSkillsFromFilesystem() {
        var agent = AgentService.create("skill-agent", "openrouter", "gpt-4.1", null);

        // Create a skill file in the agent's workspace
        var skillDir = AgentService.workspacePath("skill-agent").resolve("skills").resolve("coding");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: coding
                    description: Help with writing and reviewing code
                    ---
                    # Coding Skill
                    Follow best practices when writing code.
                    """);
        } catch (IOException e) { fail(e); }

        SkillLoader.clearCache();
        var skills = SkillLoader.loadSkills("skill-agent");
        assertEquals(1, skills.size());
        assertEquals("coding", skills.getFirst().name());
        assertEquals("Help with writing and reviewing code", skills.getFirst().description());
    }

    @Test
    public void loadSkillsReturnsEmptyForNoSkills() {
        AgentService.create("no-skills", "openrouter", "gpt-4.1", null);
        SkillLoader.clearCache();
        var skills = SkillLoader.loadSkills("no-skills");
        assertTrue(skills.isEmpty());
    }

    @Test
    public void formatSkillsXmlContainsSkillData() {
        var agent = AgentService.create("xml-agent", "openrouter", "gpt-4.1", null);

        // Create a skill file in the agent's workspace
        var skillDir = AgentService.workspacePath("xml-agent").resolve("skills").resolve("research");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: research
                    description: Deep web research with citations
                    ---
                    Research instructions here.
                    """);
        } catch (IOException e) { fail(e); }

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
        var agent = AgentService.create("convo-agent", "openrouter", "gpt-4.1", null);
        var convo1 = ConversationService.findOrCreate(agent, "web", "admin");
        assertNotNull(convo1);

        var convo2 = ConversationService.findOrCreate(agent, "web", "admin");
        assertEquals(convo1.id, convo2.id);
    }

    @Test
    public void appendAndLoadMessages() {
        var agent = AgentService.create("msg-agent", "openrouter", "gpt-4.1", null);
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

    @Test
    public void loadRecentMessagesRespectsChatMaxContextMessages() {
        ConfigService.set("chat.maxContextMessages", "3");
        var agent = AgentService.create("window-agent", "openrouter", "gpt-4.1", null);
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        for (int i = 1; i <= 5; i++) {
            ConversationService.appendUserMessage(convo, "msg-" + i);
        }

        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(3, messages.size());
        // ASC / chronological order — should be the LAST 3
        assertEquals("msg-3", messages.get(0).content);
        assertEquals("msg-4", messages.get(1).content);
        assertEquals("msg-5", messages.get(2).content);
    }

    // --- DefaultConfigJob rename migration ---

    @Test
    public void renameMigratesAgentKeyToChatKey() throws Exception {
        // Pre-seed the legacy key as if an earlier build had written it
        ConfigService.set("agent.maxToolRounds", "25");
        ConfigService.clearCache();

        // Invoke the private rename helper reflectively — it's the smallest surface
        // that exercises the migration without spinning up the full @OnApplicationStart job
        var job = new DefaultConfigJob();
        var rename = DefaultConfigJob.class.getDeclaredMethod("renameKeyIfPresent", String.class, String.class);
        rename.setAccessible(true);
        rename.invoke(job, "agent.maxToolRounds", "chat.maxToolRounds");

        assertEquals("25", ConfigService.get("chat.maxToolRounds"));
        Tx.run(() -> assertNull(Config.findByKey("agent.maxToolRounds")));

        // Idempotency: a second run is a no-op and doesn't clobber the new value
        rename.invoke(job, "agent.maxToolRounds", "chat.maxToolRounds");
        assertEquals("25", ConfigService.get("chat.maxToolRounds"));
    }

    // --- Cascade delete ---

    @Test
    public void deleteAgentCascadesChildRows() throws Exception {
        // Seed an agent plus one row in every FK-constrained child table so the delete
        // path has to clear each one. Creation itself writes an AgentToolConfig (the
        // seeded "browser=disabled" for non-main agents), so that table is pre-populated.
        var agent = AgentService.create("delete-agent", "openrouter", "gpt-4.1", null);

        var skillConfig = new models.AgentSkillConfig();
        skillConfig.agent = agent;
        skillConfig.skillName = "test-skill";
        skillConfig.enabled = true;
        skillConfig.save();

        var binding = new models.AgentBinding();
        binding.agent = agent;
        binding.channelType = "web";
        binding.peerId = "test-peer";
        binding.save();

        var convo = new models.Conversation();
        convo.agent = agent;
        convo.channelType = "web";
        convo.peerId = "test-peer";
        convo.save();

        var msg = new models.Message();
        msg.conversation = convo;
        msg.role = "user";
        msg.content = "hello";
        msg.save();

        var task = new models.Task();
        task.agent = agent;
        task.name = "test-task";
        task.type = models.Task.Type.IMMEDIATE;
        task.save();

        var mem = new models.Memory();
        mem.agentId = "delete-agent";
        mem.text = "remember this";
        mem.save();

        ConfigService.set("agent.delete-agent.shell.bypassAllowlist", "true");
        ConfigService.set("agent.delete-agent.queue.mode", "queue");

        var workspace = AgentService.workspacePath("delete-agent");
        assertTrue(Files.exists(workspace), "precondition: workspace directory exists");

        // Capture ids before delete — after AgentService.delete() the Java references are
        // detached and Hibernate will reject them in parameterized queries. Counting by id
        // keeps the assertions independent of the now-transient entities.
        var agentId = agent.id;
        var convoId = convo.id;

        // Act
        AgentService.delete(agent);

        // Assert: agent itself and every child row are gone
        assertNull(Agent.findByName("delete-agent"));
        assertEquals(0L, models.AgentToolConfig.count("agent.id = ?1", agentId));
        assertEquals(0L, models.AgentSkillConfig.count("agent.id = ?1", agentId));
        assertEquals(0L, models.AgentBinding.count("agent.id = ?1", agentId));
        assertEquals(0L, models.Conversation.count("agent.id = ?1", agentId));
        assertEquals(0L, models.Message.count("conversation.id = ?1", convoId));
        assertEquals(0L, models.Task.count("agent.id = ?1", agentId));
        assertEquals(0L, models.Memory.count("agentId = ?1", "delete-agent"));
        assertNull(ConfigService.get("agent.delete-agent.shell.bypassAllowlist"));
        assertNull(ConfigService.get("agent.delete-agent.queue.mode"));
        assertFalse(Files.exists(workspace), "workspace directory should be removed");
    }

    // --- SystemPromptAssembler tests ---

    @Test
    public void assembleIncludesWorkspaceFiles() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1", null);
        AgentService.writeWorkspaceFile("prompt-agent", "AGENT.md", "# Be helpful and concise");

        var assembled = SystemPromptAssembler.assemble(agent, "test query");
        assertNotNull(assembled.systemPrompt());
        assertTrue(assembled.systemPrompt().contains("Be helpful and concise"));
        assertTrue(assembled.systemPrompt().contains("Current date:"));
        assertTrue(assembled.systemPrompt().contains("Platform:"));
    }

    @Test
    public void assembleSkipsOptionalFiles() {
        var agent = AgentService.create("minimal-agent", "openrouter", "gpt-4.1", null);
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
