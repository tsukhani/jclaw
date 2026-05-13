import channels.TelegramChannel;
import org.junit.jupiter.api.*;
import play.test.UnitTest;

/**
 * Unit tests for {@link TelegramChannel} raw-HTTP helpers that bypass the
 * telegrambots-meta SDK (JCLAW-121 clearMessageDraft).
 *
 * <p>The SDK validates {@code SendMessageDraft} with non-empty text before
 * the HTTP call, so the clear-draft mechanism used by OpenClaw's grammY
 * streamer (post empty {@code text}) required a raw-HTTP path. These tests
 * drive that path against {@link MockTelegramServer}.
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
        if (mock != null) mock.close();
    }

    @Test
    void clearMessageDraft_postsEmptyTextToSendMessageDraft() {
        var ok = TelegramChannel.clearMessageDraft("bot-token", "12345", 42);
        assertTrue(ok, "mock returns 200 by default; helper should return true");

        var reqs = mock.requests();
        assertEquals(1, reqs.size());
        var req = reqs.get(0);
        // MockTelegramServer lowercases the method name; accept either case.
        assertTrue(req.method().equalsIgnoreCase("sendMessageDraft"),
                "expected sendMessageDraft method, got: " + req.method());
        assertTrue(req.path().contains("/botbot-token/"),
                "bot token must be in the URL path, got: " + req.path());

        // Empty text is the clear signal — confirm the wire payload carries it.
        var body = req.body();
        assertTrue(body.contains("\"text\":\"\""),
                "request body must include empty text, got: " + body);
        assertTrue(body.contains("\"chat_id\":12345"),
                "request body must include chat_id, got: " + body);
        assertTrue(body.contains("\"draft_id\":42"),
                "request body must include draft_id, got: " + body);
    }

    @Test
    void clearMessageDraft_returnsFalseOnServerError() {
        // Override to return 400 as the Telegram API would for a rejected clear.
        mock.respondWith("sendMessageDraft", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"test\"}");
        var ok = TelegramChannel.clearMessageDraft("bot-token", "12345", 42);
        assertFalse(ok, "helper must return false on non-200");
    }

    @Test
    void clearMessageDraft_returnsFalseOnNullToken() {
        // Must not make an HTTP call when the token is missing — gates the
        // clearDraftBestEffort no-op for test / admin sinks.
        var ok = TelegramChannel.clearMessageDraft(null, "12345", 42);
        assertFalse(ok);
        assertEquals(0, mock.requests().size());
    }

    @Test
    void clearMessageDraft_returnsFalseOnBlankToken() {
        var ok = TelegramChannel.clearMessageDraft("", "12345", 42);
        assertFalse(ok);
        assertEquals(0, mock.requests().size());
    }

    @Test
    void clearMessageDraft_returnsFalseOnNullChatId() {
        var ok = TelegramChannel.clearMessageDraft("bot-token", null, 42);
        assertFalse(ok);
        assertEquals(0, mock.requests().size());
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
}
