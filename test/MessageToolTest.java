import agents.ToolRegistry;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import models.Agent;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.MessageTool;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * JCLAW-327 tests: the {@code message} tool's argument parsing, channel +
 * target inference, and error envelope shapes. The actual delivery (HTTP
 * to Telegram/Slack/WhatsApp) is exercised by the channel-specific tests
 * and by DeliveryDispatcherTest; here we pin the tool-layer contract.
 */
class MessageToolTest extends UnitTest {

    // JCLAW-374: action capability toggles, reset around each test so a
    // previous test's override can't leak into the next.
    private static final String CFG_DELETE = "telegram.actions.delete";
    private static final String CFG_PIN = "telegram.actions.pin";
    private static final String CFG_REACT = "telegram.actions.react";
    // JCLAW-381: reply + edit toggles (both default ON).
    private static final String CFG_REPLY = "telegram.actions.reply";
    private static final String CFG_EDIT = "telegram.actions.edit";
    // JCLAW-387 (C1): poll toggle (default ON).
    private static final String CFG_POLL = "telegram.actions.poll";
    // Reply-target attach policy (TelegramChannel.replyToMode); pinned to "all"
    // in the reply test so the quoted message id is deterministically on the wire.
    private static final String CFG_REPLY_TO_MODE = "telegram.replyTo.mode";

    private static final String BOT_TOKEN = "374:msg-action-bot";
    private static final String CHAT_ID = "424242";

    private Agent agent;
    private MockTelegramServer server;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        clearActionConfig();
        new jobs.ToolRegistrationJob().doJob();
        agent = AgentService.create("msg-test", "openrouter", "gpt-4.1");
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
    }

    @AfterEach
    void teardown() {
        EventLogger.clear();
        clearActionConfig();
        if (server != null) server.close();
        TelegramChannel.clearForTest(BOT_TOKEN);
    }

    private static void clearActionConfig() {
        play.Play.configuration.remove(CFG_DELETE);
        play.Play.configuration.remove(CFG_PIN);
        play.Play.configuration.remove(CFG_REACT);
        play.Play.configuration.remove(CFG_REPLY);
        play.Play.configuration.remove(CFG_EDIT);
        play.Play.configuration.remove(CFG_POLL);
    }

    /** Bind {@link #BOT_TOKEN} to {@link #agent} and seed a Telegram
     *  conversation whose peer is {@link #CHAT_ID}, so the action's chat id
     *  resolves by inference when no explicit target is passed. */
    private void seedTelegramBindingAndConversation() {
        Tx.run(() -> {
            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = BOT_TOKEN;
            b.telegramUserId = "u-1";
            b.enabled = true;
            b.save();
            ConversationService.create(agent, "telegram", CHAT_ID);
        });
    }

    @Test
    void toolIsRegisteredAndDiscoverable() {
        var tool = ToolRegistry.lookupTool(MessageTool.TOOL_NAME);
        assertNotNull(tool, "message must be registered by ToolRegistrationJob");
        assertEquals(MessageTool.TOOL_NAME, tool.name());
        assertEquals("System", tool.category());
    }

    @Test
    void missingActionReturnsError() throws Exception {
        var result = invokeTool(agent.id, "{\"message\":\"hi\"}");
        assertTrue(result.startsWith("Error: 'action' is required"), result);
    }

    @Test
    void unknownActionRejectedWithAllowedSetHint() throws Exception {
        // JCLAW-381: reply/edit/delete/pin/unpin/react are all supported now;
        // an action outside the allowed set (e.g. "forward") still errors and
        // the error names the allowed set.
        var result = invokeTool(agent.id,
                "{\"action\":\"forward\",\"message\":\"hi\",\"channel\":\"telegram\",\"target\":\"1\"}");
        assertTrue(result.startsWith("Error: 'action' must be one of"), result);
        assertTrue(result.contains("reply") && result.contains("edit"),
                "rejection should list reply/edit among the supported actions: " + result);
    }

    @Test
    void missingMessageReturnsError() throws Exception {
        var result = invokeTool(agent.id, "{\"action\":\"send\"}");
        assertTrue(result.startsWith("Error: 'message' is required"), result);
    }

    @Test
    void unsupportedChannelOnExplicitOverrideReturnsClearError() throws Exception {
        // Explicit channel="discord" — not in the dispatcher's supported set.
        // Web IS supported now (routes to the parent-chain root conversation),
        // so use discord as the genuinely-unsupported channel for this test.
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"hi\",\"channel\":\"discord\",\"target\":\"u-1\"}");
        assertTrue(result.startsWith("Error: channel 'discord' is not a deliverable"), result);
        assertTrue(result.contains("telegram") && result.contains("slack") && result.contains("whatsapp")
                        && result.contains("web"),
                "unsupported error should hint at the supported channel set: " + result);
    }

    @Test
    void webChannelExplicitDispatchSucceeds() throws Exception {
        // Web routing requires an active conversation to walk up to.
        Tx.run(() -> ConversationService.create(agent, "web", "admin"));
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"hi from agent\",\"channel\":\"web\"}");
        // Web dispatch doesn't need an explicit target — the dispatcher walks
        // to the agent's parent-chain root conversation.
        var parsed = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        assertEquals("sent", parsed.get("action").getAsString(), "web dispatch must succeed: " + result);
        assertEquals("web", parsed.get("channel").getAsString());
    }

    @Test
    void webChannelInferenceFromWebChatSucceeds() throws Exception {
        // Inference: an active web conversation should resolve channel="web"
        // and dispatch successfully (no longer the 'not deliverable' error
        // that the v0.12.40 build returned for the same shape).
        Tx.run(() -> ConversationService.create(agent, "web", "admin"));
        var result = invokeTool(agent.id, "{\"action\":\"send\",\"message\":\"radarr 45%\"}");
        var parsed = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        assertEquals("sent", parsed.get("action").getAsString(), result);
        assertEquals("web", parsed.get("channel").getAsString(),
                "channel must be inferred from the active web conversation");
    }

    @Test
    void inferenceUsesCallingAgentsActiveConversation() throws Exception {
        // Conversation in a Telegram-bound thread — channel + peer should be
        // inferred from this row.
        Tx.run(() -> ConversationService.create(agent, "telegram", "878224171"));
        // No Telegram binding configured — we expect FAILED_NO_CONFIG, which
        // is the proof that inference picked up channel="telegram"
        // (otherwise we'd get an unsupported-channel error or a different
        // shape).
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"⬇ 45%\"}");
        assertTrue(result.startsWith("Error: "), "expected error from missing config, got: " + result);
        assertTrue(result.contains("Telegram") || result.contains("telegram"),
                "error must name the inferred channel as Telegram: " + result);
        assertTrue(result.contains("not configured") || result.contains("Connect a Telegram"),
                "no-config error should hint at setup: " + result);
    }

    @Test
    void inferenceFallsBackToErrorWhenNoActiveConversation() throws Exception {
        // Agent exists, no conversations seeded.
        var result = invokeTool(agent.id, "{\"action\":\"send\",\"message\":\"hi\"}");
        assertTrue(result.startsWith("Error: no active conversation"), result);
    }

    @Test
    void inferenceFromSlackConversationWithoutPeerAndNoBindingErrorsNotConfigured() throws Exception {
        // Inference picks channelType="slack" from the active conversation; the null
        // peerId leaves no conversation target, so JCLAW-425 falls back to the agent's
        // per-agent Slack binding destination. With no binding either, the tool returns
        // a clear, agent-named "not configured" error (not the old generic missing-target
        // message). Web wouldn't trip this (target is ignored for web routing), and
        // telegram reads its chat id from the binding, not the conversation peer.
        Tx.run(() -> ConversationService.create(agent, "slack", null));
        var result = invokeTool(agent.id, "{\"action\":\"send\",\"message\":\"hi\"}");
        assertTrue(result.startsWith("Error: no Slack destination configured"),
                "missing peer + no Slack binding should surface the per-agent not-configured error: " + result);
        assertTrue(result.contains(agent.name), "the not-configured error must name the agent: " + result);
    }

    // ──────── JCLAW-425: per-agent proactive-send destinations (slack/whatsapp) ────────

    @Test
    void slackProactiveSendResolvesDestinationFromBindingOwner() throws Exception {
        // No conversation at all. A Slack binding carries the owner as its per-agent
        // destination. A proactive send (explicit channel, no target) must resolve the
        // target from SlackBinding.ownerUserId (JCLAW-425) — proven by reaching the
        // dispatcher's "disabled" branch (binding found, delivery gated) rather than the
        // pre-dispatch "no Slack destination" error. The binding is disabled so dispatch
        // short-circuits before any network call, keeping the test deterministic.
        Tx.run(() -> {
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = "xoxb-425-owner";
            b.signingSecret = "sec";
            b.ownerUserId = "U-OWNER";
            b.enabled = false;
            b.save();
        });
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"daily briefing\",\"channel\":\"slack\"}");
        assertTrue(result.startsWith("Error: "), result);
        assertFalse(result.contains("no Slack destination"),
                "the target must resolve from the binding owner, not hit the no-destination error: " + result);
        assertTrue(result.contains("disabled"),
                "with the target resolved, dispatch proceeds and hits the disabled-binding branch: " + result);
    }

    @Test
    void whatsappCloudApiProactiveSendResolvesDestinationFromDefaultTarget() throws Exception {
        // Cloud-API binding with a configured default recipient. A proactive whatsapp
        // send with no conversation resolves the target from WhatsAppBinding.defaultTarget
        // (JCLAW-425). Disabled so dispatch short-circuits before the Graph call.
        Tx.run(() -> {
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.CLOUD_API;
            b.phoneNumberId = "pn-425";
            b.accessToken = "tok";
            b.defaultTarget = "+15551234567";
            b.enabled = false;
            b.save();
        });
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"briefing\",\"channel\":\"whatsapp\"}");
        assertTrue(result.startsWith("Error: "), result);
        assertFalse(result.contains("no WhatsApp destination"),
                "the target must resolve from the Cloud-API defaultTarget, not the no-destination error: " + result);
        assertTrue(result.contains("disabled"),
                "with the target resolved, dispatch proceeds and hits the disabled-binding branch: " + result);
    }

    @Test
    void whatsappWebProactiveSendResolvesDestinationFromOwnerJid() throws Exception {
        // WhatsApp-Web binding: its per-agent destination is the paired owner's JID.
        // A proactive send with no conversation resolves the target from
        // WhatsAppBinding.ownerJid (JCLAW-425). Disabled to short-circuit dispatch.
        Tx.run(() -> {
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.WHATSAPP_WEB;
            b.ownerJid = "15559998888@s.whatsapp.net";
            b.enabled = false;
            b.save();
        });
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"briefing\",\"channel\":\"whatsapp\"}");
        assertTrue(result.startsWith("Error: "), result);
        assertFalse(result.contains("no WhatsApp destination"),
                "the WhatsApp-Web target must resolve from ownerJid, not the no-destination error: " + result);
        assertTrue(result.contains("disabled"),
                "with the target resolved, dispatch proceeds and hits the disabled-binding branch: " + result);
    }

    @Test
    void whatsappCloudApiWithoutDefaultTargetRequiresExplicitTarget() throws Exception {
        // The Cloud-API carve-out (JCLAW-425): a business number receives from many
        // customers, so a Cloud-API binding with no defaultTarget has NO per-agent
        // recipient. A proactive send with no conversation and no explicit target must
        // surface the clear, agent-named not-configured error rather than guessing.
        Tx.run(() -> {
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.CLOUD_API;
            b.phoneNumberId = "pn-425b";
            b.accessToken = "tok";
            b.enabled = true; // enabled — the gap is the missing recipient, not the binding
            b.save();
        });
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"hi\",\"channel\":\"whatsapp\"}");
        assertTrue(result.startsWith("Error: no WhatsApp destination configured"),
                "a Cloud-API binding without a default recipient must require an explicit target: " + result);
        assertTrue(result.contains(agent.name), "the not-configured error must name the agent: " + result);
    }

    @Test
    void explicitChannelAndTargetOverrideInference() throws Exception {
        // Active conversation says telegram, but the call overrides to slack.
        Tx.run(() -> ConversationService.create(agent, "telegram", "12345"));
        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"hi\",\"channel\":\"slack\",\"target\":\"C123\"}");
        // Slack isn't configured — proof the override was honored (and not
        // silently substituted by inferred telegram).
        assertTrue(result.startsWith("Error: "), result);
        assertTrue(result.contains("Slack") || result.contains("slack"),
                "error should name the overridden channel as Slack: " + result);
    }

    @Test
    void mostRecentlyUpdatedConversationWinsForInference() throws Exception {
        // Seed two conversations, the second one more recently — inference
        // should pick the newer one's channel/peer.
        Tx.run(() -> {
            ConversationService.create(agent, "slack", "C-OLD");
            try { Thread.sleep(5); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            ConversationService.create(agent, "telegram", "999");
        });
        var result = invokeTool(agent.id, "{\"action\":\"send\",\"message\":\"hi\"}");
        // We expect a Telegram-shaped error (latest convo), not Slack.
        assertTrue(result.contains("Telegram") || result.contains("telegram"),
                "should infer most-recent convo (telegram, 999), got: " + result);
    }

    @Test
    void telegramTargetResolvesFromBindingNotFromConversationPeer() throws Exception {
        // The telegram chat id must come from the agent's Telegram BINDING (the channel
        // setting), with NO dependency on a prior conversation — so a proactive send
        // (e.g. a scheduled task firing in a web/internal context) reaches the user even
        // with no relevant chat history. seedTelegramBindingAndConversation sets the
        // binding's telegram_user_id to "u-1" AND a telegram conversation whose peer is
        // CHAT_ID; a more-recent web conversation has peer "admin". A channel=telegram
        // send must target the binding's "u-1" — not the telegram conv peer (CHAT_ID)
        // and not the web peer ("admin", which Telegram rejects with "chat not found").
        seedTelegramBindingAndConversation(); // binding telegram_user_id="u-1", telegram conv peer=CHAT_ID
        Tx.run(() -> {
            try { Thread.sleep(5); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            ConversationService.create(agent, "web", "admin"); // more-recent web conv
        });

        var result = invokeTool(agent.id,
                "{\"action\":\"send\",\"message\":\"daily briefing\",\"channel\":\"telegram\"}");

        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("sent", parsed.get("action").getAsString(),
                "telegram send must succeed by resolving the target from the binding: " + result);
        assertEquals(1, server.countRequests("sendMessage"), "exactly one telegram send");
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(body.contains("u-1"),
                "the send must target the binding's telegram_user_id 'u-1', not any conversation peer: " + body);
        assertTrue(!body.contains(CHAT_ID) && !body.contains("admin"),
                "the send must NOT use a conversation peer (" + CHAT_ID + " / admin): " + body);
    }

    // ──────── JCLAW-374: Telegram message actions ────────

    @Test
    void deleteActionCallsDeleteMessageWhenEnabled() throws Exception {
        // delete defaults ON; chat id inferred from the active telegram convo.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"delete\",\"message_id\":7}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals("delete", parsed.get("action").getAsString());
        assertEquals(1, server.countRequests("deleteMessage"),
                "delete must hit the deleteMessage endpoint exactly once");
    }

    @Test
    void pinActionCallsPinChatMessageWhenEnabled() throws Exception {
        // pin defaults OFF — operator must opt in.
        play.Play.configuration.setProperty(CFG_PIN, "true");
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"pin\",\"message_id\":9}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals(1, server.countRequests("pinChatMessage"),
                "pin must hit the pinChatMessage endpoint exactly once");
    }

    @Test
    void unpinActionCallsUnpinChatMessageWhenEnabled() throws Exception {
        // unpin shares the pin toggle (default OFF).
        play.Play.configuration.setProperty(CFG_PIN, "true");
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"unpin\",\"message_id\":9}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals(1, server.countRequests("unpinChatMessage"),
                "unpin must hit the unpinChatMessage endpoint exactly once");
    }

    @Test
    void reactActionCallsSetMessageReactionWithEmojiWhenEnabled() throws Exception {
        // react defaults ON; the emoji must reach the wire body.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"react\",\"message_id\":11,\"emoji\":\"👍\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals(1, server.countRequests("setMessageReaction"),
                "react must hit the setMessageReaction endpoint once");
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("setMessageReaction"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(body.contains("👍"),
                "the chosen emoji must appear in the setMessageReaction body: " + body);
    }

    @Test
    void reactActionWithBlankEmojiClearsReaction() throws Exception {
        // A blank emoji clears the bot's reaction — the SDK sends an empty
        // reaction list; the request still hits setMessageReaction.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"react\",\"message_id\":12,\"emoji\":\"\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals(1, server.countRequests("setMessageReaction"),
                "clear-react must still hit setMessageReaction once");
    }

    @Test
    void disabledDeleteIsRefusedWithoutApiCall() throws Exception {
        play.Play.configuration.setProperty(CFG_DELETE, "false");
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"delete\",\"message_id\":7}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("not-enabled", parsed.get("status").getAsString(), result);
        assertEquals(0, server.countRequests("deleteMessage"),
                "a disabled delete must NOT touch the Telegram API");
    }

    @Test
    void disabledPinByDefaultIsRefusedWithoutApiCall() throws Exception {
        // No config override — pin defaults OFF, so it should be refused.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"pin\",\"message_id\":9}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("not-enabled", parsed.get("status").getAsString(),
                "pin must default to disabled: " + result);
        assertEquals(0, server.countRequests("pinChatMessage"),
                "a disabled pin must NOT touch the Telegram API");
    }

    @Test
    void telegramActionMissingMessageIdReturnsError() throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"delete\"}");
        assertTrue(result.startsWith("Error: 'message_id' is required"), result);
    }

    @Test
    void telegramActionWithExplicitTargetUsesIt() throws Exception {
        // No conversation seeded — only the binding. Explicit target supplies
        // the chat id, proving target overrides inference.
        Tx.run(() -> {
            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = BOT_TOKEN;
            b.telegramUserId = "u-1";
            b.enabled = true;
            b.save();
        });
        var result = invokeTool(agent.id,
                "{\"action\":\"delete\",\"message_id\":7,\"target\":\"" + CHAT_ID + "\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals(1, server.countRequests("deleteMessage"));
    }

    @Test
    void telegramActionWithoutBindingReturnsError() throws Exception {
        // Conversation present (so chat id resolves) but no binding at all.
        Tx.run(() -> ConversationService.create(agent, "telegram", CHAT_ID));
        var result = invokeTool(agent.id, "{\"action\":\"delete\",\"message_id\":7}");
        assertTrue(result.startsWith("Error: no Telegram bot is connected"), result);
        assertEquals(0, server.countRequests("deleteMessage"));
    }

    // ──────── JCLAW-381: reply + edit message actions ────────

    @Test
    void replyActionCallsSendMessageWithReplyTarget() throws Exception {
        // reply defaults ON; chat id inferred from the active telegram convo.
        // It must hit sendMessage with the supplied text and quote message_id 7.
        // Pin replyTo.mode=all so the reply target is attached unconditionally
        // (default FIRST also attaches it on a single-chunk send, but pinning
        // it here keeps the wire-body assertion deterministic).
        play.Play.configuration.setProperty(CFG_REPLY_TO_MODE, "all");
        try {
            seedTelegramBindingAndConversation();
            var result = invokeTool(agent.id,
                    "{\"action\":\"reply\",\"message_id\":7,\"message\":\"replytoken\"}");
            var parsed = JsonParser.parseString(result).getAsJsonObject();
            assertEquals("ok", parsed.get("status").getAsString(), result);
            assertEquals("reply", parsed.get("action").getAsString());
            assertEquals(1, server.countRequests("sendMessage"),
                    "reply must hit the sendMessage endpoint exactly once");
            var body = server.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .reduce("", (a, b) -> a + b);
            assertTrue(body.contains("replytoken"),
                    "the reply text must appear in the sendMessage body: " + body);
            assertTrue(body.contains("7"),
                    "the reply target message id must appear in the sendMessage body: " + body);
        } finally {
            play.Play.configuration.remove(CFG_REPLY_TO_MODE);
        }
    }

    @Test
    void editActionCallsEditMessageTextWithNewText() throws Exception {
        // edit defaults ON; the new text must reach the editMessageText body.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"edit\",\"message_id\":8,\"message\":\"editedtoken\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals("edit", parsed.get("action").getAsString());
        assertEquals(1, server.countRequests("editMessageText"),
                "edit must hit the editMessageText endpoint exactly once");
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("editMessageText"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(body.contains("editedtoken"),
                "the new text must appear in the editMessageText body: " + body);
    }

    @Test
    void disabledReplyIsRefusedWithoutApiCall() throws Exception {
        play.Play.configuration.setProperty(CFG_REPLY, "false");
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"reply\",\"message_id\":7,\"message\":\"hi\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("not-enabled", parsed.get("status").getAsString(), result);
        assertEquals(0, server.countRequests("sendMessage"),
                "a disabled reply must NOT touch the Telegram API");
    }

    @Test
    void disabledEditIsRefusedWithoutApiCall() throws Exception {
        play.Play.configuration.setProperty(CFG_EDIT, "false");
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"edit\",\"message_id\":8,\"message\":\"hi\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("not-enabled", parsed.get("status").getAsString(), result);
        assertEquals(0, server.countRequests("editMessageText"),
                "a disabled edit must NOT touch the Telegram API");
    }

    static Stream<Arguments> missingRequiredFieldCases() {
        return Stream.of(
            // action, input JSON, expected error prefix, zero-count API method
            Arguments.of("reply", "{\"action\":\"reply\",\"message\":\"hi\"}",
                    "Error: 'message_id' is required", "sendMessage"),
            Arguments.of("edit",  "{\"action\":\"edit\",\"message\":\"hi\"}",
                    "Error: 'message_id' is required", "editMessageText"),
            // reply requires a `message` body; message_id alone is not enough.
            Arguments.of("reply", "{\"action\":\"reply\",\"message_id\":7}",
                    "Error: 'message' is required",    "sendMessage"),
            Arguments.of("edit",  "{\"action\":\"edit\",\"message_id\":8}",
                    "Error: 'message' is required",    "editMessageText")
        );
    }

    @ParameterizedTest(name = "action={0}: {2}")
    @MethodSource("missingRequiredFieldCases")
    void missingRequiredFieldReturnsError(
            String action, String input, String expectedErrorPrefix, String zeroCountMethod)
            throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, input);
        assertTrue(result.startsWith(expectedErrorPrefix), result);
        assertEquals(0, server.countRequests(zeroCountMethod));
    }

    // ──────── JCLAW-387 (A3): reply-with-native-quote ────────

    @Test
    void replyActionWithQuoteThreadsExcerptIntoReplyParameters() throws Exception {
        // reply defaults ON. A non-blank `quote` must reach the sendMessage
        // body as reply_parameters.quote alongside the reply target. Pin
        // replyTo.mode=all so the assertions are deterministic regardless of
        // the chunk-policy default.
        play.Play.configuration.setProperty(CFG_REPLY_TO_MODE, "all");
        try {
            seedTelegramBindingAndConversation();
            var result = invokeTool(agent.id,
                    "{\"action\":\"reply\",\"message_id\":7,\"message\":\"my answer\","
                            + "\"quote\":\"the excerpt\"}");
            var parsed = JsonParser.parseString(result).getAsJsonObject();
            assertEquals("ok", parsed.get("status").getAsString(), result);
            assertEquals("reply", parsed.get("action").getAsString());
            assertEquals(1, server.countRequests("sendMessage"),
                    "quote reply must hit sendMessage exactly once");
            var body = server.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .reduce("", (a, b) -> a + b);
            assertTrue(body.contains("quote"),
                    "reply_parameters.quote must be on the wire: " + body);
            assertTrue(body.contains("the excerpt"),
                    "the quoted excerpt must appear in the sendMessage body: " + body);
            assertTrue(body.contains("7"),
                    "the reply target message id must appear in the body: " + body);
        } finally {
            play.Play.configuration.remove(CFG_REPLY_TO_MODE);
        }
    }

    @Test
    void replyActionWithoutQuoteOmitsQuoteFromBody() throws Exception {
        // Absent `quote` must reproduce today's reply behavior exactly: a
        // sendMessage with the reply target but NO reply_parameters.quote.
        play.Play.configuration.setProperty(CFG_REPLY_TO_MODE, "all");
        try {
            seedTelegramBindingAndConversation();
            var result = invokeTool(agent.id,
                    "{\"action\":\"reply\",\"message_id\":7,\"message\":\"no excerpt here\"}");
            var parsed = JsonParser.parseString(result).getAsJsonObject();
            assertEquals("ok", parsed.get("status").getAsString(), result);
            var body = server.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .reduce("", (a, b) -> a + b);
            assertFalse(body.contains("\"quote\""),
                    "absent quote must not put a quote field on the wire: " + body);
        } finally {
            play.Play.configuration.remove(CFG_REPLY_TO_MODE);
        }
    }

    // ──────── JCLAW-387 (C1): native poll ────────

    @Test
    void pollActionSendsPollWhenEnabled() throws Exception {
        // poll defaults ON; question + options must reach the sendPoll body.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"poll\",\"question\":\"Best language?\","
                        + "\"options\":[\"Java\",\"Rust\",\"Go\"]}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        assertEquals("poll", parsed.get("action").getAsString());
        assertEquals(1, server.countRequests("sendPoll"),
                "poll must hit the sendPoll endpoint exactly once");
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendPoll"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(body.contains("Best language?"),
                "the poll question must appear in the sendPoll body: " + body);
        assertTrue(body.contains("Java") && body.contains("Rust") && body.contains("Go"),
                "every option must appear in the sendPoll body: " + body);
    }

    @Test
    void pollActionThreadsOptionalKnobs() throws Exception {
        // anonymous=false + allow_multiple=true + open_period=30 must reach the wire.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"poll\",\"question\":\"Pick any\","
                        + "\"options\":[\"A\",\"B\"],\"anonymous\":false,"
                        + "\"allow_multiple\":true,\"open_period\":30}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ok", parsed.get("status").getAsString(), result);
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendPoll"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(body.contains("is_anonymous"),
                "anonymous knob must serialize as is_anonymous: " + body);
        assertTrue(body.contains("allows_multiple_answers"),
                "allow_multiple knob must serialize as allows_multiple_answers: " + body);
        assertTrue(body.contains("open_period") && body.contains("30"),
                "open_period must be on the wire: " + body);
    }

    @Test
    void pollActionRejectsTooFewOptionsWithoutApiCall() throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"poll\",\"question\":\"One only?\",\"options\":[\"Solo\"]}");
        assertTrue(result.startsWith("Error: 'poll' requires between 2 and 10 options"), result);
        assertEquals(0, server.countRequests("sendPoll"),
                "a single-option poll must NOT touch the Telegram API");
    }

    @Test
    void pollActionRejectsTooManyOptionsWithoutApiCall() throws Exception {
        seedTelegramBindingAndConversation();
        // 11 options — over the 10-option agent-facing ceiling.
        var opts = new StringBuilder("[");
        for (int i = 1; i <= 11; i++) {
            if (i > 1) opts.append(',');
            opts.append("\"opt").append(i).append("\"");
        }
        opts.append("]");
        var result = invokeTool(agent.id,
                "{\"action\":\"poll\",\"question\":\"Too many?\",\"options\":" + opts + "}");
        assertTrue(result.startsWith("Error: 'poll' requires between 2 and 10 options"), result);
        assertEquals(0, server.countRequests("sendPoll"),
                "an 11-option poll must NOT touch the Telegram API");
    }

    @Test
    void pollActionRejectsBlankQuestionWithoutApiCall() throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id,
                "{\"action\":\"poll\",\"question\":\"  \",\"options\":[\"A\",\"B\"]}");
        assertTrue(result.startsWith("Error: 'question' is required"), result);
        assertEquals(0, server.countRequests("sendPoll"),
                "a blank-question poll must NOT touch the Telegram API");
    }

    @Test
    void disabledPollIsRefusedWithoutApiCall() throws Exception {
        play.Play.configuration.setProperty(CFG_POLL, "false");
        try {
            seedTelegramBindingAndConversation();
            var result = invokeTool(agent.id,
                    "{\"action\":\"poll\",\"question\":\"Disabled?\",\"options\":[\"A\",\"B\"]}");
            var parsed = JsonParser.parseString(result).getAsJsonObject();
            assertEquals("not-enabled", parsed.get("status").getAsString(), result);
            assertEquals(0, server.countRequests("sendPoll"),
                    "a disabled poll must NOT touch the Telegram API");
        } finally {
            play.Play.configuration.remove(CFG_POLL);
        }
    }

    @Test
    void pollActionIsDiscoverableInToolActions() {
        var tool = ToolRegistry.lookupTool(MessageTool.TOOL_NAME);
        assertNotNull(tool);
        boolean hasPoll = tool.actions().stream()
                .anyMatch(a -> "poll".equals(a.name()));
        assertTrue(hasPoll, "the poll action must be advertised by the tool");
    }

    // ──────── helpers ────────

    private String invokeTool(Long callerAgentId, String argsJson) throws Exception {
        commitAndReopen();
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var caller = Tx.run(() -> (Agent) Agent.findById(callerAgentId));
                var tool = (MessageTool) ToolRegistry.lookupTool(MessageTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, caller));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(10_000);
        assertFalse(thread.isAlive(), "message tool must complete within 10s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
