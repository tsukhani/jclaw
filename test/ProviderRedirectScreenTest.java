import com.google.gson.JsonObject;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStreamableHttpTransport;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.decision.JevApi;
import services.decision.JevException;
import utils.CircuitBreakers;
import utils.HttpFactories;
import utils.SsrfGuard;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The provider-guarded clients never connect to an address {@code SsrfGuard.isBlockedForProvider} refuses,
 * including one a redirect names as an IP literal, which OkHttp never hands to {@code PROVIDER_SAFE_DNS}
 * (JCLAW-1448). Real clients on real loopback sockets: {@code HttpFactories.runWith} answers for every tier
 * and would hide the defect. The JDK dials 0.0.0.0 as {@code InetAddress.getLocalHost()}, so an unguarded hop
 * lands on {@link #behind}, which listens there, as {@link #thePlainTierReachesTheBlockedHop} shows.
 */
class ProviderRedirectScreenTest extends UnitTest {

    private static final String PROMPT = "{\"prompt\":\"the operator's prompt\"}";
    private static final MediaType JSON = MediaType.get("application/json");

    private MockWebServer front;
    private MockWebServer behind;
    private InetAddress dialed;

    @BeforeEach
    void startServers() throws IOException {
        front = new MockWebServer();
        front.start();
        behind = new MockWebServer();
        // NioSocketImpl.connect swaps 0.0.0.0 for this: 127.0.0.1 on macOS, 127.0.1.1 on a Debian host.
        dialed = InetAddress.getLocalHost();
        behind.start(dialed, 0);
    }

    @AfterEach
    void stopServers() {
        front.close();
        behind.close();
    }

    @Test
    void generalGuardedRefusesARedirectToABlockedLiteral() throws Exception {
        assertRedirectRefused("generalGuarded", HttpFactories.generalGuarded());
    }

    @Test
    void llmSingleShotGuardedRefusesARedirectToABlockedLiteral() throws Exception {
        assertRedirectRefused("llmSingleShotGuarded", HttpFactories.llmSingleShotGuarded());
    }

    @Test
    void llmStreamingGuardedRefusesARedirectToABlockedLiteral() throws Exception {
        assertRedirectRefused("llmStreamingGuarded", HttpFactories.llmStreamingGuarded());
    }

    @Test
    void theMcpListenClientRefusesARedirectToABlockedLiteral() throws Exception {
        front.enqueue(redirect(307, blockedHop()));
        behind.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}").build());
        try (var transport = new McpStreamableHttpTransport("redirect", URI.create(front.url("/mcp").toString()),
                Map.of())) {
            var outcome = new CompletableFuture<String>();
            transport.start(m -> outcome.complete("answered: " + m),
                    (_, e) -> outcome.complete("failed: " + e.getMessage()),
                    e -> outcome.complete("broke: " + e));
            transport.send(new JsonRpc.Request(7L, "subscriptions/listen", null));

            var result = outcome.get(10, TimeUnit.SECONDS);
            assertEquals(1, front.getRequestCount(), result);
            assertEquals(0, behind.getRequestCount(), "the listen POST reached the blocked hop: " + result);
            assertTrue(result.startsWith("failed: ") && result.contains("SSRF guard"), result);
        }
    }

    @Test
    void aConfiguredBlockedLiteralIsRefusedAtConnect() {
        behind.enqueue(new MockResponse.Builder().code(200).body("metadata").build());
        for (var client : guardedClients()) {
            var request = new Request.Builder().url(blockedHop()).post(RequestBody.create(PROMPT, JSON)).build();
            var refused = assertThrows(IOException.class, () -> execute(client.client(), request), client.name());
            assertTrue(chain(refused).contains("SSRF guard"), client.name() + ": " + chain(refused));
        }
        assertEquals(0, behind.getRequestCount(), "a guarded client connected to 0.0.0.0");
    }

    @Test
    void aRedirectToANameResolvingToABlockedAddressIsRefused() {
        var hop = "http://metadata.test:" + behind.getPort() + "/latest/meta-data";
        for (var client : guardedClients()) {
            front.enqueue(redirect(307, hop));
            behind.enqueue(new MockResponse.Builder().code(200).body("metadata").build());
            var request = new Request.Builder().url(front.url("/v1/chat")).post(RequestBody.create(PROMPT, JSON))
                    .build();
            var refused = SsrfGuard.callWithHostsForTest(Map.of("metadata.test", "0.0.0.0"),
                    () -> assertThrows(IOException.class, () -> execute(client.client(), request), client.name()));
            assertTrue(chain(refused).contains("SSRF guard"), client.name() + ": " + chain(refused));
        }
        assertEquals(0, behind.getRequestCount(), "a guarded client followed a name to 0.0.0.0");
    }

    /** A provider that moves, say from one port to another, keeps working; a 307 re-sends the body to a screened host. */
    @Test
    void aRedirectToAnAllowedAddressIsFollowed() throws Exception {
        var allowed = behind.url("/v1/moved").newBuilder().host(dialed.getHostAddress()).build().toString();
        for (var client : guardedClients()) {
            for (int code : new int[] {302, 307}) {
                front.enqueue(redirect(code, allowed));
                behind.enqueue(new MockResponse.Builder().code(200).body("answer").build());

                var request = new Request.Builder().url(front.url("/v1/chat")).post(RequestBody.create(PROMPT, JSON))
                        .build();
                assertEquals("answer", execute(client.client(), request), client.name() + " on " + code);

                var landed = behind.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(landed, client.name() + " on " + code);
                assertEquals(code == 307 ? "POST" : "GET", landed.getMethod(), client.name() + " on " + code);
                assertEquals(code == 307 ? PROMPT : "", landed.getBody() == null ? "" : landed.getBody().utf8(),
                        client.name() + " on " + code);
            }
        }
    }

    /** Validates the fixture: without the guard the redirect lands behind, so a guarded zero is the guard's doing. */
    @Test
    void thePlainTierReachesTheBlockedHop() throws Exception {
        front.enqueue(redirect(307, blockedHop()));
        behind.enqueue(new MockResponse.Builder().code(200).body("metadata").build());

        var request = new Request.Builder().url(front.url("/v1/chat")).post(RequestBody.create(PROMPT, JSON)).build();
        assertEquals("metadata", execute(HttpFactories.general(), request));
        assertEquals(PROMPT, behind.takeRequest(5, TimeUnit.SECONDS).getBody().utf8());
    }

    /** {@code JevApi.send} calls both tiers, so only its behavior shows an inverted {@code Target.guarded} pick. */
    @Test
    void aDecisionTargetDialsTheTierItNames() {
        assertEquals("a", decide(false).get("choice").getAsString());
        assertEquals(1, behind.getRequestCount(),
                "an unguarded target must reach the blocked hop, or the next check proves nothing");

        var refused = assertThrows(JevException.Outage.class, () -> decide(true));
        assertEquals(2, front.getRequestCount(), refused.getMessage());
        assertEquals(1, behind.getRequestCount(), "a guarded decision target reached the blocked hop");
    }

    /** One decision POST to a server that answers 307 to the blocked hop, under a breaker of its own. */
    private JsonObject decide(boolean guarded) {
        front.enqueue(redirect(307, blockedHop()));
        behind.enqueue(new MockResponse.Builder().code(200).body("{\"choice\":\"a\"}").build());
        var breaker = "jclaw1448-decision-" + UUID.randomUUID();
        var target = new JevApi.Target("Redirect", "REDIRECT", "Redirect", front.url("/v1/systemone").toString(),
                null, breaker, guarded, _ -> false);
        var body = new JsonObject();
        body.addProperty("prompt", "the operator's prompt");
        try {
            return JevApi.post(target, body, 1, 10_000);
        } finally {
            CircuitBreakers.remove(breaker);
        }
    }

    /** 302 turns the POST into a GET and 307 keeps it and its body: both must stop before the blocked hop. */
    private void assertRedirectRefused(String name, OkHttpClient client) throws Exception {
        for (int code : new int[] {302, 307}) {
            front.enqueue(redirect(code, blockedHop()));
            behind.enqueue(new MockResponse.Builder().code(200).body("metadata").build());

            var request = new Request.Builder().url(front.url("/v1/chat")).post(RequestBody.create(PROMPT, JSON))
                    .header("Authorization", "Bearer sk-test").build();
            var refused = assertThrows(IOException.class, () -> execute(client, request), name + " on " + code);
            assertTrue(chain(refused).contains("SSRF guard"), name + " on " + code + ": " + chain(refused));
            assertEquals(0, behind.getRequestCount(), name + " on " + code + " reached the blocked hop");
        }
        assertEquals(2, front.getRequestCount(), name);
    }

    private String blockedHop() {
        return "http://0.0.0.0:" + behind.getPort() + "/latest/meta-data";
    }

    private static MockResponse redirect(int code, String location) {
        return new MockResponse.Builder().code(code).setHeader("Location", location).build();
    }

    private static String execute(OkHttpClient client, Request request) throws IOException {
        var call = client.newCall(request);
        call.timeout().timeout(10, TimeUnit.SECONDS);
        try (var response = call.execute()) {
            return response.body().string();
        }
    }

    /** Every message down the cause chain, so the assertion does not depend on which layer OkHttp reports. */
    private static String chain(Throwable t) {
        var messages = new StringBuilder();
        for (var cause = t; cause != null; cause = cause.getCause()) messages.append(cause).append(" <- ");
        return messages.toString();
    }

    private record Named(String name, OkHttpClient client) {}

    private static List<Named> guardedClients() {
        return List.of(new Named("generalGuarded", HttpFactories.generalGuarded()),
                new Named("llmSingleShotGuarded", HttpFactories.llmSingleShotGuarded()),
                new Named("llmStreamingGuarded", HttpFactories.llmStreamingGuarded()));
    }
}
