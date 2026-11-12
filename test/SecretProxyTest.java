import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SkillSecrets;
import services.secrets.SecretGrant;
import services.secrets.SecretGrants;
import services.secrets.SecretProxy;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JF-324: what the secrets proxy does with a request from a command holding a grant. A placeholder becomes its value
 * only on the way to one of its secret's hosts, encoded for where it sits; anything else carrying one is refused
 * before it reaches a service; and a value a service sends back comes to the command as its placeholder.
 */
class SecretProxyTest extends UnitTest {

    /** Characters each encoding changes, so a raw copy in the wrong place cannot pass for the right one. */
    private static final String VALUE = "p@ss w&rd\"1/é+";
    private static final Pattern PLACEHOLDER = Pattern.compile("jclaw-secret-[a-z2-7]{32}");

    private SecretProxyFixture stub;
    private SecretGrant grant;
    private OkHttpClient client;
    private String placeholder;

    @BeforeEach
    @SuppressWarnings("MustBeClosed") // the grant lives for one test and stop() closes it
    void start() throws Exception {
        stub = new SecretProxyFixture();
        grant = SecretGrants.openForTest(List.of(
                new SkillSecrets.Usable("demo", "API_KEY", VALUE, List.of(SecretProxyFixture.BOUND + ":" + stub.port())),
                new SkillSecrets.Usable("demo", "NEWLINE_KEY", "two\nlines",
                        List.of(SecretProxyFixture.BOUND + ":" + stub.port()))), stub.upstream());
        placeholder = grant.environment().get("API_KEY");
        client = SecretProxyFixture.through(grant);
    }

    @AfterEach
    void stop() {
        grant.close();
        stub.close();
    }

    private String url(String host, String path) {
        return "https://" + host + ":" + stub.port() + path;
    }

    private static String text(Response response) throws IOException {
        return response.body().string();
    }

    @Test
    void aPlaceholderIsTheValueAtItsHostAndAnEchoedValueComesBackAsThePlaceholder() throws Exception {
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/echo"))
                .header("X-Api-Key", placeholder).build()).execute()) {
            var body = text(response);
            assertEquals(200, response.code(), body);
            assertEquals("method=GET\ntarget=/echo\nx-api-key=" + placeholder + "\nauthorization=null\nbody=", body);
        }
        assertEquals(VALUE, stub.received.getFirst().header("X-Api-Key"));
    }

    @Test
    void eachPlaceAPlaceholderSitsGetsTheValueEncodedForThatPlace() throws Exception {
        var basic = "Basic " + Base64.getEncoder().encodeToString(("admin:" + placeholder).getBytes(StandardCharsets.UTF_8));
        try (var r = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/echo?apikey=" + placeholder))
                .header("Authorization", basic).build()).execute()) {
            assertEquals(200, r.code());
        }
        post("application/json", "{\"password\":\"" + placeholder + "\"}");
        post("application/x-www-form-urlencoded", "user=admin&passwd=" + placeholder);
        post("text/plain", "key " + placeholder);

        var seen = stub.received;
        assertEquals("/echo?apikey=p%40ss%20w%26rd%221%2F%C3%A9%2B", seen.get(0).target());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(("admin:" + VALUE).getBytes(StandardCharsets.UTF_8)),
                seen.get(0).header("Authorization"));
        assertEquals("{\"password\":\"p@ss w&rd\\\"1/é+\"}", seen.get(1).bodyText());
        assertEquals("user=admin&passwd=" + URLEncoder.encode(VALUE, StandardCharsets.UTF_8), seen.get(2).bodyText());
        assertEquals("key " + VALUE, seen.get(3).bodyText());
    }

    private void post(String type, String body) throws IOException {
        try (var r = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/echo"))
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), MediaType.get(type))).build()).execute()) {
            assertEquals(200, r.code(), text(r));
        }
    }

    @Test
    void aPlaceholderSentToAnotherHostIsRefusedAndReachesNothing() throws Exception {
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.OTHER, "/echo"))
                .header("X-Api-Key", placeholder).build()).execute()) {
            assertEquals(403, response.code());
            assertEquals("JClaw refused this request: secret API_KEY may be sent only to " + SecretProxyFixture.BOUND + ":"
                    + stub.port() + ", not to " + SecretProxyFixture.OTHER + ":" + stub.port() + ".\n", text(response));
        }
        assertEquals(List.of(), stub.received);
    }

    @Test
    void aRequestWithoutAPlaceholderGoesToAnyHostUnchanged() throws Exception {
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.OTHER, "/echo"))
                .header("X-Api-Key", "plain").build()).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals("plain", stub.received.getFirst().header("X-Api-Key"));
        assertEquals(SecretProxyFixture.OTHER, stub.received.getFirst().host());
    }

    @Test
    void anUnknownPlaceholderIsRefusedAndAClosedGrantCannotLogIn() throws Exception {
        var forged = "jclaw-secret-" + "a".repeat(32);
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/echo"))
                .header("X-Api-Key", forged).build()).execute()) {
            assertEquals(403, response.code());
            assertEquals("JClaw refused this request: it carries a secret placeholder this command was not given, or one"
                    + " whose command has ended.\n", text(response));
        }
        grant.close();
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/echo"))
                .header("X-Api-Key", placeholder).build()).execute()) {
            fail("a closed grant's login opened a tunnel: " + response.code());
        } catch (IOException e) {
            assertEquals("Failed to authenticate with proxy", e.getMessage());
        }
        assertEquals(List.of(), stub.received);
    }

    @Test
    void aConnectWithoutTheLoginOrWithAWrongTokenGets407() throws Exception {
        assertEquals("HTTP/1.1 407 Proxy Authentication Required", connectStatus(null));
        var proxyUrl = java.net.URI.create(grant.environment().get("HTTPS_PROXY"));
        var id = proxyUrl.getUserInfo().split(":", 2)[0];
        assertEquals("HTTP/1.1 407 Proxy Authentication Required", connectStatus(id + ":" + "b".repeat(32)));
        assertEquals("HTTP/1.1 200 Connection established", connectStatus(proxyUrl.getUserInfo()));
    }

    private String connectStatus(String login) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), SecretProxy.portForTest())) {
            var auth = login == null ? "" : "Proxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString(login.getBytes(StandardCharsets.UTF_8)) + "\r\n";
            socket.getOutputStream().write(("CONNECT " + SecretProxyFixture.BOUND + ":" + stub.port() + " HTTP/1.1\r\nHost: "
                    + SecretProxyFixture.BOUND + ":" + stub.port() + "\r\n" + auth + "\r\n").getBytes(StandardCharsets.US_ASCII));
            return readLine(socket.getInputStream());
        }
    }

    @Test
    void aRequestWhoseHostNamesAnotherSiteThanItsTunnelIsRefused() throws Exception {
        var proxyUrl = java.net.URI.create(grant.environment().get("HTTPS_PROXY"));
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), SecretProxy.portForTest())) {
            var authority = SecretProxyFixture.OTHER + ":" + stub.port();
            socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nProxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString(proxyUrl.getUserInfo().getBytes(StandardCharsets.UTF_8))
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 200 Connection established", readLine(socket.getInputStream()));
            readLine(socket.getInputStream());
            var tls = (SSLSocket) trustingJclaw().createSocket(socket, SecretProxyFixture.OTHER, stub.port(), true);
            tls.startHandshake();
            tls.getOutputStream().write(("GET /echo HTTP/1.1\r\nHost: " + SecretProxyFixture.BOUND + ":" + stub.port()
                    + "\r\nX-Api-Key: " + placeholder + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            var reply = new String(tls.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(reply.startsWith("HTTP/1.1 403 Forbidden\r\n"), reply);
            assertTrue(reply.endsWith("\r\n\r\nJClaw refused this request: its Host header names " + SecretProxyFixture.BOUND
                    + ":" + stub.port() + " but its tunnel goes to " + SecretProxyFixture.OTHER + ".\n"), reply);
        }
        assertEquals(List.of(), stub.received);
    }

    /** A tunnel to {@code tunnelHost} opened with the grant's login, {@code head} sent raw inside it, and the reply. */
    private String throughTunnel(String tunnelHost, String head) throws Exception {
        var proxyUrl = java.net.URI.create(grant.environment().get("HTTPS_PROXY"));
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), SecretProxy.portForTest())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("CONNECT " + tunnelHost + ":" + stub.port() + " HTTP/1.1\r\n"
                    + "Proxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString(proxyUrl.getUserInfo().getBytes(StandardCharsets.UTF_8))
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 200 Connection established", readLine(socket.getInputStream()));
            readLine(socket.getInputStream());
            var tls = (SSLSocket) trustingJclaw().createSocket(socket, tunnelHost, stub.port(), true);
            tls.startHandshake();
            tls.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            return new String(tls.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aRequestTargetThatIsNotAPathIsRefusedBeforeAnySecretIsWritten() throws Exception {
        var bound = SecretProxyFixture.BOUND + ":" + stub.port();
        var other = SecretProxyFixture.OTHER + ":" + stub.port();
        var wrong = new java.util.ArrayList<String>();
        for (var target : List.of("@" + other + "/echo?k=" + placeholder, ".evil.test/echo?k=" + placeholder,
                "https://" + other + "/echo?k=" + placeholder, "*")) {
            var reply = throughTunnel(SecretProxyFixture.BOUND, "GET " + target + " HTTP/1.1\r\nHost: " + bound + "\r\n\r\n");
            if (!reply.startsWith("HTTP/1.1 403 Forbidden\r\n")
                    || !reply.endsWith("\r\n\r\nJClaw refused this request: its request target is not a path.\n")) {
                wrong.add(target + " -> " + reply.lines().findFirst().orElse("(no reply)"));
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals(List.of(), stub.received);
    }

    private static SSLSocketFactory trustingJclaw() throws Exception {
        var store = java.security.KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("jclaw", SecretProxy.caForTest());
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        var context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    private static String readLine(java.io.InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') out.write(c);
        var line = out.toString(StandardCharsets.ISO_8859_1);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    @Test
    void aRedirectComesBackToTheCommandAndIsNotFollowed() throws Exception {
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/redirect"))
                .header("X-Api-Key", placeholder).build()).execute()) {
            assertEquals(302, response.code());
            assertEquals("https://" + SecretProxyFixture.OTHER + ":" + stub.port() + "/echo", response.header("Location"));
        }
        assertEquals(1, stub.received.size());
    }

    @Test
    void aValueWithALineBreakCannotGoInAHeader() throws Exception {
        try (var response = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/echo"))
                .header("X-Api-Key", grant.environment().get("NEWLINE_KEY")).build()).execute()) {
            assertEquals(403, response.code());
            assertEquals("JClaw refused this request: secret NEWLINE_KEY holds a line break, so it cannot go in a header.\n",
                    text(response));
        }
        assertEquals(List.of(), stub.received);
    }

    @Test
    void aLongUploadIsRewrittenAsItStreamsAndALongDownloadIsMaskedThroughout() throws Exception {
        var size = SecretProxy.class.getDeclaredField("BUFFERED_BODY_LIMIT");
        size.setAccessible(true);
        int limit = size.getInt(null);
        var upload = new StringBuilder("a".repeat(limit + 5_000)).append(placeholder).append("b".repeat(70_000))
                .append(placeholder).toString();
        try (var r = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/upload"))
                .post(RequestBody.create(upload.getBytes(StandardCharsets.UTF_8), MediaType.get("text/plain"))).build())
                .execute()) {
            assertEquals(404, r.code(), "the stub has no /upload route; what it received is the check");
        }
        var sent = stub.received.getFirst().bodyText();
        assertEquals("a".repeat(limit + 5_000) + VALUE + "b".repeat(70_000) + VALUE, sent);

        var download = new StringBuilder();
        for (int i = 0; i < 300; i++) download.append("x".repeat(16_381 + i % 7)).append(VALUE);
        stub.route("/download", r -> download.toString().getBytes(StandardCharsets.UTF_8));
        try (var r = client.newCall(new Request.Builder().url(url(SecretProxyFixture.BOUND, "/download")).build()).execute()) {
            assertEquals(download.toString().replace(VALUE, placeholder), text(r));
        }
    }

    @Test
    void theCommandsEnvironmentHoldsPlaceholdersAndTheProxyNeverAValue() {
        var env = grant.environment();
        assertTrue(PLACEHOLDER.matcher(env.get("API_KEY")).matches(), env.get("API_KEY"));
        assertEquals("http://", env.get("HTTPS_PROXY").substring(0, 7));
        assertTrue(env.get("HTTPS_PROXY").endsWith("@127.0.0.1:" + SecretProxy.portForTest()), env.get("HTTPS_PROXY"));
        assertEquals(env.get("SSL_CERT_FILE"), env.get("CURL_CA_BUNDLE"));
        assertEquals("localhost,127.0.0.1,::1", env.get("NO_PROXY"));
        for (var value : env.values()) assertFalse(value.contains(VALUE), value);
        assertEquals("before [secret API_KEY] after", grant.mask("before " + VALUE + " after"));
    }
}
