import agents.AgentRunner;
import agents.DangerousActionGate;
import agents.QueueDrainOrchestrator;
import agents.SkillLoader;
import agents.TaskRunSink;
import agents.ToolContext;
import channels.TelegramChannel;
import channels.TelegramReactionNotifier;
import channels.TelegramReactionNotifier.ReactionDelta;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.Tx;
import tools.TaskTool;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * A turn that carries a tool allow-list is offered only those tools. Its system prompt has to say
 * the same: the Tool Catalog section once listed the agent's whole set, so a fenced task or its
 * subagent named, and tried, tools it could not call. Read off the request the model is sent.
 *
 * <p>JF-148: {@code jclaw_api} is offered, and named in the catalog, only on a turn the owner started.
 *
 * <p>Holds {@link ToolRegistrySync}: what is checked is what the native registry offers.
 */
class FencedPromptCatalogTest extends UnitTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
    private static final Set<String> ALLOWED = Set.of("web_search", "datetime");
    private static final Pattern CATALOG_ROW = Pattern.compile("(?m)^\\| `([A-Za-z0-9_.-]+)` \\|");
    private static final String JCLAW_API = "jclaw_api";
    private static final String JCLAW_API_SKILL = "jclaw-api";
    private static final Pattern SKILL_NAME = Pattern.compile("<name>([^<]+)</name>");

    // Own provider name: breakers are keyed by provider in a process-global registry.
    private final String provider = "fenced-prompt-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<String> systemPrompt = new AtomicReference<>();
    private final AtomicReference<Set<String>> requestTools = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer llmServer;
    private Long agentId;

    @BeforeEach
    void setup() throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            systemPrompt.set(body.getAsJsonObject().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
            var tools = new TreeSet<String>();
            if (body.getAsJsonObject().has("tools")) {
                for (var tool : body.getAsJsonObject().getAsJsonArray("tools")) {
                    tools.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
                }
            }
            requestTools.set(tools);
            requests.incrementAndGet();
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"agent-model\",\"name\":\"Agent\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        ToolRegistrySync.canonicalForTest();

        var agent = AgentService.create(provider + "-agent", provider, "agent-model");
        agent.enabled = true;
        agent.save();
        agentId = agent.id;
        // The catalog section is written only for an agent that has a skill to check against it.
        var skill = AgentService.workspacePath(agent.name).resolve("skills").resolve("notes");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: notes
                description: Keep short notes
                ---
                # Notes
                Write the note down.
                """);
        // Declared as the shipped jclaw-api skill declares it: it needs nothing but the tool.
        var apiSkill = AgentService.workspacePath(agent.name).resolve("skills").resolve(JCLAW_API_SKILL);
        Files.createDirectories(apiSkill);
        Files.writeString(apiSkill.resolve("SKILL.md"), """
                ---
                name: jclaw-api
                description: Operate JClaw itself from chat
                metadata:
                  jclaw-tools: jclaw_api
                ---
                # JClaw API
                Call jclaw_api.
                """);
        SkillLoader.clearCache();
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    @AfterEach
    void teardown() {
        ToolRegistrySync.release();
        llmServer.stop(0);
        CircuitBreakers.remove(provider);
        for (var suffix : new String[] {"baseUrl", "apiKey", "models"}) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
    }

    @Test
    void aFencedFireIsToldOnlyAboutTheToolsItsTaskAllows() throws Exception {
        fire(null);
        var open = catalogTools();
        fire(ALLOWED);
        var fenced = catalogTools();

        assertTrue(open.stream().anyMatch(name -> !ALLOWED.contains(name)),
                "control: without a list the catalog names tools outside it, so the check below can fail: " + open);
        assertEquals(new TreeSet<>(ALLOWED), fenced, "the prompt's catalog must match what the fire can call");
    }

    @Test
    void aTurnInAConversationThatCarriesAListIsToldOnlyAboutThoseTools() throws Exception {
        var conversationId = Tx.run(() -> {
            Agent agent = Agent.findById(agentId);
            var conversation = ConversationService.create(agent, "web", null);
            conversation.enabledToolNames = "[\"web_search\",\"datetime\"]";
            conversation.save();
            return conversation.id;
        });
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            var conversation = Tx.run(() -> ConversationService.findById(conversationId));
            AgentRunner.run(agent, conversation, "hello");
        });

        assertEquals(new TreeSet<>(ALLOWED), catalogTools(),
                "a fenced task's subagent runs in such a conversation, and its prompt must match its tools");
    }

    // ── JF-148: jclaw_api is offered only where the owner reads the reply alone ──

    @Test
    void anOwnersTelegramTurnIsOfferedJclawApiAndToldAboutIt() throws Exception {
        grantJclawApi();

        var owners = new LinkedHashMap<String, Turn>();
        owners.put("the owner's Telegram DM", streamedTurn("telegram", "4242", true));
        owners.put("the owner's Slack DM", streamedTurn("slack", "D456", true));

        owners.forEach((who, owner) -> {
            assertTrue(owner.offered().contains(JCLAW_API), who + " keeps the tool: " + owner.offered());
            assertTrue(owner.catalog().contains(JCLAW_API), who + ", and the prompt says so: " + owner.catalog());
            assertTrue(owner.skills().contains(JCLAW_API_SKILL), who + ", and lists its skill: " + owner.skills());
        });
    }

    @Test
    void aTurnTheOwnerDoesNotReadAloneLosesJclawApiAndNothingElse() throws Exception {
        grantJclawApi();
        var web = syncTurn("web", null);
        assertTrue(web.offered().contains(JCLAW_API), "control: the operator's own chat is offered it: " + web.offered());
        assertTrue(web.skills().contains(JCLAW_API_SKILL), "control: and lists its skill: " + web.skills());

        var others = new LinkedHashMap<String, Turn>();
        others.put("a non-owner's Telegram DM", streamedTurn("telegram", "5151", false));
        others.put("a non-owner in a Telegram group", streamedTurn("telegram", "-100300", false));
        others.put("the owner in a Telegram group", streamedTurn("telegram", "-100200", true));
        others.put("the owner in a Slack channel", streamedTurn("slack", "C123", true));
        // SlackInbound binds owner-initiated only against a saved owner, so with none every sender is a guest.
        others.put("a Slack DM on a binding with no owner saved", streamedTurn("slack", "D123", false));
        others.put("WhatsApp", streamedTurn("whatsapp", "15550001111", false));
        others.put("a hosted app", syncTurn("app", "an-app"));

        var expected = new Turn(without(web.offered(), JCLAW_API), without(web.catalog(), JCLAW_API),
                without(web.skills(), JCLAW_API_SKILL));
        others.forEach((who, turn) -> assertEquals(expected, turn, who));
    }

    @Test
    void aVoiceTurnKeepsJclawApi() throws Exception {
        grantJclawApi();

        var voice = streamedTurn("voice", "admin", false);

        assertTrue(voice.offered().contains(JCLAW_API), "voice needs the operator's session: " + voice.offered());
        assertTrue(voice.catalog().contains(JCLAW_API), voice.catalog().toString());
        assertTrue(voice.skills().contains(JCLAW_API_SKILL), voice.skills().toString());
    }

    @Test
    void anOwnersReactionInTheirDmIsTheOwnersTurn() throws Exception {
        grantJclawApi();
        var botToken = "jf148-reaction-" + UUID.randomUUID();
        try (var telegram = new MockTelegramServer()) {
            telegram.start();
            TelegramChannel.installForTest(botToken, telegram.telegramUrl());
            try {
                var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
                TelegramReactionNotifier.handleReaction(agent, botToken, "4242",
                        new ReactionDelta("4242", "private", 7, "4242", "@owner", List.of("👍"), List.of()));

                awaitUntil(() -> requests.get() == 1, "the reaction ran a turn");
                var reaction = lastTurn();
                awaitUntil(() -> telegram.countRequests("sendMessage") == 1, "the reply went to the DM");

                assertTrue(reaction.offered().contains(JCLAW_API), reaction.offered().toString());
                assertTrue(reaction.catalog().contains(JCLAW_API), reaction.catalog().toString());
            } finally {
                TelegramChannel.clearForTest(botToken);
            }
        }
    }

    @Test
    void aFireIsOfferedJclawApiOnlyWhenItsTaskIsOfTheOperatorsOrigin() throws Exception {
        grantJclawApi();

        var operatorFire = fireTask(newTask("web"));
        var guestFire = fireTask(newTask("telegram"));

        assertTrue(operatorFire.offered().contains(JCLAW_API), operatorFire.offered().toString());
        assertTrue(operatorFire.catalog().contains(JCLAW_API), operatorFire.catalog().toString());
        assertEquals(new Turn(without(operatorFire.offered(), JCLAW_API), without(operatorFire.catalog(), JCLAW_API),
                without(operatorFire.skills(), JCLAW_API_SKILL)), guestFire, "a task with nothing recorded but its origin");
    }

    @Test
    void aTaskTheOwnerMadeInTheirDmKeepsJclawApiOnEveryFire() throws Exception {
        grantJclawApi();
        var taskId = taskMadeOn("telegram", "4242", true, "jf148-owner-dm");

        var first = fireTask(taskId);
        var second = fireTask(taskId);

        assertTrue(first.offered().contains(JCLAW_API), "first fire: " + first.offered());
        assertTrue(second.offered().contains(JCLAW_API), "second fire: " + second.offered());
        assertTrue(second.catalog().contains(JCLAW_API), second.catalog().toString());
    }

    @Test
    void aTaskMadeInAGroupOrByAGuestFiresWithoutJclawApi() throws Exception {
        grantJclawApi();
        var control = fireTask(taskMadeOn("telegram", "4242", true, "jf148-control"));
        assertTrue(control.offered().contains(JCLAW_API), "control: " + control.offered());

        var expected = new Turn(without(control.offered(), JCLAW_API), without(control.catalog(), JCLAW_API),
                without(control.skills(), JCLAW_API_SKILL));
        assertEquals(expected, fireTask(taskMadeOn("telegram", "-100200", true, "jf148-group")),
                "made by the owner in a group");
        assertEquals(expected, fireTask(taskMadeOn("telegram", "5151", false, "jf148-guest")), "made by a guest");
    }

    @Test
    void anOwnersMessageQueuedBehindATurnIsStillTheOwnersWhenDrained() throws Exception {
        grantJclawApi();

        var drained = drainedTurn(true);

        assertTrue(drained.offered().contains(JCLAW_API), drained.offered().toString());
        assertTrue(drained.catalog().contains(JCLAW_API), drained.catalog().toString());
    }

    @Test
    void aGuestsMessageQueuedBehindATurnIsStillTheGuestsWhenDrained() throws Exception {
        grantJclawApi();

        var drained = drainedTurn(false);

        assertFalse(drained.offered().contains(JCLAW_API), drained.offered().toString());
        assertFalse(drained.catalog().contains(JCLAW_API), drained.catalog().toString());
    }

    @Test
    void aCollectedBatchWithAGuestsMessageInItIsTheGuestsTurn() throws Exception {
        grantJclawApi();
        var mode = "agent." + Tx.run(() -> ((Agent) Agent.findById(agentId)).name) + ".queue.mode";
        ConfigService.set(mode, "collect");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        try {
            var drained = drainedTurn(true, false);

            assertFalse(drained.offered().contains(JCLAW_API), drained.offered().toString());
            assertFalse(drained.catalog().contains(JCLAW_API), drained.catalog().toString());
        } finally {
            ConfigService.delete(mode);
        }
    }

    /** What one turn put in front of the model: the request's tools, the prompt's Tool Catalog and its skills. */
    private record Turn(Set<String> offered, Set<String> catalog, Set<String> skills) {}

    private static Set<String> without(Set<String> names, String name) {
        var rest = new TreeSet<>(names);
        rest.remove(name);
        return rest;
    }

    private Turn lastTurn() {
        return new Turn(requestTools.get(), catalogTools(), skillNames());
    }

    private void grantJclawApi() {
        Tx.run(() -> {
            var row = new AgentToolConfig();
            row.agent = Agent.findById(agentId);
            row.toolName = JCLAW_API;
            row.enabled = true;
            row.save();
        });
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        agents.ToolRegistry.invalidateDisabledToolsCache(Tx.run(() -> (Agent) Agent.findById(agentId)));
        // The skill list is cached with the agent's tool grants applied.
        SkillLoader.clearCache();
    }

    private Long conversation(String channel, String peerId) {
        var id = Tx.run(() -> ConversationService.create((Agent) Agent.findById(agentId), channel, peerId).id);
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return id;
    }

    /** A turn on the synchronous path, as a hosted app's invoke and a subagent run it. */
    private Turn syncTurn(String channel, String peerId) throws Exception {
        var conversationId = conversation(channel, peerId);
        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            var conversation = Tx.run(() -> ConversationService.findById(conversationId));
            AgentRunner.run(agent, conversation, "hello");
        });
        return lastTurn();
    }

    /** A turn on the streaming path, as a channel's inbound dispatch runs it for its sender. */
    private Turn streamedTurn(String channel, String peerId, boolean ownerInitiated) throws Exception {
        var conversationId = conversation(channel, peerId);
        onVirtualThread(() -> DangerousActionGate.withOwnerInitiated(ownerInitiated, () -> {
            stream(conversationId, channel, peerId, "hello");
            return null;
        }));
        return lastTurn();
    }

    private void stream(Long conversationId, String channel, String peerId, String text) {
        var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {},
                _ -> done.countDown(),
                e -> { failure.set(e); done.countDown(); },
                done::countDown);
        AgentRunner.runStreaming(agent, conversationId, channel, peerId, text, new AtomicBoolean(false), cb, null);
        try {
            assertTrue(done.await(30, TimeUnit.SECONDS), "the turn answers within 30s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertNull(failure.get(), "the turn did not fail");
    }

    /** A committed task with {@code origin} recorded and nothing else, as one made before JF-148 reads. */
    private Long newTask(String origin) {
        var taskId = Tx.run(() -> {
            var task = new Task();
            task.agent = Agent.findById(agentId);
            task.name = "jf148-" + origin;
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.RUNNING;
            task.nextRunAt = Instant.now();
            task.originChannel = origin;
            task.save();
            return task.id;
        });
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return taskId;
    }

    /** A task the agent's task_manager created on a {@code channel} turn in {@code peerId}, the way a chat asks for one. */
    private Long taskMadeOn(String channel, String peerId, boolean ownerInitiated, String name) throws Exception {
        var conversationId = conversation(channel, peerId);
        var reply = new AtomicReference<String>();
        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            reply.set(DangerousActionGate.withOwnerInitiated(ownerInitiated, () -> ToolContext.withConversation(
                    conversationId, () -> new TaskTool().execute("""
                            {"action":"createTask","name":"%s","description":"Summarize the day.","schedule":"30m"}"""
                            .formatted(name), agent))));
        });
        assertTrue(reply.get().startsWith("Task '"), "the task was created: " + reply.get());
        return Tx.run(() -> ((Task) Task.find("name = ?1", name).first()).id);
    }

    /** One fire of {@code taskId}, run the way TaskExecutor runs one. */
    private Turn fireTask(Long taskId) throws Exception {
        var before = requests.get();
        var taskRunId = Tx.run(() -> {
            var run = new TaskRun();
            run.task = Task.findById(taskId);
            run.startedAt = Instant.now();
            run.save();
            return run.id;
        });
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            var run = Tx.run(() -> (TaskRun) TaskRun.findById(taskRunId));
            AgentRunner.runForTask(agent, "Summarize the day.", new TaskRunSink(run), null, "jf148-fire", null, null);
        });
        assertEquals(before + 1, requests.get(), "the fire made one model call");
        return lastTurn();
    }

    /**
     * Telegram messages sent while another turn holds their conversation, one per {@code senders} entry (whether
     * the owner sent it), so they are queued, then drained the way a finishing turn drains them: on a thread that
     * inherits nothing from any sender's.
     */
    private Turn drainedTurn(boolean... senders) throws Exception {
        var peerId = "4242";
        var conversationId = conversation("telegram", peerId);
        var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
        long held = ConversationQueue.tryAcquireOwnership(conversationId,
                new ConversationQueue.QueuedMessage("running turn", "telegram", peerId, agent), null);
        assertNotEquals(ConversationQueue.NOT_ACQUIRED, held, "the running turn holds the conversation");
        try {
            for (var owner : senders) {
                onVirtualThread(() -> DangerousActionGate.withOwnerInitiated(owner, () -> {
                    stream(conversationId, "telegram", peerId, owner ? "mine" : "theirs");
                    return null;
                }));
            }
            assertEquals(0, requests.get(), "the messages were queued, not run");

            var drained = ConversationQueue.drain(conversationId, held);
            assertEquals(senders.length, drained.size(), "every message waits behind the turn, in one drain");
            QueueDrainOrchestrator.runOwned(conversationId, held, drained);
            awaitUntil(() -> requests.get() == 1, "the drained messages ran one turn");
            return lastTurn();
        } finally {
            awaitReleased(conversationId);
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), what + " within 30s");
    }

    // The drained run releases the conversation once its own end finds nothing more queued.
    private static void awaitReleased(Long conversationId) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (ConversationQueue.isBusy(conversationId) && System.nanoTime() < deadline) Thread.sleep(20);
        ConversationQueue.releaseOwnership(conversationId);
    }

    private void fire(Set<String> allowedTools) throws Exception {
        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            AgentRunner.runForTask(agent, "Summarize the day.", new AgentExecutionSinkTest.Recorder(),
                    allowedTools, "fenced-task", null, null);
        });
    }

    /** The tool names in the last request's Tool Catalog section. */
    private Set<String> catalogTools() {
        var prompt = systemPrompt.get();
        int start = prompt.indexOf("## Tool Catalog");
        assertTrue(start >= 0, "the prompt must carry a Tool Catalog section for this check to mean anything");
        int end = prompt.indexOf("\n## ", start + 1);
        var names = new TreeSet<String>();
        var rows = CATALOG_ROW.matcher(prompt.substring(start, end < 0 ? prompt.length() : end));
        while (rows.find()) names.add(rows.group(1));
        return names;
    }

    /** The skill names in the last request's available-skills list. */
    private Set<String> skillNames() {
        var prompt = systemPrompt.get();
        int start = prompt.indexOf("<available_skills>");
        assertTrue(start >= 0, "the prompt must list skills for this check to mean anything");
        var names = new TreeSet<String>();
        var rows = SKILL_NAME.matcher(prompt.substring(start, prompt.indexOf("</available_skills>", start)));
        while (rows.find()) names.add(rows.group(1));
        return names;
    }

    // A fire and a subagent turn run on their own threads with no ambient transaction; the test thread holds one.
    private static void onVirtualThread(ThrowingRunnable body) throws Exception {
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the turn should complete within 30s");
        if (error.get() != null) throw error.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
