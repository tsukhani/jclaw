import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpClients;

import java.net.http.HttpClient;

/**
 * Pins the HTTP-version configuration on {@link HttpClients} and the
 * routing logic in {@link HttpClients#forLlmProvider(String)}.
 *
 * <p>The HTTP/1.1 pin on {@link HttpClients#LLM_HTTP_1_1} exists because
 * LM Studio's local API (Express on Node) hangs on Java HttpClient's
 * default {@code Upgrade: h2c} attempt: Node's {@code http.Server}
 * fires an {@code 'upgrade'} event for that header, Express doesn't
 * register an upgrade handler, and the request never reaches the
 * regular pipeline. The routing helper picks that client only for
 * provider names containing "lm-studio"; everything else (cloud LLMs,
 * Ollama Local, future LAN-hosted providers) gets HTTP/2-default and
 * relies on ALPN-or-h2c-with-fallback to negotiate the right protocol.
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
                "the default LLM client must keep HTTP/2 — ALPN handles HTTPS providers, "
                        + "h2c-with-fallback handles HTTP/1.1-only servers like Ollama");
    }

    @Test
    public void llmHttp11ClientPinsHttp11() {
        assertEquals(HttpClient.Version.HTTP_1_1, HttpClients.LLM_HTTP_1_1.version(),
                "the LM Studio client must pin HTTP/1.1 to dodge the Express/Node "
                        + "upgrade-event hang on h2c");
    }

    @Test
    public void generalClientUsesHttp2() {
        assertEquals(HttpClient.Version.HTTP_2, HttpClients.GENERAL.version(),
                "channel webhooks (Slack/Telegram/WhatsApp) are always HTTPS — "
                        + "no h2c risk, HTTP/2 fine");
    }

    @Test
    public void forLlmProviderPicksHttp11ForLmStudio() {
        for (var name : new String[] {
                "lm-studio",
                "LM-Studio",
                "lm-studio-mirror",       // any name that *contains* the substring routes the same
                "my-lm-studio-instance",
                "LM-STUDIO"
        }) {
            assertSame(HttpClients.LLM_HTTP_1_1, HttpClients.forLlmProvider(name),
                    "expected lm-studio-named provider to route to LLM_HTTP_1_1: " + name);
        }
    }

    @Test
    public void forLlmProviderPicksDefaultForOtherProviders() {
        // Ollama Local explicitly included: under the new rule it uses HTTP/2
        // default, since Ollama's server handles h2c gracefully and falls back
        // transparently. The rule is now provider-based, not URL-loopback-based.
        for (var name : new String[] {
                "ollama-cloud",
                "openrouter",
                "ollama-local",
                "openai",
                "anthropic",
                "groq",
                "lambda-labs"
        }) {
            assertSame(HttpClients.LLM, HttpClients.forLlmProvider(name),
                    "expected non-lm-studio provider to route to LLM (HTTP/2-default): " + name);
        }
    }

    @Test
    public void forLlmProviderHandlesNullName() {
        // Null provider name is a misconfiguration the registry would reject before
        // the call ever fires, but the helper shouldn't NPE on the path that gets
        // there anyway.
        assertSame(HttpClients.LLM, HttpClients.forLlmProvider(null));
    }
}
