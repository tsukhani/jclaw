import channels.Channel;
import channels.SlackChannel;
import channels.SlackChannel.DeliveryOutcome;
import channels.SlackWebApi;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Unit tests for {@link SlackChannel}'s network-free surface: bot-token guards on
 * every outbound helper (send, native streaming, chat.update, assistant status),
 * the JCLAW-454/458 delivery resolution-failure mapping (via the package-private
 * {@code sendForDeliveryLive}, called reflectively so a concurrent
 * {@code deliverySender} seam swap in another test can't reroute it), Retry-After
 * header parsing, and the Events API inbound parsing branches (envelope guards,
 * own-bot drop, mention detection, files[] extraction with URL fallback).
 *
 * <p>Paths that put bytes on the wire to slack.com ({@code postOnce}, the happy
 * paths of {@code startStream}/{@code appendStream}/{@code stopStream}/
 * {@code postText}/{@code editMessage}/{@code setAssistantStatus}, and the
 * post-resolution leg of {@code sendForDeliveryLive}) are intentionally not
 * exercised — {@code SlackChannel.slack} is a static final SDK client with no
 * injectable seam, so those need a live workspace.
 */
class SlackChannelTest extends UnitTest {

    private static final Method SEND_FOR_DELIVERY_LIVE;
    private static final Method PARSE_RETRY_AFTER_MS;
    private static final Field LISTER_FIELD;

    static {
        try {
            SEND_FOR_DELIVERY_LIVE = SlackChannel.class.getDeclaredMethod(
                    "sendForDeliveryLive", String.class, String.class, String.class);
            SEND_FOR_DELIVERY_LIVE.setAccessible(true);
            PARSE_RETRY_AFTER_MS = SlackChannel.class.getDeclaredMethod(
                    "parseRetryAfterMs", String.class);
            PARSE_RETRY_AFTER_MS.setAccessible(true);
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("channelLister");
            LISTER_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DeliveryOutcome deliverLive(String botToken, String target, String text)
            throws Exception {
        return (DeliveryOutcome) SEND_FOR_DELIVERY_LIVE.invoke(null, botToken, target, text);
    }

    private static long retryAfterMs(String header) throws Exception {
        return (Long) PARSE_RETRY_AFTER_MS.invoke(null, header);
    }

    // ──────── bot-token guards (JCLAW-441: no app-global token, per-binding only) ────────

    @Test
    void forTokenInstanceWithoutTokenFailsTrySendWithoutNetwork() {
        var noToken = SlackChannel.forToken(null);
        assertEquals("slack", noToken.channelName());
        assertEquals(Channel.SendResult.FAILED, noToken.trySend("C0GENERAL", "hi"));

        var blankToken = SlackChannel.forToken("   ");
        assertEquals(Channel.SendResult.FAILED, blankToken.trySend("C0GENERAL", "hi"));
    }

    @Test
    void sendTextWithoutTokenFailsThroughTheSharedRetryPath() {
        // The default-constructed instance has no bot token; sendText routes
        // through Channel.sendWithRetry, whose single retry also fails on the
        // token guard — the observable contract is FAILED, never a throw.
        assertEquals(Channel.SendResult.FAILED, new SlackChannel().sendText("C0GENERAL", "hi"));
    }

    @Test
    void sendMessageWithoutBotTokenReturnsFalse() {
        assertFalse(SlackChannel.sendMessage("C0GENERAL", "hello", "1700000000.000100", null));
        assertFalse(SlackChannel.sendMessage("C0GENERAL", "hello", null, "  "));
    }

    @Test
    void streamingAndEditHelpersRejectMissingBotToken() {
        assertNull(SlackChannel.startStream("C1", "1700.1", "U1", "hi", null));
        assertNull(SlackChannel.startStream("C1", "1700.1", "U1", "hi", " "));
        assertFalse(SlackChannel.appendStream("C1", "1700.1", "delta", null));
        assertFalse(SlackChannel.appendStream("C1", "1700.1", "delta", ""));
        assertFalse(SlackChannel.stopStream("C1", "1700.1", null));
        assertFalse(SlackChannel.stopStream("C1", "1700.1", " "));
        assertNull(SlackChannel.postText("C1", "text", null, null));
        assertNull(SlackChannel.postText("C1", "text", null, ""));
        assertFalse(SlackChannel.editMessage("C1", "1700.1", "text", null));
        assertFalse(SlackChannel.editMessage("C1", "1700.1", "text", " "));
        // Best-effort void helper: the guard must return silently, not throw.
        SlackChannel.setAssistantStatus("C1", "1700.1", "is typing...", null);
        SlackChannel.setAssistantStatus("C1", "1700.1", "is typing...", "  ");
    }

    // ──────── JCLAW-454/458: delivery resolution-failure mapping ────────

    @Test
    void deliveryWithoutBotTokenFailsAsMissingBotToken() throws Exception {
        var noToken = deliverLive(null, "#ops", "hi");
        assertFalse(noToken.ok());
        assertEquals("missing_bot_token", noToken.error());

        var blankToken = deliverLive("   ", "#ops", "hi");
        assertFalse(blankToken.ok());
        assertEquals("missing_bot_token", blankToken.error());
    }

    @Test
    void deliveryWithUnresolvableTargetFailsAsChannelNotFound() throws Exception {
        // A null/blank target short-circuits resolution with no error code, so the
        // outcome falls back to channel_not_found — no lookup, no post, no network.
        var nullTarget = deliverLive("xoxb-slackchanneltest-nores", null, "hi");
        assertFalse(nullTarget.ok());
        assertEquals("channel_not_found", nullTarget.error());

        var blankTarget = deliverLive("xoxb-slackchanneltest-nores", "   ", "hi");
        assertFalse(blankTarget.ok());
        assertEquals("channel_not_found", blankTarget.error());
    }

    @Test
    void deliverySurfacesResolutionErrorInsteadOfFlatteningIt() throws Exception {
        // JCLAW-458: a missing_scope failure from conversations.list must reach the
        // DeliveryOutcome verbatim (TaskRun.delivery_error shows the real cause).
        // Swap the SlackWebApi lister seam (snapshot/restore) so no network is hit;
        // unique token + name dodge the process-wide resolution cache.
        Object original = LISTER_FIELD.get(null);
        SlackWebApi.ChannelLister failing =
                (token, name) -> new SlackWebApi.ChannelLookup(null, "missing_scope");
        LISTER_FIELD.set(null, failing);
        try {
            var out = deliverLive("xoxb-slackchanneltest-scope", "#slackchanneltest-scope", "hi");
            assertFalse(out.ok());
            assertEquals("missing_scope", out.error());
        } finally {
            LISTER_FIELD.set(null, original);
        }
    }

    // ──────── Retry-After parsing (Slack 429s carry seconds; we schedule in ms) ────────

    @Test
    void retryAfterHeaderParsesSecondsToMillis() throws Exception {
        assertEquals(2000L, retryAfterMs("2"));
        assertEquals(5000L, retryAfterMs(" 5 "));
    }

    @Test
    void retryAfterHeaderMissingOrMalformedYieldsZero() throws Exception {
        assertEquals(0L, retryAfterMs(null));
        assertEquals(0L, retryAfterMs("   "));
        assertEquals(0L, retryAfterMs("soon"));
        assertEquals(0L, retryAfterMs("1.5"));
    }

    // ──────── Events API inbound parsing ────────

    private static JsonObject envelope(String eventJson) {
        return JsonParser.parseString(
                "{\"type\":\"event_callback\",\"event\":" + eventJson + "}").getAsJsonObject();
    }

    @Test
    void parseEventIgnoresNonEventCallbackEnvelopes() {
        var urlVerification = JsonParser.parseString(
                "{\"type\":\"url_verification\",\"challenge\":\"c123\"}").getAsJsonObject();
        assertNull(SlackChannel.parseEvent(urlVerification, null));
    }

    @Test
    void parseEventIgnoresMissingOrNonMessageEvents() {
        var noEvent = JsonParser.parseString("{\"type\":\"event_callback\"}").getAsJsonObject();
        assertNull(SlackChannel.parseEvent(noEvent, null));

        assertNull(SlackChannel.parseEvent(
                envelope("{\"type\":\"app_mention\",\"user\":\"U1\",\"text\":\"hi\"}"), null));
    }

    @Test
    void parseEventDropsTheBindingsOwnBotMessages() {
        var event = "{\"type\":\"message\",\"user\":\"UBOT\",\"text\":\"echo\",\"channel\":\"C1\"}";
        assertNull(SlackChannel.parseEvent(envelope(event), "UBOT"));

        // The same message from a *different* bot user id is a real user message.
        var msg = SlackChannel.parseEvent(envelope(event), "UOTHER");
        assertNotNull(msg);
        assertEquals("UBOT", msg.userId());
        assertEquals("C1", msg.channelId());
    }

    @Test
    void parseEventMissingTextBecomesEmptyAndNeverMentions() {
        var msg = SlackChannel.parseEvent(envelope(
                "{\"type\":\"message\",\"user\":\"U1\",\"channel\":\"C9\",\"channel_type\":\"im\"}"),
                "UBOT");
        assertNotNull(msg);
        assertEquals("", msg.text());
        assertEquals("im", msg.channelType());
        assertFalse(msg.botMentioned(), "no text → no mention, even with a bot id configured");
        assertTrue(msg.files().isEmpty(), "no files[] → empty pending-file list");
    }

    @Test
    void parseEventDetectsBotMentionInBothRenderedForms() {
        // Slack renders mentions as <@U…> or <@U…|name>; the prefix match covers both.
        var plain = envelope("{\"type\":\"message\",\"user\":\"U1\",\"channel\":\"C1\","
                + "\"text\":\"hey <@UBOT> ping\",\"channel_type\":\"channel\","
                + "\"thread_ts\":\"1700000000.000200\"}");
        var plainMsg = SlackChannel.parseEvent(plain, "UBOT");
        assertNotNull(plainMsg);
        assertTrue(plainMsg.botMentioned());
        assertEquals("1700000000.000200", plainMsg.threadTs());

        var named = envelope("{\"type\":\"message\",\"user\":\"U1\",\"channel\":\"C1\","
                + "\"text\":\"hey <@UBOT|assistant> ping\",\"channel_type\":\"channel\"}");
        var namedMsg = SlackChannel.parseEvent(named, "UBOT");
        assertNotNull(namedMsg);
        assertTrue(namedMsg.botMentioned());

        // Another bot's mention is not this binding's mention.
        var otherMsg = SlackChannel.parseEvent(named, "UZZZ");
        assertNotNull(otherMsg);
        assertFalse(otherMsg.botMentioned());
    }

    @Test
    void parseEventDropsStructurallyMalformedEvent() {
        // A files field that isn't an array blows up inside the Slack SDK's
        // Gson mapping (before our parseFiles guards run). parseEvent must
        // drop such an event as "not a plain user message" rather than let
        // the exception crash the webhook handler.
        var msg = SlackChannel.parseEvent(envelope(
                "{\"type\":\"message\",\"user\":\"U1\",\"channel\":\"C1\",\"text\":\"x\","
                        + "\"files\":42}"), null);
        assertNull(msg, "a structurally malformed event is dropped, not thrown");
    }

    @Test
    void parseEventExtractsFilesPreferringDirectDownloadUrl() {
        var msg = SlackChannel.parseEvent(envelope("""
                {"type":"message","subtype":"file_share","user":"U1","channel":"C1","text":"here",
                 "files":[
                   {"id":"F0NOURL","name":"ghost.bin"},
                   {"id":"F1","url_private":"https://files.slack.com/p1","name":"a.txt",
                    "mimetype":"text/plain","size":null},
                   {"id":"F2","url_private_download":"https://files.slack.com/d2",
                    "url_private":"https://files.slack.com/p2","name":"clip.mp4",
                    "mimetype":"video/mp4","size":2048,"subtype":"slack_audio"}
                 ]}
                """), null);
        assertNotNull(msg);
        // The no-URL file object is skipped; the two with URLs survive.
        assertEquals(2, msg.files().size());

        var fallback = msg.files().get(0);
        assertEquals("F1", fallback.id());
        assertEquals("https://files.slack.com/p1", fallback.urlPrivateDownload(),
                "url_private is the fallback when url_private_download is absent");
        assertEquals("a.txt", fallback.name());
        assertEquals("text/plain", fallback.mimeType());
        assertEquals(0L, fallback.sizeBytes(), "JSON-null size reads as 0");
        assertEquals("", fallback.subtype());

        var direct = msg.files().get(1);
        assertEquals("F2", direct.id());
        assertEquals("https://files.slack.com/d2", direct.urlPrivateDownload(),
                "url_private_download wins over url_private");
        assertEquals(2048L, direct.sizeBytes());
        assertEquals("slack_audio", direct.subtype());
    }
}
