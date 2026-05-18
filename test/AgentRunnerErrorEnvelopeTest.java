import agents.AgentRunner;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.Tx;

import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-308: pin the deterministic error-envelope contract of
 * {@link AgentRunner#run}. The runner MUST surface provider failures as
 * normal-shaped {@link agents.AgentRunner.RunResult} values whose
 * {@code response} carries one of a fixed set of human-readable prefixes —
 * never as a thrown exception bubbling to the caller, never as an empty
 * string, and never as a raw stack trace.
 *
 * <p>Sibling to {@link AgentRunnerCoreTest} which exercises the happy path
 * and the truncation envelope; this file isolates the failure envelopes so a
 * regression in any one of them lands a precise failure rather than mixing
 * with the larger core suite.
 *
 * <p>Envelopes pinned here:
 * <ul>
 *   <li>"No LLM provider configured" — agent points at an unknown provider
 *       name; the {@link llm.ProviderRegistry#get} miss converts to a canned
 *       assistant message and is persisted before the RunResult returns.</li>
 *   <li>"No response received" — provider returned a 200 with an empty
 *       {@code choices} array (defensive code path in
 *       {@link agents.ToolCallLoopRunner#callWithToolLoop}).</li>
 *   <li>"I'm sorry, I encountered an error" — provider raised on the LLM call
 *       (5xx after retry, network error). The catch in the loop converts the
 *       throw into a normal RunResult.</li>
 * </ul>
 */
class AgentRunnerErrorEnvelopeTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        // Mirror AgentRunnerCoreTest's setup: a 200ms gap lets any prior
        // test's lingering VT activity (queue drain, EventLogger flush)
        // settle before this test resets the DB. Without it the
        // Fixtures.deleteDatabase() can race in-flight writes from the
        // previous test's tail.
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
    }

    // ----- "No LLM provider configured" envelope -----

    @Test
    void runReturnsProviderEnvelopeAndPersistsItAsAssistantMessage() {
        // No provider configured AT ALL — the registry refresh runs with an
        // empty config and ProviderRegistry.get returns null. The runner
        // builds the canned envelope, persists it as an ASSISTANT row, and
        // returns it on the RunResult. The contract here is that the
        // assistant message lives in the DB so the chat UI's history view
        // shows the failure rather than rendering a blank turn.
        var agent = createAgent("envelope-no-provider", "unknown-provider", "model");
        var convo = ConversationService.create(agent, "web", "u-envelope");

        var result = AgentRunner.run(agent, convo, "Hello");

        assertNotNull(result);
        assertNotNull(result.response());
        assertTrue(result.response().contains("No LLM provider configured"),
                "envelope must contain canonical 'No LLM provider configured' phrase, got: "
                        + result.response());

        // Persisted shape — both user and assistant rows must be present, in
        // order, so the chat UI's scrollback reads correctly. Re-fetch
        // through {@link ConversationService} (same pattern as
        // AgentRunnerCoreTest's first test) to dodge the harness EM's
        // stale snapshot.
        var all = ConversationService.loadRecentMessages(
                ConversationService.findById(convo.id));
        assertTrue(all.size() >= 2, "expected user + assistant rows, got " + all.size());

        Message user = all.getFirst();
        assertEquals(MessageRole.USER.value, user.role,
                "first row must be the persisted user input");
        assertEquals("Hello", user.content);

        Message assistantEnvelope = null;
        for (var m : all) {
            if (MessageRole.ASSISTANT.value.equals(m.role)) { assistantEnvelope = m; break; }
        }
        assertNotNull(assistantEnvelope,
                "envelope must be persisted as an ASSISTANT row so the chat UI shows it");
        assertTrue(assistantEnvelope.content.contains("No LLM provider configured"),
                "persisted assistant content must mirror the RunResult envelope");
    }

    // ----- "I'm sorry" envelope on LLM 5xx (post-retry) -----

    @Test
    void runReturnsErrorEnvelopeWhenProviderRaisesOnLlmCall() throws Exception {
        // Provider 500 on every call. The OkHttp-backed provider retries 5xx
        // a bounded number of times and then surfaces an exception to the
        // tool-loop's catch, which converts it to the canned envelope. This
        // pins the "exception → envelope" boundary contract.
        startLlmServer(exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("envelope-5xx", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-5xx");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Hello");

        assertNotNull(result.response());
        assertFalse(result.response().isBlank(),
                "envelope must not be empty on provider failure");
        assertFalse(result.response().toLowerCase().contains("exception"),
                "envelope must not surface raw exception class names, got: " + result.response());
        // The canonical 5xx envelope from ToolCallLoopRunner.callWithToolLoop
        // is "I'm sorry, I encountered an error communicating with the AI
        // provider." Pin the exact prefix — a loose `contains("error")` or
        // similar would let a regression that ships a raw stack-trace
        // substring through.
        assertTrue(result.response().startsWith("I'm sorry"),
                "envelope must start with the canonical 'I'm sorry' apology, got: "
                        + result.response());
    }

    // ----- "No response received" envelope on empty choices -----

    @Test
    void runReturnsEnvelopeWhenProviderReturnsEmptyChoicesArray() throws Exception {
        // Provider returns a 200 with a valid OpenAI-compat shape but an
        // empty choices array — a known degraded response from some
        // proxies and budget models. The runner defensively checks for
        // this and emits the canned "No response received" envelope. The
        // important contract: the caller doesn't crash on an
        // IndexOutOfBounds in choices.getFirst().
        var emptyChoices = """
            {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":0}}""";
        startLlmServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, emptyChoices.getBytes().length);
            exchange.getResponseBody().write(emptyChoices.getBytes());
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("envelope-empty-choices", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-empty-choices");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Hello");

        assertNotNull(result.response());
        assertTrue(result.response().contains("No response received"),
                "envelope must contain canonical 'No response received' phrase, got: "
                        + result.response());
    }

    // ----- Envelope is persisted on the conversation -----

    @Test
    void llmFailureEnvelopeIsPersistedAlongsideTheUserMessage() throws Exception {
        // Belt-and-suspenders for the 5xx path: not only does the caller see
        // an envelope on the RunResult, but the chat history reflects the
        // failure too. Without this, a chat-UI page reload after the failure
        // would render the user's turn with no assistant reply at all.
        startLlmServer(exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("envelope-persist", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-persist");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        runOnVirtualThread(agent, convo, "Will fail");

        // Mirror AgentRunnerCoreTest's pattern: clear the test's JPA
        // entity-manager so the VT's committed writes are visible, then
        // re-fetch the conversation through the working
        // {@link ConversationService#findById} + {@code loadRecentMessages}
        // pair (Message.find directly on the harness EM has shown stale-
        // snapshot behavior under parallel-worktree gradle pressure).
        JPA.em().clear();
        var messages = ConversationService.loadRecentMessages(
                ConversationService.findById(convo.id));
        assertTrue(messages.size() >= 2,
                "expected user + assistant envelope rows, got " + messages.size());
        Message lastAssistant = null;
        for (var m : messages) {
            if (MessageRole.ASSISTANT.value.equals(m.role)) lastAssistant = m;
        }
        assertNotNull(lastAssistant,
                "5xx envelope must persist as ASSISTANT row, got rows: "
                        + messages.stream().map(m -> m.role + "=" + m.content).toList());
        assertNotNull(lastAssistant.content,
                "ASSISTANT row content must not be null on the failure path");
        assertFalse(lastAssistant.content.isBlank(),
                "ASSISTANT row content must not be empty on the failure path");
    }

    // ----- Determinism: same input twice yields the same envelope -----

    @Test
    void noProviderEnvelopeIsDeterministicAcrossInvocations() {
        // Regression guard: the envelope must NOT include any non-deterministic
        // content (timestamps, run ids, stack frames) that would defeat
        // log-grep based monitoring. Two runs against the same misconfiguration
        // must produce byte-identical envelope text on the RunResult.
        var agent = createAgent("envelope-deterministic", "missing", "model");
        var convo1 = ConversationService.create(agent, "web", "u-det-1");
        var convo2 = ConversationService.create(agent, "web", "u-det-2");

        var first = AgentRunner.run(agent, convo1, "Hello");
        var second = AgentRunner.run(agent, convo2, "Hello");

        assertEquals(first.response(), second.response(),
                "the no-provider envelope must be byte-identical across invocations");
    }

    // ----- JSON validity guard on envelope -----

    @Test
    void envelopeIsPlainTextNotJsonShaped() {
        // Negative assertion: the envelope is plain prose, not a JSON object.
        // A future "well, let's surface the failure as a JSON error" change
        // would break every channel that ships the envelope verbatim to a
        // user (telegram, slack — they render markdown, not JSON), so pin
        // the plain-text shape here.
        var agent = createAgent("envelope-shape", "missing", "model");
        var convo = ConversationService.create(agent, "web", "u-shape");

        var result = AgentRunner.run(agent, convo, "Hello");

        boolean isJson;
        try {
            JsonParser.parseString(result.response()).getAsJsonObject();
            isJson = true;
        } catch (Exception _) {
            isJson = false;
        }
        assertFalse(isJson,
                "envelope must be plain text, not a JSON object — got: " + result.response());
    }

    // ===== Helpers =====

    private Agent createAgent(String name, String provider, String model) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void startLlmServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", handler);
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private void configureProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    private AgentRunner.RunResult runOnVirtualThread(Agent agent, Conversation convo, String message) throws Exception {
        var agentId = agent.id;
        var convoId = convo.id;
        var resultRef = new AtomicReference<AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agentId));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                resultRef.set(AgentRunner.run(a, c, message));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "AgentRunner.run should complete within 60s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
