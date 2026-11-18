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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * JF-375: a secret bound to a path on its host is written only into a request to that path, compared as JF-374's capture
 * rule compares its own, so a sign-in password goes to the sign-in endpoint and nowhere else on the host.
 */
class SecretPathBindingTest extends UnitTest {

    private static final String PASSWORD = "fake-password-jf375";
    private static final String TOKEN = "fake-token-jf375";
    private static final String KEY = "fake-key-jf375";

    private SecretProxyFixture stub;
    private SecretGrant grant;
    private String bound;

    @BeforeEach
    @SuppressWarnings("MustBeClosed") // the grant lives for one test and stop() closes it
    void start() throws Exception {
        stub = new SecretProxyFixture();
        stub.replyToEveryOtherPath(r -> SecretProxyFixture.Reply.json(200, "{\"ok\":true}"));
        bound = SecretProxyFixture.BOUND + ":" + stub.port();
        grant = SecretGrants.openForTest(List.of(
                new SkillSecrets.Usable("backoffice", "PASSWORD", PASSWORD, List.of(bound + "/v1/sessions/current")),
                new SkillSecrets.Usable("backoffice", "TOKEN", TOKEN, List.of(bound + "/v1/sessions/")),
                new SkillSecrets.Usable("backoffice", "API_KEY", KEY, List.of(bound))), stub.upstream());
    }

    @AfterEach
    void stop() {
        grant.close();
        stub.close();
    }

    private String standIn(String name) {
        return grant.environment().get(name);
    }

    /** A request sent as these bytes through a tunnel to the bound host, so no client tidies its target first. */
    private String send(String method, String target, String headers, String body) throws Exception {
        var head = method + " " + target + " HTTP/1.1\r\nHost: " + bound + "\r\n" + headers
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
        var proxyUrl = java.net.URI.create(grant.environment().get("HTTPS_PROXY"));
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), SecretProxy.portForTest())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("CONNECT " + bound + " HTTP/1.1\r\nProxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString(proxyUrl.getUserInfo().getBytes(StandardCharsets.UTF_8))
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 200 Connection established", readLine(socket.getInputStream()));
            readLine(socket.getInputStream());
            var tls = (SSLSocket) trustingJclaw().createSocket(socket, SecretProxyFixture.BOUND, stub.port(), true);
            tls.startHandshake();
            tls.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            var reply = new String(tls.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return reply.lines().findFirst().orElse("") + "\n" + reply.substring(reply.indexOf("\r\n\r\n") + 4);
        }
    }

    private String postForm(String target, String body) throws Exception {
        return send("POST", target, "Content-Type: application/x-www-form-urlencoded\r\n", body);
    }

    private static final String OK = "HTTP/1.1 200 OK\n{\"ok\":true}";

    private String refused(String name, String allowed, String path) {
        return "HTTP/1.1 403 Forbidden\nJClaw refused this request: secret " + name + " may be sent only to " + bound
                + allowed + ", not to " + bound + path + ".\n";
    }

    @Test
    void aPasswordBoundToTheSignInPathIsSwappedThere() throws Exception {
        assertEquals(OK, postForm("/v1/sessions/current", "password=" + standIn("PASSWORD")));
        assertEquals("password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8), stub.received.getFirst().bodyText());
    }

    @Test
    void theSamePasswordIsRefusedOnAnotherPathOfItsHostAndReachesNothing() throws Exception {
        assertEquals(refused("PASSWORD", "/v1/sessions/current", "/v1/sessions/refresh"),
                postForm("/v1/sessions/refresh", "password=" + standIn("PASSWORD")));
        assertEquals(refused("PASSWORD", "/v1/sessions/current", "/v1/users"),
                postForm("/v1/users", "password=" + standIn("PASSWORD")));
        assertEquals(List.of(), stub.received);
    }

    @Test
    void spellingsOfTheSignInPathMatchAsTheCaptureRuleMatchesThem() throws Exception {
        var wrong = new ArrayList<String>();
        // A doubled slash goes mid-path: the stub's JDK server refuses a target that starts with "//".
        for (var path : List.of("/v1//sessions//current", "/v1/./sessions/current", "/v1/other/../sessions/current",
                "/v1/%73essions/current", "/V1/Sessions/Current", "/v1/sessions/current;jsessionid=1",
                "/v1/sessions/current.json", "/v1/sessions/current?next=1")) {
            var reply = postForm(path, "password=" + standIn("PASSWORD"));
            if (!reply.equals(OK)) wrong.add(path + " -> " + reply);
        }
        assertEquals(List.of(), wrong);
        assertEquals(8, stub.received.size());
    }

    @Test
    void aPathAServerCouldReadMoreThanOneWayGetsNoBoundSecret() throws Exception {
        var wrong = new ArrayList<String>();
        for (var path : List.of("/v1/sessions%2Fcurrent", "/v1%2Fsessions/current", "/v1/sessions/%252e%252e/current")) {
            var reply = postForm(path, "password=" + standIn("PASSWORD"));
            if (!reply.startsWith("HTTP/1.1 403 Forbidden\n")) wrong.add(path + " -> " + reply);
        }
        assertEquals(List.of(), wrong);
        assertEquals(List.of(), stub.received);
    }

    @Test
    void aPathEntryEndingInASlashTakesWhatLiesBelowItButNotASibling() throws Exception {
        assertEquals(OK, send("GET", "/v1/sessions/current", "Authorization: Bearer " + standIn("TOKEN") + "\r\n", ""));
        assertEquals(OK, send("GET", "/v1/sessions", "Authorization: Bearer " + standIn("TOKEN") + "\r\n", ""));
        assertEquals(refused("TOKEN", "/v1/sessions/", "/v1/sessionsx"),
                send("GET", "/v1/sessionsx", "Authorization: Bearer " + standIn("TOKEN") + "\r\n", ""));
        assertEquals(2, stub.received.size());
    }

    @Test
    void anEntryWithNoPathLetsTheSecretGoAnywhereOnItsHost() throws Exception {
        assertEquals(OK, send("GET", "/anything/at/all", "X-Api-Key: " + standIn("API_KEY") + "\r\n", ""));
        assertEquals(KEY, stub.received.getFirst().header("X-Api-Key"));
    }

    @Test
    void theQueryAHeaderBasicAuthAndAFormBodyAreEachCheckedAgainstThePath() {
        var password = standIn("PASSWORD");
        var basic = "Authorization: Basic " + Base64.getEncoder().encodeToString(("admin:" + password)
                .getBytes(StandardCharsets.UTF_8)) + "\r\n";
        var places = new java.util.LinkedHashMap<String, String[]>();
        places.put("query", new String[] {"/v1/sessions/current?password=" + password, "", ""});
        places.put("header", new String[] {"/v1/sessions/current", "X-Password: " + password + "\r\n", ""});
        places.put("basic", new String[] {"/v1/sessions/current", basic, ""});
        places.put("form", new String[] {"/v1/sessions/current",
                "Content-Type: application/x-www-form-urlencoded\r\n", "password=" + password});
        var wrong = new ArrayList<String>();
        places.forEach((where, request) -> {
            try {
                if (!send("POST", request[0], request[1], request[2]).equals(OK)) wrong.add(where + " on its path");
                var elsewhere = request[0].replace("/v1/sessions/current", "/v1/refresh");
                if (!send("POST", elsewhere, request[1], request[2]).startsWith("HTTP/1.1 403 Forbidden\n")) {
                    wrong.add(where + " elsewhere");
                }
            } catch (Exception e) {
                wrong.add(where + ": " + e);
            }
        });
        assertEquals(List.of(), wrong);
        assertEquals(4, stub.received.size(), "only the four on the bound path reached the service");
    }

    @Test
    void aPathBoundSecretNeverStandsInTheRequestsPath() throws Exception {
        assertEquals("HTTP/1.1 403 Forbidden\nJClaw refused this request: secret PASSWORD is bound to a path, so it cannot"
                + " stand in the request's path itself.\n", send("GET", "/v1/sessions/" + standIn("PASSWORD"), "", ""));
        assertEquals(OK, send("GET", "/v1/keys/" + standIn("API_KEY"), "", ""), "a secret with no path may, as before");
        assertEquals("/v1/keys/" + KEY, stub.received.getFirst().target());
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
}
