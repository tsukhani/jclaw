import com.google.errorprone.annotations.MustBeClosed;
import models.Agent;
import models.SkillSecret;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.AgentService;
import services.CredentialMask;
import services.EventLogger;
import services.Tx;
import services.secrets.SecretGrant;
import services.secrets.SecretGrants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-374: a renewed secret's value is taken by the secrets proxy from the sign-in answer the operator's capture rule
 * names. The command holding the sign-in sees only its stand-in, which sends the new key at once; a matching answer the
 * key cannot be taken from is withheld; and nothing else is captured.
 */
class RenewedSecretCaptureTest extends FunctionalTest {

    private static final String PASSWORD = "fake-backoffice-password-jf374";
    private static final String KEY = "fake-session-key-jf374-Zq7mN2pX9vB4kL1wR8tY5u";
    private static final String NAME = "BACKOFFICE_SESSION_KEY";
    private static final String PASSWORD_NAME = "BACKOFFICE_PASSWORD";
    private static final String REFRESH = "BACKOFFICE_REFRESH_KEY";
    private static final String REFRESH_KEY = "fake-refresh-key-jf374-Hc3vQ8sT1nW6yE0rP4";
    private static final String SIGN_IN = "/v1/sessions/current";
    private static final String SKILL_MD = """
            ---
            name: backoffice
            description: test skill
            ---
            # backoffice

            ```secrets
            BACKOFFICE_PASSWORD=backoffice.example.com   # The sign-in password
            renewed BACKOFFICE_SESSION_KEY=backoffice.example.com from POST /v1/sessions/current json session using BACKOFFICE_PASSWORD in form password   # The key
            ```
            """;

    private Long agentId;
    private String agentName;
    private SecretProxyFixture stub;

    @BeforeEach
    void seed() throws Exception {
        AuthFixture.seedAdminPassword("changeme");
        agentName = "jf374-" + UUID.randomUUID().toString().substring(0, 8);
        agentId = commitInFreshTx(() -> AgentService.create(agentName, "openrouter", "gpt-4.1").id);
        var dir = AgentService.workspacePath(agentName).resolve("skills/backoffice");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), SKILL_MD);
        agents.SkillLoader.clearCache();
        stub = new SecretProxyFixture();
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\",\"user\":\"admin\"}"));
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
        assertIsOk(put("BACKOFFICE_PASSWORD", "{\"value\":\"" + PASSWORD + "\",\"hosts\":[\"" + bound() + "\"]}"));
    }

    @AfterEach
    void cleanUp() {
        stub.close();
        clearCookies();
        commitInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            if (agent != null) AgentService.delete(agent);
            return null;
        });
        CredentialMask.invalidate();
    }

    private String bound() {
        return SecretProxyFixture.BOUND + ":" + stub.port();
    }

    private play.mvc.Http.Response put(String secret, String json) {
        return PUT("/api/agents/" + agentId + "/skills/backoffice/secrets/" + secret, "application/json", json);
    }

    /** The operator's rule, saved with the secret and no value: the sign-in sends the password as its form field. */
    private void saveRule(String from, String field) {
        saveRule(NAME, "POST", SIGN_IN, from, field);
    }

    private void saveRule(String name, String method, String path, String from, String field) {
        saveRule(name, method, path, from, field, PASSWORD_NAME, "form password");
    }

    private void saveRule(String name, String method, String path, String from, String field, String credential,
                          String credentialIn) {
        assertIsOk(put(name, "{\"hosts\":[\"" + bound() + "\"],\"capture\":" + rule(method, path, from, field, credential,
                credentialIn) + "}"));
    }

    private static String rule(String method, String path, String from, String field, String credential,
                               String credentialIn) {
        return "{\"method\":\"" + method + "\",\"path\":\"" + path + "\",\"from\":\"" + from + "\",\"field\":\"" + field
                + "\",\"credential\":\"" + credential + "\",\"credentialIn\":\"" + credentialIn + "\"}";
    }

    /** The default rule's JSON: the sign-in's answer holds the key in {@code session}, the password sent as a form field. */
    private static final String SIGN_IN_RULE = rule("POST", SIGN_IN, "json", "session", "BACKOFFICE_PASSWORD",
            "form password");

    /** Every path the stub has no route for answers {@code reply}: the sign-in, however its path is spelled. */
    private void signInAnswers(SecretProxyFixture.Reply reply) {
        stub.replyToEveryOtherPath(r -> reply);
    }

    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // opened in a fresh transaction and returned for the test to close
    private SecretGrant running(String command) {
        var grant = commitInFreshTx(() -> SecretGrants.callWithUpstreamForTest(stub.upstream(), () -> SecretGrants
                .forCommand(Agent.findById(agentId), command, AgentService.workspacePath(agentName), false)));
        assertNotNull(grant, "the fixture must give the command a secret, or the test proves nothing");
        return grant;
    }

    /** A command of agent {@code id}, named {@code name}, given its secrets as {@link #running(String)} gives them. */
    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // opened in a fresh transaction and returned for the test to close
    private SecretGrant runningFor(Long id, String name, String command) {
        var grant = commitInFreshTx(() -> SecretGrants.callWithUpstreamForTest(stub.upstream(), () -> SecretGrants
                .forCommand(Agent.findById(id), command, AgentService.workspacePath(name), false)));
        assertNotNull(grant, "the fixture must give the command a secret, or the test proves nothing");
        return grant;
    }

    /** As {@link #running(String)}, with each grant failing where {@code rulesRead} or {@code store} throws. */
    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // opened in a fresh transaction and returned for the test to close
    private SecretGrant running(String command, Runnable rulesRead, Runnable store) {
        var grant = commitInFreshTx(() -> SecretGrants.callWithCaptureFaultsForTest(rulesRead, store, () -> SecretGrants
                .callWithUpstreamForTest(stub.upstream(), () -> SecretGrants.forCommand(Agent.findById(agentId), command,
                        AgentService.workspacePath(agentName), false))));
        assertNotNull(grant, "the fixture must give the command a secret, or the test proves nothing");
        return grant;
    }

    /** The password's stand-in, so a sign-in that sends it elsewhere than its form body is made with the skill's secret. */
    private static String passwordStandIn(SecretGrant grant) {
        return grant.environment().get("BACKOFFICE_PASSWORD");
    }

    /** The sign-in the skill sends, its password as the stand-in, to {@code path}: the status and body it gets. */
    private String signIn(SecretGrant grant, String method, String path) throws Exception {
        return send(grant, SecretProxyFixture.BOUND, method, path,
                "user=admin&password=" + grant.environment().get("BACKOFFICE_PASSWORD"));
    }

    private String signIn(SecretGrant grant) throws Exception {
        return signIn(grant, "POST", SIGN_IN);
    }

    private String send(SecretGrant grant, String host, String method, String path, String form) throws Exception {
        var body = RequestBody.create(form.getBytes(StandardCharsets.UTF_8),
                MediaType.get("application/x-www-form-urlencoded"));
        try (var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                .url("https://" + host + ":" + stub.port() + path).method(method, body).build()).execute()) {
            return response.code() + " " + response.body().string();
        }
    }

    private String stored() {
        return stored(NAME);
    }

    private String stored(String name) {
        return commitInFreshTx(() -> {
            SkillSecret row = SkillSecret.find("agent.id = ?1 AND name = ?2", agentId, name).first();
            return row == null ? null : row.value;
        });
    }

    /** The secret {@code name} as the panel lists it. */
    private com.google.gson.JsonObject listed(String name) {
        for (var e : com.google.gson.JsonParser.parseString(getContent(GET("/api/agents/" + agentId
                + "/skills/backoffice/secrets"))).getAsJsonObject().getAsJsonArray("secrets")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject();
        }
        throw new AssertionError("not listed: " + name);
    }

    /** As {@link #running(String)}, with {@code chosen} run once the command's secrets are read and before it is live. */
    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // opened in a fresh transaction and returned for the test to close
    private SecretGrant running(String command, Runnable chosen) {
        var grant = commitInFreshTx(() -> SecretGrants.callWithChosenHookForTest(chosen, () -> SecretGrants
                .callWithUpstreamForTest(stub.upstream(), () -> SecretGrants.forCommand(Agent.findById(agentId), command,
                        AgentService.workspacePath(agentName), false))));
        assertNotNull(grant, "the fixture must give the command a secret, or the test proves nothing");
        return grant;
    }

    /** The command sends {@code name}'s stand-in to {@code /echo} and the stub receives {@code value}. */
    private void assertSent(SecretGrant grant, String name, String value) throws Exception {
        var placeholder = grant.environment().get(name);
        assertEquals("200 method=GET\ntarget=/echo\nx-api-key=" + placeholder + "\nauthorization=null\nbody=",
                stub.send(grant, placeholder));
        assertEquals(value, stub.received.getLast().header("X-Api-Key"));
    }

    private String withheld() {
        return "502 " + "JClaw withheld this response: renewing a secret from it failed.\n";
    }

    @Test
    void aSignInReachesTheCommandAsItsStandInWhichSendsTheNewKeyAtOnce() throws Exception {
        saveRule("json", "session");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = grant.environment().get(NAME);
            assertEquals("201 {\"session\":\"" + standIn + "\",\"user\":\"admin\"}", signIn(grant));
            assertEquals("user=admin&password=" + PASSWORD, stub.received.getLast().bodyText());
            assertEquals(KEY, stored());
            assertSent(grant, NAME, KEY);
            assertEquals(List.of("BACKOFFICE_PASSWORD", NAME), grant.names(), "the capture revokes nothing");
            assertEquals("echoed " + standIn, stubEcho(grant, KEY), "the key a service sends back is masked too");
        }
        var listed = com.google.gson.JsonParser.parseString(getContent(GET("/api/agents/" + agentId
                + "/skills/backoffice/secrets"))).getAsJsonObject().getAsJsonArray("secrets");
        var row = listed.get(1).getAsJsonObject();
        assertEquals(NAME + " true", row.get("name").getAsString() + " " + row.get("hasValue"));
        assertFalse(row.get("renewedAt").isJsonNull(), row.toString());
        assertFalse(listed.toString().contains(KEY), listed.toString());
    }

    /** What {@code /echo-body} answers when the command sends {@code text}: the stub's reply as the command reads it. */
    private String stubEcho(SecretGrant grant, String text) throws Exception {
        stub.route("/echo-body", r -> ("echoed " + text).getBytes(StandardCharsets.UTF_8));
        try (var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                .url("https://" + bound() + "/echo-body").build()).execute()) {
            return response.body().string();
        }
    }

    @Test
    void aLaterCommandGetsTheKeyUnderAStandInOfItsOwn() throws Exception {
        saveRule("json", "session");
        String first;
        try (var grant = running("bash skills/backoffice/run.sh")) {
            first = grant.environment().get(NAME);
            signIn(grant);
        }
        try (var next = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me")) {
            assertEquals(List.of(NAME), next.names());
            assertNotEquals(first, next.environment().get(NAME));
            assertSent(next, NAME, KEY);
        }
    }

    @Test
    void aHeaderRuleTakesTheHeaderAndLeavesTheBodyAlone() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "header", "X-Session", PASSWORD_NAME, "header X-Password");
        signInAnswers(new SecretProxyFixture.Reply(200, Map.of("X-Session", KEY, "Content-Type", "text/plain"),
                "signed in".getBytes(StandardCharsets.UTF_8)));
        try (var grant = running("bash skills/backoffice/run.sh");
             var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                     .url("https://" + bound() + SIGN_IN).header("X-Password", passwordStandIn(grant))
                     .post(RequestBody.create(new byte[0], null)).build()).execute()) {
            assertEquals(grant.environment().get(NAME), response.header("X-Session"));
            assertEquals("signed in", response.body().string());
            assertEquals(KEY, stored());
        }
    }

    @Test
    void everyOpenGrantHoldingTheSecretSendsTheNewKey() throws Exception {
        saveRule("json", "session");
        try (var signer = running("bash skills/backoffice/run.sh");
             var other = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me")) {
            signIn(signer);
            assertSent(other, NAME, KEY);
            assertSent(signer, NAME, KEY);
            assertEquals(List.of(NAME), other.names(), "the reconcile after the capture keeps the renewed grant");
        }
    }

    @Test
    void aCommandNotGivenTheSecretReadsAMarkerAndTheKeyIsStillStored() throws Exception {
        saveRule("json", "session");
        try (var grant = running("curl -d \"password=$BACKOFFICE_PASSWORD\" https://backoffice.example.com/v1/sessions")) {
            assertEquals(List.of("BACKOFFICE_PASSWORD"), grant.names());
            assertEquals("201 {\"session\":\"[renewed: " + NAME + "]\",\"user\":\"admin\"}", signIn(grant));
            assertEquals(KEY, stored());
            assertEquals("echoed [renewed: " + NAME + "]", stubEcho(grant, KEY), "a later answer holding the key");
        }
    }

    /** The rule is the operator's: the agent deleting or rewriting the SKILL.md line changes nothing it does. */
    @Test
    void changingOrDeletingTheSkillMdLineLeavesTheCaptureAsItWas() throws Exception {
        saveRule("json", "session");
        var skillMd = AgentService.workspacePath(agentName).resolve("skills/backoffice/SKILL.md");
        int n = 0;
        for (var rewritten : List.of(SKILL_MD.replace("json session", "json user"),
                SKILL_MD.replaceAll("renewed .*\\n", ""))) {
            Files.writeString(skillMd, rewritten);
            agents.SkillLoader.clearCache();
            var key = KEY + "-" + n++;
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key + "\",\"user\":\"admin\"}"));
            try (var grant = running("bash skills/backoffice/run.sh")) {
                assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                        signIn(grant));
            }
            assertEquals(key, stored());
        }
    }

    @Test
    void eachSpellingOfTheSignInPathAndMethodIsCaptured() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "header X-Password");
        var missed = new ArrayList<String>();
        int n = 0;
        for (var line : List.of("POST /v1//sessions/current/", "POST /v1/./sessions/current", "post " + SIGN_IN,
                "POST /v1/x/../sessions/%63urrent?next=1", "POST /V1/Sessions/Current", "POST /v1/sessions;x=1/current",
                "POST /v1/sessions/current;jsessionid=a1", "POST /v1/x/..;y/sessions/current",
                "POST /v1/sessions/current.json", "POST /v1/sessions/Current.XML", "POST /v1/sessions/current.json;x=1")) {
            var key = KEY + "-" + n++;
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key + "\"}"));
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var reply = stub.raw(grant, line + " HTTP/1.1\r\nHost: " + bound() + "\r\nX-Password: "
                        + passwordStandIn(grant) + "\r\nContent-Length: 0\r\n\r\n");
                var expected = "{\"session\":\"" + grant.environment().get(NAME) + "\"}";
                if (!reply.startsWith("HTTP/1.1 201 ") || !reply.endsWith("\r\n\r\n" + expected) || !key.equals(stored())) {
                    missed.add(line + " -> " + reply.lines().findFirst().orElse("(no reply)"));
                }
            }
        }
        assertEquals(List.of(), missed);
    }

    @Test
    void aSignInPathWithAnEncodedSlashHasItsAnswerWithheld() throws Exception {
        saveRule("json", "session");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), signIn(grant, "POST", "/v1/sessions%2Fcurrent"));
            assertEquals("/v1/sessions%2Fcurrent", stub.received.getLast().target(), "the request was sent");
            assertEquals(withheld(), signIn(grant, "POST", "/v1/sessions%252Fcurrent"), "a slash escaped twice");
            assertEquals(withheld(), signIn(grant, "POST", "/v1/x/%252e%252e/sessions/current"), "dots escaped twice");
            assertEquals("/v1/x/%252e%252e/sessions/current", stub.received.getLast().target());
            assertEquals(404, statusOf(grant, "/v1/sessions%2Fcurrent", 404),
                    "an answer that is no success passes as before");
        }
        assertEquals("", stored());
    }

    private int statusOf(SecretGrant grant, String path, int status) throws Exception {
        signInAnswers(SecretProxyFixture.Reply.json(status, "{\"error\":\"no\"}"));
        try (var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                .url("https://" + bound() + path).post(RequestBody.create(new byte[0], null)).build()).execute()) {
            return response.code();
        }
    }

    @Test
    void aMatchingAnswerTheKeyCannotBeTakenFromIsWithheldAndStoresNothing() throws Exception {
        saveRule("json", "session");
        int limit = 1 << 20;
        var tooLong = "{\"session\":\"" + KEY + "\",\"pad\":\"" + "x".repeat(limit) + "\"}";
        var wrong = new ArrayList<String>();
        for (var reply : List.of(
                SecretProxyFixture.Reply.json(201, "{\"user\":\"admin\"}"),
                SecretProxyFixture.Reply.json(201, "{\"session\":42}"),
                SecretProxyFixture.Reply.json(201, "{\"session\":\"\"}"),
                SecretProxyFixture.Reply.json(201, "{\"session\":\"fake-first-session-jf374\",\"session\":\"" + KEY + "\"}"),
                SecretProxyFixture.Reply.json(201, "[{\"session\":\"" + KEY + "\"}]"),
                SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\"} trailing"),
                SecretProxyFixture.Reply.json(201, "{'session':'" + KEY + "'}"),
                new SecretProxyFixture.Reply(201, Map.of(), ("session=" + KEY).getBytes(StandardCharsets.UTF_8)),
                SecretProxyFixture.Reply.json(201, tooLong),
                new SecretProxyFixture.Reply(201, Map.of("Content-Type", "application/json", "Content-Encoding", "deflate"),
                        ("{\"session\":\"" + KEY + "\"}").getBytes(StandardCharsets.UTF_8)))) {
            signInAnswers(reply);
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var got = signIn(grant);
                if (!got.equals(withheld())) wrong.add(new String(reply.body(), 0, Math.min(40, reply.body().length),
                        StandardCharsets.UTF_8) + " -> " + got.substring(0, Math.min(80, got.length())));
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals("", stored());
    }

    @Test
    void aSignInAnswerOfExactlyTheBufferedLimitIsCaptured() throws Exception {
        saveRule("json", "session");
        var head = "{\"session\":\"" + KEY + "\",\"pad\":\"";
        var body = head + "x".repeat((1 << 20) - head.length() - 2) + "\"}";
        assertEquals(1 << 20, body.length());
        signInAnswers(SecretProxyFixture.Reply.json(201, body));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 " + body.replace(KEY, grant.environment().get(NAME)), signIn(grant));
        }
        assertEquals(KEY, stored());
    }

    /** However the service escapes the key inside its JSON string, the command reads the stand-in. */
    @Test
    void anEscapedKeyIsStillReplaced() throws Exception {
        saveRule("json", "session");
        var key = "fake/key+jf374/Zq7mN2pX9vB4kL1wR8tY5u==";
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key.replace("/", "\\/")
                + "\",\"note\":\"your key is \\u0066ake\\/key+jf374\\/Zq7mN2pX9vB4kL1wR8tY5u==\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = grant.environment().get(NAME);
            assertEquals("201 {\"session\":\"" + standIn + "\",\"note\":\"your key is " + standIn + "\"}", signIn(grant));
        }
        assertEquals(key, stored());
    }

    @Test
    void anotherHostOrAnAnswerThatIsNoSuccessPassesAsBeforeAndStoresNothing() throws Exception {
        saveRule("json", "session");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}",
                    send(grant, SecretProxyFixture.OTHER, "POST", SIGN_IN, "user=admin"), "another host");
            signInAnswers(SecretProxyFixture.Reply.json(401, "{\"session\":\"" + KEY + "\"}"));
            assertEquals("401 {\"session\":\"" + KEY + "\"}", signIn(grant), "an answer that is no success");
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\"}"));
            assertEquals("201 {\"session\":\"" + KEY + "\"}", signIn(grant, "PUT", SIGN_IN), "another method");
            assertEquals("201 {\"session\":\"" + KEY + "\"}", signIn(grant, "POST", SIGN_IN + "s"), "another path");
        }
        assertEquals("", stored());
    }

    /** With no rule saved, a declared renewed secret captures nothing: the sign-in answer passes as it did before. */
    @Test
    void withoutTheOperatorsRuleNothingIsCaptured() throws Exception {
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(List.of("BACKOFFICE_PASSWORD"), grant.names());
            assertEquals("201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}", signIn(grant));
        }
        assertEquals(null, stored());
    }

    /** The operator may still enter the value; a capture later replaces it, and the operator's is masked as the old one. */
    @Test
    void theOperatorsOwnValueIsSentUntilASignInRenewsIt() throws Exception {
        assertIsOk(put(NAME, "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"" + bound() + "\"],\"capture\":"
                + SIGN_IN_RULE + "}"));
        var setAt = listed(NAME).get("setAt").getAsString();
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertSent(grant, NAME, "fake-typed-session-jf374");
            signIn(grant);
            assertSent(grant, NAME, KEY);
            assertEquals("echoed " + grant.environment().get(NAME), stubEcho(grant, "fake-typed-session-jf374"));
        }
        var renewed = listed(NAME);
        assertEquals(setAt, renewed.get("setAt").getAsString(), "a capture is no save of the operator's");
        assertFalse(renewed.get("renewedAt").isJsonNull(), renewed.toString());
        assertIsOk(put(NAME, "{\"value\":\"fake-retyped-session-jf374\",\"hosts\":[\"" + bound() + "\"]}"));
        var retyped = listed(NAME);
        assertNotEquals(setAt, retyped.get("setAt").getAsString());
        assertTrue(retyped.get("renewedAt").isJsonNull(), "a typed value is the operator's, not a renewed one");
        assertIsOk(DELETE("/api/agents/" + agentId + "/skills/backoffice/secrets/" + NAME));
        assertEquals(null, stored());
    }

    /** A HEAD reaches the route of a GET, and a method-override header the route of the method it names. */
    @Test
    void aHeadCountsAsAGetAndAMethodOverrideHeaderAsTheMethodItNames() throws Exception {
        saveRule(NAME, "GET", SIGN_IN, "header", "X-Session", PASSWORD_NAME, "header X-Password");
        signInAnswers(new SecretProxyFixture.Reply(200, Map.of("X-Session", KEY), new byte[0]));
        try (var grant = running("bash skills/backoffice/run.sh");
             var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                     .url("https://" + bound() + SIGN_IN).header("X-Password", passwordStandIn(grant)).head().build())
                     .execute()) {
            assertEquals("200 " + grant.environment().get(NAME), response.code() + " " + response.header("X-Session"));
        }
        assertEquals(KEY, stored());

        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "header X-Password");
        var missed = new ArrayList<String>();
        int n = 0;
        for (var header : List.of("X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override")) {
            var key = KEY + "-" + n++;
            signInAnswers(SecretProxyFixture.Reply.json(200, "{\"session\":\"" + key + "\"}"));
            try (var grant = running("bash skills/backoffice/run.sh");
                 var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                         .url("https://" + bound() + SIGN_IN).header(header, "post")
                         .header("X-Password", passwordStandIn(grant)).build()).execute()) {
                var got = response.code() + " " + response.body().string();
                if (!got.equals("200 {\"session\":\"" + grant.environment().get(NAME) + "\"}") || !key.equals(stored())) {
                    missed.add(header + " -> " + got);
                }
            }
        }
        assertEquals(List.of(), missed);
    }

    @Test
    void aRedirectThatAnswersTheSignInIsCapturedAsASuccessIs() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "header", "X-Session", PASSWORD_NAME, "header X-Password");
        signInAnswers(new SecretProxyFixture.Reply(302, Map.of("X-Session", KEY, "Location", "/home"), new byte[0]));
        try (var grant = running("bash skills/backoffice/run.sh");
             var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                     .url("https://" + bound() + SIGN_IN).header("X-Password", passwordStandIn(grant))
                     .post(RequestBody.create(new byte[0], null)).build()).execute()) {
            assertEquals("302 " + grant.environment().get(NAME) + " /home",
                    response.code() + " " + response.header("X-Session") + " " + response.header("Location"));
        }
        assertEquals(KEY, stored());
    }

    @Test
    void aJsonRulesRedirectWithoutTheFieldIsWithheld() throws Exception {
        saveRule("json", "session");
        signInAnswers(new SecretProxyFixture.Reply(303, Map.of("Location", "/home", "Content-Type", "text/html"),
                ("<a href=\"/home\">" + KEY + "</a>").getBytes(StandardCharsets.UTF_8)));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), signIn(grant));
        }
        assertEquals("", stored());
    }

    /** An escaped slash withholds an answer only where a reading of the path could be the sign-in. */
    @Test
    void anEscapedSlashElsewhereOnTheHostPassesAsBefore() throws Exception {
        saveRule("json", "session");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}", signIn(grant, "POST", "/v1/files/a%2Fb"));
            assertEquals("/v1/files/a%2Fb", stub.received.getLast().target(), "the request was sent as written");
            assertEquals(withheld(), signIn(grant, "POST", "/v1%2Fsessions%2Fcurrent"), "read as slashes, it is the sign-in");
        }
        assertEquals("", stored());
    }

    @Test
    void anAnswerMarkedIdentityEncodedIsNotCompressedAndIsCaptured() throws Exception {
        saveRule("json", "session");
        signInAnswers(new SecretProxyFixture.Reply(201, Map.of("Content-Type", "application/json", "Content-Encoding",
                "identity"), ("{\"session\":\"" + KEY + "\"}").getBytes(StandardCharsets.UTF_8)));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\"}", signIn(grant));
        }
        assertEquals(KEY, stored());
    }

    /**
     * Two sign-ins answered at the same moment store one key after the other: every command holding the secret then
     * sends the key stored last, and none loses the secret.
     */
    @Test
    void twoSignInsAtOnceLeaveEveryCommandSendingTheKeyStoredLast() throws Exception {
        saveRule("json", "session");
        var wrong = new ArrayList<String>();
        for (int round = 0; round < 8; round++) {
            var bothIn = new CyclicBarrier(2);
            var n = new AtomicInteger();
            var prefix = KEY + "-" + round + "-";
            stub.replyToEveryOtherPath(r -> {
                try {
                    bothIn.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return SecretProxyFixture.Reply.json(201, "{\"session\":\"" + prefix + n.incrementAndGet() + "\"}");
            });
            try (var a = running("bash skills/backoffice/run.sh");
                 var b = running("bash skills/backoffice/run.sh");
                 var watcher = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me")) {
                var failure = new AtomicReference<Throwable>();
                var answeredB = new AtomicReference<String>();
                var other = Thread.ofPlatform().start(() -> {
                    try {
                        answeredB.set(signIn(b));
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
                var answeredA = signIn(a);
                other.join(30_000);
                assertFalse(other.isAlive(), "the second sign-in never ended");
                assertNull(failure.get());
                for (var answer : List.of(Map.entry(a, answeredA), Map.entry(b, String.valueOf(answeredB.get())))) {
                    var standIn = answer.getKey().environment().get(NAME);
                    if (!answer.getValue().equals("201 {\"session\":\"" + standIn + "\"}")) {
                        wrong.add(round + ": a sign-in got " + answer.getValue() + ", not its stand-in " + standIn);
                    }
                }
                var last = stored();
                if (!last.startsWith(prefix)) wrong.add(round + ": stored " + last);
                for (var grant : List.of(a, b, watcher)) {
                    if (!grant.names().contains(NAME)) {
                        wrong.add(round + ": a command lost " + NAME);
                        continue;
                    }
                    var placeholder = grant.environment().get(NAME);
                    stub.send(grant, placeholder);
                    var sent = stub.received.getLast().header("X-Api-Key");
                    if (!last.equals(sent)) wrong.add(round + ": a command sent " + sent + ", not " + last);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    /**
     * More secret saves and sign-ins for one agent at once than the database pool has connections, all queued behind
     * a sign-in that holds the agent's capture lock while it stores: each finishes well inside the pool's wait for a
     * connection, and every command holding the session key sends the key stored last. A save waiting on that lock
     * with its commit's connection still checked out would leave the lock holder none for the read its reconcile
     * makes, and that read would fail, stopping every command's secrets, only when the pool's wait ran out.
     */
    @Test
    void moreSavesAndSignInsAtOnceThanThePoolHasConnectionsAllFinishAndLeaveEveryCommandOnTheLastKey() throws Exception {
        int pool = Integer.parseInt(play.Play.configuration.getProperty("db.pool.maxSize"));
        long connectionWaitMs = Long.parseLong(play.Play.configuration.getProperty("db.pool.timeout"));
        long boundMs = connectionWaitMs * 4 / 5;
        saveRule("json", "session");
        var n = new AtomicInteger();
        stub.replyToEveryOtherPath(r -> SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "-"
                + n.incrementAndGet() + "\"}"));
        var inStore = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holding = new AtomicBoolean();
        try (var holder = running("bash skills/backoffice/run.sh", () -> { }, () -> {
                 if (!holding.compareAndSet(false, true)) return;
                 inStore.countDown();
                 try {
                     assertTrue(release.await(60, TimeUnit.SECONDS), "the test never released the lock holder");
                 } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     throw new IllegalStateException(e);
                 }
             });
             var signer = running("bash skills/backoffice/run.sh");
             var watcher = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me")) {
            var failures = new ConcurrentLinkedQueue<String>();
            var ends = new ConcurrentHashMap<String, Long>();
            var all = new ArrayList<Thread>();
            all.add(timed("the sign-in holding the lock", failures, ends, () -> expectStandIn(holder, signIn(holder))));
            assertTrue(inStore.await(30, TimeUnit.SECONDS), "the first sign-in never reached its store");
            var saves = new ArrayList<Thread>();
            for (int i = 0; i < pool + 4; i++) {
                var name = "JF374_EXTRA_KEY_" + i;
                var value = "fake-extra-key-jf374-" + i;
                saves.add(timed("save " + name, failures, ends, () -> Tx.run(() -> {
                    Agent owner = Agent.findById(agentId);
                    services.SkillSecrets.put(owner, "backoffice", name, value, List.of(bound()));
                    SecretGrants.reconcileAfterCommit(owner);
                })));
            }
            all.addAll(saves);
            for (int i = 0; i < 4; i++) {
                all.add(timed("sign-in " + i, failures, ends, () -> expectStandIn(signer, signIn(signer))));
            }
            awaitParked(saves);
            long released = System.nanoTime();
            release.countDown();
            for (var t : all) {
                t.join(Duration.ofSeconds(90));
                if (t.isAlive()) failures.add(t.getName() + " never ended");
            }
            assertEquals(List.of(), List.copyOf(failures));
            var late = ends.entrySet().stream().filter(e -> (e.getValue() - released) / 1_000_000 > boundMs)
                    .map(e -> e.getKey() + " ended " + (e.getValue() - released) / 1_000_000 + " ms after the release")
                    .sorted().toList();
            assertEquals(List.of(), late, "each must end inside " + boundMs + " ms, under the pool's "
                    + connectionWaitMs + " ms wait for a connection");
            var last = stored();
            assertTrue(last.startsWith(KEY + "-"), last);
            for (var grant : List.of(holder, signer, watcher)) {
                assertTrue(grant.names().contains(NAME), "a command lost " + NAME + ": " + grant.names());
                assertSent(grant, NAME, last);
            }
        }
    }

    private interface Step {
        void run() throws Exception;
    }

    /** {@code step} on a thread of its own named {@code what}: a failure is added to {@code failures}, its end to {@code ends}. */
    private static Thread timed(String what, java.util.Queue<String> failures, Map<String, Long> ends, Step step) {
        return Thread.ofPlatform().name(what).start(() -> {
            try {
                step.run();
            } catch (Throwable t) {
                failures.add(what + ": " + t);
            } finally {
                ends.put(what, System.nanoTime());
            }
        });
    }

    private static void expectStandIn(SecretGrant grant, String answer) {
        var expected = "201 {\"session\":\"" + grant.environment().get(NAME) + "\"}";
        if (!answer.equals(expected)) throw new AssertionError("got " + answer + ", not " + expected);
    }

    /**
     * Wait until every one of {@code threads} has stayed parked, on a lock or for a connection, for a quarter second,
     * or five seconds have passed: the saves have committed, or are waiting for a connection, behind the lock holder.
     */
    private static void awaitParked(List<Thread> threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int quiet = 0;
        while (quiet < 5 && System.nanoTime() < deadline) {
            boolean parked = threads.stream().allMatch(t -> t.getState() == Thread.State.WAITING
                    || t.getState() == Thread.State.TIMED_WAITING);
            quiet = parked ? quiet + 1 : 0;
            Thread.sleep(50);
        }
    }

    /**
     * A sign-in that is stored after a new command's secrets were read, and before that command's grant is live,
     * leaves the command the secret, with the new key.
     */
    @Test
    void aCommandOpenedWhileASignInIsStoredKeepsTheSecretAndSendsTheNewKey() throws Exception {
        saveRule("json", "session");
        try (var signer = running("bash skills/backoffice/run.sh")) {
            var signedIn = new AtomicReference<String>();
            try (var opened = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me", () -> {
                if (signedIn.get() != null) return;
                try {
                    signedIn.set(signIn(signer));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })) {
                assertEquals("201 {\"session\":\"" + signer.environment().get(NAME) + "\",\"user\":\"admin\"}",
                        signedIn.get());
                assertEquals(KEY, stored());
                assertEquals(List.of(NAME), opened.names(), "the secret it was chosen survives the sign-in");
                assertSent(opened, NAME, KEY);
            }
        }
    }

    @Test
    void twoRenewedSecretsOnOneAnswerAreBothStoredAndBothReplaced() throws Exception {
        saveRule("json", "session");
        saveRule(REFRESH, "post", "/v1/sessions/current/", "json", "refresh");
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\",\"refresh\":\"" + REFRESH_KEY + "\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"refresh\":\""
                    + grant.environment().get(REFRESH) + "\"}", signIn(grant));
            assertSent(grant, REFRESH, REFRESH_KEY);
            assertSent(grant, NAME, KEY);
        }
        assertEquals(KEY + " " + REFRESH_KEY, stored() + " " + stored(REFRESH));
    }

    /**
     * The operator changes one of two rules while the sign-in is on its way: that secret can no longer be stored from
     * the answer, so neither is, the answer is withheld, and the log says why.
     */
    @Test
    void aRuleChangedWhileItsSignInIsInFlightStoresNeitherSecretAndWithholdsTheAnswer() throws Exception {
        saveRule("json", "session");
        saveRule(REFRESH, "POST", SIGN_IN, "json", "refresh");
        var changed = new AtomicReference<Integer>();
        stub.replyToEveryOtherPath(r -> {
            if (changed.get() == null) {
                changed.set(put(NAME, "{\"hosts\":[\"" + bound() + "\"],\"capture\":" + rule("POST", SIGN_IN, "json",
                        "token", PASSWORD_NAME, "form password") + "}").status);
            }
            return SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\",\"refresh\":\"" + REFRESH_KEY + "\"}");
        });
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try (var grant = running("bash skills/backoffice/run.sh")) {
                assertEquals(withheld(), signIn(grant));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(200, changed.get());
        assertEquals(" ", stored() + " " + stored(REFRESH));
        assertEquals(List.of("Secrets proxy: withheld the answer to POST " + SIGN_IN + " from " + SecretProxyFixture.BOUND
                        + ", as " + NAME + " could not be renewed from it: the secret or its rule changed"),
                events.stream().map(EventLogger.Captured::message).filter(m -> m.startsWith("Secrets proxy:")).toList());
        assertEquals(List.of(), events.stream().map(EventLogger.Captured::message)
                .filter(m -> m.startsWith("Secret " + REFRESH + " of skill")).toList(),
                "the store that took " + REFRESH + " first rolled back, so no renewal of it is logged");
    }

    @Test
    void aKeyOfTheLongestValueAStoreTakesIsCapturedAndOneCharacterLongerIsWithheld() throws Exception {
        saveRule("json", "session");
        var longest = "k".repeat(services.SkillSecrets.MAX_VALUE_CHARS);
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + longest + "x\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), signIn(grant));
        }
        assertEquals("", stored());
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + longest + "\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\"}", signIn(grant));
        }
        assertEquals(longest, stored());
    }

    /** A rule whose skill is switched off captures nothing, and withholds nothing either. */
    @Test
    void aSwitchedOffSkillsRuleNeitherCapturesNorWithholds() throws Exception {
        saveRule("json", "session");
        var other = AgentService.workspacePath(agentName).resolve("skills/other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("SKILL.md"), "---\nname: other\ndescription: test skill\n---\n# other\n");
        agents.SkillLoader.clearCache();
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/OTHER_KEY", "application/json",
                "{\"value\":\"fake-other-key-jf374\",\"hosts\":[\"" + bound() + "\"]}"));
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/backoffice", "application/json", "{\"enabled\":false}"));
        try (var grant = running("curl -H \"api-key: $OTHER_KEY\" https://backoffice.example.com/v1/me")) {
            assertEquals(List.of("OTHER_KEY"), grant.names());
            assertEquals("201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}",
                    send(grant, SecretProxyFixture.BOUND, "POST", SIGN_IN, "user=admin"));
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"user\":\"admin\"}"));
            assertEquals("201 {\"user\":\"admin\"}", send(grant, SecretProxyFixture.BOUND, "POST", SIGN_IN, "user=admin"),
                    "an answer without the field is not withheld");
        }
        assertEquals("", stored());
    }

    /**
     * Only a value the proxy took from a sign-in reaches the commands already running: one the operator types stops the
     * secret for them, as any secret's change does, and the next command gets it. A new rule alone changes nothing.
     */
    @Test
    void anOperatorsNewValueForARenewedSecretStopsItForARunningCommandAsForAnySecret() throws Exception {
        assertIsOk(put(NAME, "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"" + bound() + "\"],\"capture\":"
                + SIGN_IN_RULE + "}"));
        try (var grant = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me")) {
            assertIsOk(put(NAME, "{\"hosts\":[\"" + bound() + "\"],\"capture\":" + rule("GET", "/v2/token", "header",
                    "X-Session", NAME, "header api-key") + "}"));
            assertEquals(List.of(NAME), grant.names(), "a new rule changes nothing the command holds");
            assertSent(grant, NAME, "fake-typed-session-jf374");
            assertIsOk(put(NAME, "{\"value\":\"fake-second-typed-jf374\",\"hosts\":[\"" + bound() + "\"]}"));
            assertEquals(List.of(), grant.names(), "a typed value stops it, as for any secret");
        }
        try (var next = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me")) {
            assertSent(next, NAME, "fake-second-typed-jf374");
        }
    }

    /** No event holds the key, and a tool result produced straight after the capture already masks it. */
    @Test
    void noEventHoldsTheKeyAndAToolResultMasksItAtOnce() throws Exception {
        saveRule("json", "session");
        assertEquals("signed in as " + KEY, maskedOnAToolThread("signed in as " + KEY));
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try (var grant = running("bash skills/backoffice/run.sh")) {
                signIn(grant);
                signInAnswers(SecretProxyFixture.Reply.json(201, "{\"user\":\"admin\"}"));
                signIn(grant);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals("signed in as [masked: " + NAME + "]", maskedOnAToolThread("signed in as " + KEY));
        var messages = events.stream().filter(e -> e.message().contains(NAME)).map(EventLogger.Captured::message).toList();
        assertEquals(List.of(
                "A command was given the secrets BACKOFFICE_PASSWORD, " + NAME,
                "Secret " + NAME + " of skill backoffice renewed from the answer to POST " + SIGN_IN,
                "Secrets proxy: withheld the answer to POST " + SIGN_IN + " from " + SecretProxyFixture.BOUND + ", as " + NAME
                        + " could not be renewed from it: it has no field session holding a non-empty string"), messages);
        for (var e : events) {
            assertFalse(e.message().contains(KEY) || e.details() != null && e.details().contains(KEY), e.message());
        }
    }

    // ==================== whose credentials the sign-in carries ====================

    /**
     * A sign-in made with credentials none of the skill's secrets supplied belongs to whoever supplied them: it passes
     * as before, whether it carries no stand-in or another skill's, and only one made with the skill's own is captured.
     */
    @Test
    void aSignInCarryingNoneOfTheSkillsSecretsPassesAsBeforeAndStoresNothing() throws Exception {
        saveRule("json", "session");
        var other = AgentService.workspacePath(agentName).resolve("skills/other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("SKILL.md"), "---\nname: other\ndescription: test skill\n---\n# other\n");
        agents.SkillLoader.clearCache();
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/OTHER_KEY", "application/json",
                "{\"value\":\"fake-other-key-jf374\",\"hosts\":[\"" + bound() + "\"]}"));
        try (var grant = running("bash skills/backoffice/run.sh \"$OTHER_KEY\"")) {
            assertEquals(List.of("BACKOFFICE_PASSWORD", NAME, "OTHER_KEY"), grant.names());
            var inClear = "201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}";
            assertEquals(inClear, send(grant, SecretProxyFixture.BOUND, "POST", SIGN_IN,
                    "user=admin&password=fake-own-password-jf374"), "a password the command typed itself");
            assertEquals(inClear, send(grant, SecretProxyFixture.BOUND, "POST", SIGN_IN,
                    "user=admin&password=" + grant.environment().get("OTHER_KEY")), "another skill's secret");
            assertEquals("", stored());
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}", signIn(grant),
                    "the skill's own password");
        }
        assertEquals(KEY, stored());
    }

    /** The status and body a command holding {@code grant} gets for {@code request}, built on {@link #bound()}. */
    private String call(SecretGrant grant, Request.Builder request) throws Exception {
        try (var response = SecretProxyFixture.through(grant).newCall(request.build()).execute()) {
            return response.code() + " " + response.body().string();
        }
    }

    private Request.Builder toSignIn(String path) {
        return new Request.Builder().url("https://" + bound() + path);
    }

    private static RequestBody json(String body) {
        return RequestBody.create(body.getBytes(StandardCharsets.UTF_8), MediaType.get("application/json"));
    }

    private static RequestBody form(String body) {
        return RequestBody.create(body.getBytes(StandardCharsets.UTF_8), MediaType.get("application/x-www-form-urlencoded"));
    }

    /**
     * A sign-in the proxy never put the password into was made with credentials the service read from somewhere else:
     * another value in the named place, or a stand-in written escaped, which the proxy does not replace. It passes as
     * before, and so does one carrying the stand-in of a renewed secret with no value yet, which sends nothing.
     */
    @Test
    void aSignInThatNeverSendsThePasswordPassesAsBefore() throws Exception {
        saveRule("json", "session");
        var inClear = "201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}";
        var wrong = new ArrayList<String>();
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = passwordStandIn(grant);
            var escaped = "%" + Integer.toHexString(standIn.charAt(0)).toUpperCase() + standIn.substring(1);
            for (var request : List.of(
                    Map.entry("a password the command typed itself", toSignIn(SIGN_IN)
                            .post(form("user=admin&password=fake-own-password-jf374"))),
                    Map.entry("the stand-in escaped alone, so never replaced", toSignIn(SIGN_IN)
                            .post(form("user=admin&password=" + escaped))),
                    Map.entry("the stand-in escaped in a header too", toSignIn(SIGN_IN).header("X-Password", escaped)
                            .post(form("user=admin&password=" + escaped))))) {
                var got = call(grant, request.getValue());
                if (!got.equals(inClear)) wrong.add(request.getKey() + " -> " + got);
            }
            assertEquals("user=admin&password=" + escaped, stub.received.getLast().bodyText(), "sent as written");
        }
        assertEquals(List.of(), wrong);
        assertEquals("", stored());

        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "json password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = passwordStandIn(grant);
            var escaped = "\\u00" + Integer.toHexString(standIn.charAt(0)) + standIn.substring(1);
            assertEquals(inClear, call(grant, toSignIn(SIGN_IN).post(json("{\"password\":\"" + escaped + "\"}"))),
                    "a JSON field that decodes to the stand-in but is written escaped, so never replaced");
            assertEquals("{\"password\":\"" + escaped + "\"}", stub.received.getLast().bodyText());
        }
        assertEquals("", stored());

        saveRule(REFRESH, "POST", "/v1/refresh", "json", "refresh", PASSWORD_NAME, "form password");
        // The store refuses a rule whose credential has no value, so this one is written as a row could still hold it.
        commitInFreshTx(() -> {
            SkillSecret row = SkillSecret.find("agent.id = ?1 AND name = ?2", agentId, NAME).first();
            row.captureCredential = REFRESH;
            row.captureCredentialIn = "form password";
            row.save();
            return null;
        });
        agents.SkillLoader.clearCache();
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(inClear, call(grant, toSignIn(SIGN_IN).post(form("password=" + grant.environment().get(REFRESH)))),
                    "the stand-in of a renewed secret with no value yet sends nothing a service could accept");
        }
        assertEquals("", stored());
    }

    /** The error a refused save answers with: its status and message. */
    private static String refusal(play.mvc.Http.Response response) {
        var body = com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject();
        return response.status + " " + body.get("message").getAsString();
    }

    /**
     * A rule whose credential has no value, or is sent to none of the hosts the sign-in goes to, could never take a
     * value: the proxy would send nothing, or refuse the sign-in. It is refused, and nothing is saved.
     */
    @Test
    void aRuleWhoseCredentialHasNoValueOrSharesNoHostIsRefused() throws Exception {
        saveRule(REFRESH, "POST", "/v1/refresh", "json", "refresh", PASSWORD_NAME, "form password");
        assertEquals("400 " + REFRESH + " has no value yet, so a sign-in that sends it sends nothing, and the rule could"
                        + " never take a value for " + NAME + ": give " + REFRESH + " a value first.",
                refusal(put(NAME, "{\"hosts\":[\"" + bound() + "\"],\"capture\":" + rule("POST", SIGN_IN, "json",
                        "session", REFRESH, "form password") + "}")));
        assertEquals("400 " + PASSWORD_NAME + " is sent only to " + bound() + ", none of the hosts " + NAME
                        + "'s sign-in goes to, so the rule could never take a value: give the two secrets a host in common.",
                refusal(put(NAME, "{\"hosts\":[\"backoffice.example.com\"],\"capture\":" + SIGN_IN_RULE + "}")));
        assertEquals(null, stored());
        assertIsOk(put(NAME, "{\"hosts\":[\"backoffice.example.com\",\"" + bound() + "\"],\"capture\":" + SIGN_IN_RULE
                + "}"));
        assertEquals("", stored(), "one host in common is enough");
        assertIsOk(put("BACKOFFICE_PASSWORD", "{\"hosts\":[\"backoffice.example.com\"]}"));
        assertIsOk(put("OTHER_SESSION_KEY", "{\"hosts\":[\"backoffice.example.com:443\"],\"capture\":" + SIGN_IN_RULE
                + "}"));
        assertEquals("", stored("OTHER_SESSION_KEY"), "a host without a port is the same as on port 443");
    }

    /**
     * The proxy puts the password in place of every stand-in, so a sign-in that sends it other than once, in the
     * place the rule names, went out with the operator's password though whose session it answers cannot be told:
     * its answer is withheld, and nothing is stored. The request itself was sent, with the password.
     */
    @Test
    void aSignInThatSendsThePasswordOtherThanOnceInItsPlaceIsWithheldAndStoresNothing() throws Exception {
        saveRule("json", "session");
        var wrong = new ArrayList<String>();
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = passwordStandIn(grant);
            var escaped = "%" + Integer.toHexString(standIn.charAt(0)).toUpperCase() + standIn.substring(1);
            var basic = okhttp3.Credentials.basic("admin", standIn);
            var named = "user=admin&password=" + standIn;
            var own = "user=admin&password=fake-own-password-jf374";
            for (var request : List.of(
                    Map.entry("the named field twice", toSignIn(SIGN_IN).post(form("password=" + standIn + "&password="
                            + standIn))),
                    Map.entry("the named field and another field", toSignIn(SIGN_IN).post(form("user=" + standIn
                            + "&password=" + standIn))),
                    Map.entry("the named field and a header", toSignIn(SIGN_IN).header("X-Password", standIn)
                            .post(form(named))),
                    Map.entry("the named field and Basic auth", toSignIn(SIGN_IN).header("Authorization", basic)
                            .post(form(named))),
                    Map.entry("the named field and the URL", toSignIn(SIGN_IN + "?pw=" + standIn).post(form(named))),
                    Map.entry("only another field", toSignIn(SIGN_IN).post(form("user=" + standIn
                            + "&password=fake-own-password-jf374"))),
                    Map.entry("only a header", toSignIn(SIGN_IN).header("X-Password", standIn).post(form(own))),
                    Map.entry("only Basic auth", toSignIn(SIGN_IN).header("Authorization", basic).post(form(own))),
                    Map.entry("only the URL", toSignIn(SIGN_IN + "?pw=" + standIn).post(form(own))),
                    Map.entry("a JSON body, which has no form field", toSignIn(SIGN_IN)
                            .post(json("{\"password\":\"" + standIn + "\"}"))),
                    Map.entry("an escaped stand-in in the named field and the real one in a header", toSignIn(SIGN_IN)
                            .header("X-Password", standIn).post(form("user=admin&password=" + escaped))))) {
                int before = stub.received.size();
                var got = call(grant, request.getValue());
                if (!got.equals(withheld()) || stub.received.size() != before + 1) {
                    wrong.add(request.getKey() + " -> " + got);
                }
            }
            var last = stub.received.getLast();
            assertEquals(PASSWORD + " user=admin&password=" + escaped, last.header("X-Password") + " " + last.bodyText(),
                    "the request went out with the password, and the escaped stand-in as written");
        }
        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "json password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = passwordStandIn(grant);
            var got = call(grant, toSignIn(SIGN_IN).post(json("{\"user\":\"" + standIn + "\",\"password\":\"" + standIn
                    + "\"}")));
            if (!got.equals(withheld())) wrong.add("the named JSON field and another JSON field -> " + got);
        }
        assertEquals(List.of(), wrong);
        assertEquals("", stored());
    }

    /** Why a stray password withholds the answer is logged, naming the secrets and the rule, never a value. */
    @Test
    void aStrayPasswordIsLoggedByNameWithoutItsValue() throws Exception {
        saveRule("json", "session");
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try (var grant = running("bash skills/backoffice/run.sh")) {
                assertEquals(withheld(), call(grant, toSignIn(SIGN_IN).header("X-Password", passwordStandIn(grant))
                        .post(form("user=admin&password=" + passwordStandIn(grant)))));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(List.of("WARN Secrets proxy: withheld the answer to POST " + SIGN_IN + " from "
                        + SecretProxyFixture.BOUND + ", as " + NAME + " could not be renewed from it: its request sent "
                        + PASSWORD_NAME + " other than once in form password"),
                events.stream().filter(e -> e.message().startsWith("Secrets proxy:"))
                        .map(e -> e.level() + " " + e.message()).toList());
        for (var e : events) {
            assertFalse(e.message().contains(PASSWORD) || e.details() != null && e.details().contains(PASSWORD),
                    e.message());
        }
        assertEquals("", stored());
    }

    /**
     * A body too long to read whole streams, its stand-ins replaced on the way: one carrying a second copy of the
     * password past the first MiB sent it twice, though the named header holds it once, so its answer is withheld.
     * The same sign-in without that copy is captured.
     */
    @Test
    void aStreamedBodyCarryingASecondCopyOfThePasswordPastTheFirstMibIsWithheld() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "header X-Password");
        var pad = "x".repeat((1 << 20) + 64);
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var standIn = passwordStandIn(grant);
            assertEquals(withheld(), call(grant, toSignIn(SIGN_IN).header("X-Password", standIn)
                    .post(octets(pad + standIn))));
            assertEquals(pad + PASSWORD, stub.received.getLast().bodyText(), "the streamed copy went out replaced");
            assertEquals("", stored());
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                    call(grant, toSignIn(SIGN_IN).header("X-Password", standIn).post(octets(pad))));
        }
        assertEquals(KEY, stored());
    }

    private static RequestBody octets(String body) {
        return RequestBody.create(body.getBytes(StandardCharsets.UTF_8), MediaType.get("application/octet-stream"));
    }

    private static RequestBody multipart(String body) {
        return RequestBody.create(body.getBytes(StandardCharsets.UTF_8), MediaType.get("multipart/form-data; boundary=b1"));
    }

    /**
     * A part whose blank line follows its delimiter line at once has no head, and so no name, and the password in the
     * part after it is read. A delimiter after a bare LF, not CRLF, is content to a strict server and a new part to a
     * lenient one, so which field holds the password cannot be told: the answer is withheld.
     */
    @Test
    void aMultipartPartWithNoHeadIsReadPastAndADelimiterAfterABareLineFeedIsWithheld() throws Exception {
        saveRule("json", "session");
        var wrong = new ArrayList<String>();
        int n = 0;
        for (var blank : List.of("\r\n", "\n")) {
            var key = KEY + "-" + n++;
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key + "\"}"));
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var got = call(grant, toSignIn(SIGN_IN).post(multipart("--b1" + blank + blank + "no head\r\n--b1\r\n"
                        + "Content-Disposition: form-data; name=\"password\"\r\n\r\n" + passwordStandIn(grant)
                        + "\r\n--b1--\r\n")));
                if (!got.equals("201 {\"session\":\"" + grant.environment().get(NAME) + "\"}") || !key.equals(stored())) {
                    wrong.add(blank.replace("\r", "CR").replace("\n", "LF") + " -> " + got);
                }
            }
        }
        assertEquals(List.of(), wrong);
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"fake-unread-session-jf374\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), call(grant, toSignIn(SIGN_IN).post(multipart("--b1\r\nContent-Disposition: form-data;"
                    + " name=\"user\"\r\n\r\nadmin\n--b1\r\nContent-Disposition: form-data; name=\"password\"\r\n\r\n"
                    + passwordStandIn(grant) + "\r\n--b1--\r\n"))));
        }
        assertEquals(KEY + "-1", stored(), "the withheld answer stored nothing");
    }

    /**
     * A rules read that fails passes the request of an agent last read with no rule, whose answers hold no key to
     * take; for one last read with a rule, or never read, it sends nothing.
     */
    @Test
    void aRulesReadThatFailsPassesAnAgentLastReadWithNoRuleAndHoldsBackOneWithARule() throws Exception {
        var inClear = "201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}";
        Runnable fails = () -> {
            throw new IllegalStateException("fake rules read failure");
        };
        try (var reader = running("bash skills/backoffice/run.sh")) {
            assertEquals(inClear, signIn(reader), "read, with no rule");
        }
        try (var grant = running("bash skills/backoffice/run.sh", fails, () -> { })) {
            assertEquals(inClear, signIn(grant));
        }
        saveRule("json", "session");
        try (var reader = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + reader.environment().get(NAME) + "\",\"user\":\"admin\"}",
                    signIn(reader), "read, with the rule");
        }
        try (var grant = running("bash skills/backoffice/run.sh", fails, () -> { })) {
            int sent = stub.received.size();
            assertEquals("502 JClaw could not check this request's secrets; try again.\n", signIn(grant));
            assertEquals(sent, stub.received.size(), "the sign-in was not sent");
        }
    }

    /** A value holding a line break or a NUL would break every header it went in: it is not stored, and the log says why. */
    @Test
    void aCapturedValueHoldingALineBreakOrANulIsWithheldAndTheLogSaysWhy() throws Exception {
        saveRule("json", "session");
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            for (var escape : List.of("n", "r", "u0000")) {
                signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"fake-broken-session" + "\\" + escape
                        + "jf374\"}"));
                try (var grant = running("bash skills/backoffice/run.sh")) {
                    assertEquals(withheld(), signIn(grant));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        assertEquals("", stored());
        var why = "Secrets proxy: withheld the answer to POST " + SIGN_IN + " from " + SecretProxyFixture.BOUND + ", as "
                + NAME + " could not be renewed from it: its value holds a line break or a NUL, which no header can carry";
        assertEquals(List.of(why, why, why), events.stream().map(EventLogger.Captured::message)
                .filter(m -> m.startsWith("Secrets proxy:")).toList());
        for (var e : events) assertFalse(e.message().contains("fake-broken-session"), e.message());
    }

    /** A kept file assigning the renewed secret holds a key older than the one a sign-in stores: it is deleted then. */
    @Test
    void aKeptFileAssigningTheRenewedSecretIsDeletedOnceASignInFillsIt() throws Exception {
        saveRule("json", "session");
        keep("credentials/session.env", NAME + "=fake-old-session-jf374\n");
        assertEquals(List.of("credentials/session.env"), keptPaths(), "kept while the secret has no value");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}", signIn(grant));
        }
        assertEquals(KEY, stored());
        assertEquals(List.of(), keptPaths());
    }

    /** Keep {@code path} of an old copy of backoffice holding {@code content}, as an update would. */
    private void keep(String path, String content) throws Exception {
        var old = Files.createTempDirectory("jf374-old-");
        var fresh = Files.createTempDirectory("jf374-new-");
        try {
            Files.createDirectories(old.resolve(path).getParent());
            Files.writeString(old.resolve(path), content);
            commitInFreshTx(() -> {
                try {
                    return services.KeptSkillFiles.copyOut(Agent.findById(agentId), "backoffice", old, fresh);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } finally {
            services.SkillPromotionService.deleteRecursive(old);
            services.SkillPromotionService.deleteRecursive(fresh);
        }
    }

    private List<String> keptPaths() {
        var out = new ArrayList<String>();
        for (var e : com.google.gson.JsonParser.parseString(getContent(GET("/api/agents/" + agentId
                + "/skills/backoffice/kept"))).getAsJsonObject().getAsJsonArray("kept")) {
            out.add(e.getAsJsonObject().get("path").getAsString());
        }
        return out;
    }

    /** A header rule's answer still compressed may hide the header's copies in its body: it is withheld. */
    @Test
    void aHeaderRulesAnswerStillCompressedIsWithheldAndStoresNothing() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "header", "X-Session", PASSWORD_NAME, "header X-Password");
        signInAnswers(new SecretProxyFixture.Reply(200, Map.of("X-Session", KEY, "Content-Type", "text/plain",
                "Content-Encoding", "deflate"), "fake deflated body".getBytes(StandardCharsets.UTF_8)));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), call(grant, toSignIn(SIGN_IN).header("X-Password", passwordStandIn(grant))
                    .post(form("user=admin"))));
        }
        assertEquals("", stored());
    }

    /**
     * A stored rule JClaw cannot read is warned about once for each version of it saved, however often the rules are
     * read, and so is one saved only in part, which is no rule either.
     */
    @Test
    void aStoredRuleThatCannotBeReadIsWarnedAboutOncePerSavedVersion() throws Exception {
        saveRule("json", "session");
        var cannot = "Secret " + NAME + " of skill backoffice has a stored capture rule JClaw cannot read%s, so nothing is"
                + " taken under it: save its rule again in the Secrets panel.";
        var warned = new ArrayList<String>();
        for (var broken : List.<java.util.function.Consumer<SkillSecret>>of(row -> row.captureFrom = "xml", row -> {
            row.captureFrom = "json";
            row.captureField = null;
        })) {
            commitInFreshTx(() -> {
                SkillSecret row = SkillSecret.find("agent.id = ?1 AND name = ?2", agentId, NAME).first();
                broken.accept(row);
                row.save();
                return null;
            });
            var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
                for (int i = 0; i < 3; i++) {
                    assertEquals(List.of(), commitInFreshTx(() -> services.SkillSecrets.renewable(Agent.findById(agentId))));
                }
            });
            events.stream().map(EventLogger.Captured::message).filter(m -> m.contains("stored capture rule"))
                    .forEach(warned::add);
        }
        assertEquals(List.of(cannot.formatted(""), cannot.formatted(", saved only in part")), warned);
    }

    /**
     * A body the proxy cannot parse for the password, a broken JSON object or a multipart body without its parts, may
     * still carry it to a service that reads leniently: a success it gets is withheld.
     */
    @Test
    void aCredentialBodyTheProxyCannotParseIsWithheld() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "json password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), call(grant, toSignIn(SIGN_IN)
                    .post(json("{\"password\":\"" + passwordStandIn(grant) + "\","))));
            assertEquals("{\"password\":\"" + PASSWORD + "\",", stub.received.getLast().bodyText(), "the request was sent");
        }
        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "form password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals(withheld(), call(grant, toSignIn(SIGN_IN).post(RequestBody.create(("password="
                    + passwordStandIn(grant)).getBytes(StandardCharsets.UTF_8), MediaType.get("multipart/form-data; boundary=b1")))));
        }
        assertEquals("", stored());
    }

    /** A form whose content type has a malformed parameter is still a form to a lenient server, so it names any method. */
    @Test
    void aFormWhoseContentTypeCannotBeParsedCountsAsEveryMethod() throws Exception {
        saveRule(NAME, "PUT", SIGN_IN, "json", "session", PASSWORD_NAME, "form password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                    call(grant, toSignIn(SIGN_IN).header("Content-Type", "application/x-www-form-urlencoded; charset")
                            .post(RequestBody.create(("_method=PUT&password=" + passwordStandIn(grant))
                                    .getBytes(StandardCharsets.UTF_8), null))));
            assertEquals("_method=PUT&password=" + PASSWORD, stub.received.getLast().bodyText());
        }
        assertEquals(KEY, stored());
    }

    /** Each place a rule can name captures the sign-in that sends the password's stand-in there, and the service gets it. */
    @Test
    void eachPlaceARuleNamesCapturesTheSignInThatSendsThePasswordThere() throws Exception {
        var missed = new ArrayList<String>();
        int n = 0;
        for (var place : List.of("json password", "form password", "form password (multipart)", "header X-Password",
                "header Authorization (Bearer)", "basic")) {
            var key = KEY + "-" + n++;
            saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, place.replaceFirst(" \\(.*\\)$", ""));
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key + "\"}"));
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var standIn = passwordStandIn(grant);
                var request = toSignIn(SIGN_IN);
                switch (place) {
                    case "json password" -> request.post(json("{\"user\":\"admin\",\"password\":\"" + standIn + "\"}"));
                    case "form password" -> request.post(form("user=admin&password=" + standIn));
                    case "form password (multipart)" -> request.post(new okhttp3.MultipartBody.Builder()
                            .setType(okhttp3.MultipartBody.FORM).addFormDataPart("user", "admin")
                            .addFormDataPart("password", standIn).build());
                    case "header X-Password" -> request.header("X-Password", standIn).post(form("user=admin"));
                    case "header Authorization (Bearer)" -> request.header("Authorization", "Bearer " + standIn)
                            .post(form("user=admin"));
                    default -> request.header("Authorization", okhttp3.Credentials.basic("admin", standIn))
                            .post(form("user=admin"));
                }
                var got = call(grant, request);
                var sent = stub.received.getLast();
                var carried = sent.bodyText().contains(PASSWORD) || PASSWORD.equals(sent.header("X-Password"))
                        || ("Bearer " + PASSWORD).equals(sent.header("Authorization"))
                        || okhttp3.Credentials.basic("admin", PASSWORD).equals(sent.header("Authorization"));
                if (!got.equals("201 {\"session\":\"" + grant.environment().get(NAME) + "\"}") || !key.equals(stored())
                        || !carried) {
                    missed.add(place + " -> " + got + (carried ? "" : ", the service did not get the password"));
                }
            }
        }
        assertEquals(List.of(), missed);
    }

    /** A JSON body too long to read whole cannot be read for the password, so a success it gets is withheld. */
    @Test
    void aJsonSignInTooLongToReadForThePasswordIsWithheld() throws Exception {
        saveRule(NAME, "POST", SIGN_IN, "json", "session", PASSWORD_NAME, "json password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var head = "{\"password\":\"" + passwordStandIn(grant) + "\",\"pad\":\"";
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                    call(grant, toSignIn(SIGN_IN).post(json(head + "x".repeat((1 << 20) - head.length() - 2) + "\"}"))),
                    "a body exactly as long as the proxy reads");
            assertEquals(withheld(), call(grant, toSignIn(SIGN_IN).post(json(head + "x".repeat(1 << 20) + "\"}"))));
        }
        assertEquals(KEY, stored(), "only the sign-in the proxy could read stored its key");
    }

    /** A rule saved with a format suffix matches the sign-in without it, as a server that renders formats routes it. */
    @Test
    void aRulePathWithAFormatSuffixMatchesTheSignInWithoutIt() throws Exception {
        saveRule(NAME, "POST", SIGN_IN + ".json", "json", "session", PASSWORD_NAME, "form password");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                    signIn(grant));
        }
        assertEquals(KEY, stored());
    }

    /** A {@code _method} in a multipart or a JSON body routes the request as the method it names. */
    @Test
    void aMethodParameterInAMultipartOrJsonBodyCountsAsTheMethodItNames() throws Exception {
        var missed = new ArrayList<String>();
        int n = 0;
        for (var kind : List.of("multipart", "json")) {
            var key = KEY + "-" + n++;
            saveRule(NAME, "PUT", SIGN_IN, "json", "session", PASSWORD_NAME, kind.equals("json") ? "json password"
                    : "form password");
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key + "\"}"));
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var standIn = passwordStandIn(grant);
                var body = kind.equals("json") ? json("{\"_method\":\"put\",\"password\":\"" + standIn + "\"}")
                        : new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                                .addFormDataPart("_method", "PUT").addFormDataPart("password", standIn).build();
                var got = call(grant, toSignIn(SIGN_IN).post(body));
                if (!got.equals("201 {\"session\":\"" + grant.environment().get(NAME) + "\"}") || !key.equals(stored())) {
                    missed.add(kind + " -> " + got);
                }
            }
        }
        assertEquals(List.of(), missed);
    }

    /** A refresh-style sign-in sends the renewed key itself: a rule naming it as the credential captures the next one. */
    @Test
    void aRefreshSignInThatSendsTheRenewedSecretItselfIsCaptured() throws Exception {
        assertIsOk(put(NAME, "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"" + bound() + "\"],\"capture\":"
                + rule("POST", SIGN_IN, "json", "session", NAME, "header api-key") + "}"));
        try (var grant = running("curl -H \"api-key: $" + NAME + "\" https://backoffice.example.com/v1/me");
             var response = SecretProxyFixture.through(grant).newCall(new Request.Builder()
                     .url("https://" + bound() + SIGN_IN).header("api-key", grant.environment().get(NAME))
                     .post(RequestBody.create(new byte[0], null)).build()).execute()) {
            assertEquals(List.of(NAME), grant.names());
            assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                    response.code() + " " + response.body().string());
            assertEquals("fake-typed-session-jf374", stub.received.getLast().header("api-key"));
        }
        assertEquals(KEY, stored());
    }

    /** A {@code _method} in the query or in a form body routes the request as the method it names. */
    @Test
    void aMethodParameterInTheQueryOrAFormBodyCountsAsTheMethodItNames() throws Exception {
        saveRule(NAME, "PUT", SIGN_IN, "json", "session");
        var missed = new ArrayList<String>();
        int n = 0;
        for (var request : List.of(List.of(SIGN_IN + "?_method=PUT", "user=admin&password="),
                List.of(SIGN_IN + "?next=1&_method=put", "user=admin&password="),
                List.of(SIGN_IN, "_method=PUT&user=admin&password="),
                List.of(SIGN_IN, "user=admin&_method=put&password="))) {
            var key = KEY + "-" + n++;
            signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + key + "\"}"));
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var got = send(grant, SecretProxyFixture.BOUND, "POST", request.get(0), request.get(1) + passwordStandIn(grant));
                if (!got.equals("201 {\"session\":\"" + grant.environment().get(NAME) + "\"}") || !key.equals(stored())) {
                    missed.add(request + " -> " + got);
                }
            }
        }
        assertEquals(List.of(), missed);
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertEquals("201 {\"session\":\"" + KEY + "\"}", signIn(grant), "a POST naming no other method is no PUT");
        }
    }

    /**
     * A form body to the sign-in's path that is too long for the proxy to read whole could name any method, so it counts
     * as every one, and whether it sent the password cannot be read either: its answer is withheld. One exactly as long
     * as the proxy reads is read, and names no other method.
     */
    @Test
    void aFormBodyTooLongToReadToTheSignInsPathCountsAsEveryMethodAndIsWithheld() throws Exception {
        saveRule(NAME, "PUT", SIGN_IN, "json", "session");
        int limit = 1 << 20;
        signInAnswers(SecretProxyFixture.Reply.json(201, "{\"session\":\"" + KEY + "\"}"));
        try (var grant = running("bash skills/backoffice/run.sh")) {
            var head = "user=admin&password=" + passwordStandIn(grant) + "&pad=";
            assertEquals("201 {\"session\":\"" + KEY + "\"}", send(grant, SecretProxyFixture.BOUND, "POST", SIGN_IN,
                    head + "x".repeat(limit - head.length())), "a body the proxy read whole");
            assertEquals("", stored());
            var tail = "&password=" + passwordStandIn(grant);
            assertEquals(withheld(), send(grant, SecretProxyFixture.BOUND, "POST", SIGN_IN,
                    "user=admin&pad=" + "x".repeat(limit) + tail));
            assertTrue(stub.received.getLast().bodyText().endsWith("&password=" + PASSWORD), "the request was sent");
        }
        assertEquals("", stored());
    }

    @Test
    void aHeaderRulesAnswerWithoutTheHeaderOrWithItTwiceIsWithheld() throws Exception {
        saveRule("header", "X-Session");
        var wrong = new ArrayList<String>();
        for (var reply : List.of(
                new SecretProxyFixture.Reply(200, Map.of("Content-Type", "text/plain"),
                        ("signed in as " + KEY).getBytes(StandardCharsets.UTF_8)),
                new SecretProxyFixture.Reply(200, Map.of("Content-Type", "text/plain"), "signed in".getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Session", List.of(KEY, "fake-second-session-jf374"))))) {
            signInAnswers(reply);
            try (var grant = running("bash skills/backoffice/run.sh")) {
                var got = signIn(grant);
                if (!got.equals(withheld())) wrong.add(reply.repeated() + " -> " + got);
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals("", stored());
    }

    /** A capture rule read that fails sends nothing, and the command is told its secrets could not be checked. */
    @Test
    void aRulesReadThatFailsSendsNothingAndRepliesThatItCouldNotBeChecked() throws Exception {
        saveRule("json", "session");
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try (var grant = running("bash skills/backoffice/run.sh", () -> {
                throw new IllegalStateException("fake rules read failure");
            }, () -> { })) {
                int sent = stub.received.size();
                assertEquals("502 JClaw could not check this request's secrets; try again.\n", signIn(grant));
                assertEquals(sent, stub.received.size(), "the sign-in was not sent");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(List.of("WARN Secrets proxy: could not read the capture rules for a request to "
                        + SecretProxyFixture.BOUND + ", so it was not sent: IllegalStateException"),
                events.stream().filter(e -> e.message().startsWith("Secrets proxy:"))
                        .map(e -> e.level() + " " + e.message()).toList());
        assertEquals("", stored());
    }

    /** A store that fails stores nothing, the answer is withheld, and the log says why without the failure's text. */
    @Test
    void aStoreThatFailsWithholdsTheAnswerAndStoresNothing() throws Exception {
        saveRule("json", "session");
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try (var grant = running("bash skills/backoffice/run.sh", () -> { }, () -> {
                throw new IllegalStateException("fake store failure " + KEY);
            })) {
                assertEquals(withheld(), signIn(grant));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(List.of("Secrets proxy: withheld the answer to POST " + SIGN_IN + " from " + SecretProxyFixture.BOUND
                        + ", as " + NAME + " could not be renewed from it: storing it failed (IllegalStateException)"),
                events.stream().map(EventLogger.Captured::message).filter(m -> m.startsWith("Secrets proxy:")).toList());
        assertEquals("", stored());
    }

    /**
     * A grant with no agent behind it, as the proxy's own tests open one, captures nothing, though its owner's name
     * has a rule a command of that agent's would be captured under.
     */
    @Test
    void aGrantWithNoAgentBehindItCapturesNothing() throws Exception {
        // SecretGrants.openForTest names its owner "test": that name gets the rule, so only the grant's kind differs.
        assertNull(commitInFreshTx(() -> Agent.findByName("test")), "an agent this test did not make holds the name");
        Long testId = commitInFreshTx(() -> AgentService.create("test", "openrouter", "gpt-4.1").id);
        try {
            var dir = AgentService.workspacePath("test").resolve("skills/backoffice");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), SKILL_MD);
            agents.SkillLoader.clearCache();
            var secrets = "/api/agents/" + testId + "/skills/backoffice/secrets/";
            assertIsOk(PUT(secrets + "BACKOFFICE_PASSWORD", "application/json",
                    "{\"value\":\"" + PASSWORD + "\",\"hosts\":[\"" + bound() + "\"]}"));
            assertIsOk(PUT(secrets + NAME, "application/json", "{\"hosts\":[\"" + bound() + "\"],\"capture\":"
                    + SIGN_IN_RULE + "}"));
            try (var grant = SecretGrants.openForTest(List.of(new services.SkillSecrets.Usable("backoffice",
                    "BACKOFFICE_PASSWORD", PASSWORD, List.of(bound()))), stub.upstream())) {
                assertEquals("201 {\"session\":\"" + KEY + "\",\"user\":\"admin\"}", signIn(grant));
            }
            try (var grant = runningFor(testId, "test", "bash skills/backoffice/run.sh")) {
                assertEquals("201 {\"session\":\"" + grant.environment().get(NAME) + "\",\"user\":\"admin\"}",
                        signIn(grant), "the same sign-in from that agent's command");
            }
        } finally {
            commitInFreshTx(() -> {
                Agent test = Agent.findById(testId);
                if (test != null) AgentService.delete(test);
                return null;
            });
        }
    }

    /** A command whose renewed secret a change stopped reads the marker where the key was, never its dead stand-in. */
    @Test
    void aCommandWhoseRenewedSecretAChangeStoppedReadsTheMarker() throws Exception {
        saveRule("json", "session");
        try (var grant = running("bash skills/backoffice/run.sh")) {
            assertIsOk(put(NAME, "{\"hosts\":[\"" + bound() + "\",\"backoffice.example.com\"]}"));
            assertEquals(List.of("BACKOFFICE_PASSWORD"), grant.names(), "a change of hosts stops the secret");
            assertEquals("201 {\"session\":\"[renewed: " + NAME + "]\",\"user\":\"admin\"}", signIn(grant));
            assertEquals(KEY, stored());
            assertEquals("echoed [renewed: " + NAME + "]", stubEcho(grant, KEY), "a later answer holding the key");
        }
    }

    /**
     * {@code text} masked as a tool's result is, on a thread with no transaction of its own yet: the test body's
     * transaction keeps the rows it read, the stored key's old value among them.
     */
    private static String maskedOnAToolThread(String text) {
        return commitInFreshTx(() -> CredentialMask.mask(text));
    }

    /**
     * {@code block} committed on a thread of its own: the test body runs inside a transaction that commits only when
     * it ends, and the proxy reads the rows on a thread of its own.
     */
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
