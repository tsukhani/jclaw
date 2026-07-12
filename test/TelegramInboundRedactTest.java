import channels.TelegramInboundParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;

/**
 * JCLAW-730 regression: the bot token must never reach the download-failure
 * warn log. A {@code TelegramFileDownloader.DownloadFailed} reason is built from
 * raw IO exception messages that embed the request URL, and Telegram URLs carry
 * the bot token ({@code .../bot<token>/...}). {@code TelegramInboundParser.redact}
 * strips the token before it is logged; it's private, so we reach it by
 * reflection (matching the file's {@code matchesWakeWord} test convention).
 */
class TelegramInboundRedactTest extends UnitTest {

    @Test
    void redactStripsTheBotTokenFromAReason() throws Exception {
        var token = "123456:ABCdefGHIjklMNO";
        var reason = "getFile: java.net.ConnectException failed connecting to "
                + "https://api.telegram.org/bot" + token + "/getFile?file_id=x";
        var out = redact(reason, token);
        assertFalse(out.contains(token), "the bot token must not survive redaction: " + out);
        assertTrue(out.contains("<token>"), "the token should be replaced with a marker: " + out);
    }

    @Test
    void redactIsANoOpWithoutAToken() throws Exception {
        assertEquals("download HTTP 404", redact("download HTTP 404", null));
        assertEquals("download HTTP 404", redact("download HTTP 404", ""));
    }

    @Test
    void redactToleratesNullReason() throws Exception {
        assertEquals("", redact(null, "123:abc"));
    }

    private static String redact(String s, String token) throws Exception {
        Method m = TelegramInboundParser.class.getDeclaredMethod("redact", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s, token);
    }
}
