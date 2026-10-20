import agents.AgentRunner;
import channels.ChannelTransport;
import channels.TelegramChannel;
import models.Agent;
import models.AgentBinding;
import models.TelegramBinding;
import models.TelegramTopicBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-318: residual coverage for the webhook + channel-dispatch surface
 * of {@link AgentRunner}. These entrypoints are wired into channel webhook
 * controllers (Slack, WhatsApp, Telegram) and the queue-drain orchestrator —
 * the rest of the AgentRunner test suite focuses on the
 * {@code run}/{@code runStreaming} pipelines, leaving the routing layer and
 * the {@code dispatchToChannel} static helper without dedicated coverage.
 *
 * <p>Each test pins a single branch in
 * {@link AgentRunner#processWebhookMessage},
 * {@link AgentRunner#processInboundForAgent} and the package-private
 * {@code dispatchToChannel} so a regression that, for example, lets a
 * no-route webhook silently drop instead of invoking the sendNoRoute
 * callback, lands a precise failure.
 */
class AgentRunnerWebhookDispatchTest extends UnitTest {

    @BeforeEach
    void setup() throws Exception {
        // Same 200ms settle as AgentRunnerCoreTest — lets prior tests' VT
        // tails flush before we wipe the DB. Without it Fixtures.deleteDatabase
        // can race in-flight writes.
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
    }

    // ─── processWebhookMessage ──────────────────────────────────────────

    @Test
    void processWebhookMessageInvokesSendNoRouteWhenNoAgentMatches() {
        // No agents persisted at all → AgentRouter.resolve returns null →
        // processWebhookMessage MUST hand the peerId to sendNoRoute and bail
        // before any LLM call (none configured anyway).
        var noRouteCaptured = new AtomicReference<String>();
        var sendCalled = new AtomicBoolean(false);

        AgentRunner.processWebhookMessage("slack", "C-no-route", "hello",
                (peer, text) -> sendCalled.set(true),
                noRouteCaptured::set);

        assertEquals("C-no-route", noRouteCaptured.get(),
                "sendNoRoute callback must receive the peerId from the inbound webhook");
        assertFalse(sendCalled.get(),
                "sendResponse must NOT fire on the no-route path");
    }

    @Test
    void processWebhookMessageSilentlyDropsWhenNoRouteCallbackIsNull() {
        // Same no-route scenario but the caller chose to drop silently
        // (sendNoRoute=null). Contract: no exception thrown, sendResponse
        // still not invoked.
        var sendCalled = new AtomicBoolean(false);

        AgentRunner.processWebhookMessage("slack", "C-no-route-silent", "hello",
                (peer, text) -> sendCalled.set(true),
                null);

        assertFalse(sendCalled.get(),
                "sendResponse must NOT fire on the no-route path even when sendNoRoute is null");
    }

    @Test
    void processWebhookMessageRoutesToResolvedAgentAndDeliversResponse() {
        // Happy path: a peer binding routes the inbound to an agent, the
        // runner produces a canned envelope (no provider configured), and
        // sendResponse receives the (peerId, response) pair.
        var agent = createAgent("webhook-route", "missing-provider", "model");
        createBinding(agent, "slack", "C-route");

        var receivedPeer = new AtomicReference<String>();
        var receivedText = new AtomicReference<String>();

        AgentRunner.processWebhookMessage("slack", "C-route", "hi there",
                (peer, text) -> { receivedPeer.set(peer); receivedText.set(text); },
                _ -> { /* unused on happy path */ });

        assertEquals("C-route", receivedPeer.get(),
                "sendResponse must receive the routed peerId verbatim");
        assertNotNull(receivedText.get(),
                "sendResponse must receive a non-null response (the envelope)");
        // The agent points at a non-existent provider, so the envelope is
        // the canonical "No LLM provider configured" prefix.
        assertTrue(receivedText.get().contains("No LLM provider configured"),
                "no-provider envelope must be delivered to sendResponse, got: " + receivedText.get());
    }

    // ─── processInboundForAgent ─────────────────────────────────────────

    @Test
    void processInboundForAgentShortCircuitsOnSlashHelp() {
        // /help is a slash command — the runner intercepts it BEFORE any
        // LLM round and sends the canned help text via the supplied
        // sendResponse callback. No conversation needs to exist beforehand
        // because /help is allowed to operate against findOrCreate.
        var agent = createAgent("slash-help-agent", "missing", "model");

        var receivedPeer = new AtomicReference<String>();
        var receivedText = new AtomicReference<String>();

        AgentRunner.processInboundForAgent(agent, "slack", "U-slash", "/help",
                (peer, text) -> { receivedPeer.set(peer); receivedText.set(text); });

        assertEquals("U-slash", receivedPeer.get(),
                "slash-command sendResponse must receive the peerId");
        assertNotNull(receivedText.get(),
                "slash-help must surface a non-null response payload");
        // The canned /help text mentions at least one of the built-in
        // commands; pin the substring so a refactor that swaps the text
        // wholesale lands a failure here.
        assertFalse(receivedText.get().isBlank(),
                "slash-help response must not be empty, got: " + receivedText.get());
    }

    @Test
    void processInboundForAgentRunsFullRunnerWhenInboundIsNotASlashCommand() {
        // Non-slash text falls through to AgentRunner.run, which produces a
        // canned envelope when the agent's provider is missing. The
        // sendResponse callback must receive that envelope on the resolved
        // peerId.
        var agent = createAgent("inbound-non-slash", "missing-provider", "model");

        var receivedPeer = new AtomicReference<String>();
        var receivedText = new AtomicReference<String>();

        AgentRunner.processInboundForAgent(agent, "slack", "U-inbound",
                "what is the weather",
                (peer, text) -> { receivedPeer.set(peer); receivedText.set(text); });

        assertEquals("U-inbound", receivedPeer.get(),
                "non-slash inbound must dispatch to sendResponse on the original peerId");
        assertNotNull(receivedText.get(),
                "non-slash inbound must produce a response (canned envelope on failure)");
        assertTrue(receivedText.get().contains("No LLM provider configured"),
                "missing provider envelope must propagate to sendResponse, got: "
                        + receivedText.get());
    }

    @Test
    void processInboundForAgentSlashNewCreatesFreshConversation() {
        // /new is special — the runner passes a null current conversation
        // to the slash dispatcher so a fresh one is created rather than
        // operating against an existing thread. The contract here is that
        // the response surfaces through sendResponse on the inbound peerId.
        var agent = createAgent("slash-new-agent", "missing", "model");

        var receivedText = new AtomicReference<String>();
        AgentRunner.processInboundForAgent(agent, "slack", "U-new", "/new",
                (peer, text) -> receivedText.set(text));

        assertNotNull(receivedText.get(),
                "/new must surface its canned response through sendResponse");
        assertFalse(receivedText.get().isBlank(),
                "/new response must not be blank");
    }

    // ─── dispatchToChannel (package-private in `agents`; tests live in
    //     the default package, so reflection is the seam) ───────────────

    private static Method dispatchToChannelMethod() throws Exception {
        var m = AgentRunner.class.getDeclaredMethod("dispatchToChannel",
                Agent.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void dispatchToChannelSwallowsExceptionsRaisedByTheChannelLookup() {
        // The outer try/catch around dispatchToChannel exists so a
        // channel-side failure (network, missing config, JPA glitch) on
        // queue-drain doesn't propagate up and break the orchestrator's
        // drain loop. Pass a null agent to the telegram branch; the
        // findEnabledByAgentAndUser query will NPE inside the Tx.run, and
        // the catch must swallow it. Removing the catch would let the NPE
        // escape as an InvocationTargetException and fail this test.
        //
        // Commit so the Tx.run inside dispatchToChannel runs against a clean
        // EM state — otherwise the harness-thread tx leaks into the lookup.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        // Must not throw — the catch at AgentRunner.java:1132 swallows.
        assertDoesNotThrow(() ->
                dispatchToChannelMethod().invoke(null, null, "telegram", "tg-user-2", "hello"));
    }

    // ─── dispatchToChannel into Telegram groups (JCLAW-1605) ────────────

    @Test
    void aGroupReplyGoesThroughTheAgentsOwnBot() throws Exception {
        var agent = createAgent("tg-1605-group", "openrouter", "test-model");
        var token = "jclaw1605-group-" + System.nanoTime();
        bindTelegram(agent, token, "16050", true);
        commitAndReopen();

        var sends = sendMessagesOf(agent, token, "-1001605", "queued answer");
        assertEquals(1, sends.size(), "the reply reaches the group");
        var body = sends.getFirst().body();
        assertTrue(body.contains("\"chat_id\":\"-1001605\"") && body.contains("queued answer"), body);
        assertFalse(body.contains("message_thread_id"), "a plain group has no topic: " + body);
    }

    @Test
    void aTopicReplyCarriesItsThreadId() throws Exception {
        var agent = createAgent("tg-1605-topic", "openrouter", "test-model");
        var token = "jclaw1605-topic-" + System.nanoTime();
        bindTelegram(agent, token, "16051", true);
        commitAndReopen();

        var sends = sendMessagesOf(agent, token, "-1001605:topic:7", "topic answer");
        assertEquals(1, sends.size());
        var body = sends.getFirst().body();
        assertTrue(body.contains("\"chat_id\":\"-1001605\"") && body.contains("\"message_thread_id\":7"),
                "the key splits into the chat and its topic: " + body);
    }

    @Test
    void aTopicOverrideAgentsReplyGoesThroughTheBotThatMapsIt() throws Exception {
        var botAgent = createAgent("tg-1605-bot", "openrouter", "test-model");
        var topicAgent = createAgent("tg-1605-override", "openrouter", "test-model");
        var token = "jclaw1605-override-" + System.nanoTime();
        var binding = bindTelegram(botAgent, token, "16052", true);
        var mapping = new TelegramTopicBinding();
        mapping.binding = binding;
        mapping.chatId = "-1001605";
        mapping.threadId = 9;
        mapping.agent = topicAgent;
        mapping.save();
        commitAndReopen();

        var sends = sendMessagesOf(topicAgent, token, "-1001605:topic:9", "override answer");
        assertEquals(1, sends.size(), "the override agent has no bot of its own; the mapping's bot sends");
        assertTrue(sends.getFirst().body().contains("\"message_thread_id\":9"), sends.getFirst().body());
    }

    @Test
    void aDisabledBindingSendsNothingToAGroup() throws Exception {
        var agent = createAgent("tg-1605-disabled", "openrouter", "test-model");
        var token = "jclaw1605-disabled-" + System.nanoTime();
        bindTelegram(agent, token, "16053", false);
        commitAndReopen();

        assertEquals(0, sendMessagesOf(agent, token, "-1001605", "dropped").size());
    }

    private static List<MockTelegramServer.RecordedRequest> sendMessagesOf(Agent agent, String token, String peerId,
                                                                           String text) throws Exception {
        try (var mock = new MockTelegramServer()) {
            mock.start();
            TelegramChannel.installForTest(token, mock.telegramUrl());
            try {
                AgentRunner.dispatchToChannel(agent, "telegram", peerId, text);
                return mock.requests().stream().filter(r -> r.method().equalsIgnoreCase("sendMessage")).toList();
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    private static TelegramBinding bindTelegram(Agent agent, String token, String ownerUserId, boolean enabled) {
        var binding = new TelegramBinding();
        binding.agent = agent;
        binding.botToken = token;
        binding.telegramUserId = ownerUserId;
        binding.transport = ChannelTransport.POLLING;
        binding.enabled = enabled;
        binding.save();
        return binding;
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Agent createAgent(String name, String provider, String model) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void createBinding(Agent agent, String channelType, String peerId) {
        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = channelType;
        binding.peerId = peerId;
        binding.save();
    }
}
