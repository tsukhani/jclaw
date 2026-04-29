import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpClients;

import java.net.http.HttpClient;

/**
 * Pins the HTTP version on the shared {@link HttpClients} singletons. Java's
 * default is {@link HttpClient.Version#HTTP_2}, which attempts an
 * {@code Upgrade: h2c} negotiation on plain HTTP requests. LM Studio's
 * bundled HTTP server parses that upgrade header and then hangs the
 * response, so any path that talks to it via these singletons (chat
 * completions in {@link llm.LlmProvider}, model discovery in
 * {@link services.ModelDiscoveryService}) needs HTTP/1.1 to succeed.
 *
 * <p>This test is structural — it doesn't reproduce the LM Studio server
 * quirk (Java's embedded {@link com.sun.net.httpserver.HttpServer} is
 * HTTP/1.1 only and would never expose the bug). It exists to prevent a
 * future refactor from accidentally dropping the {@code .version(HTTP_1_1)}
 * line and silently re-introducing the hang.
 */
public class HttpClientsTest extends UnitTest {

    @Test
    public void llmHttpClientForcesHttp11() {
        assertEquals(HttpClient.Version.HTTP_1_1, HttpClients.LLM.version(),
                "LLM HttpClient must pin HTTP/1.1 to dodge LM Studio's h2c-upgrade hang");
    }

    @Test
    public void generalHttpClientForcesHttp11() {
        assertEquals(HttpClient.Version.HTTP_1_1, HttpClients.GENERAL.version(),
                "GENERAL HttpClient must pin HTTP/1.1 — it's the transport for "
                        + "ModelDiscoveryService, which hits LM Studio's /v1/models");
    }
}
