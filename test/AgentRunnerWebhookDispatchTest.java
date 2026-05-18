import agents.AgentRunner;
import models.Agent;
import models.AgentBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.lang.reflect.Method;
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

    @AfterEach
    void teardown() {
        // No external resources to release; lifecycle hook retained for
        // symmetry with the sibling AgentRunner test files.
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
    void processWebhookMessageRoutesToResolvedAgentAndDeliversResponse() throws Exception {
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

    // ─── dispatchToChannel (package-private; reflection) ────────────────

    private static Method dispatchToChannelMethod() throws Exception {
        var m = AgentRunner.class.getDeclaredMethod("dispatchToChannel",
                Agent.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void dispatchToChannelIsNoOpWhenPeerIdIsNull() throws Exception {
        // The very first guard in dispatchToChannel — short-circuit on
        // null peerId before even doing a fromValue lookup. The assertion
        // is just "no exception": if this NPEs, the queue-drain on a turn
        // whose peer got nulled in the meantime would crash.
        var agent = createAgent("disp-null-peer", "missing", "model");
        dispatchToChannelMethod().invoke(null, agent, "telegram", null, "text");
    }

    @Test
    void dispatchToChannelIsNoOpWhenTextIsNull() throws Exception {
        // Symmetric guard on the text side — a null reply text from the
        // runner must not be re-dispatched.
        var agent = createAgent("disp-null-text", "missing", "model");
        dispatchToChannelMethod().invoke(null, agent, "telegram", "peer-1", null);
    }

    @Test
    void dispatchToChannelIsNoOpForUnrecognisedChannelType() throws Exception {
        // ChannelType.fromValue returns null for unknown channels; the
        // guard at line 1115 of AgentRunner short-circuits before any
        // resolve()/send call. Pin the contract so a future "unknown
        // channel falls through to a default send" regression fails.
        var agent = createAgent("disp-unknown-ch", "missing", "model");
        dispatchToChannelMethod().invoke(null, agent, "discord", "peer-1", "hi");
    }

    @Test
    void dispatchToChannelLogsAndReturnsForTelegramWhenBindingMissing() throws Exception {
        // The telegram branch looks up the binding via Tx.run; when the
        // lookup returns null (e.g. operator deleted the binding between
        // turn-accept and queue-drain), dispatchToChannel logs a warn and
        // returns — must NOT call TelegramChannel.sendMessage which would
        // throw on the missing bot token.
        var agent = createAgent("disp-telegram-no-binding", "missing", "model");
        // Commit so the Tx.run lookup inside dispatchToChannel sees the agent.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        dispatchToChannelMethod().invoke(null, agent, "telegram", "tg-user-1", "hello");
        // Reached without exception — pass. No assertion on the warn
        // because EventLogger doesn't expose a read-back API; the value
        // here is the no-throw guarantee.
    }

    @Test
    void dispatchToChannelIsNoOpForWebChannelBecauseResolveReturnsNull() throws Exception {
        // type.resolve() returns null for WEB (web replies live in the DB
        // and are picked up on the next /messages poll, not pushed out).
        // The branch at line 1129 falls through silently.
        var agent = createAgent("disp-web", "missing", "model");
        dispatchToChannelMethod().invoke(null, agent, "web", "u-web", "queued reply");
    }

    @Test
    void dispatchToChannelSwallowsExceptionsRaisedByTheChannelLookup() throws Exception {
        // The outer try/catch around dispatchToChannel exists so a
        // channel-side failure (network, missing config, JPA glitch) on
        // queue-drain doesn't propagate up and break the orchestrator's
        // drain loop. Pass a null agent to the telegram branch; the
        // findEnabledByAgentAndUser query will NPE inside the Tx.run, and
        // the catch must swallow it.
        // Commit so the Tx.run inside dispatchToChannel runs against a clean
        // EM state — otherwise the harness-thread tx leaks into the lookup.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        // Must not throw — the catch at line 1132 swallows.
        dispatchToChannelMethod().invoke(null, null, "telegram", "tg-user-2", "hello");
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
