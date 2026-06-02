import channels.TelegramChannel;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import play.test.UnitTest;

/**
 * Unit tests for {@link TelegramChannel} outbound paths (photo upload
 * timeouts, inline keyboard rendering) against {@link MockTelegramServer}.
 */
class TelegramChannelTest extends UnitTest {

    private MockTelegramServer mock;
    private String prevBase;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockTelegramServer();
        mock.start();
        prevBase = TelegramChannel.TELEGRAM_API_BASE;
        TelegramChannel.TELEGRAM_API_BASE = "http://127.0.0.1:" + mock.port();
    }

    @AfterEach
    void teardown() {
        TelegramChannel.TELEGRAM_API_BASE = prevBase;
        // JCLAW-369: the reply-mode tests set this property; clear it so they
        // can't leak into other tests in the suite.
        play.Play.configuration.remove("telegram.replyTo.mode");
        if (mock != null) mock.close();
    }

    // ─── Upload client timeouts (JCLAW-122) ─────────────────────────────

    @Test
    void trySendPhoto_succeedsWhenMockDelayExceedsTextPathTimeout() throws Exception {
        // MockTelegramServer sits on 127.0.0.1 so the SDK's OkHttp client
        // will hit it directly. We install a TelegramChannel pointing at the
        // mock, then set a response delay of 3500ms — over the text-path
        // read timeout of 3000ms, well under the upload-path read timeout of
        // 60000ms. The sendPhoto should succeed, proving the dedicated
        // uploadClient (not the fast text client) is used for photo uploads.
        String botToken = "upload-timeout-bot";
        TelegramChannel.installForTest(botToken, mock.telegramUrl());
        mock.respondWithDelay("sendPhoto", 200,
                "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1700000000,"
                        + "\"chat\":{\"id\":12345,\"type\":\"private\"}}}",
                3500);

        try {
            // Minimal real file on disk — SDK's SendPhoto validates that the
            // InputFile resolves to something readable.
            var tmp = java.nio.file.Files.createTempFile("jclaw-photo-", ".png");
            java.nio.file.Files.write(tmp, new byte[]{ (byte) 0x89, 'P', 'N', 'G' });

            long start = System.currentTimeMillis();
            boolean ok = TelegramChannel.forToken(botToken).trySendPhoto(
                    "12345", tmp.toFile(), "test-photo.png");
            long elapsedMs = System.currentTimeMillis() - start;

            assertTrue(ok, "sendPhoto should succeed — the 3500 ms server delay fits in the 60 s upload timeout");
            assertTrue(elapsedMs >= 3500,
                    "elapsed time confirms the call waited for the delayed response: " + elapsedMs + " ms");
            assertTrue(elapsedMs < 30_000,
                    "elapsed time must be bounded by the upload timeout, not hang: " + elapsedMs + " ms");
            java.nio.file.Files.deleteIfExists(tmp);
        } finally {
            TelegramChannel.clearForTest(botToken);
        }
    }

    // ─── JCLAW-325: residual coverage ───────────────────────────────────

    @Test
    void botToken_accessorReturnsTokenPassedToConstructor() {
        String token = "accessor-token-" + System.nanoTime();
        try {
            var ch = TelegramChannel.forToken(token);
            assertEquals(token, ch.botToken(),
                    "botToken() accessor must round-trip the constructor arg");
        } finally {
            TelegramChannel.evictToken(token);
        }
    }

    @Test
    void sendMessage_nullArgumentsShortCircuitReturnsFalse() {
        // Defensive nullability gate at line 355: any null arg fails fast
        // with a single error log; no HTTP traffic.
        String token = "null-args-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertFalse(TelegramChannel.sendMessage(null, "chat", "hi"));
            assertFalse(TelegramChannel.sendMessage(token, null, "hi"));
            assertFalse(TelegramChannel.sendMessage(token, "chat", null));
            assertEquals(0, mock.requests().size(),
                    "null-guard must short-circuit before any HTTP call");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_emptyTextStillSendsBlankMessage() {
        // Empty text is not null — the SDK accepts empty strings but Telegram
        // rejects them with HTTP 400. Mock returns 200 so we just confirm the
        // helper drives through to sendMessage when text is "".
        String token = "empty-text-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            // Empty text produces an empty TextSegment → sendTextSegment
            // short-circuits at the isBlank check, no wire traffic.
            assertTrue(TelegramChannel.sendMessage(token, "12345", ""),
                    "empty text is a no-op success");
            assertEquals(0, mock.requests().size(),
                    "blank text must not produce any sendMessage on the wire");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void setMyCommands_shortCircuitsOnNullOrEmptyArguments() {
        // Line 249: triple-guard against null token / null commands / empty
        // commands. None should cause an HTTP call.
        String token = "setcmd-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            TelegramChannel.setMyCommands(null, java.util.List.of(
                    org.telegram.telegrambots.meta.api.objects.commands.BotCommand
                            .builder().command("/x").description("y").build()));
            TelegramChannel.setMyCommands(token, null);
            TelegramChannel.setMyCommands(token, java.util.List.of());
            assertEquals(0, mock.requests().size(),
                    "null/empty inputs must short-circuit setMyCommands");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void setMyCommands_successFiresSetMyCommandsRequest() {
        String token = "setcmd-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            TelegramChannel.setMyCommands(token, java.util.List.of(
                    org.telegram.telegrambots.meta.api.objects.commands.BotCommand
                            .builder().command("/help").description("Show help").build()));
            assertEquals(1, mock.countRequests("setMyCommands"),
                    "setMyCommands should dispatch one Bot API call");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void setMyCommands_swallowsExceptionFromServerError() {
        // Mock returns 400 — setMyCommands MUST NOT propagate; the binding
        // activation loop relies on swallowed failures.
        String token = "setcmd-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("setMyCommands", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad commands\"}");
            Assertions.assertDoesNotThrow(() ->
                    TelegramChannel.setMyCommands(token, java.util.List.of(
                            org.telegram.telegrambots.meta.api.objects.commands.BotCommand
                                    .builder().command("/x").description("y").build())));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendTypingAction_shortCircuitsOnNullTokenOrChat() {
        // Line 317: null guard. No wire traffic.
        String token = "typing-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            TelegramChannel.sendTypingAction(null, "chat");
            TelegramChannel.sendTypingAction(token, null);
            assertEquals(0, mock.requests().size(),
                    "null guard must skip the HTTP path");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendTypingAction_swallowsExceptionFromServerError() {
        // Failures must NOT abort the LLM flow that owns the actual response.
        String token = "typing-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendChatAction", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad\"}");
            Assertions.assertDoesNotThrow(() ->
                    TelegramChannel.sendTypingAction(token, "12345"));
            // Sanity: the call did try the wire (1 request landed).
            assertEquals(1, mock.countRequests("sendChatAction"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── trySend (instance) — JCLAW-325 ─────────────────────────────────

    @Test
    void trySend_returnsRateLimitedOn429WithRetryAfter() {
        // Verifies line 784-788: a 429 with retry_after in the parameters
        // surfaces as SendResult.rateLimited carrying the ms-converted delay.
        String token = "trysend-429-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 429,
                    "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                            + "\"parameters\":{\"retry_after\":3}}");
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>hi</b>");
            assertFalse(result.ok());
            assertEquals(3_000L, result.retryAfterMs(),
                    "retry_after=3s must convert to 3000 ms in SendResult");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_returnsFailedOn400WithoutRetryAfter() {
        // Lines 790-792: TelegramApiRequestException without retryAfter falls
        // through to the generic FAILED branch.
        String token = "trysend-400-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad request\"}");
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>hi</b>");
            assertFalse(result.ok());
            assertEquals(0L, result.retryAfterMs(),
                    "non-rate-limit failure must have zero retryAfterMs");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_returnsOkOnSuccessfulResponse() {
        String token = "trysend-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>hi</b>");
            assertTrue(result.ok());
            assertEquals(1, mock.countRequests("sendMessage"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── trySendDocument — JCLAW-325 ───────────────────────────────────

    @Test
    void trySendDocument_successPathReturnsTrue() throws Exception {
        // Mirrors the trySendPhoto coverage but for the document path.
        String token = "doc-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var tmp = java.nio.file.Files.createTempFile("jclaw-doc-", ".txt");
            java.nio.file.Files.writeString(tmp, "hello");
            boolean ok = TelegramChannel.forToken(token).trySendDocument(
                    "12345", tmp.toFile(), "report.txt");
            assertTrue(ok);
            assertEquals(1, mock.countRequests("sendDocument"));
            java.nio.file.Files.deleteIfExists(tmp);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySendDocument_failurePathReturnsFalse() throws Exception {
        // Mock returns 400 — drives the catch branch (lines 762-766).
        String token = "doc-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendDocument", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad doc\"}");
            var tmp = java.nio.file.Files.createTempFile("jclaw-doc-fail-", ".bin");
            java.nio.file.Files.write(tmp, new byte[]{1, 2, 3});
            boolean ok = TelegramChannel.forToken(token).trySendDocument(
                    "12345", tmp.toFile(), null);
            assertFalse(ok, "400 server response must surface as false return");
            java.nio.file.Files.deleteIfExists(tmp);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySendPhoto_failurePathReturnsFalse() throws Exception {
        // Drives lines 731-736: photo upload error catch.
        String token = "photo-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendPhoto", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad photo\"}");
            var tmp = java.nio.file.Files.createTempFile("jclaw-photo-fail-", ".png");
            java.nio.file.Files.write(tmp, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            boolean ok = TelegramChannel.forToken(token).trySendPhoto(
                    "12345", tmp.toFile(), null);
            assertFalse(ok, "400 from Telegram must surface as false");
            java.nio.file.Files.deleteIfExists(tmp);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── Inline keyboard helpers (JCLAW-109) ───────────────────────────

    @Test
    void sendMessageWithKeyboard_successReturnsMessageId() {
        String token = "kbd-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 200,
                    "{\"ok\":true,\"result\":{\"message_id\":99,\"chat\":{\"id\":12345,"
                            + "\"type\":\"private\"},\"date\":1700000000,\"text\":\"hi\"}}");
            var keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                            .text("OK").callbackData("ok").build()))
                    .build();
            Integer mid = TelegramChannel.sendMessageWithKeyboard(token, "12345",
                    "<b>pick</b>", keyboard);
            assertNotNull(mid);
            assertEquals(99, mid.intValue(),
                    "messageId comes from the response payload");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessageWithKeyboard_failureReturnsNull() {
        // Drives lines 821-825: keyboard send catch.
        String token = "kbd-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad\"}");
            var keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                            .text("OK").callbackData("ok").build()))
                    .build();
            assertNull(TelegramChannel.sendMessageWithKeyboard(token, "12345", "x", keyboard));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void editMessageText_keyboardOverload_successAndFailurePaths() {
        // Covers both the 5-arg static editMessageText: success and the
        // exception catch (lines 846-849). Includes a null-keyboard branch
        // to exercise line 842.
        String token = "edit-kbd-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            // Default 200: with keyboard.
            var kbd = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                            .text("ack").callbackData("ack").build()))
                    .build();
            assertTrue(TelegramChannel.editMessageText(token, "12345", 7, "x", kbd));
            // Null keyboard branch (line 842 false-side).
            assertTrue(TelegramChannel.editMessageText(token, "12345", 7, "x", null));

            // Now force failure.
            mock.respondWith("editMessageText", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"no\"}");
            assertFalse(TelegramChannel.editMessageText(token, "12345", 7, "x", null),
                    "exception path must surface as false");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void editMessageText_threeArgVariant_propagatesExceptionOnError() {
        // Line 227-235: the 4-arg overload that propagates instead of
        // swallowing. Drive the error path and assert the exception is
        // surfaced — callers (recovery job) decide retry policy.
        String token = "edit-3arg-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("editMessageText", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"missing msg\"}");
            assertThrows(
                    org.telegram.telegrambots.meta.exceptions.TelegramApiException.class,
                    () -> TelegramChannel.editMessageText(token, "12345", 7, "x"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void editMessageText_threeArgVariant_successCompletesQuietly() throws Exception {
        String token = "edit-3arg-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            // Default 200 response — must not throw.
            TelegramChannel.editMessageText(token, "12345", 7, "x");
            assertEquals(1, mock.countRequests("editMessageText"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void answerCallbackQuery_successWithText() {
        String token = "ans-ok-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.answerCallbackQuery(token, "cb-1", "ok!", true));
            assertEquals(1, mock.countRequests("answerCallbackQuery"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void answerCallbackQuery_nullAndEmptyTextSkipBuilderTextField() {
        // Lines 866 false-side: null/empty text skips the builder.text() call.
        String token = "ans-blank-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.answerCallbackQuery(token, "cb-1", null, false));
            assertTrue(TelegramChannel.answerCallbackQuery(token, "cb-2", "", false));
            assertEquals(2, mock.countRequests("answerCallbackQuery"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void answerCallbackQuery_failureReturnsFalse() {
        // Drives lines 870-873: exception catch.
        String token = "ans-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("answerCallbackQuery", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"stale\"}");
            assertFalse(TelegramChannel.answerCallbackQuery(token, "cb-1", "msg", true));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── parseCallback — JCLAW-325 ──────────────────────────────────────

    @Test
    void parseCallback_returnsNullForNonCallbackUpdates() {
        // Plain text update → no callback_query → null. Both overloads.
        var jsonNoCb = JsonParser.parseString("""
                {"update_id":1,"message":{"message_id":1,
                  "chat":{"id":1,"type":"private"},"date":1,"text":"hi"}}
                """).getAsJsonObject();
        assertNull(TelegramChannel.parseCallback(jsonNoCb));
        assertNull(TelegramChannel.parseCallback((com.google.gson.JsonObject) null));
        assertNull(TelegramChannel.parseCallback(
                (org.telegram.telegrambots.meta.api.objects.Update) null));
    }

    @Test
    void parseCallback_returnsNullForBlankOrMissingData() {
        // cq.getData() blank → return null. Don't dispatch to a router that
        // can't even pick a destination.
        var jsonBlank = JsonParser.parseString("""
                {"update_id":1,"callback_query":{"id":"cb-1",
                  "from":{"id":42,"is_bot":false,"first_name":"x"},
                  "chat_instance":"ci","data":""}}
                """).getAsJsonObject();
        assertNull(TelegramChannel.parseCallback(jsonBlank));
    }

    @Test
    void parseCallback_returnsNullWhenFromMissing() {
        // No from → unauthorizable → null.
        var json = JsonParser.parseString("""
                {"update_id":1,"callback_query":{"id":"cb-1",
                  "chat_instance":"ci","data":"x"}}
                """).getAsJsonObject();
        assertNull(TelegramChannel.parseCallback(json));
    }

    @Test
    void parseCallback_extractsChatAndMessageIdWhenPresent() {
        var json = JsonParser.parseString("""
                {"update_id":1,"callback_query":{"id":"cb-1",
                  "from":{"id":42,"is_bot":false,"first_name":"x"},
                  "message":{"message_id":7,
                    "chat":{"id":99,"type":"private"},"date":1,"text":"prev"},
                  "chat_instance":"ci","data":"act:next"}}
                """).getAsJsonObject();
        var cb = TelegramChannel.parseCallback(json);
        assertNotNull(cb);
        assertEquals("cb-1", cb.callbackId());
        assertEquals("42", cb.fromId());
        assertEquals("99", cb.chatId());
        assertEquals("private", cb.chatType());
        assertEquals(7, cb.messageId().intValue());
        assertEquals("act:next", cb.data());
    }

    @Test
    void parseCallback_handlesMissingMessageOrigin() {
        // Inline-mode callbacks may have no `message` (just chat_instance);
        // chatId/messageId stay null, but the callback is still parseable.
        var json = JsonParser.parseString("""
                {"update_id":1,"callback_query":{"id":"cb-2",
                  "from":{"id":42,"is_bot":false,"first_name":"x"},
                  "chat_instance":"ci","data":"inline:foo"}}
                """).getAsJsonObject();
        var cb = TelegramChannel.parseCallback(json);
        assertNotNull(cb);
        assertNull(cb.chatId());
        assertNull(cb.messageId());
        assertEquals("inline:foo", cb.data());
    }

    @Test
    void parseCallback_returnsNullOnGsonError() {
        // Lines 696-699: a malformed JSON payload that the Jackson reader can't
        // bind must return null with a logged warn — not propagate.
        var bogus = JsonParser.parseString("""
                {"update_id":"not-a-number","callback_query":1}
                """).getAsJsonObject();
        // The Jackson deserializer fails on the structural mismatch.
        assertNull(TelegramChannel.parseCallback(bogus));
    }

    // ─── parseUpdate (SDK Update) — VideoNote + Gson-error paths ────────

    @Test
    void parseUpdate_returnsNullOnGsonError() {
        // Lines 550-553: malformed JSON returns null instead of throwing.
        var bogus = JsonParser.parseString("""
                {"update_id":"oops"}
                """).getAsJsonObject();
        assertNull(TelegramChannel.parseUpdate(bogus));
    }

    @Test
    void parseUpdate_extractsVideoNoteAttachment() {
        // Lines 629-635: video_note path (separate from video).
        var json = JsonParser.parseString("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"x"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "video_note":{"file_id":"VN","file_unique_id":"uvn",
                    "length":240,"duration":5,"file_size":12345}}}
                """).getAsJsonObject();
        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals(1, msg.attachments().size());
        assertEquals("VN", msg.attachments().get(0).telegramFileId());
        assertEquals(models.MessageAttachment.KIND_FILE,
                msg.attachments().get(0).kind(),
                "video_note maps to KIND_FILE");
    }

    // ─── setWebhook(token,url,secret) + deleteWebhook — JCLAW-325/339 ───────

    @Test
    void setWebhook_tokenOverloadRegistersAndGuardsNulls() {
        String token = "whx-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.setWebhook(token, "https://example.com/wh", "sek"));
            assertEquals(1, mock.countRequests("setWebhook"));
            // Null token or url short-circuits before any request.
            assertFalse(TelegramChannel.setWebhook(null, "https://example.com/wh", "sek"));
            assertFalse(TelegramChannel.setWebhook(token, null, "sek"));
            assertEquals(1, mock.countRequests("setWebhook"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void deleteWebhook_nullTokenReturnsFalse() {
        assertFalse(TelegramChannel.deleteWebhook(null));
    }

    @Test
    void deleteWebhook_successAndFailurePaths() {
        String token = "del-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.deleteWebhook(token));
            assertEquals(1, mock.countRequests("deleteWebhook"));

            mock.respondWith("deleteWebhook", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"nope\"}");
            assertFalse(TelegramChannel.deleteWebhook(token));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── JCLAW-369: outbound reply targeting + topic-aware sends ─────────
    //
    // The SDK posts text methods (sendMessage, sendChatAction) as a JSON body
    // (objectMapper.writeValueAsString → application/json), so we assert
    // directly on the recorded request body's snake_case JSON keys
    // (reply_parameters / reply_to_message_id / message_thread_id).

    /** Concatenate every recorded sendMessage body so multi-chunk turns are easy to scan. */
    private String allSendMessageBodies() {
        StringBuilder sb = new StringBuilder();
        for (var r : mock.requests()) {
            if (r.method().equalsIgnoreCase("sendMessage")) sb.append(r.body()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void sendMessage_nullReplyAndThread_omitsBothFields_backCompat() {
        // AC4: the dormant default — existing callers pass nothing → null →
        // no reply_parameters, no message_thread_id on the wire.
        String token = "jclaw369-backcompat-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "all");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.sendMessage(token, "12345", "plain reply", null, null, null));
            String body = allSendMessageBodies();
            assertFalse(body.contains("reply_parameters"),
                    "null replyToMessageId must omit reply_parameters even when mode=all");
            assertFalse(body.contains("message_thread_id"),
                    "null messageThreadId must omit message_thread_id");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_replyModeFirst_setsReplyOnlyOnFirstChunk() {
        // mode=first: the reply badge lands on exactly the first chunk of the
        // turn. Force a two-chunk turn by exceeding the 4000-char chunk size.
        String token = "jclaw369-first-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "first");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            String big = "A".repeat(4500);
            assertTrue(TelegramChannel.sendMessage(token, "12345", big, null, 9001, null));
            long sends = mock.countRequests("sendMessage");
            assertTrue(sends >= 2, "4500 chars must split into >=2 chunks; got " + sends);
            long withReply = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .filter(r -> r.body().contains("reply_parameters"))
                    .count();
            assertEquals(1, withReply,
                    "mode=first must set reply_parameters on exactly one (the first) chunk");
            assertTrue(allSendMessageBodies().contains("9001"),
                    "the reply target message id must appear in the reply_parameters payload");
            assertTrue(allSendMessageBodies().contains("allow_sending_without_reply"),
                    "allow_sending_without_reply must be set so a deleted target degrades gracefully");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_replyModeAll_setsReplyOnEveryChunk() {
        String token = "jclaw369-all-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "all");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            String big = "B".repeat(4500);
            assertTrue(TelegramChannel.sendMessage(token, "12345", big, null, 7777, null));
            long sends = mock.countRequests("sendMessage");
            long withReply = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .filter(r -> r.body().contains("reply_parameters"))
                    .count();
            assertTrue(sends >= 2, "expected a multi-chunk turn; got " + sends);
            assertEquals(sends, withReply,
                    "mode=all must set reply_parameters on every chunk of the turn");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_replyModeOff_neverSetsReply() {
        String token = "jclaw369-off-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "off");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.sendMessage(token, "12345", "hi there", null, 4242, null));
            assertFalse(allSendMessageBodies().contains("reply_parameters"),
                    "mode=off must never set reply_parameters even with a non-null target");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void replyToMode_defaultsToFirstWhenUnsetOrUnrecognized() {
        // No property set → default "first".
        play.Play.configuration.remove("telegram.replyTo.mode");
        assertEquals("first", TelegramChannel.replyToMode(),
                "unset telegram.replyTo.mode defaults to first");
        // Unrecognized value normalizes to first; case-insensitive for known ones.
        play.Play.configuration.setProperty("telegram.replyTo.mode", "garbage");
        assertEquals("first", TelegramChannel.replyToMode(),
                "unrecognized value normalizes to first");
        play.Play.configuration.setProperty("telegram.replyTo.mode", "ALL");
        assertEquals("all", TelegramChannel.replyToMode(),
                "known value is lowercased");
    }

    @Test
    void sendMessage_threadIdApplied_whenNotGeneral() {
        // AC3: a non-General topic thread id is set on the send.
        String token = "jclaw369-thread-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "off");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.sendMessage(token, "12345", "in topic", null, null, 42));
            String body = allSendMessageBodies();
            assertTrue(body.contains("message_thread_id"),
                    "non-General topic id must set message_thread_id on the send");
            assertTrue(body.contains("42"),
                    "the thread id value must appear on the wire");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_threadIdOmitted_forGeneralTopic() {
        // AC3: thread id == 1 (General) must be OMITTED on sends — naming it
        // explicitly is rejected by the Bot API; a bare send lands in General.
        String token = "jclaw369-general-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "off");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.sendMessage(token, "12345", "general topic", null, null, 1));
            assertFalse(allSendMessageBodies().contains("message_thread_id"),
                    "General topic (thread id 1) must be omitted on sends");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendTypingAction_threadIdIncludesGeneralTopic() {
        // AC3: typing/chat-action carries message_thread_id when present —
        // General topic (1) INCLUDED for typing, unlike sends.
        String token = "jclaw369-typing-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            TelegramChannel.sendTypingAction(token, "12345", 1);
            TelegramChannel.sendTypingAction(token, "12345", 99);
            assertEquals(2, mock.countRequests("sendChatAction"));
            String bodies = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendChatAction"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(bodies.contains("message_thread_id"),
                    "typing action must carry message_thread_id when present");
            assertTrue(bodies.contains("\"message_thread_id\":1") || bodies.contains("message_thread_id\":1"),
                    "General topic (1) must be INCLUDED on the typing action");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendTypingAction_nullThreadId_omitsField_backCompat() {
        String token = "jclaw369-typing-null-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            TelegramChannel.sendTypingAction(token, "12345"); // legacy no-thread overload
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendChatAction"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .reduce("", (a, b) -> a + b);
            assertFalse(body.contains("message_thread_id"),
                    "legacy typing overload must omit message_thread_id");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessageWithKeyboard_replyAndThreadOverload_appliesBoth() {
        // The keyboard send is a single message → treated as the turn's first
        // chunk, so first/all both apply the reply; thread is General-stripped.
        String token = "jclaw369-kbd-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.replyTo.mode", "first");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 200,
                    "{\"ok\":true,\"result\":{\"message_id\":5,\"chat\":{\"id\":12345,"
                            + "\"type\":\"supergroup\"},\"date\":1,\"text\":\"hi\"}}");
            var keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                            .text("OK").callbackData("ok").build()))
                    .build();
            Integer mid = TelegramChannel.sendMessageWithKeyboard(token, "12345",
                    "<b>pick</b>", keyboard, 3030, 55);
            assertEquals(5, mid.intValue());
            String body = allSendMessageBodies();
            assertTrue(body.contains("reply_parameters") && body.contains("3030"),
                    "keyboard send must carry reply_parameters for the target");
            assertTrue(body.contains("message_thread_id") && body.contains("55"),
                    "keyboard send must carry the non-General thread id");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }
}
