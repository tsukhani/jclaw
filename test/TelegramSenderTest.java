import channels.TelegramChannel;
import channels.TelegramOutboundPlanner;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import play.test.UnitTest;

import java.util.List;

/**
 * Behavior tests for the {@code channels.TelegramSender} outbound engine
 * (reached through its public {@link TelegramChannel} adapter — the sender
 * class itself is package-private), against {@link MockTelegramServer}.
 * Complements {@code TelegramChannelTest}, which owns the planner/keyboard/
 * album basics; this file covers:
 *
 * <ul>
 *   <li>{@code deleteMessage} — request shape, null-guards, error mapping;</li>
 *   <li>the plain (streaming-recovery) {@code editMessageText} honoring
 *       link-preview suppression;</li>
 *   <li>error mapping on {@code trySend} for a transport-level failure (no
 *       Telegram error envelope at all);</li>
 *   <li>{@code sendTextWithRetry} through {@code sendText} — the single
 *       scheduled retry after a 429 (honoring {@code retry_after}) and the
 *       exhausted-retry failure;</li>
 *   <li>the {@code chunk} boundary-bias contract (paragraph → line → word →
 *       hard cut);</li>
 *   <li>typing-action outcome mapping (401 → UNAUTHORIZED vs 5xx → FAILED);</li>
 *   <li>reply/thread/caption params riding every single-file media upload and
 *       {@code sendMediaGroup}, plus bot-sent-id cache recording;</li>
 *   <li>bot-sent-id cache input guards.</li>
 * </ul>
 */
class TelegramSenderTest extends UnitTest {

    private MockTelegramServer mock;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockTelegramServer();
        mock.start();
    }

    @AfterEach
    void teardown() {
        play.Play.configuration.remove("telegram.linkPreview");
        play.Play.configuration.remove("telegram.replyTo.mode");
        if (mock != null) mock.close();
    }

    /** First recorded request body whose method matches, case-insensitively. */
    private String firstBodyFor(String methodName) {
        return mock.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase(methodName))
                .map(MockTelegramServer.RecordedRequest::body)
                .findFirst().orElse("");
    }

    private java.io.File tempFile(String suffix) throws Exception {
        var tmp = java.nio.file.Files.createTempFile("jclaw-sender-", suffix);
        java.nio.file.Files.write(tmp, new byte[]{ 1, 2, 3, 4 });
        tmp.toFile().deleteOnExit();
        return tmp.toFile();
    }

    // ─── deleteMessage (JCLAW-374) ───────────────────────────────────────

    @Test
    void deleteMessage_sendsChatAndMessageIdOnTheWire() {
        String token = "del-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.deleteMessage(token, "100", 7),
                    "deleteMessage must return true on a 200");
            assertEquals(1, mock.countRequests("deleteMessage"));
            var body = JsonParser.parseString(firstBodyFor("deleteMessage")).getAsJsonObject();
            assertEquals("100", body.get("chat_id").getAsString(),
                    "the target chat id must ride the request");
            assertEquals(7, body.get("message_id").getAsInt(),
                    "the target message id must ride the request");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void deleteMessage_nullArgsShortCircuitWithoutHttp() {
        String token = "del-null-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertFalse(TelegramChannel.deleteMessage(null, "100", 7));
            assertFalse(TelegramChannel.deleteMessage(token, null, 7));
            assertFalse(TelegramChannel.deleteMessage(token, "100", null));
            assertEquals(0, mock.requests().size(),
                    "null-arg guards must short-circuit before any HTTP call");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void deleteMessage_serverErrorMapsToFalseNotThrow() {
        String token = "del-err-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("deleteMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"message to delete not found\"}");
            assertFalse(TelegramChannel.deleteMessage(token, "100", 7),
                    "a Telegram rejection maps to false — deleteMessage never throws");
            assertEquals(1, mock.countRequests("deleteMessage"),
                    "the failed call was attempted exactly once (drop, not retry)");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── plain editMessageText + link-preview suppression (JCLAW-359) ────

    @Test
    void editMessageText_plainVariant_appliesLinkPreviewSuppressionOnlyWhenConfigured() throws Exception {
        String token = "edit-lp-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            // Default (preview on): no link_preview_options on the wire.
            TelegramChannel.editMessageText(token, "100", 5, "updated text");
            assertFalse(firstBodyFor("editMessageText").contains("link_preview_options"),
                    "with the default config the edit must not carry link_preview_options");

            // telegram.linkPreview=off: the edit must disable the preview.
            play.Play.configuration.setProperty("telegram.linkPreview", "off");
            TelegramChannel.editMessageText(token, "100", 5, "updated again");
            var edits = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("editMessageText"))
                    .toList();
            assertEquals(2, edits.size());
            var body = JsonParser.parseString(edits.get(1).body()).getAsJsonObject();
            assertTrue(body.has("link_preview_options"),
                    "linkPreview=off must attach link_preview_options to the edit");
            assertTrue(body.getAsJsonObject("link_preview_options").get("is_disabled").getAsBoolean(),
                    "the attached options must disable the preview");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── error mapping: transport-level failure (no Telegram envelope) ───

    @Test
    void trySend_transportFailureMapsToFailedWithNoRetryHint() throws Exception {
        // Point the client at a port nothing listens on: the SDK surfaces a
        // plain TelegramApiException (no request envelope, no retry_after) and
        // trySend must map it to FAILED rather than throwing.
        int deadPort;
        try (var probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        var deadUrl = org.telegram.telegrambots.meta.TelegramUrl.builder()
                .schema("http").host("127.0.0.1").port(deadPort).build();
        String token = "dead-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, deadUrl);
            var result = TelegramChannel.forToken(token).trySend("100", "hello");
            assertFalse(result.ok(), "a connection failure must map to FAILED");
            assertEquals(0L, result.retryAfterMs(),
                    "a transport failure carries no rate-limit retry hint");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── sendTextWithRetry via sendText (JCLAW-369 retry wrapper) ────────

    @Test
    void sendText_rateLimitedFirstAttempt_retriesAfterBackoffAndSucceeds() {
        String token = "retry-429-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            // First sendMessage → 429 retry_after=1; queue then drains to the
            // default 200, so the single scheduled retry must land the message.
            mock.enqueueResponse("sendMessage", 429,
                    "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                            + "\"parameters\":{\"retry_after\":1}}");
            long start = System.currentTimeMillis();
            var result = TelegramChannel.forToken(token).sendText("100", "rate limited once");
            long elapsedMs = System.currentTimeMillis() - start;

            assertTrue(result.ok(), "the retry after the 429 must deliver the message");
            assertEquals(2, mock.countRequests("sendMessage"),
                    "exactly two sends: the 429'd attempt plus one scheduled retry");
            assertTrue(elapsedMs >= 1000,
                    "the retry must honor retry_after (>=1s); elapsed " + elapsedMs + " ms");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendText_persistentServerErrorFailsAfterExactlyOneRetry() {
        String token = "retry-500-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 500,
                    "{\"ok\":false,\"error_code\":500,\"description\":\"Internal Server Error\"}");
            var result = TelegramChannel.forToken(token).sendText("100", "never lands");
            assertFalse(result.ok(), "a persistently failing send must report failure");
            assertEquals(2, mock.countRequests("sendMessage"),
                    "the retry policy is single-shot: initial attempt + one retry, then give up");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── chunk(): boundary-bias contract ─────────────────────────────────

    @Test
    void chunk_nullAndShortInputsPassThrough() {
        assertEquals(List.of(""), TelegramChannel.chunk(null, 10),
                "null text normalizes to a single empty chunk");
        assertEquals(List.of(""), TelegramChannel.chunk("", 10),
                "empty text stays a single empty chunk");
        assertEquals(List.of("short"), TelegramChannel.chunk("short", 10),
                "text within the budget is returned unsplit");
    }

    @Test
    void chunk_prefersParagraphThenLineThenWordBoundaries() {
        String para = "A".repeat(30);
        assertEquals(List.of(para, para),
                TelegramChannel.chunk(para + "\n\n" + para, 40),
                "a paragraph break inside the window wins and is consumed");

        String line = "B".repeat(20);
        assertEquals(List.of(line, line),
                TelegramChannel.chunk(line + "\n" + line, 30),
                "without a paragraph break, a newline is the next-best split");

        assertEquals(List.of("aaaa bbbb", "cccc"),
                TelegramChannel.chunk("aaaa bbbb cccc", 9),
                "without any newline, the last space inside the window splits");
    }

    @Test
    void chunk_hardCutsUnbreakableRunsWithoutLosingContent() {
        String unbreakable = "x".repeat(25);
        var chunks = TelegramChannel.chunk(unbreakable, 10);
        assertEquals(List.of("x".repeat(10), "x".repeat(10), "x".repeat(5)), chunks,
                "an unbreakable run hard-cuts at the budget");
        for (var c : chunks) {
            assertTrue(c.length() <= 10, "no chunk may exceed the budget: " + c.length());
        }
        assertEquals(unbreakable, String.join("", chunks),
                "hard cuts must not drop characters");
    }

    // ─── sendPoll: bot-sent-id recording + question guard (JCLAW-387 C1) ─

    @Test
    void sendPoll_recordsSentMessageIdAndRejectsNullQuestion() {
        String token = "poll-rec-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.sendPoll(token, "100", "Ship it?",
                            List.of("yes", "no"), null, null, null));
            // Mock's default sendPoll response is message_id=1 → the reaction
            // gate must now recognize that id as bot-sent in chat 100.
            assertTrue(TelegramChannel.wasSentByBot(token, "100", 1),
                    "a successful poll send must land in the bot-sent-id cache");

            assertFalse(TelegramChannel.sendPoll(token, "100", null,
                            List.of("yes", "no"), null, null, null),
                    "a null question is rejected before any API call");
            assertEquals(1, mock.countRequests("sendPoll"),
                    "the rejected poll must not reach the wire");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── typing-action outcome mapping (JCLAW-342) ───────────────────────

    @Test
    void sendTypingAction_mapsAuthVsTransientVsSuccessOutcomes() {
        String token = "typing-map-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendChatAction", 401,
                    "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
            assertEquals(TelegramChannel.TypingActionOutcome.UNAUTHORIZED,
                    TelegramChannel.sendTypingAction(token, "100", null),
                    "a 401 means a revoked token — callers must stop re-firing");

            mock.respondWith("sendChatAction", 500,
                    "{\"ok\":false,\"error_code\":500,\"description\":\"Internal Server Error\"}");
            assertEquals(TelegramChannel.TypingActionOutcome.FAILED,
                    TelegramChannel.sendTypingAction(token, "100", null),
                    "a transient 5xx maps to FAILED (safe to keep trying)");

            mock.respondWith("sendChatAction", 200, "{\"ok\":true,\"result\":true}");
            assertEquals(TelegramChannel.TypingActionOutcome.SENT,
                    TelegramChannel.sendTypingAction(token, "100", null));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── reply/thread/caption ride every media upload (JCLAW-364/369) ────

    @Test
    void mediaUploads_carryReplyThreadAndCaptionForEveryKind() throws Exception {
        String token = "media-rtc-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var ch = TelegramChannel.forToken(token);
            var reply = ReplyParameters.builder()
                    .messageId(42)
                    .allowSendingWithoutReply(true)
                    .build();

            assertTrue(ch.trySendPhoto("100", tempFile(".png"), "p.png", reply, 9, "cap-photo"));
            assertTrue(ch.trySendDocument("100", tempFile(".pdf"), "d.pdf", reply, 9, "cap-doc"));
            assertTrue(ch.trySendVoice("100", tempFile(".ogg"), "v.ogg", reply, 9, "cap-voice"));
            assertTrue(ch.trySendAudio("100", tempFile(".mp3"), "a.mp3", reply, 9, "cap-audio"));
            assertTrue(ch.trySendVideo("100", tempFile(".mp4"), "m.mp4", reply, 9, "cap-video"));

            String[][] expectations = {
                    {"sendPhoto", "cap-photo"},
                    {"sendDocument", "cap-doc"},
                    {"sendVoice", "cap-voice"},
                    {"sendAudio", "cap-audio"},
                    {"sendVideo", "cap-video"},
            };
            for (var e : expectations) {
                assertEquals(1, mock.countRequests(e[0]), e[0] + " must fire exactly once");
                String body = firstBodyFor(e[0]);
                assertTrue(body.contains("reply_parameters"),
                        e[0] + " must carry reply_parameters: " + body);
                assertTrue(body.contains("42"),
                        e[0] + " reply target id must be on the wire");
                assertTrue(body.contains("message_thread_id"),
                        e[0] + " must scope to the forum topic: " + body);
                assertTrue(body.contains(e[1]),
                        e[0] + " must carry its caption '" + e[1] + "'");
            }
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── sendMediaGroup: reply/thread + id recording + video caption ─────

    @Test
    void sendMediaGroup_carriesReplyAndThreadAndRecordsEveryReturnedId() throws Exception {
        String token = "album-rtc-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var reply = ReplyParameters.builder()
                    .messageId(42)
                    .allowSendingWithoutReply(true)
                    .build();
            var items = List.of(
                    new TelegramOutboundPlanner.FileSegment(
                            "a.png", tempFile(".png"), true, false,
                            TelegramOutboundPlanner.MediaKind.PHOTO),
                    new TelegramOutboundPlanner.FileSegment(
                            "b.png", tempFile(".png"), true, false,
                            TelegramOutboundPlanner.MediaKind.PHOTO));
            assertTrue(TelegramChannel.forToken(token)
                    .sendMediaGroup("100", items, null, reply, 9));

            String body = firstBodyFor("sendMediaGroup");
            assertTrue(body.contains("reply_parameters"),
                    "the album must carry reply_parameters: " + body);
            assertTrue(body.contains("42"), "reply target id must be on the wire");
            assertTrue(body.contains("message_thread_id"),
                    "the album must scope to the forum topic: " + body);
            // The mock's default sendMediaGroup response returns message ids 1
            // and 2 — BOTH album items must land in the bot-sent-id cache.
            assertTrue(TelegramChannel.wasSentByBot(token, "100", 1),
                    "the first album message id must be recorded as bot-sent");
            assertTrue(TelegramChannel.wasSentByBot(token, "100", 2),
                    "the second album message id must be recorded as bot-sent");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMediaGroup_videoLeadingItemCarriesTheAlbumCaption() throws Exception {
        String token = "album-vidcap-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var items = List.of(
                    new TelegramOutboundPlanner.FileSegment(
                            "clip.mp4", tempFile(".mp4"), false, false,
                            TelegramOutboundPlanner.MediaKind.VIDEO),
                    new TelegramOutboundPlanner.FileSegment(
                            "shot.png", tempFile(".png"), true, false,
                            TelegramOutboundPlanner.MediaKind.PHOTO));
            assertTrue(TelegramChannel.forToken(token)
                    .sendMediaGroup("100", items, "the album caption", null, null));

            String body = firstBodyFor("sendMediaGroup");
            assertTrue(body.contains("\"type\":\"video\""), "video item must lead: " + body);
            int captionCount = body.split("the album caption", -1).length - 1;
            assertEquals(1, captionCount,
                    "the album caption rides exactly the first (video) item: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── bot-sent-id cache guards + misc adapter surface ─────────────────

    @Test
    void recordSent_blankOrNullInputsAreIgnoredWhileValidOnesRegister() {
        String token = "cache-guard-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var ch = TelegramChannel.forToken(token);
            // Guarded inputs: none may throw, none may register.
            ch.recordSentForTest(null, 5);
            ch.recordSentForTest("", 5);
            ch.recordSentForTest("   ", 5);
            ch.recordSentForTest("chat", null);
            assertFalse(ch.wasSentByBot("", 5), "a blank chat id never registers");
            assertFalse(ch.wasSentByBot("chat", null), "a null message id never matches");
            assertFalse(ch.wasSentByBot("chat", 5), "the guarded writes must not have registered");
            // A valid write still lands.
            ch.recordSentForTest("chat", 5);
            assertTrue(ch.wasSentByBot("chat", 5));
            assertEquals("telegram", ch.channelName(),
                    "the channel identifies itself as telegram");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendText_agentOverloadDeliversPlainTextTurn() {
        String token = "agent-text-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var agent = new models.Agent();
            agent.name = "sender-test-agent-" + System.nanoTime();
            var result = TelegramChannel.forToken(token)
                    .sendText("100", "hello from the agent", agent);
            assertTrue(result.ok(), "a plain-text agent turn must deliver");
            assertEquals(1, mock.countRequests("sendMessage"),
                    "one text segment → exactly one sendMessage");
            assertTrue(firstBodyFor("sendMessage").contains("hello from the agent"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }
}
