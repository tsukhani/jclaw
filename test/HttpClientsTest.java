import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpClients;

import java.net.http.HttpClient;

/**
 * Pins the HTTP-version configuration on the remaining JDK
 * {@link HttpClients} singletons. JCLAW-187 deleted
 * {@code HttpClients.LLM_HTTP_1_1} and the {@code forLlmProvider}
 * routing helper alongside the JDK LLM driver, so this test only
 * covers the two singletons that remain — both HTTP/2-capable, both
 * non-LLM-bound after phase 3. Phase 4 (JCLAW-188) deletes this whole
 * class once non-LLM callers move to OkHttp.
 */
public class HttpClientsTest extends UnitTest {

    @Test
    public void llmClientUsesHttp2() {
        assertEquals(HttpClient.Version.HTTP_2, HttpClients.LLM.version(),
                "the LLM JDK client must keep HTTP/2 — ALPN handles HTTPS providers, "
                        + "h2c-with-fallback handles HTTP/1.1-only servers");
    }

    @Test
    public void generalClientUsesHttp2() {
        assertEquals(HttpClient.Version.HTTP_2, HttpClients.GENERAL.version(),
                "channel webhooks (Slack/Telegram/WhatsApp) are always HTTPS — "
                        + "no h2c risk, HTTP/2 fine");
    }
}
