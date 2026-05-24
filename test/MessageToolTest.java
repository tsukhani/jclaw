import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
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

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        new jobs.ToolRegistrationJob().doJob();
        agent = AgentService.create("msg-test", "openrouter", "gpt-4.1");
    }

    @AfterEach
    void teardown() {
        EventLogger.clear();
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
    void unsupportedActionRejectedWithHintAtP1Followups() throws Exception {
        // AC-3 lists reply/edit/delete/react but only send is in this build.
        var result = invokeTool(agent.id,
                "{\"action\":\"reply\",\"message\":\"hi\",\"channel\":\"telegram\",\"target\":\"1\"}");
        assertTrue(result.startsWith("Error: 'action' must be one of"), result);
        assertTrue(result.contains("not yet supported"),
                "rejection should hint that reply/edit/delete/react are deferred: " + result);
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
