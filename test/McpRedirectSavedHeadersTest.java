import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpClient;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStreamableHttpTransport;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.McpServer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.EventLogger;
import services.McpServerService;
import utils.HttpFactories;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An HTTP MCP server's saved headers reach only the scheme, host and port they were entered for (JF-304): a
 * redirect to another address is followed without them. Real loopback sockets under {@code HttpFactories.runWith},
 * because OkHttp follows a redirect below the application interceptors and a canned transport never sees one. The
 * bound client is the production guarded one with every name answered by loopback, so another host is another name.
 */
class McpRedirectSavedHeadersTest extends UnitTest {

    private static final Map<String, String> SAVED = Map.of("X-Api-Key", "saved-key", "Authorization", "Bearer saved-token");
    private static final String VERSION = "2025-06-18";
    private static final String WHY = "JClaw sends this server's saved headers only to the address they were entered"
            + " for, so the redirect went without them. If the server has moved, change its URL to the new address and"
            + " enter the headers again.";

    @TempDir Path tmp;

    /** The address the headers were entered for, and one on another port. */
    private MockWebServer saved;
    private MockWebServer elsewhere;
    private OkHttpClient client;
    private final String name = "jf304-" + UUID.randomUUID();

    @BeforeEach
    void startServers() throws IOException {
        saved = new MockWebServer();
        saved.start();
        elsewhere = new MockWebServer();
        elsewhere.start();
        var loopback = saved.getSocketAddress().getAddress();
        client = HttpFactories.llmStreamingGuarded().newBuilder().dns(_ -> List.of(loopback)).build();
    }

    @AfterEach
    void stopServers() {
        saved.close();
        elsewhere.close();
    }

    // ==================== another address gets no saved header ====================

    @Test
    void aRedirectToAnotherPortIsFollowedWithoutTheSavedHeaders() throws Exception {
        for (var method : List.of("tools/list", "subscriptions/listen")) {
            saved.enqueue(redirect(elsewhere.url("/mcp")));
            elsewhere.enqueue(answer());
            try (var transport = transport(saved.url("/mcp"), SAVED)) {
                assertEquals("answered 7", exchange(transport, method), method);
            }
            assertCarriesSavedHeaders(saved.takeRequest(5, TimeUnit.SECONDS), method);
            assertFollowedWithoutSavedHeaders(elsewhere.takeRequest(5, TimeUnit.SECONDS), method);
        }
    }

    @Test
    void aRedirectToAnotherHostIsFollowedWithoutTheSavedHeaders() throws Exception {
        var moved = HttpUrl.get("http://moved.test:" + saved.getPort() + "/mcp");
        saved.enqueue(redirect(moved));
        saved.enqueue(answer());
        try (var transport = transport(HttpUrl.get("http://saved.test:" + saved.getPort() + "/mcp"), SAVED)) {
            assertEquals("answered 7", exchange(transport, "tools/list"));
        }
        var first = saved.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("saved.test", first.getUrl().host());
        assertCarriesSavedHeaders(first, "saved.test");
        var landed = saved.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("moved.test", landed.getUrl().host());
        assertFollowedWithoutSavedHeaders(landed, "moved.test");
    }

    /** The redirect a server makes most often: the same host, now over TLS. */
    @Test
    void aRedirectToAnotherSchemeIsFollowedWithoutTheSavedHeaders() throws Exception {
        var keys = localhostKeys();
        try (var secure = new MockWebServer()) {
            secure.useHttps(serving(keys).getSocketFactory());
            secure.start();
            var trust = trusting(keys);
            var context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[] {trust}, null);
            client = client.newBuilder().sslSocketFactory(context.getSocketFactory(), trust).build();

            var upgraded = HttpUrl.get("https://localhost:" + secure.getPort() + "/mcp");
            saved.enqueue(redirect(upgraded));
            secure.enqueue(answer());
            try (var transport = transport(HttpUrl.get("http://localhost:" + saved.getPort() + "/mcp"), SAVED)) {
                assertEquals("answered 7", exchange(transport, "tools/list"));
            }
            assertCarriesSavedHeaders(saved.takeRequest(5, TimeUnit.SECONDS), "http");
            var landed = secure.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("https", landed.getUrl().scheme());
            assertFollowedWithoutSavedHeaders(landed, "https");
        }
    }

    // ==================== what keeps working ====================

    @Test
    void aRedirectWithinTheOriginKeepsTheSavedHeaders() throws Exception {
        for (var location : List.of("/mcp/", saved.url("/v2/mcp").toString())) {
            saved.enqueue(new MockResponse.Builder().code(307).setHeader("Location", location).build());
            saved.enqueue(answer());
            try (var transport = transport(saved.url("/mcp"), SAVED)) {
                assertEquals("answered 7", exchange(transport, "tools/list"), location);
            }
            var first = saved.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("/mcp", first.getUrl().encodedPath(), location);
            assertCarriesSavedHeaders(first, location);
            var landed = saved.takeRequest(5, TimeUnit.SECONDS);
            assertEquals(saved.url(location).encodedPath(), landed.getUrl().encodedPath(), location);
            assertCarriesSavedHeaders(landed, location);
        }
    }

    @Test
    void aServerWithNoSavedHeadersIsStillFollowedToAnotherAddress() throws Exception {
        saved.enqueue(redirect(elsewhere.url("/mcp")));
        elsewhere.enqueue(answer());
        var events = eventsDuring(() -> {
            try (var transport = transport(saved.url("/mcp"), Map.of())) {
                assertEquals("answered 7", exchange(transport, "tools/list"));
            }
        });
        assertFollowedWithoutSavedHeaders(elsewhere.takeRequest(5, TimeUnit.SECONDS), "no saved headers");
        assertEquals(List.of(), events, "nothing was withheld, so nothing is logged");
    }

    // ==================== the operator is told ====================

    @Test
    void anAddressThatRefusesTheRequestWithoutTheHeadersFailsTheConnectionAndSaysWhy() {
        for (int status : new int[] {401, 403}) {
            saved.enqueue(redirect(elsewhere.url("/mcp")));
            elsewhere.enqueue(new MockResponse.Builder().code(status).body("no key").build());
            var events = eventsDuring(() -> {
                try (var transport = transport(saved.url("/mcp"), SAVED)) {
                    assertEquals("connection failed: MCP HTTP " + status + " after a redirect to " + origin(elsewhere)
                            + ". " + WHY, exchange(transport, "tools/list"));
                }
            });
            assertEquals(List.of(), events, "the connection error says it, so nothing more is logged here");
        }
    }

    /** What the MCP Servers page shows: the connect fails on its first request, under the message that names the redirect. */
    @Test
    void aConnectThatIsRefusedWithoutTheHeadersReportsTheRedirectAsItsError() {
        saved.setDispatcher(always(redirect(elsewhere.url("/mcp"))));
        elsewhere.setDispatcher(always(new MockResponse.Builder().code(401).build()));
        var expected = "MCP HTTP 401 after a redirect to " + origin(elsewhere) + ". " + WHY;
        try (var mcp = newClient(saved.url("/mcp"), SAVED)) {
            var failure = HttpFactories.callWith(client, () -> assertThrows(IOException.class, mcp::connect));
            assertEquals(expected, failure.getMessage());
            assertEquals(expected, mcp.lastError());
            assertEquals(McpClient.State.DISCONNECTED, mcp.state());
        }
    }

    /** Only a 401 or 403 says the headers are missed; the statuses either side of them answer one request, as ever. */
    @Test
    void anyOtherRefusalFromTheOtherAddressStaysThatRequestsOwnAndIsLogged() {
        for (int status : new int[] {400, 402, 404, 500}) {
            saved.enqueue(redirect(elsewhere.url("/mcp")));
            elsewhere.enqueue(new MockResponse.Builder().code(status).build());
            var events = eventsDuring(() -> {
                try (var transport = transport(saved.url("/mcp"), SAVED)) {
                    assertEquals("request failed: MCP HTTP " + status, exchange(transport, "tools/list"));
                }
            });
            assertEquals(List.of(redirectWarning()), events, "HTTP " + status);
        }
    }

    @Test
    void aRefusalFromTheSavedAddressItselfIsNotBlamedOnARedirect() throws Exception {
        for (var location : List.of("", "/mcp/")) {
            if (!location.isEmpty()) saved.enqueue(new MockResponse.Builder().code(307).setHeader("Location", location).build());
            saved.enqueue(new MockResponse.Builder().code(401).build());
            try (var transport = transport(saved.url("/mcp"), SAVED)) {
                assertEquals("request failed: MCP HTTP 401", exchange(transport, "tools/list"), location);
            }
        }
    }

    @Test
    void aRefusalFromAnotherAddressIsNotBlamedOnHeadersTheServerNeverHad() throws Exception {
        saved.enqueue(redirect(elsewhere.url("/mcp")));
        elsewhere.enqueue(new MockResponse.Builder().code(401).build());
        try (var transport = transport(saved.url("/mcp"), Map.of())) {
            assertEquals("request failed: MCP HTTP 401", exchange(transport, "tools/list"));
        }
    }

    @Test
    void aRedirectThatWorksWithoutTheHeadersIsLoggedOnceForTheConnection() {
        var events = eventsDuring(() -> {
            try (var transport = transport(saved.url("/mcp"), SAVED)) {
                for (int i = 0; i < 2; i++) {
                    saved.enqueue(redirect(elsewhere.url("/mcp")));
                    elsewhere.enqueue(answer());
                    assertEquals("answered 7", exchange(transport, "tools/list"));
                }
            }
        });
        assertEquals(List.of(redirectWarning()), events);
    }

    // ==================== which address is another one ====================

    /** JF-291 drops the saved headers when an edit moves the URL to another origin; a redirect draws the same line. */
    @Test
    void aRedirectLeavesTheOriginExactlyWhereAUrlEditDoes() {
        var from = "http://mcp.test/mcp";
        var same = List.of("http://mcp.test/v2/mcp?page=1", "http://mcp.test:80/mcp", "http://MCP.test/mcp");
        var other = List.of("https://mcp.test/mcp", "http://mcp.test:8080/mcp", "http://other.test/mcp",
                "http://sub.mcp.test/mcp");
        for (var to : same) {
            assertTrue(HttpFactories.sameOrigin(HttpUrl.get(from), HttpUrl.get(to)), to);
            assertTrue(anEditKeepsTheHeaders(from, to), to);
        }
        for (var to : other) {
            assertFalse(HttpFactories.sameOrigin(HttpUrl.get(from), HttpUrl.get(to)), to);
            assertFalse(anEditKeepsTheHeaders(from, to), to);
        }
        // The scheme alone, which no pair of listeners on one port can show.
        assertFalse(HttpFactories.sameOrigin(HttpUrl.get("http://mcp.test:8443/mcp"), HttpUrl.get("https://mcp.test:8443/mcp")));
        assertFalse(anEditKeepsTheHeaders("http://mcp.test:8443/mcp", "https://mcp.test:8443/mcp"));
    }

    private static boolean anEditKeepsTheHeaders(String from, String to) {
        var stored = new JsonObject();
        stored.addProperty("url", from);
        stored.add("headers", JsonParser.parseString("{\"X-Api-Key\":\"saved-key\"}"));
        var edit = new JsonObject();
        edit.addProperty("url", to);
        var merged = McpServerService.mergeConfigJson(McpServer.Transport.HTTP, stored.toString(), edit);
        return JsonParser.parseString(merged).getAsJsonObject().has("headers");
    }

    /** As a caller outside MCP would use it: only the named headers stay behind, whatever case names them. */
    @Test
    void keepingToOriginWithholdsOnlyTheNamedHeadersWhateverTheirCase() throws Exception {
        saved.enqueue(redirect(elsewhere.url("/v1")));
        elsewhere.enqueue(answer());
        var request = new Request.Builder().url(saved.url("/v1"))
                .header("X-Api-Key", "saved-key").header("X-Trace", "kept").build();
        try (var response = HttpFactories.keepingToOrigin(client, List.of("x-api-key")).newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals("saved-key", saved.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("X-Api-Key"));
        var landed = elsewhere.takeRequest(5, TimeUnit.SECONDS);
        assertNull(landed.getHeaders().get("X-Api-Key"));
        assertEquals("kept", landed.getHeaders().get("X-Trace"));
    }

    @Test
    void keepingToOriginWithNoNamesIsTheClientItself() {
        assertSame(client, HttpFactories.keepingToOrigin(client, List.of()));
    }

    // ==================== fixture ====================

    /** One request over {@code transport}, sent as a connection sends it, and how it ended. */
    private String exchange(McpStreamableHttpTransport transport, String method) throws Exception {
        var outcome = new CompletableFuture<String>();
        transport.start(m -> outcome.complete("answered " + ((JsonRpc.Response) m).id()),
                (_, e) -> outcome.complete("request failed: " + e.getMessage()),
                e -> outcome.complete("connection failed: " + e.getMessage()));
        HttpFactories.runWith(client, () -> {
            try {
                transport.send(request(method), Map.of("MCP-Protocol-Version", VERSION));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return outcome.get(10, TimeUnit.SECONDS);
    }

    private interface Body {
        void run() throws Exception;
    }

    /** What was logged about this test's server while {@code body} ran, on whichever thread. */
    private List<EventLogger.Captured> eventsDuring(Body body) {
        return EventLogger.captureMatchingForTest(e -> e.message().contains(name), _ -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private EventLogger.Captured redirectWarning() {
        return new EventLogger.Captured("WARN", "MCP_CONNECT", null, null,
                "MCP server '" + name + "' redirected a request to " + origin(elsewhere) + ". " + WHY, null);
    }

    private static JsonRpc.Request request(String method) {
        return new JsonRpc.Request(7L, method, null);
    }

    private void assertCarriesSavedHeaders(RecordedRequest request, String when) {
        assertNotNull(request, when);
        assertEquals("saved-key", request.getHeaders().get("X-Api-Key"), when);
        assertEquals("Bearer saved-token", request.getHeaders().get("Authorization"), when);
    }

    /** The redirect was followed with the body and the transport's own headers, and nothing the server had saved. */
    private void assertFollowedWithoutSavedHeaders(RecordedRequest request, String when) {
        assertNotNull(request, when);
        assertNull(request.getHeaders().get("X-Api-Key"), when);
        assertNull(request.getHeaders().get("Authorization"), when);
        assertEquals("POST", request.getMethod(), when);
        assertEquals("application/json, text/event-stream", request.getHeaders().get("Accept"), when);
        assertEquals(VERSION, request.getHeaders().get("MCP-Protocol-Version"), when);
        var body = JsonRpc.decode(request.getBody().utf8());
        assertEquals(7L, ((JsonRpc.Request) body).id(), when);
    }

    @MustBeClosed
    private McpStreamableHttpTransport transport(HttpUrl endpoint, Map<String, String> headers) {
        return new McpStreamableHttpTransport(name, URI.create(endpoint.toString()), headers);
    }

    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // the client owns the transport and closes it
    private McpClient newClient(HttpUrl endpoint, Map<String, String> headers) {
        return new McpClient(name, transport(endpoint, headers), "0", Duration.ofSeconds(5));
    }

    private static String origin(MockWebServer server) {
        return "http://" + server.getHostName() + ":" + server.getPort();
    }

    private static MockResponse redirect(HttpUrl location) {
        return new MockResponse.Builder().code(307).setHeader("Location", location.toString()).build();
    }

    private static MockResponse answer() {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}").build();
    }

    private static Dispatcher always(MockResponse response) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return response;
            }
        };
    }

    /** A certificate for localhost from the JDK's keytool, as {@code RestartNarratorTest} makes one. */
    private KeyStore localhostKeys() throws Exception {
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "the test JVM has no keytool");
        var store = tmp.resolve("localhost.p12");
        var p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "mcp", "-keyalg", "EC",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, out);
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        return keys;
    }

    private static SSLContext serving(KeyStore keys) throws Exception {
        var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "changeit".toCharArray());
        var context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    /** Trusts exactly the certificate in {@code keys}. */
    private static X509TrustManager trusting(KeyStore keys) throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("mcp", keys.getCertificate("mcp"));
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        return (X509TrustManager) factory.getTrustManagers()[0];
    }
}
