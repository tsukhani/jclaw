import channels.Channel;
import channels.WhatsAppChannel;
import com.google.gson.JsonParser;
import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.EventLogger;
import services.Tx;
import utils.HttpFactories;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unit coverage for {@link WhatsAppChannel}'s pure outbound helpers (JCLAW-447):
 * the 4096-char text chunker and the MIME → media-message-type mapping. The live
 * HTTP send paths (sendMedia upload two-step, sendReaction, the window-gated text
 * send) post to the fixed Graph host through {@code HttpFactories.general()} and
 * are driven here through a canned transport, beside the splitting and
 * type-selection logic.
 */
class WhatsAppChannelOutboundTest extends UnitTest {

    // ── chunkText ──

    @Test
    void shortTextIsASingleChunk() {
        var chunks = WhatsAppChannel.chunkText("hello world", WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }

    @Test
    void emptyOrNullTextYieldsNoChunks() {
        assertTrue(WhatsAppChannel.chunkText("", WhatsAppChannel.MAX_TEXT_CHARS).isEmpty());
        assertTrue(WhatsAppChannel.chunkText(null, WhatsAppChannel.MAX_TEXT_CHARS).isEmpty());
    }

    @Test
    void longTextSplitsAtTheLimitAndPreservesContent() {
        // 10000 'a's with no break points → hard cuts at the limit.
        var text = "a".repeat(10_000);
        var chunks = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(3, chunks.size(), "10000 / 4096 → 3 chunks");
        for (var c : chunks) {
            assertTrue(c.length() <= WhatsAppChannel.MAX_TEXT_CHARS,
                    "no chunk may exceed the limit: " + c.length());
        }
        assertEquals(text, String.join("", chunks), "chunks must reassemble to the original");
    }

    @Test
    void prefersBreakingAtWhitespaceWithinTheWindow() {
        // A space just before the limit should be the break point (not a hard cut).
        var head = "x".repeat(WhatsAppChannel.MAX_TEXT_CHARS - 1);
        var text = head + " tail";
        var chunks = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).endsWith(" "),
                "first chunk should break at the space (inclusive)");
        assertEquals("tail", chunks.get(1));
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void breaksAtNewlineWhenPresent() {
        var head = "y".repeat(100);
        var tail = "z".repeat(50);
        var text = head + "\n" + tail;
        // Limit larger than the whole string → single chunk (no split needed).
        var single = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(1, single.size());
        // Tight limit forces a split; the newline is the preferred break.
        var split = WhatsAppChannel.chunkText(text, 120);
        assertTrue(split.size() >= 2);
        assertEquals(text, String.join("", split));
    }

    // ── mediaMessageType ──

    @Test
    void mediaMessageTypeMapsByMimePrefix() {
        assertEquals("image", WhatsAppChannel.mediaMessageType("image/png"));
        assertEquals("image", WhatsAppChannel.mediaMessageType("IMAGE/JPEG"));
        assertEquals("audio", WhatsAppChannel.mediaMessageType("audio/ogg"));
        assertEquals("video", WhatsAppChannel.mediaMessageType("video/mp4"));
        assertEquals("document", WhatsAppChannel.mediaMessageType("application/pdf"));
        assertEquals("document", WhatsAppChannel.mediaMessageType(null),
                "unknown/null MIME falls back to document");
    }

    @Test
    void chunkerNeverProducesEmptyTrailingChunk() {
        var text = "a".repeat(WhatsAppChannel.MAX_TEXT_CHARS); // exactly the limit
        List<String> chunks = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(1, chunks.size(), "an exactly-limit-length text is one chunk, no empty tail");
    }

    @Test
    void breakpointExactlyAtTheWindowEdgeStaysWithinTheLimit() {
        // Regression for the JCLAW-447 off-by-one: a space AT index i+limit
        // used to be matched by lastIndexOf(ch, end) (inclusive fromIndex),
        // producing a limit+1-length chunk — a 4097-char body at the real
        // 4096 cap, which Meta's API rejects (the chunk was lost after the
        // single retry). chunkText now searches from end-1, so no chunk can
        // exceed the limit; the edge space starts the next chunk instead.
        var chunks = WhatsAppChannel.chunkText("aaaa b", 4);
        assertEquals(List.of("aaaa", " b"), chunks,
                "the window-edge space must start the next chunk, not overflow this one");
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 4),
                "no chunk may ever exceed the limit");
    }

    // ── JCLAW-1409: Markdown converted before it reaches the wire ──

    @Test
    void aMarkdownReplyReachesTheWireConverted() {
        var bodies = sendCapturing("Status: **green**, see [docs](https://d.io).\n\n- one\n- two");
        assertEquals(List.of("Status: *green*, see docs. https://d.io\n\n- one\n- two"), bodies);
    }

    @Test
    void aReplyThatFitsOnlyAfterConversionGoesAsOneMessage() {
        // "**ab** " is 7 chars raw, "*ab* " 5 converted: 5732 raw, 4094 converted.
        var md = "**ab** ".repeat(819);
        assertTrue(md.length() > WhatsAppChannel.MAX_TEXT_CHARS);
        var bodies = sendCapturing(md);
        assertEquals(1, bodies.size(), "chunking must measure the converted text");
        assertEquals(4094, bodies.get(0).length());
    }

    @Test
    void aReplyOverTheCapAfterConversionIsChunkedWithinIt() {
        var bodies = sendCapturing("**ab** ".repeat(820));
        assertEquals(2, bodies.size());
        for (var b : bodies) {
            assertTrue(b.length() <= WhatsAppChannel.MAX_TEXT_CHARS, "chunk length " + b.length());
            assertFalse(b.contains("**"), "every chunk carries converted text");
        }
        assertEquals("*ab* ".repeat(820).stripTrailing(), String.join("", bodies));
    }

    /** Send {@code text} through a binding with no window context, returning each posted text body. */
    private static List<String> sendCapturing(String text) {
        var binding = new WhatsAppBinding();
        binding.phoneNumberId = "1550001";
        binding.accessToken = "EAAG-tok";
        var bodies = new CopyOnWriteArrayList<String>();
        Interceptor canned = chain -> {
            var buf = new Buffer();
            chain.request().body().writeTo(buf);
            bodies.add(JsonParser.parseString(buf.readUtf8()).getAsJsonObject()
                    .getAsJsonObject("text").get("body").getAsString());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body(ResponseBody.create("{\"messages\":[{\"id\":\"wamid.X\"}]}", null))
                    .build();
        };
        var client = new OkHttpClient.Builder().addInterceptor(canned).build();
        var result = HttpFactories.callWith(client,
                () -> WhatsAppChannel.forBinding(binding).sendText("447900000001", text));
        assertTrue(result.ok());
        return bodies;
    }

    // ── JCLAW-1411: a send Meta refuses is recorded on the binding ──

    @Test
    void aRefusedSendRecordsTheFailureOnTheBinding() {
        var b = sendThroughPersistedBinding(400,
                "{\"error\":{\"message\":\"(#131042) Business eligibility payment issue\",\"code\":131042}}");
        assertNotNull(b.lastDeliveryFailureAt);
        assertEquals(131042, b.lastDeliveryFailureCode);
        assertEquals("(#131042) Business eligibility payment issue", b.lastDeliveryFailureTitle);
    }

    @Test
    void anOutsideTheWindowRefusalIsNotRecorded() {
        var b = sendThroughPersistedBinding(400,
                "{\"error\":{\"message\":\"Re-engagement message\",\"code\":131047}}");
        assertNull(b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void aRefusalWithANonJsonBodyRecordsNullCodeAndTitle() {
        var b = sendThroughPersistedBinding(502, "<html>Bad gateway</html>");
        assertNotNull(b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void aSuccessfulSendRecordsNothing() {
        var b = sendThroughPersistedBinding(200, "{\"messages\":[{\"id\":\"wamid.OK\"}]}");
        assertNull(b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void aTransportExceptionRecordsNothing() {
        var b = sendThroughPersistedBinding(chain -> {
            throw new java.io.IOException("connection reset");
        });
        assertNull(b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void metaErrorMessageNeverThrows() {
        assertEquals("boom", WhatsAppChannel.metaErrorMessage("{\"error\":{\"message\":\"boom\"}}"));
        assertNull(WhatsAppChannel.metaErrorMessage(null));
        assertNull(WhatsAppChannel.metaErrorMessage("<html>"));
        assertNull(WhatsAppChannel.metaErrorMessage("{\"error\":\"x\"}"));
        assertNull(WhatsAppChannel.metaErrorMessage("{\"error\":{\"message\":{}}}"));
    }

    /** Send through a committed binding against a canned Graph response; returns the binding re-read afterwards. */
    private static WhatsAppBinding sendThroughPersistedBinding(int status, String responseBody) {
        return sendThroughPersistedBinding(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("canned")
                .body(ResponseBody.create(responseBody, null))
                .build());
    }

    private static WhatsAppBinding sendThroughPersistedBinding(Interceptor canned) {
        var suffix = String.valueOf(System.nanoTime());
        WhatsAppBinding binding = Tx.run(() -> {
            var agent = new Agent();
            agent.name = "wa-outbound-agent-" + suffix;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.CLOUD_API;
            b.phoneNumberId = "1" + suffix;
            b.accessToken = "EAAG-tok";
            b.enabled = true;
            b.save();
            return b;
        });
        try {
            var client = new OkHttpClient.Builder().addInterceptor(canned).build();
            HttpFactories.callWith(client,
                    () -> WhatsAppChannel.forBinding(binding).trySend("447900000001", "hi"));
            return Tx.run(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(binding.id);
                JPA.em().refresh(b); // the failure is a bulk update, which a managed copy does not see
                return b;
            });
        } finally {
            Tx.run(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(binding.id);
                var agent = b.agent;
                b.delete();
                agent.delete();
            });
        }
    }

    // ── JCLAW-1024 follow-up: the phone number id goes out as one Graph path segment ──

    private static final String PHONE_NUMBER_ID = "106540352242922";
    private static final String PEER = "447900000001";

    @Test
    void aDigitsOnlyPhoneNumberIdSendsToItsMessagesEdge() {
        var sent = new CopyOnWriteArrayList<Request>();
        var result = HttpFactories.callWith(graph(sent), () -> channelFor(PHONE_NUMBER_ID).trySend(PEER, "hi"));

        assertEquals(Channel.SendResult.sent(List.of("wamid.M")), result);
        assertEquals(List.of("POST https://graph.facebook.com/v21.0/106540352242922/messages"), lines(sent));
        assertEquals(List.of("v21.0", PHONE_NUMBER_ID, "messages"), sent.get(0).url().pathSegments());
        assertEquals("Bearer EAAG-tok", sent.get(0).header("Authorization"));
    }

    @Test
    void aDigitsOnlyPhoneNumberIdSendsTheTypingCueToItsMessagesEdge() {
        var sent = new CopyOnWriteArrayList<Request>();
        HttpFactories.runWith(graph(sent), () -> channelFor(PHONE_NUMBER_ID).startTyping(PEER, "wamid.IN"));

        assertEquals(List.of("POST https://graph.facebook.com/v21.0/106540352242922/messages"), lines(sent));
        assertEquals("Bearer EAAG-tok", sent.get(0).header("Authorization"));
    }

    @Test
    void aDigitsOnlyPhoneNumberIdUploadsMediaToItsMediaEdge() throws IOException {
        var file = tempImage();
        try {
            var sent = new CopyOnWriteArrayList<Request>();
            var result = HttpFactories.callWith(graph(sent),
                    () -> channelFor(PHONE_NUMBER_ID).sendMedia(PEER, file, "image/png", "a chart"));

            assertEquals(Channel.SendResult.sent(List.of("wamid.M")), result);
            assertEquals(List.of("POST https://graph.facebook.com/v21.0/106540352242922/media",
                    "POST https://graph.facebook.com/v21.0/106540352242922/messages"), lines(sent));
            assertEquals(List.of("v21.0", PHONE_NUMBER_ID, "media"), sent.get(0).url().pathSegments());
            assertEquals(List.of("Bearer EAAG-tok", "Bearer EAAG-tok"),
                    sent.stream().map(r -> r.header("Authorization")).toList());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    void aPhoneNumberIdThatIsNotDigitsSendsNothingFromAnySink() throws IOException {
        var file = tempImage();
        try {
            for (var id : List.of("../me/accounts?fields=access_token", PHONE_NUMBER_ID + "?fields=access_token",
                    "%2e%2e/me/accounts", PHONE_NUMBER_ID + "/../../me")) {
                var sent = new CopyOnWriteArrayList<Request>();
                var channel = channelFor(id);
                var results = new ArrayList<Channel.SendResult>();
                var logged = EventLogger.captureForTest(() -> HttpFactories.runWith(graph(sent), () -> {
                    results.add(channel.trySend(PEER, "hi"));
                    results.add(channel.sendReaction(PEER, "wamid.T", "ok"));
                    results.add(channel.sendMedia(PEER, file, "image/png", "a chart"));
                    channel.startTyping(PEER, "wamid.IN");
                }));

                assertEquals(List.of(), lines(sent), "phone number id " + id + " must send nothing");
                assertEquals(Collections.nCopies(3, Channel.SendResult.FAILED), results, "phone number id " + id);
                assertEquals(Collections.nCopies(4,
                                "Graph request refused: phoneNumberId must be Meta's numeric Phone number ID (digits only)"),
                        logged.stream().map(EventLogger.Captured::message).toList(), "phone number id " + id);
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static WhatsAppChannel channelFor(String phoneNumberId) {
        var binding = new WhatsAppBinding();
        binding.phoneNumberId = phoneNumberId;
        binding.accessToken = "EAAG-tok";
        return WhatsAppChannel.forBinding(binding);
    }

    /** Records each request and answers as Graph does: a media id for an upload, a message id otherwise. */
    private static OkHttpClient graph(List<Request> sent) {
        Interceptor canned = chain -> {
            var request = chain.request();
            sent.add(request);
            var body = "media".equals(request.url().pathSegments().getLast())
                    ? "{\"id\":\"9001\"}" : "{\"messages\":[{\"id\":\"wamid.M\"}]}";
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static List<String> lines(List<Request> sent) {
        return sent.stream().map(r -> r.method() + " " + r.url()).toList();
    }

    private static File tempImage() throws IOException {
        var path = Files.createTempFile("wa-graph-id", ".png");
        Files.write(path, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        return path.toFile();
    }
}
