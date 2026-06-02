import agents.ToolRegistry;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.TelegramBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void inferenceFromTelegramConversationWithoutPeerErrorsOnMissingTarget() throws Exception {
        // Inference picks channelType from the active conversation; if that
        // conversation has no peerId AND the channel is external (target is
        // required), the missing-target guard fires. Web wouldn't trip this
        // because target is ignored for web routing.
        Tx.run(() -> ConversationService.create(agent, "telegram", null));
        var result = invokeTool(agent.id, "{\"action\":\"send\",\"message\":\"hi\"}");
        assertTrue(result.startsWith("Error: no 'target' inferred"),
                "missing-peerId on an external channel should surface the missing-target error: " + result);
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

    @Test
    void replyMissingMessageIdReturnsError() throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"reply\",\"message\":\"hi\"}");
        assertTrue(result.startsWith("Error: 'message_id' is required"), result);
        assertEquals(0, server.countRequests("sendMessage"));
    }

    @Test
    void editMissingMessageIdReturnsError() throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"edit\",\"message\":\"hi\"}");
        assertTrue(result.startsWith("Error: 'message_id' is required"), result);
        assertEquals(0, server.countRequests("editMessageText"));
    }

    @Test
    void replyMissingMessageTextReturnsError() throws Exception {
        // reply requires a `message` body; message_id alone is not enough.
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"reply\",\"message_id\":7}");
        assertTrue(result.startsWith("Error: 'message' is required"), result);
        assertEquals(0, server.countRequests("sendMessage"));
    }

    @Test
    void editMissingMessageTextReturnsError() throws Exception {
        seedTelegramBindingAndConversation();
        var result = invokeTool(agent.id, "{\"action\":\"edit\",\"message_id\":8}");
        assertTrue(result.startsWith("Error: 'message' is required"), result);
        assertEquals(0, server.countRequests("editMessageText"));
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
