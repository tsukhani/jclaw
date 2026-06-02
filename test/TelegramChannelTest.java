import channels.TelegramChannel;
import channels.TelegramOutboundPlanner;
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
        // JCLAW-359: same for the link-preview flag the suppression tests set.
        play.Play.configuration.remove("telegram.linkPreview");
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

    // ─── JCLAW-359: HTML-parse plain-text fallback + link-preview ───────

    /** The exact 400 shape Telegram returns when the HTML payload is malformed. */
    private static final String PARSE_ERROR_400 =
            "{\"ok\":false,\"error_code\":400,\"description\":"
                    + "\"Bad Request: can't parse entities: Unsupported start tag \\\"foo\\\" at byte offset 0\"}";

    @Test
    void trySend_htmlParseError_retriesAsPlainTextAndSucceeds() {
        // AC1: a 400 "can't parse entities" on the HTML send retries the SAME
        // text once without parse_mode and succeeds. Two sendMessage calls hit
        // the wire; the second carries no parse_mode.
        String token = "jclaw359-parse-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            // First sendMessage → parse-error 400; second → default 200 success.
            mock.enqueueResponse("sendMessage", 400, PARSE_ERROR_400.replace("\n", ""));
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>broken");
            assertTrue(result.ok(), "plain-text fallback must succeed → SendResult.OK");
            assertEquals(2, mock.countRequests("sendMessage"),
                    "exactly two sends: rejected HTML, then plain-text retry");
            var sends = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .toList();
            assertTrue(sends.get(0).body().contains("parse_mode"),
                    "first (rejected) send must carry parse_mode=HTML");
            assertFalse(sends.get(1).body().contains("parse_mode"),
                    "plain-text retry must omit parse_mode entirely");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_nonParse400_failsWithoutRetry() {
        // AC1: a non-parse 400 (genuine bad request) must NOT trigger the
        // plain-text retry — exactly one send, result FAILED, no spin.
        String token = "jclaw359-bad400-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}");
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>hi</b>");
            assertFalse(result.ok(), "a non-parse 400 stays FAILED");
            assertEquals(0L, result.retryAfterMs(), "non-rate-limit failure has zero retryAfterMs");
            assertEquals(1, mock.countRequests("sendMessage"),
                    "a non-parse 400 must not retry — exactly one send");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_parseFallbackAlsoFails_returnsFailed() {
        // AC1: if the plain-text retry itself fails, the result is FAILED (not a
        // false OK). Both scripted sends 400; only the first is a parse error.
        String token = "jclaw359-bothfail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.enqueueResponse("sendMessage", 400, PARSE_ERROR_400.replace("\n", ""));
            mock.enqueueResponse("sendMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}");
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>broken");
            assertFalse(result.ok(), "plain-text fallback failing must surface as FAILED");
            assertEquals(2, mock.countRequests("sendMessage"),
                    "one HTML send + one plain-text retry — no third attempt");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_rateLimit429_unchangedByFallback() {
        // AC1: the 429 retry_after path is untouched — still surfaces as
        // rateLimited with the ms delay, no plain-text retry.
        String token = "jclaw359-429-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 429,
                    "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                            + "\"parameters\":{\"retry_after\":3}}");
            var result = TelegramChannel.forToken(token).trySend("12345", "<b>hi</b>");
            assertFalse(result.ok());
            assertEquals(3_000L, result.retryAfterMs(),
                    "429 retry_after=3 must still map to 3000 ms");
            assertEquals(1, mock.countRequests("sendMessage"),
                    "429 must not trigger the parse fallback — one send only");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_linkPreviewDisabled_whenConfiguredOff() {
        // AC2: telegram.linkPreview=off attaches link_preview_options with
        // is_disabled=true to the send body.
        String token = "jclaw359-lpoff-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.linkPreview", "off");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.forToken(token).trySend("12345", "see https://x.test").ok());
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .findFirst().orElse("");
            assertTrue(body.contains("link_preview_options"),
                    "off must attach link_preview_options to the send");
            assertTrue(body.contains("is_disabled"),
                    "the options must carry is_disabled=true");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySend_linkPreviewAbsent_byDefault() {
        // AC2: with the flag unset, no link_preview_options on the wire —
        // Telegram's default preview-on behavior is preserved.
        String token = "jclaw359-lpdefault-" + System.nanoTime();
        try {
            play.Play.configuration.remove("telegram.linkPreview");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.forToken(token).trySend("12345", "see https://x.test").ok());
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .findFirst().orElse("");
            assertFalse(body.contains("link_preview_options"),
                    "default (unset) must omit link_preview_options entirely");
            assertFalse(TelegramChannel.suppressLinkPreview(),
                    "suppressLinkPreview() must be false when the flag is unset");
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
    void sendMessageWithKeyboard_linkPreviewDisabled_whenConfiguredOff() {
        // JCLAW-380: telegram.linkPreview=off attaches link_preview_options with
        // is_disabled=true to the keyboard send body, mirroring the main path.
        String token = "jclaw380-kbd-lpoff-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.linkPreview", "off");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                            .text("OK").callbackData("ok").build()))
                    .build();
            assertNotNull(TelegramChannel.sendMessageWithKeyboard(token, "12345",
                    "<b>see https://x.test</b>", keyboard));
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .findFirst().orElse("");
            assertTrue(body.contains("link_preview_options"),
                    "off must attach link_preview_options to the keyboard send");
            assertTrue(body.contains("is_disabled"),
                    "the options must carry is_disabled=true");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessageWithKeyboard_linkPreviewAbsent_byDefault() {
        // JCLAW-380: with the flag unset, the keyboard send omits
        // link_preview_options — Telegram's default preview-on behavior holds.
        String token = "jclaw380-kbd-lpdefault-" + System.nanoTime();
        try {
            play.Play.configuration.remove("telegram.linkPreview");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                            .text("OK").callbackData("ok").build()))
                    .build();
            assertNotNull(TelegramChannel.sendMessageWithKeyboard(token, "12345",
                    "<b>see https://x.test</b>", keyboard));
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .findFirst().orElse("");
            assertFalse(body.contains("link_preview_options"),
                    "default (unset) must omit link_preview_options on the keyboard send");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void editMessageText_linkPreviewDisabled_whenConfiguredOff() {
        // JCLAW-380: telegram.linkPreview=off attaches link_preview_options with
        // is_disabled=true to the edit body, mirroring the main path.
        String token = "jclaw380-edit-lpoff-" + System.nanoTime();
        try {
            play.Play.configuration.setProperty("telegram.linkPreview", "off");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.editMessageText(token, "12345", 7,
                    "<b>see https://x.test</b>", null));
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("editMessageText"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .findFirst().orElse("");
            assertTrue(body.contains("link_preview_options"),
                    "off must attach link_preview_options to the edit");
            assertTrue(body.contains("is_disabled"),
                    "the options must carry is_disabled=true");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void editMessageText_linkPreviewAbsent_byDefault() {
        // JCLAW-380: with the flag unset, the edit omits link_preview_options —
        // Telegram's default preview-on behavior is preserved.
        String token = "jclaw380-edit-lpdefault-" + System.nanoTime();
        try {
            play.Play.configuration.remove("telegram.linkPreview");
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.editMessageText(token, "12345", 7,
                    "<b>see https://x.test</b>", null));
            String body = mock.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("editMessageText"))
                    .map(MockTelegramServer.RecordedRequest::body)
                    .findFirst().orElse("");
            assertFalse(body.contains("link_preview_options"),
                    "default (unset) must omit link_preview_options on the edit");
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

    // ─── JCLAW-364: native media send paths ─────────────────────────────

    /** First recorded request body whose method matches {@code methodName}, case-insensitively. */
    private String firstBodyFor(String methodName) {
        return mock.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase(methodName))
                .map(MockTelegramServer.RecordedRequest::body)
                .findFirst().orElse("");
    }

    private java.io.File tempFile(String suffix) throws Exception {
        var tmp = java.nio.file.Files.createTempFile("jclaw-media-", suffix);
        java.nio.file.Files.write(tmp, new byte[]{ 1, 2, 3, 4 });
        tmp.toFile().deleteOnExit();
        return tmp.toFile();
    }

    @Test
    void trySendVoice_hitsSendVoiceEndpoint() throws Exception {
        String token = "voice-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.forToken(token)
                    .trySendVoice("12345", tempFile(".ogg"), "memo.ogg");
            assertTrue(ok, "voice upload should succeed against the mock");
            assertEquals(1, mock.countRequests("sendVoice"),
                    "voice file must dispatch sendVoice");
            assertEquals(0, mock.countRequests("sendDocument"));
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySendAudio_hitsSendAudioEndpoint() throws Exception {
        String token = "audio-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.forToken(token)
                    .trySendAudio("12345", tempFile(".mp3"), "track.mp3");
            assertTrue(ok);
            assertEquals(1, mock.countRequests("sendAudio"),
                    "audio file must dispatch sendAudio");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySendVideo_hitsSendVideoEndpoint() throws Exception {
        String token = "video-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.forToken(token)
                    .trySendVideo("12345", tempFile(".mp4"), "demo.mp4");
            assertTrue(ok);
            assertEquals(1, mock.countRequests("sendVideo"),
                    "video file must dispatch sendVideo");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySendVoice_attachesCaptionInMultipartBody() throws Exception {
        String token = "voice-cap-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.forToken(token).trySendVoice(
                    "12345", tempFile(".ogg"), "memo.ogg", null, null, "Here is the recap");
            assertTrue(ok);
            String body = firstBodyFor("sendVoice");
            assertTrue(body.contains("caption"), "caption form field must be present: " + body);
            assertTrue(body.contains("Here is the recap"),
                    "caption text must be on the wire: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void trySendPhoto_attachesCaptionInMultipartBody() throws Exception {
        String token = "photo-cap-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.forToken(token).trySendPhoto(
                    "12345", tempFile(".png"), "shot.png", null, null, "look at this");
            assertTrue(ok);
            String body = firstBodyFor("sendPhoto");
            assertTrue(body.contains("caption") && body.contains("look at this"),
                    "photo caption must ride in the multipart body: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void mediaSend_returnsFalseOnServerError() throws Exception {
        String token = "media-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendVideo", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad video\"}");
            boolean ok = TelegramChannel.forToken(token)
                    .trySendVideo("12345", tempFile(".mp4"), "demo.mp4");
            assertFalse(ok, "a 400 from Telegram must surface as false, not throw");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── JCLAW-364: dormant reaction / pin primitives ───────────────────

    @Test
    void setMessageReaction_sendsEmojiToReactionEndpoint() {
        String token = "react-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.setMessageReaction(token, "12345", 77, "👍");
            assertTrue(ok, "reaction set should succeed against the mock");
            assertEquals(1, mock.countRequests("setMessageReaction"),
                    "must hit the setMessageReaction endpoint");
            String body = firstBodyFor("setMessageReaction");
            assertTrue(body.contains("reaction"), "reaction list must be present: " + body);
            assertTrue(body.contains("emoji"), "emoji reaction type must be present: " + body);
            assertTrue(body.contains("\"message_id\":77"),
                    "target message id must be on the wire: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void setMessageReaction_nullEmojiClearsReaction() {
        String token = "react-clear-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.setMessageReaction(token, "12345", 77, null);
            assertTrue(ok);
            assertEquals(1, mock.countRequests("setMessageReaction"));
            String body = firstBodyFor("setMessageReaction");
            // Empty reaction list clears — the field is present but carries no emoji.
            assertTrue(body.contains("\"reaction\":[]"),
                    "clearing must send an empty reaction list: " + body);
            assertFalse(body.contains("emoji"),
                    "no emoji reaction type when clearing: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void setMessageReaction_returnsFalseOnNullArgsAndServerError() throws Exception {
        String token = "react-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertFalse(TelegramChannel.setMessageReaction(null, "12345", 1, "x"));
            assertFalse(TelegramChannel.setMessageReaction(token, null, 1, "x"));
            assertFalse(TelegramChannel.setMessageReaction(token, "12345", null, "x"));
            assertEquals(0, mock.requests().size(), "null args must short-circuit");

            mock.respondWith("setMessageReaction", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad\"}");
            assertFalse(TelegramChannel.setMessageReaction(token, "12345", 1, "👍"),
                    "server error must surface as false, not throw");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void pinChatMessage_hitsPinEndpoint() {
        String token = "pin-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.pinChatMessage(token, "12345", 42);
            assertTrue(ok);
            assertEquals(1, mock.countRequests("pinChatMessage"),
                    "must hit the pinChatMessage endpoint");
            assertTrue(firstBodyFor("pinChatMessage").contains("\"message_id\":42"),
                    "pin must carry the target message id");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void unpinChatMessage_hitsUnpinEndpoint() {
        String token = "unpin-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            boolean ok = TelegramChannel.unpinChatMessage(token, "12345", 42);
            assertTrue(ok);
            assertEquals(1, mock.countRequests("unpinChatMessage"),
                    "must hit the unpinChatMessage endpoint");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void pinAndUnpin_returnFalseOnNullArgsAndServerError() {
        String token = "pin-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertFalse(TelegramChannel.pinChatMessage(null, "12345", 1));
            assertFalse(TelegramChannel.pinChatMessage(token, "12345", null));
            assertFalse(TelegramChannel.unpinChatMessage(token, null, 1));
            assertEquals(0, mock.requests().size(), "null args must short-circuit");

            mock.respondWith("pinChatMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad\"}");
            assertFalse(TelegramChannel.pinChatMessage(token, "12345", 1),
                    "pin server error must surface as false");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── JCLAW-365: outbound albums (sendMediaGroup) ────────────────────

    /** A foreground PHOTO FileSegment wrapping {@code file} with the given display name. */
    private static TelegramOutboundPlanner.FileSegment photoSeg(java.io.File file, String name) {
        return new TelegramOutboundPlanner.FileSegment(
                name, file, true, false, TelegramOutboundPlanner.MediaKind.PHOTO);
    }

    /** A foreground VIDEO FileSegment wrapping {@code file} with the given display name. */
    private static TelegramOutboundPlanner.FileSegment videoSeg(java.io.File file, String name) {
        return new TelegramOutboundPlanner.FileSegment(
                name, file, false, false, TelegramOutboundPlanner.MediaKind.VIDEO);
    }

    @Test
    void sendMediaGroup_threeItemsHitSendMediaGroupOnceWithThreeItems() throws Exception {
        String token = "album-3-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var items = java.util.List.of(
                    photoSeg(tempFile(".png"), "a.png"),
                    photoSeg(tempFile(".png"), "b.png"),
                    photoSeg(tempFile(".png"), "c.png"));
            boolean ok = TelegramChannel.forToken(token)
                    .sendMediaGroup("12345", items, null, null, null);
            assertTrue(ok, "album send should succeed against the mock");
            assertEquals(1, mock.countRequests("sendMediaGroup"),
                    "three photos must dispatch exactly one sendMediaGroup");
            assertEquals(0, mock.countRequests("sendPhoto"),
                    "grouped photos must not also fire individual sendPhoto");
            String body = firstBodyFor("sendMediaGroup");
            int photoEntries = body.split("\"type\":\"photo\"", -1).length - 1;
            assertEquals(3, photoEntries,
                    "media-group body must describe three photo items: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMediaGroup_captionRidesFirstItemOnly() throws Exception {
        String token = "album-cap-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var items = java.util.List.of(
                    photoSeg(tempFile(".png"), "a.png"),
                    photoSeg(tempFile(".png"), "b.png"));
            boolean ok = TelegramChannel.forToken(token)
                    .sendMediaGroup("12345", items, "look at these", null, null);
            assertTrue(ok);
            String body = firstBodyFor("sendMediaGroup");
            assertTrue(body.contains("look at these"),
                    "album caption must be on the wire: " + body);
            // The caption text must ride exactly one item (the first) — it must
            // not be duplicated onto the second item.
            int captionTextCount = body.split("look at these", -1).length - 1;
            assertEquals(1, captionTextCount,
                    "caption text must appear on exactly one (the first) album item: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMediaGroup_videoAndPhotoGroupTogether() throws Exception {
        String token = "album-av-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var items = java.util.List.of(
                    videoSeg(tempFile(".mp4"), "clip.mp4"),
                    photoSeg(tempFile(".png"), "shot.png"));
            boolean ok = TelegramChannel.forToken(token)
                    .sendMediaGroup("12345", items, null, null, null);
            assertTrue(ok);
            assertEquals(1, mock.countRequests("sendMediaGroup"));
            String body = firstBodyFor("sendMediaGroup");
            assertTrue(body.contains("\"type\":\"video\""), "video item must be present: " + body);
            assertTrue(body.contains("\"type\":\"photo\""), "photo item must be present: " + body);
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMediaGroup_rejectsOutOfRangeItemCounts() throws Exception {
        String token = "album-range-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var ch = TelegramChannel.forToken(token);
            assertFalse(ch.sendMediaGroup("12345", null, null, null, null),
                    "null items must return false");
            assertFalse(ch.sendMediaGroup("12345", java.util.List.of(), null, null, null),
                    "empty items must return false");
            assertFalse(ch.sendMediaGroup("12345",
                            java.util.List.of(photoSeg(tempFile(".png"), "solo.png")),
                            null, null, null),
                    "a single item must return false (use the single-send path instead)");
            assertEquals(0, mock.countRequests("sendMediaGroup"),
                    "out-of-range counts must short-circuit before any HTTP call");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMediaGroup_returnsFalseOnServerError() throws Exception {
        String token = "album-fail-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMediaGroup", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"bad album\"}");
            var items = java.util.List.of(
                    photoSeg(tempFile(".png"), "a.png"),
                    photoSeg(tempFile(".png"), "b.png"));
            boolean ok = TelegramChannel.forToken(token)
                    .sendMediaGroup("12345", items, null, null, null);
            assertFalse(ok, "a 400 from Telegram must surface as false, not throw");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_threeImagesDispatchOneAlbum_endToEnd() {
        // Drive the realistic planner→dispatch path: an agent response with
        // three adjacent images must produce exactly one sendMediaGroup.
        String token = "album-e2e-" + System.nanoTime();
        var agent = services.AgentService.create("album-e2e-agent", "openrouter", "gpt-4.1");
        services.AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        services.AgentService.writeWorkspaceFile(agent.name, "b.png", "b");
        services.AgentService.writeWorkspaceFile(agent.name, "c.png", "c");
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var md = "[a.png](<a.png>) [b.png](<b.png>) [c.png](<c.png>)";
            assertTrue(TelegramChannel.sendMessage(token, "12345", md, agent));
            assertEquals(1, mock.countRequests("sendMediaGroup"),
                    "three adjacent images must coalesce into one album send");
            assertEquals(0, mock.countRequests("sendPhoto"),
                    "no individual sendPhoto when the photos are grouped");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMessage_singleImageStillUsesSinglePhotoSend_endToEnd() {
        // A lone image must keep the existing single-send path: one sendPhoto,
        // no sendMediaGroup.
        String token = "single-img-e2e-" + System.nanoTime();
        var agent = services.AgentService.create("single-img-agent", "openrouter", "gpt-4.1");
        services.AgentService.writeWorkspaceFile(agent.name, "solo.png", "x");
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.sendMessage(token, "12345", "[solo.png](<solo.png>)", agent));
            assertEquals(0, mock.countRequests("sendMediaGroup"),
                    "a single image must not use an album send");
            assertEquals(1, mock.countRequests("sendPhoto"),
                    "the lone image rides the single sendPhoto path");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void sendMediaGroupFailure_fallsBackToIndividualSends_endToEnd() {
        // When the album send fails, every item must still reach the user via
        // individual sends (one sendPhoto per grouped photo).
        String token = "album-fallback-" + System.nanoTime();
        var agent = services.AgentService.create("album-fallback-agent", "openrouter", "gpt-4.1");
        services.AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        services.AgentService.writeWorkspaceFile(agent.name, "b.png", "b");
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMediaGroup", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"album rejected\"}");
            assertTrue(TelegramChannel.sendMessage(token, "12345",
                    "[a.png](<a.png>) [b.png](<b.png>)", agent));
            assertEquals(1, mock.countRequests("sendMediaGroup"),
                    "the album was attempted once");
            assertEquals(2, mock.countRequests("sendPhoto"),
                    "album failure must fall back to one individual sendPhoto per item");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ─── JCLAW-383: bot-sent-message-id cache ───────────────────────────

    /** A 200 sendMessage reply carrying a chosen message_id in the chosen chat. */
    private static String messageResponse(int messageId, long chatId) {
        return ("{\"ok\":true,\"result\":{\"message_id\":%d,\"chat\":{\"id\":%d,"
                + "\"type\":\"supergroup\"},\"date\":1,\"text\":\"hi\"}}")
                .formatted(messageId, chatId);
    }

    @Test
    void wasSentByBot_trueAfterSend_falseForUnseenIdChatOrNullArgs() {
        // A real text send records the returned message_id under its chat; the
        // query is true for that (chat,id) and false for anything else.
        String token = "jclaw383-sent-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            mock.respondWith("sendMessage", 200, messageResponse(555, 12345));
            assertTrue(TelegramChannel.sendMessage(token, "12345", "hi there"),
                    "the send itself must succeed");

            assertTrue(TelegramChannel.wasSentByBot(token, "12345", 555),
                    "the just-sent message id must be recognized as bot-sent");
            assertFalse(TelegramChannel.wasSentByBot(token, "12345", 999),
                    "an id the bot never sent must not be recognized");
            assertFalse(TelegramChannel.wasSentByBot(token, "67890", 555),
                    "a different chat must not match even on the same id");
            assertFalse(TelegramChannel.wasSentByBot(token, null, 555),
                    "null chat id must be false");
            assertFalse(TelegramChannel.wasSentByBot(token, "12345", null),
                    "null message id must be false");
            assertFalse(TelegramChannel.wasSentByBot(null, "12345", 555),
                    "null bot token must be false");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void wasSentByBot_falseBeforeAnySend_coldCache() {
        // A freshly-installed channel has sent nothing — every query is false
        // (the cold-after-restart case: conservative under-notify).
        String token = "jclaw383-cold-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertFalse(TelegramChannel.wasSentByBot(token, "12345", 1),
                    "a cold cache must never recognize an id");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void wasSentByBot_recordedByPhotoAndMediaGroupSends() throws Exception {
        // Coverage that the file/album send paths feed the cache too. The mock's
        // default sendPhoto id is 1; sendMediaGroup returns ids 1 and 2.
        String token = "jclaw383-media-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            assertTrue(TelegramChannel.forToken(token)
                    .trySendPhoto("12345", tempFile(".png"), "p.png"));
            assertTrue(TelegramChannel.wasSentByBot(token, "12345", 1),
                    "a photo send must record its message id");

            var album = java.util.List.of(
                    photoSeg(tempFile(".png"), "a.png"),
                    photoSeg(tempFile(".png"), "b.png"));
            assertTrue(TelegramChannel.forToken(token)
                    .sendMediaGroup("55555", album, null, null, null));
            assertTrue(TelegramChannel.wasSentByBot(token, "55555", 1)
                            && TelegramChannel.wasSentByBot(token, "55555", 2),
                    "every album item's message id must be recorded");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void wasSentByBot_evictsOldestIdPastPerChatCap() {
        // The per-chat ring is bounded: once more than SENT_IDS_PER_CHAT_CAP ids
        // are recorded, the OLDEST is evicted while the newest survive.
        String token = "jclaw383-evict-id-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var ch = TelegramChannel.forToken(token);
            int cap = TelegramChannel.SENT_IDS_PER_CHAT_CAP;
            // Record ids 1..cap, then one more (cap+1) to force a single eviction.
            for (int id = 1; id <= cap + 1; id++) {
                ch.recordSentForTest("chatA", id);
            }
            assertFalse(ch.wasSentByBot("chatA", 1),
                    "the oldest id must be evicted once past the per-chat cap");
            assertTrue(ch.wasSentByBot("chatA", 2),
                    "the second-oldest id must still be present");
            assertTrue(ch.wasSentByBot("chatA", cap + 1),
                    "the newest id must be present");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    @Test
    void wasSentByBot_evictsColdestChatPastChatCap() {
        // The chat map is bounded + access-ordered: tracking more than
        // SENT_CHATS_CAP chats evicts the least-recently-touched one.
        String token = "jclaw383-evict-chat-" + System.nanoTime();
        try {
            TelegramChannel.installForTest(token, mock.telegramUrl());
            var ch = TelegramChannel.forToken(token);
            int cap = TelegramChannel.SENT_CHATS_CAP;
            for (int c = 0; c < cap; c++) {
                ch.recordSentForTest("chat" + c, 7);
            }
            // Touch chat0 (read) so it's no longer the coldest, then add one more
            // chat to force an eviction — chat1 (now coldest) should go.
            assertTrue(ch.wasSentByBot("chat0", 7));
            ch.recordSentForTest("chatNew", 7);
            assertTrue(ch.wasSentByBot("chat0", 7),
                    "the recently-read chat0 must survive the eviction");
            assertFalse(ch.wasSentByBot("chat1", 7),
                    "the coldest chat (chat1) must be evicted past the chat cap");
            assertTrue(ch.wasSentByBot("chatNew", 7),
                    "the newest chat must be present");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }
}
