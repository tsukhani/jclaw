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
    void isSupportedKnowsTheThreeDeliverableChannels() {
        assertTrue(DeliveryDispatcher.isSupported("telegram"));
        assertTrue(DeliveryDispatcher.isSupported("slack"));
        assertTrue(DeliveryDispatcher.isSupported("whatsapp"));
        // Case-insensitive — channelType values are typically lowercase but
        // an LLM-supplied "Telegram" shouldn't trip the dispatcher.
        assertTrue(DeliveryDispatcher.isSupported("Telegram"));
        // Non-deliverable: 'web' is the JClaw UI channel, no external send.
        assertFalse(DeliveryDispatcher.isSupported("web"));
        assertFalse(DeliveryDispatcher.isSupported("subagent"));
        assertFalse(DeliveryDispatcher.isSupported(null));
        assertFalse(DeliveryDispatcher.isSupported(""));
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
        var result = Tx.run(() -> DeliveryDispatcher.dispatch(parent, "web", "anything", "hi"));
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
