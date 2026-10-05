import channels.Channel;
import channels.WhatsAppChannel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.EventLogger;
import utils.HttpFactories;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

/**
 * Behavior coverage for {@link WhatsAppChannel}'s config resolution, guard
 * branches, and the 24h-window send gating (JCLAW-446/447) — everything on the
 * outbound path that is decidable BEFORE the hardwired Graph HTTP call.
 *
 * <p>The channel posts to the fixed {@code https://graph.facebook.com} host
 * through the shared {@code HttpFactories.general()} client (no per-class URL
 * seam), and swapping that
 * shared client would flip a process-global static the concurrent test lanes
 * depend on. So every test here drives the channel only down paths that return
 * before a socket would open: missing config, missing file, missing target,
 * and the window-gating decision, whose branch taken is asserted through the
 * event lines each branch emits.
 */
class WhatsAppChannelTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        // findByType negative-caches misses for 60s; start each test clean so a
        // prior test's empty read can't mask a row seeded here (and vice versa).
        ChannelConfig.evictAllCache();
    }

    // ── WhatsAppConfig.load() — the app-global config record ──

    @Test
    void configLoadReturnsNullWithoutARow() {
        assertNull(WhatsAppChannel.WhatsAppConfig.load(),
                "no channel_config row → no WhatsApp config");
    }

    @Test
    void configLoadReturnsNullWhenDisabled() {
        seedWhatsAppConfig("{\"phoneNumberId\":\"111\",\"accessToken\":\"tok\"}", false);
        assertNull(WhatsAppChannel.WhatsAppConfig.load(),
                "a disabled row must not yield a usable config");
    }

    @Test
    void configLoadParsesAllFourFields() {
        seedWhatsAppConfig("""
                {"phoneNumberId":"1550001","accessToken":"EAAG-tok",
                 "appSecret":"s3cret","verifyToken":"vt-42"}
                """, true);
        var config = WhatsAppChannel.WhatsAppConfig.load();
        assertNotNull(config);
        assertEquals("1550001", config.phoneNumberId());
        assertEquals("EAAG-tok", config.accessToken());
        assertEquals("s3cret", config.appSecret());
        assertEquals("vt-42", config.verifyToken());
    }

    @Test
    void configLoadDefaultsOptionalSecretsToNull() {
        seedWhatsAppConfig("{\"phoneNumberId\":\"1550002\",\"accessToken\":\"tok\"}", true);
        var config = WhatsAppChannel.WhatsAppConfig.load();
        assertNotNull(config);
        assertNull(config.appSecret(), "appSecret is optional in the stored JSON");
        assertNull(config.verifyToken(), "verifyToken is optional in the stored JSON");
    }

    // ── sendText guards + unconfigured failure mapping ──

    @Test
    void sendTextWithNullOrEmptyTextIsANoOpSuccess() {
        var ch = new WhatsAppChannel();
        assertTrue(ch.sendText("15550001111", null).ok(), "null text → OK without any send");
        assertTrue(ch.sendText("15550001111", "").ok(), "empty text → OK without any send");
    }

    @Test
    void sendTextFailsAndLogsWhenChannelIsUnconfigured() {
        // Stateless instance, no config row: the in-window free-form path runs
        // (no binding → no window to enforce), each chunk's trySend fails before
        // any HTTP because effectiveConfig() is null, and the shared retry policy
        // surfaces the terminal per-peer error line.
        var peer = "wa-unconfigured-peer-" + System.nanoTime();
        var ok = new boolean[1];
        var logged = EventLogger.captureForTest(() -> ok[0] = new WhatsAppChannel().sendText(peer, "hello").ok());
        assertFalse(ok[0]);
        assertTrue(logExists(logged, "Failed to send message to " + peer + " after retries"),
                "the retry policy must log the terminal failure for this peer");
    }

    @Test
    void trySendFailsWithoutConfig() {
        assertFalse(new WhatsAppChannel().trySend("15550001111", "hi").ok(),
                "no config → FAILED, never OK");
    }

    @Test
    void staticSendMessageReturnsFalseWithoutConfig() {
        assertFalse(WhatsAppChannel.sendMessage("15550001111", "hi"),
                "the legacy static send must report false when WhatsApp is not configured");
    }

    // ── sendMedia guards ──

    @Test
    void sendMediaFailsWhenFileIsMissingOrUnreadable() {
        var ch = new WhatsAppChannel();
        assertFalse(ch.sendMedia("15550001111", null, "image/png", "cap").ok(),
                "null file → FAILED");
        assertFalse(ch.sendMedia("15550001111",
                        new File("/nonexistent/wa-test-" + System.nanoTime() + ".png"),
                        "image/png", "cap").ok(),
                "nonexistent file → FAILED");
    }

    @Test
    void sendMediaFailsWhenChannelIsUnconfigured() throws Exception {
        var tmp = Files.createTempFile("wa-media-", ".png");
        Files.write(tmp, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        try {
            assertFalse(new WhatsAppChannel().sendMedia("15550001111", tmp.toFile(), "image/png", null).ok(),
                    "a readable file with no config must fail before any upload");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── sendReaction guards ──

    @Test
    void sendReactionFailsWithoutConfig() {
        assertFalse(new WhatsAppChannel().sendReaction("15550001111", "wamid.X", "👍").ok(),
                "no config → FAILED");
    }

    @Test
    void sendReactionRequiresATargetMessageId() {
        // Per-binding credentials resolve through the binding fields (not the
        // app-global row), and the missing-target guard fires before any HTTP.
        var binding = new WhatsAppBinding();
        binding.phoneNumberId = "1550009";
        binding.accessToken = "tok";
        var ch = WhatsAppChannel.forBinding(binding);
        EventLogger.clear();
        var logged = EventLogger.captureForTest(() -> {
            assertFalse(ch.sendReaction("15550001111", null, "👍").ok(),
                    "null target message id → FAILED");
            assertFalse(ch.sendReaction("15550001111", "   ", "👍").ok(),
                    "blank target message id → FAILED");
        });
        // The guard's own log line distinguishes "guard fired" from "guard
        // deleted and a live graph.facebook.com call failed" — both would
        // otherwise surface as the same FAILED result.
        assertEquals(2L, logged.stream().filter(e -> e.category().equals("channel")
                        && e.message().contains("sendReaction skipped: no target message id")).count(),
                "each guarded call must log the skip, proving no wire attempt was made");
    }

    // ── 24h customer-service window gating (JCLAW-447) ──

    @Test
    void outOfWindowWithoutTemplateFallsBackToFreeFormWithAWarning() {
        var binding = boundBinding("wa-ch-ow-agent", null);
        var ch = WhatsAppChannel.forBinding(binding);
        var peer = "wa-ow-peer-" + System.nanoTime();

        var ok = new boolean[1];
        var logged = EventLogger.captureForTest(() -> ok[0] = ch.sendText(peer, "reply after 24h").ok());
        assertFalse(ok[0], "unconfigured best-effort send must fail");
        assertTrue(logExists(logged, "Outbound to " + peer + " is outside the 24h window"),
                "the operator-config gap (no template) must be surfaced as a warning");
        assertTrue(logExists(logged, "Failed to send message to " + peer + " after retries"),
                "the free-form fallback must actually attempt the send (retry error proves it)");
    }

    @Test
    void outOfWindowWithATemplateSendsTheTemplateNotFreeForm() {
        // Template configured: the opener path is taken — the agent's free-form
        // reply is NOT attempted (no per-peer retry error), and no missing-template
        // warning is emitted. With no credentials the template post itself fails
        // fast on the null config, so no socket opens.
        var binding = boundBinding("wa-ch-tpl-agent", "hello_world");
        var ch = WhatsAppChannel.forBinding(binding);
        var peer = "wa-tpl-peer-" + System.nanoTime();

        var ok = new boolean[1];
        var logged = EventLogger.captureForTest(() -> ok[0] = ch.sendText(peer, "reply after 24h").ok());
        assertFalse(ok[0]);
        assertFalse(logExists(logged, "Outbound to " + peer + " is outside the 24h window"),
                "a configured template must not trigger the missing-template warning");
        assertFalse(logExists(logged, "Failed to send message to " + peer),
                "the free-form retry path must NOT run when a template opener is configured");
    }

    @Test
    void recentInboundReopensTheFreeFormWindow() {
        // Same binding shape as the template test — but the peer messaged us just
        // now, so the window is open and the reply goes free-form (chunked +
        // retried), NOT via the template.
        var binding = boundBinding("wa-ch-win-agent", "hello_world");
        var ch = WhatsAppChannel.forBinding(binding);
        var peer = "wa-win-peer-" + System.nanoTime();
        WhatsAppConversationWindow.recordInbound(binding.id, peer, Instant.now());

        var ok = new boolean[1];
        var logged = EventLogger.captureForTest(() -> ok[0] = ch.sendText(peer, "in-window reply").ok());
        assertFalse(ok[0], "unconfigured free-form send must fail");
        assertFalse(logExists(logged, "Outbound to " + peer + " is outside the 24h window"),
                "an open window must not route through the out-of-window opener");
        assertTrue(logExists(logged, "Failed to send message to " + peer + " after retries"),
                "in-window the free-form send is attempted despite the configured template");
    }

    /**
     * Meta's 131047 is a business rule a resend cannot change, so it maps to REJECTED — which the
     * shared retry neither resends nor escalates to an ERROR — and a binding that does have a
     * template is not told it has none. The canned transport stands in for the Graph host.
     */
    @Test
    void anOutsideWindowRejectionIsNotRetryableAndRespectsTheConfiguredTemplate() {
        var binding = new WhatsAppBinding();
        binding.id = 987_654L;
        binding.phoneNumberId = "1550001";
        binding.accessToken = "EAAG-tok";
        binding.templateName = "reopen_conversation";
        var body = "{\"error\":{\"message\":\"Re-engagement message\",\"code\":131047}}";

        var result = new Channel.SendResult[1];
        var logged = EventLogger.captureForTest(() -> result[0] = HttpFactories.callWith(cannedClient(400, body),
                () -> WhatsAppChannel.forBinding(binding).trySend("447900987654", "hello")));

        assertEquals(Channel.SendResult.REJECTED, result[0]);
        assertTrue(logExists(logged, "binding 987654 held a reply"), "the rejection is logged");
        assertFalse(logExists(logged, "binding 987654 held a reply", "none configured"),
                "this binding has a template, so the log must not say it has none");
    }

    // ── JCLAW-1297: the ids a send returns, for a reply that quotes it ──

    @Test
    void anInWindowSendReturnsTheIdGraphAssignedEachChunk() {
        var binding = boundBinding("wa-ch-ids-agent", null);
        binding.phoneNumberId = "1550002";
        binding.accessToken = "EAAG-tok";
        var peer = "wa-ids-peer-" + System.nanoTime();
        WhatsAppConversationWindow.recordInbound(binding.id, peer, Instant.now());
        var sent = "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.SENT\"}]}";

        var result = HttpFactories.callWith(cannedClient(200, sent),
                () -> WhatsAppChannel.forBinding(binding).sendText(peer, "x".repeat(5000)));

        assertTrue(result.ok());
        assertEquals(List.of("wamid.SENT", "wamid.SENT"), result.messageIds(),
                "a text over the 4096 cap goes as two messages, each quotable by its own id");
    }

    @Test
    void sentMessageIdReadsTheFirstMessageAndNeverThrows() {
        assertEquals("wamid.A", WhatsAppChannel.sentMessageId("{\"messages\":[{\"id\":\"wamid.A\"}]}"));
        assertNull(WhatsAppChannel.sentMessageId("{\"messages\":[]}"));
        assertNull(WhatsAppChannel.sentMessageId("{\"error\":{\"code\":1}}"));
        assertNull(WhatsAppChannel.sentMessageId("not json"));
        assertNull(WhatsAppChannel.sentMessageId(null));
    }

    // ── verifySignature guard ──

    @Test
    void verifySignatureRejectsNullHeader() {
        assertFalse(WhatsAppChannel.verifySignature("secret", "body", null),
                "a missing X-Hub-Signature-256 header must never verify");
    }

    // ── parseWebhook guard branches ──

    @Test
    void parseWebhookReturnsNullWithoutEntry() {
        assertNull(WhatsAppChannel.parseWebhook(json("{\"object\":\"whatsapp_business_account\"}")));
    }

    @Test
    void parseWebhookReturnsNullForEmptyEntryArray() {
        assertNull(WhatsAppChannel.parseWebhook(json("{\"entry\":[]}")));
    }

    @Test
    void parseWebhookReturnsNullWithoutChanges() {
        assertNull(WhatsAppChannel.parseWebhook(json("{\"entry\":[{}]}")));
    }

    @Test
    void parseWebhookReturnsNullForEmptyChangesArray() {
        assertNull(WhatsAppChannel.parseWebhook(json("{\"entry\":[{\"changes\":[]}]}")));
    }

    @Test
    void parseWebhookReturnsNullWhenChangeHasNoValue() {
        assertNull(WhatsAppChannel.parseWebhook(json("{\"entry\":[{\"changes\":[{}]}]}")));
    }

    @Test
    void parseWebhookReturnsNullWhenValueHasNoMessages() {
        // A delivery-status callback: value present, messages absent.
        assertNull(WhatsAppChannel.parseWebhook(json(
                "{\"entry\":[{\"changes\":[{\"value\":{\"statuses\":[{\"status\":\"delivered\"}]}}]}]}")));
    }

    @Test
    void parseWebhookReturnsNullForEmptyMessagesArray() {
        assertNull(WhatsAppChannel.parseWebhook(json(
                "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[]}}]}]}")));
    }

    @Test
    void parseWebhookToleratesMissingMetadata() {
        var msg = WhatsAppChannel.parseWebhook(json("""
                {"entry":[{"changes":[{"value":{"messages":[
                  {"type":"text","from":"447911111111","id":"wamid.ABC",
                   "text":{"body":"hi there"}}
                ]}}]}]}
                """));
        assertNotNull(msg, "a text message without metadata still parses");
        assertEquals("447911111111", msg.from());
        assertEquals("hi there", msg.text());
        assertEquals("wamid.ABC", msg.messageId());
        assertNull(msg.phoneNumberId(),
                "missing metadata → null phone_number_id (caller routes on the app-global binding)");
    }

    // ── helpers ──

    private static void seedWhatsAppConfig(String configJson, boolean enabled) {
        var cc = new ChannelConfig();
        cc.channelType = "whatsapp";
        cc.configJson = configJson;
        cc.enabled = enabled;
        cc.save();
    }

    /** A saved Cloud-API binding with NO credentials (so every send fails fast on
     *  the null config, before any socket) and the given out-of-window template. */
    private static WhatsAppBinding boundBinding(String agentName, String templateName) {
        var agent = AgentService.create(agentName, "openrouter", "gpt-4.1");
        var binding = new WhatsAppBinding();
        binding.agent = agent;
        binding.templateName = templateName;
        binding.save();
        return binding;
    }

    /** Whether a captured message contains {@code parts} in this order, as the old {@code LIKE '%a%b%'} did. */
    private static boolean logExists(List<EventLogger.Captured> logged, String... parts) {
        return logged.stream().anyMatch(e -> {
            int at = 0;
            for (var part : parts) {
                at = e.message().indexOf(part, at);
                if (at < 0) return false;
                at += part.length();
            }
            return true;
        });
    }

    /** A transport whose interceptor answers every request with {@code code} and {@code body}. */
    private static OkHttpClient cannedClient(int code, String body) {
        Interceptor canned = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(ResponseBody.create(body, null))
                .build();
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }
}
