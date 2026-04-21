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
public class TelegramChannelTest extends UnitTest {

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
    public void clearMessageDraft_postsEmptyTextToSendMessageDraft() {
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
    public void clearMessageDraft_returnsFalseOnServerError() {
        // Override to return 400 as the Telegram API would for a rejected clear.
        mock.respondWith("sendMessageDraft", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"test\"}");
        var ok = TelegramChannel.clearMessageDraft("bot-token", "12345", 42);
        assertFalse(ok, "helper must return false on non-200");
    }

    @Test
    public void clearMessageDraft_returnsFalseOnNullToken() {
        // Must not make an HTTP call when the token is missing — gates the
        // clearDraftBestEffort no-op for test / admin sinks.
        var ok = TelegramChannel.clearMessageDraft(null, "12345", 42);
        assertFalse(ok);
        assertEquals(0, mock.requests().size());
    }

    @Test
    public void clearMessageDraft_returnsFalseOnBlankToken() {
        var ok = TelegramChannel.clearMessageDraft("", "12345", 42);
        assertFalse(ok);
        assertEquals(0, mock.requests().size());
    }

    @Test
    public void clearMessageDraft_returnsFalseOnNullChatId() {
        var ok = TelegramChannel.clearMessageDraft("bot-token", null, 42);
        assertFalse(ok);
        assertEquals(0, mock.requests().size());
    }
}
