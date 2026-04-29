import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpClients;

import java.net.http.HttpClient;

/**
 * Pins the HTTP-version configuration on {@link HttpClients} and the
 * routing logic in {@link HttpClients#forLlmBaseUrl(String)}.
 *
 * <p>The HTTP/1.1 pin on {@link HttpClients#LLM_HTTP_1_1} exists because
 * LM Studio's bundled HTTP server parses Java's {@code Upgrade: h2c}
 * header on plain-HTTP requests but never completes the upgrade, hanging
 * the request for the full response timeout. The routing helper picks
 * that client for loopback URLs and the HTTP/2-capable default for
 * HTTPS, preserving HTTP/2 multiplexing on cloud LLM providers.
 *
 * <p>Java's embedded {@link com.sun.net.httpserver.HttpServer} is
 * HTTP/1.1 only and never reproduces the LM Studio quirk, so a
 * behavioral test isn't viable here. These structural assertions exist
 * to catch a future refactor that drops the version pin or breaks the
 * routing predicate.
 */
public class HttpClientsTest extends UnitTest {

    @Test
    public void llmDefaultClientUsesHttp2() {
        assertEquals(HttpClient.Version.HTTP_2, HttpClients.LLM.version(),
                "HTTPS LLM endpoints (ollama-cloud, openrouter, ...) keep HTTP/2 — "
                        + "ALPN negotiates cleanly over TLS, no h2c risk");
    }

    @Test
    public void llmHttp11ClientPinsHttp11() {
        assertEquals(HttpClient.Version.HTTP_1_1, HttpClients.LLM_HTTP_1_1.version(),
                "Plain-HTTP loopback LLM endpoints must pin HTTP/1.1 to dodge "
                        + "LM Studio's h2c-upgrade hang");
    }

    @Test
    public void generalClientUsesHttp2() {
        assertEquals(HttpClient.Version.HTTP_2, HttpClients.GENERAL.version(),
                "Channel webhooks (Slack/Telegram/WhatsApp) are always HTTPS — "
                        + "no h2c risk, HTTP/2 fine");
    }

    @Test
    public void forLlmBaseUrlPicksHttp11ForLoopbackUrls() {
        for (var url : new String[] {
                "http://localhost:11434/v1",
                "http://localhost:1234/v1",
                "http://127.0.0.1:1234/v1",
                "http://127.42.0.1:8080",
                "http://[::1]:1234/v1",
                "http://0.0.0.0:1234/v1",
                "HTTP://LOCALHOST:1234"
        }) {
            assertSame(HttpClients.LLM_HTTP_1_1, HttpClients.forLlmBaseUrl(url),
                    "expected loopback URL to route to LLM_HTTP_1_1: " + url);
        }
    }

    @Test
    public void forLlmBaseUrlPicksDefaultForRemoteUrls() {
        // HTTPS endpoints get HTTP/2 via ALPN. Non-loopback plain-HTTP also
        // falls through to the default — the h2c-hang risk is correlated
        // with quirky local servers (LM Studio specifically), so we don't
        // pessimize all plain-HTTP traffic. Operators with a LAN-hosted
        // local model that exhibits the same hang can override at the
        // call site if it ever comes up.
        for (var url : new String[] {
                "https://ollama.com/v1",
                "https://openrouter.ai/api/v1",
                "https://api.openai.com/v1",
                "http://192.168.1.5:1234/v1",
                "http://corp-internal.example.com/v1"
        }) {
            assertSame(HttpClients.LLM, HttpClients.forLlmBaseUrl(url),
                    "expected non-loopback URL to route to LLM (HTTP/2-default): " + url);
        }
    }

    @Test
    public void forLlmBaseUrlHandlesNullBaseUrl() {
        // Null baseUrl is a misconfiguration the registry would reject before
        // the call ever fires, but the helper shouldn't NPE on the path that
        // gets there anyway.
        assertSame(HttpClients.LLM, HttpClients.forLlmBaseUrl(null));
    }
}
