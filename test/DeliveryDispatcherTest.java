import models.Agent;
import models.ChannelConfig;
import models.TelegramBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.DeliveryDispatcher;
import services.DeliveryDispatcher.DispatchResult;
import services.EventLogger;
import services.Tx;

/**
 * JCLAW-327 tests: the shared dispatch backbone for the {@code message}
 * tool and the (future) JCLAW-295 task-completion delivery wiring.
 *
 * <p>Tests focus on parsing / config-lookup paths that don't require live
 * channel APIs. Actual Telegram/Slack/WhatsApp HTTP delivery is exercised
 * by the channel-specific tests (TelegramChannelTest, etc.) — this file
 * confirms the dispatcher picks the right adapter and emits the right
 * error shapes when a config is missing.
 */
class DeliveryDispatcherTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
    }

    @AfterEach
    void teardown() {
        EventLogger.clear();
    }

    @Test
    void isSupportedKnowsTheDeliverableChannels() {
        assertTrue(DeliveryDispatcher.isSupported("telegram"));
        assertTrue(DeliveryDispatcher.isSupported("slack"));
        assertTrue(DeliveryDispatcher.isSupported("whatsapp"));
        // Web is supported too — routes to the calling agent's parent-chain
        // root conversation so the chat UI's poller picks the message up.
        assertTrue(DeliveryDispatcher.isSupported("web"));
        // Case-insensitive — channelType values are typically lowercase but
        // an LLM-supplied "Telegram" shouldn't trip the dispatcher.
        assertTrue(DeliveryDispatcher.isSupported("Telegram"));
        // Genuinely unsupported: subagent is an internal label, not a
        // user-watchable channel.
        assertFalse(DeliveryDispatcher.isSupported("subagent"));
        assertFalse(DeliveryDispatcher.isSupported("discord"));
        assertFalse(DeliveryDispatcher.isSupported(null));
        assertFalse(DeliveryDispatcher.isSupported(""));
    }

    @Test
    void dispatchWebWritesMessageToParentChainRoot() {
        var parent = createAgent("ds-web-parent");
        var rootConv = Tx.run(() -> {
            var c = services.ConversationService.create(parent, "web", "admin");
            return c;
        });
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "web", "ignored", "live progress 42%"));
        assertTrue(result.ok(), "web dispatch must succeed when an active conversation exists, got: " + result.reason());
        // Verify the message landed on the root conversation with the right
        // shape (USER role, messageKind="subagent_send", metadata source).
        var msgs = Tx.run(() -> models.Message.find(
                "conversation = ?1 ORDER BY id ASC", rootConv).fetch());
        assertEquals(1, msgs.size(), "exactly one message appended to the root conversation");
        var msg = (models.Message) msgs.get(0);
        assertEquals("user", msg.role, "web routing writes USER-role so the chat UI shows it");
        assertEquals("live progress 42%", msg.content);
        assertEquals("subagent_send", msg.messageKind,
                "uses the existing chat-UI render path for agent-initiated messages");
        assertNotNull(msg.metadata);
        assertTrue(msg.metadata.contains("\"source\":\"message_tool\""),
                "metadata distinguishes message_tool sends from conversation_send: " + msg.metadata);
    }

    @Test
    void dispatchWebWalksUpToRootWhenCalledFromSubagentChain() {
        var parent = createAgent("ds-web-walkup-p");
        var child = createAgent("ds-web-walkup-c");
        var rootId = Tx.run(() -> {
            var rootConv = services.ConversationService.create(parent, "web", "admin");
            var childConv = services.ConversationService.create(child, "web", "admin");
            childConv.parentConversation = rootConv;
            childConv.save();
            return rootConv.id;
        });
        // Dispatch from the CHILD agent — the walker should climb to the
        // root (parent's conversation) and write there, not the child's.
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(child, "web", "ignored", "from subagent"));
        assertTrue(result.ok(), "web dispatch from subagent chain must succeed, got: " + result.reason());
        var rootConv = Tx.run(() -> (models.Conversation) models.Conversation.findById(rootId));
        var msgs = Tx.run(() -> models.Message.find(
                "conversation = ?1 ORDER BY id ASC", rootConv).fetch());
        assertEquals(1, msgs.size(),
                "message landed on the parent-chain root, not the child conversation");
        assertEquals("from subagent", ((models.Message) msgs.get(0)).content);
    }

    @Test
    void dispatchWebWithoutActiveConversationFails() {
        var parent = createAgent("ds-web-nogconv");
        // No conversations seeded — dispatch should fail with a clear hint.
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "web", "ignored", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_DELIVERY, result.status());
        assertTrue(result.reason().contains("No active conversation"),
                "error should name the missing-conversation root cause: " + result.reason());
    }

    @Test
    void dispatchSpecParsesChannelColonTarget() {
        var parent = createAgent("ds-spec-ok");
        // No Telegram binding configured for this agent — exercise the
        // parser, not the actual send. We expect FAILED_NO_CONFIG, NOT
        // a parse error.
        var result = Tx.run(() -> DeliveryDispatcher.dispatchSpec(parent, "telegram:12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status(),
                "parser successfully routed to Telegram; only config was missing");
        assertTrue(result.reason().contains("Telegram binding") || result.reason().contains("not configured"));
    }

    @Test
    void dispatchSpecRejectsMissingColon() {
        var parent = createAgent("ds-no-colon");
        var result = Tx.run(() -> DeliveryDispatcher.dispatchSpec(parent, "telegram12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_DELIVERY, result.status());
        assertTrue(result.reason().contains("channel:target"),
                "error message must reference the expected format, got: " + result.reason());
    }

    @Test
    void dispatchSpecRejectsLeadingOrTrailingEmpty() {
        var parent = createAgent("ds-empty-parts");
        // Leading empty (":12345") should fail format
        var r1 = Tx.run(() -> DeliveryDispatcher.dispatchSpec(parent, ":12345", "hi"));
        assertFalse(r1.ok());
        // Trailing empty ("telegram:") should fail format
        var r2 = Tx.run(() -> DeliveryDispatcher.dispatchSpec(parent, "telegram:", "hi"));
        assertFalse(r2.ok());
    }

    @Test
    void dispatchUnsupportedChannelReturnsUnsupported() {
        var parent = createAgent("ds-unsupp");
        // discord is not in JClaw's channel set today (no DiscordChannel
        // adapter). Web is now a supported channel so it can't fill this role.
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "discord", "anything", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.CHANNEL_UNSUPPORTED, result.status());
        assertTrue(result.reason().contains("telegram") && result.reason().contains("slack")
                        && result.reason().contains("whatsapp"),
                "unsupported error should hint at the supported channel set: " + result.reason());
    }

    @Test
    void telegramWithoutBindingReturnsNoConfigWithSetupHint() {
        var parent = createAgent("ds-tg-nobind");
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "telegram", "12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status());
        // AC-6: clear error with setup instructions
        assertTrue(result.reason().contains("Settings → Channels → Telegram")
                        || result.reason().contains("api/telegram-bindings"),
                "no-config error should point to where to configure: " + result.reason());
    }

    @Test
    void telegramWithDisabledBindingReturnsNoConfig() {
        var parent = createAgent("ds-tg-disabled");
        Tx.run(() -> {
            var binding = new TelegramBinding();
            binding.agent = parent;
            binding.botToken = "fake-token-for-disabled-binding";
            binding.telegramUserId = "999";
            binding.enabled = false;
            binding.save();
        });
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "telegram", "12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status());
        assertTrue(result.reason().contains("disabled"),
                "disabled-binding error should say so: " + result.reason());
    }

    @Test
    void telegramDispatchFromSubagentInheritsParentBinding() {
        // Sub-agents created by subagent_spawn are fresh Agent rows with no
        // Telegram binding of their own — only the user-facing root agent
        // (e.g. 'main') has a binding. Without parent-chain walk, a sub-agent's
        // outbound message tool call fails with "Channel 'telegram' is not
        // configured" even though the user IS reachable via the root's bot.
        //
        // Wire up: parent with a disabled binding, child whose parentAgent
        // points at the parent. Dispatching from the child must resolve up to
        // the parent's binding (so we see the "disabled" error from the
        // parent's binding rather than the "not configured" error). The
        // disabled-binding short-circuit lets us assert the walk happened
        // without any HTTP traffic.
        var parent = createAgent("ds-tg-parent-with-binding");
        var child = Tx.run(() -> {
            var c = AgentService.create("ds-tg-child-no-binding", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });
        Tx.run(() -> {
            var binding = new TelegramBinding();
            binding.agent = parent;
            binding.botToken = "fake-token-for-parent-binding-walk";
            binding.telegramUserId = "12345";
            binding.enabled = false;
            binding.save();
        });
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(child, "telegram", "12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status());
        assertTrue(result.reason().contains("disabled"),
                "child dispatch should hit the parent's binding (disabled) rather than "
                        + "the 'no binding' branch: " + result.reason());
        // Cross-check: the error names the binding-owning agent (parent),
        // not the calling agent (child) — so an operator following the hint
        // toggles the right binding.
        assertTrue(result.reason().contains(parent.name),
                "disabled-binding error should name the binding's owning agent: " + result.reason());
    }

    @Test
    void telegramDispatchFromSubagentWithNoBindingChainReturnsNoConfig() {
        // Sub-agent whose ancestor chain has NO binding anywhere — the
        // walk should still terminate cleanly and return NO_CONFIG with
        // the helpful hint, not silently fail or loop.
        var orphanParent = createAgent("ds-tg-orphan-parent");
        var child = Tx.run(() -> {
            var c = AgentService.create("ds-tg-orphan-child", "openrouter", "gpt-4.1");
            c.parentAgent = orphanParent;
            c.save();
            return c;
        });
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(child, "telegram", "12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status());
        assertTrue(result.reason().contains("ancestors"),
                "no-binding-anywhere error should mention ancestors so the operator knows "
                        + "binding the root agent will fix it: " + result.reason());
    }

    @Test
    void slackWithoutConfigReturnsNoConfig() {
        var parent = createAgent("ds-slack-nocfg");
        // No ChannelConfig row for 'slack' — dispatch should short-circuit
        // before any HTTP call.
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "slack", "C123", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status());
        assertTrue(result.reason().contains("Slack"));
    }

    @Test
    void whatsappWithoutConfigReturnsNoConfig() {
        var parent = createAgent("ds-wa-nocfg");
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "whatsapp", "+15551234567", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_NO_CONFIG, result.status());
        assertTrue(result.reason().contains("WhatsApp"));
    }

    @Test
    void dispatchRejectsBlankTarget() {
        var parent = createAgent("ds-blank-target");
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "telegram", "", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_DELIVERY, result.status());
        assertTrue(result.reason().toLowerCase().contains("target"),
                "blank-target error should name the missing field: " + result.reason());
    }

    @Test
    void dispatchTelegramWithoutAgentReturnsHelpfulError() {
        // Agent context is required for Telegram (per-binding token lookup).
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(null, "telegram", "12345", "hi"));
        assertFalse(result.ok());
        assertEquals(DispatchResult.Status.FAILED_DELIVERY, result.status());
        assertTrue(result.reason().toLowerCase().contains("agent"),
                "missing-agent error must explain the requirement: " + result.reason());
    }

    // ──────── helpers ────────

    private Agent createAgent(String name) {
        return Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }
}
