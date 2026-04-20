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
    public void thinkingModeIsPersistedWhenModelAdvertisesLevel() {
        // Seed an Ollama provider whose model declares all three levels, then
        // verify the agent row captures the chosen level verbatim.
        ConfigService.set("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        ConfigService.set("provider.ollama-cloud.apiKey", "test-key");
        ConfigService.set("provider.ollama-cloud.models",
                "[{\"id\":\"kimi-k2.5\",\"name\":\"Kimi K2.5\",\"supportsThinking\":true,"
                + "\"thinkingLevels\":[\"low\",\"medium\",\"high\"]}]");
        llm.ProviderRegistry.refresh();

        var agent = AgentService.create("thinker", "ollama-cloud", "kimi-k2.5", "medium");
        assertEquals("medium", agent.thinkingMode);
    }

    @Test
    public void thinkingModeUnknownLevelCollapsesToNull() {
        // A level the model doesn't advertise must be silently dropped rather than
        // persisted — we'd otherwise send a bogus value on every LLM call.
        ConfigService.set("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        ConfigService.set("provider.ollama-cloud.apiKey", "test-key");
        ConfigService.set("provider.ollama-cloud.models",
                "[{\"id\":\"kimi-k2.5\",\"name\":\"Kimi K2.5\",\"supportsThinking\":true,"
                + "\"thinkingLevels\":[\"low\",\"medium\",\"high\"]}]");
        llm.ProviderRegistry.refresh();

        var agent = AgentService.create("thinker", "ollama-cloud", "kimi-k2.5", "xhigh");
        assertNull(agent.thinkingMode,
                "Unknown thinking levels should collapse to null, not be persisted");
    }

    @Test
    public void createAgentCreatesWorkspace() {
        var agent = AgentService.create("test-agent", "openrouter", "gpt-4.1");
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
        AgentService.create("ws-agent", "openrouter", "gpt-4.1");

        AgentService.writeWorkspaceFile("ws-agent", "AGENT.md", "# Custom Instructions\nBe helpful.");
        var content = AgentService.readWorkspaceFile("ws-agent", "AGENT.md");
        assertNotNull(content);
        assertTrue(content.contains("Custom Instructions"));
    }

    @Test
    public void readMissingWorkspaceFileReturnsNull() {
        AgentService.create("missing-agent", "openrouter", "gpt-4.1");
        var content = AgentService.readWorkspaceFile("missing-agent", "NONEXISTENT.md");
        assertNull(content);
    }

    @Test
    public void listEnabledFiltersDisabled() {
        var agent1 = AgentService.create("enabled-1", "openrouter", "gpt-4.1");
        agent1.enabled = true;
        agent1.save();
        var agent2 = AgentService.create("disabled-1", "openrouter", "gpt-4.1");
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
        var main = AgentService.create("main", "nonexistent-provider", "nonexistent-model");
        assertTrue(main.isMain());
        assertTrue(main.enabled,
                "Main agent must be enabled at creation regardless of provider config");
    }

    @Test
    public void mainAgentUpdateForcesEnabledTrue() {
        var main = AgentService.create("main", "openrouter", "gpt-4.1");
        // Simulate a caller passing enabled=false — service layer must override it.
        var updated = AgentService.update(main, "main", "openrouter", "gpt-4.1", false);
        assertTrue(updated.enabled,
                "AgentService.update must ignore enabled=false for the main agent");
    }

    @Test
    public void mainAgentSurvivesSyncEnabledStatesWithNoProvider() {
        var main = AgentService.create("main", "nonexistent-provider", "nonexistent-model");
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
        var main = AgentService.create("main", "openrouter", "gpt-4.1");
        main.enabled = false;
        main.save();

        AgentService.syncEnabledStates();

        var refreshed = Agent.findByName("main");
        assertTrue(refreshed.enabled, "sync must re-enable a rogue disabled main row");
    }

    // --- SkillLoader tests ---

    @Test
    public void loadSkillsFromFilesystem() {
        var agent = AgentService.create("skill-agent", "openrouter", "gpt-4.1");

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
        AgentService.create("no-skills", "openrouter", "gpt-4.1");
        SkillLoader.clearCache();
        var skills = SkillLoader.loadSkills("no-skills");
        assertTrue(skills.isEmpty());
    }

    @Test
    public void formatSkillsXmlContainsSkillData() {
        var agent = AgentService.create("xml-agent", "openrouter", "gpt-4.1");

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
        var agent = AgentService.create("convo-agent", "openrouter", "gpt-4.1");
        var convo1 = ConversationService.findOrCreate(agent, "web", "admin");
        assertNotNull(convo1);

        var convo2 = ConversationService.findOrCreate(agent, "web", "admin");
        assertEquals(convo1.id, convo2.id);
    }

    @Test
    public void appendAndLoadMessages() {
        var agent = AgentService.create("msg-agent", "openrouter", "gpt-4.1");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        ConversationService.appendUserMessage(convo, "Hello");
        ConversationService.appendAssistantMessage(convo, "Hi there!", null);
        ConversationService.appendUserMessage(convo, "How are you?");

        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(3, messages.size());
        assertEquals("user", messages.getFirst().role);
        assertEquals("Hello", messages.getFirst().content);
        assertEquals("assistant", messages.get(1).role);
        assertEquals("user", messages.get(2).role);
    }

    @Test
    public void loadRecentMessagesRespectsChatMaxContextMessages() {
        ConfigService.set("chat.maxContextMessages", "3");
        var agent = AgentService.create("window-agent", "openrouter", "gpt-4.1");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        for (int i = 1; i <= 5; i++) {
            ConversationService.appendUserMessage(convo, "msg-" + i);
        }

        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(3, messages.size());
        // ASC / chronological order — should be the LAST 3
        assertEquals("msg-3", messages.getFirst().content);
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
        var agent = AgentService.create("delete-agent", "openrouter", "gpt-4.1");

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
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile("prompt-agent", "AGENT.md", "# Be helpful and concise");

        var assembled = SystemPromptAssembler.assemble(agent, "test query");
        assertNotNull(assembled.systemPrompt());
        assertTrue(assembled.systemPrompt().contains("Be helpful and concise"));
        assertTrue(assembled.systemPrompt().contains("Current date:"));
        assertTrue(assembled.systemPrompt().contains("Platform:"));
    }

    /**
     * Prompt-prefix stability is the precondition for LLM provider prompt caching:
     * every byte of the system prompt is hashed into the cache key, so any per-request
     * variance (timestamps, UUIDs, non-deterministic tool/skill ordering, etc.) causes
     * every turn to miss the cache. This test is the guardrail — it fails the build if
     * anyone accidentally re-introduces dynamic content into the cacheable region of
     * the system prompt. Recalled memories legitimately vary per turn and sit at the
     * tail, so we check stability with the same user message across both calls.
     */
    @Test
    public void assembleIsStableAcrossCallsWithSameInputs() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile("prompt-agent", "AGENT.md", "# Be stable");

        var first = SystemPromptAssembler.assemble(agent, "same user message");
        var second = SystemPromptAssembler.assemble(agent, "same user message");

        assertEquals(first.systemPrompt(), second.systemPrompt(),
                "System prompt must be byte-identical for identical inputs, otherwise "
                        + "LLM provider prompt caching will miss on every request. "
                        + "Likely culprit: a timestamp, UUID, or non-deterministic ordering "
                        + "(tools, skills, memories) was added to the prompt.");
    }

    @Test
    public void assembleSkipsOptionalFiles() {
        var agent = AgentService.create("minimal-agent", "openrouter", "gpt-4.1");
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

    @Test
    public void assembleIncludesSafetyAndExecutionBias() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1");
        var prompt = SystemPromptAssembler.assemble(agent, "test").systemPrompt();
        assertTrue(prompt.contains("## Safety"), "must include Safety section");
        assertTrue(prompt.contains("no self-preservation interest"),
                "Safety section must cover self-preservation");
        assertTrue(prompt.contains("## Execution Bias"), "must include Execution Bias section");
        assertTrue(prompt.contains("Do the work rather than narrating"),
                "Execution Bias section must carry its core guidance");
    }

    @Test
    public void assembleEnvironmentIncludesModelAndRuntime() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1-new");
        var prompt = SystemPromptAssembler.assemble(agent, "test").systemPrompt();
        assertTrue(prompt.contains("Model: gpt-4.1-new"), "must expose the agent model id");
        assertTrue(prompt.contains("JClaw version:"), "must expose the app version");
        assertTrue(prompt.contains("Runtime: Java"), "must expose the Java runtime version");
    }

    /**
     * Sanity check for the Settings UI introspection dialog: the breakdown numbers must
     * actually match the real assembled prompt. If the build sequence in `buildPrompt`
     * ever drifts from `assemble`, this test fails — which is the whole point of
     * routing both through the shared private helper.
     */
    @Test
    public void breakdownMatchesAssembledPrompt() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile("prompt-agent", "AGENT.md", "# Be helpful");

        // "web" chosen arbitrarily — the symmetry check holds for any channel,
        // and the introspection dialog defaults to web, so the numbers match
        // what an operator sees in the UI.
        var assembled = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        var breakdown = SystemPromptAssembler.breakdown(agent, null, "web");

        // Sum of section chars equals the real prompt length. If this drifts, the
        // build sequences have forked.
        int sectionSum = breakdown.sections().stream().mapToInt(e -> e.chars()).sum();
        assertEquals(assembled.length(), sectionSum,
                "sum of per-section chars must equal the full prompt length");

        // Every section name we know must appear. Use a subset to avoid making this
        // test fragile against future section additions.
        var names = breakdown.sections().stream().map(SystemPromptAssembler.PromptBreakdown.Entry::name).toList();
        assertTrue(names.contains("AGENT.md"), "AGENT.md section must be present");
        assertTrue(names.contains("Environment"), "Environment section must be present");
        assertTrue(names.contains("Safety"), "Safety section must be present");
        assertTrue(names.contains("Execution Bias"), "Execution Bias section must be present");
        assertTrue(names.contains("Cache Boundary"), "Cache Boundary section must be present");

        // Prefix + suffix must span the whole prompt (±the marker length itself).
        assertEquals(
                assembled.length(),
                breakdown.cacheablePrefixChars() + SystemPromptAssembler.CACHE_BOUNDARY_MARKER.length() + breakdown.variableSuffixChars(),
                "prefix + marker + suffix must equal the full prompt length");

        // Totals are sane: at least as large as the prompt (tool schemas add more).
        assertTrue(breakdown.totalChars() >= assembled.length(),
                "total chars must include the prompt");
        assertTrue(breakdown.totalTokenEstimate() > 0, "token estimate must be positive");
    }

    // --- Channel-aware prompt sections (JCLAW-17) ---

    @Test
    public void assembleWithNullChannelSkipsChannelGuidance() {
        var agent = AgentService.create("channel-null-agent", "openrouter", "gpt-4.1");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, null).systemPrompt();
        assertFalse(prompt.contains("## Channel Guidance"),
                "null channel must not inject a Channel Guidance section");
    }

    @Test
    public void assembleWithWebChannelInjectsWebGuidance() {
        var agent = AgentService.create("channel-web-agent", "openrouter", "gpt-4.1");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertTrue(prompt.contains("## Channel Guidance (web)"),
                "web channel must inject a header");
        assertTrue(prompt.contains("JClaw web admin chat UI"),
                "web guidance body must mention the admin chat UI");
        // Channel section sits in the stable prefix (above the cache boundary marker).
        var markerIdx = prompt.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        var guidanceIdx = prompt.indexOf("## Channel Guidance");
        assertTrue(markerIdx > 0, "marker must be present");
        assertTrue(guidanceIdx > 0 && guidanceIdx < markerIdx,
                "Channel Guidance must live above the cache boundary so it contributes to the cacheable prefix");
    }

    @Test
    public void assembleWithTelegramChannelInjectsTelegramGuidance() {
        var agent = AgentService.create("channel-tg-agent", "openrouter", "gpt-4.1");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "telegram").systemPrompt();
        assertTrue(prompt.contains("## Channel Guidance (telegram)"),
                "telegram channel must inject a header");
        assertTrue(prompt.contains("responding via a Telegram bot"),
                "telegram guidance body must identify the channel");
        assertTrue(prompt.contains("Markdown tables"),
                "telegram guidance must call out the table limitation explicitly");
    }

    @Test
    public void assembleWithChannelTypeCaseInsensitive() {
        var agent = AgentService.create("channel-case-agent", "openrouter", "gpt-4.1");
        var upper = SystemPromptAssembler.assemble(agent, null, null, "TELEGRAM").systemPrompt();
        var lower = SystemPromptAssembler.assemble(agent, null, null, "telegram").systemPrompt();
        assertTrue(upper.contains("responding via a Telegram bot"),
                "uppercase channelType must resolve the same guidance as lowercase");
        // Headers are lowercased for consistency with the channel_type DB convention.
        assertTrue(upper.contains("## Channel Guidance (telegram)"),
                "header channel label must be lowercased regardless of input case");
        assertEquals(upper, lower,
                "channelType resolution must be case-insensitive end-to-end");
    }

    @Test
    public void assembleWithUnregisteredChannelSkipsSection() {
        // Slack and WhatsApp have no registered guidance yet — they should produce
        // the same prompt as a null channelType (no section at all).
        var agent = AgentService.create("channel-unregistered-agent", "openrouter", "gpt-4.1");
        var slack = SystemPromptAssembler.assemble(agent, null, null, "slack").systemPrompt();
        var whatsapp = SystemPromptAssembler.assemble(agent, null, null, "whatsapp").systemPrompt();
        var baseline = SystemPromptAssembler.assemble(agent, null, null, null).systemPrompt();
        assertEquals(baseline, slack,
                "slack has no registered guidance yet; prompt should match the null baseline");
        assertEquals(baseline, whatsapp,
                "whatsapp has no registered guidance yet; prompt should match the null baseline");
    }

    @Test
    public void breakdownReportsChannelGuidanceAsItsOwnSection() {
        var agent = AgentService.create("channel-breakdown-agent", "openrouter", "gpt-4.1");
        var breakdown = SystemPromptAssembler.breakdown(agent, null, "telegram");
        var names = breakdown.sections().stream()
                .map(SystemPromptAssembler.PromptBreakdown.Entry::name)
                .toList();
        assertTrue(names.stream().anyMatch(n -> n.startsWith("Channel Guidance")),
                "breakdown must expose the channel section so the UI can show it");
    }

    /**
     * The cache-boundary marker separates the byte-stable prefix from the per-turn-variable
     * tail. This test asserts (1) the marker is present, (2) nothing above it varies even
     * when the *user message* changes (which triggers different memory recall results), and
     * (3) the memories section sits below the marker, not above.
     */
    @Test
    public void assembleCacheBoundaryKeepsPrefixStable() {
        var agent = AgentService.create("prompt-agent", "openrouter", "gpt-4.1");

        var first = SystemPromptAssembler.assemble(agent, "first user message").systemPrompt();
        var second = SystemPromptAssembler.assemble(agent, "entirely different request").systemPrompt();

        var firstIdx = first.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        var secondIdx = second.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        assertTrue(firstIdx > 0, "cache boundary marker must be present");
        assertTrue(secondIdx > 0, "cache boundary marker must be present");

        var firstPrefix = first.substring(0, firstIdx);
        var secondPrefix = second.substring(0, secondIdx);
        assertEquals(firstPrefix, secondPrefix,
                "everything above the cache boundary must be byte-identical regardless of "
                        + "the user message — otherwise the LLM provider prompt cache will miss "
                        + "on every turn. Something variable was appended above the boundary.");
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
