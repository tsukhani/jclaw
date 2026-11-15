import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.AgentAccess;
import controllers.ApiSkillSecretsController;
import controllers.RequestPrincipal;
import jobs.CredentialEncryptionJob;
import models.Agent;
import models.AgentSkillConfig;
import models.SkillSecret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.db.jpa.JPA;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.EventLogger;
import services.KeptSkillFiles;
import services.Tx;
import services.secrets.SecretGrant;
import services.secrets.SecretGrants;
import utils.CredentialCipher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-323: a skill's secrets are stored sealed, listed without their values, refused to every agent, and gone with
 * the skill or the agent; no response body or log line carries a value.
 */
class ApiSkillSecretsControllerTest extends FunctionalTest {

    private static final String VALUE = "fake-nas-password-jf323-7c1e";

    private Long agentId;
    private String agentName;

    @BeforeEach
    void seed() throws Exception {
        AuthFixture.seedAdminPassword("changeme");
        agentName = "jf323-" + UUID.randomUUID().toString().substring(0, 8);
        agentId = commitInFreshTx(() -> AgentService.create(agentName, "openrouter", "gpt-4.1").id);
        // The in-memory database numbers agents from 1 again in every run, and the kept root under tmp/ outlives it.
        KeptSkillFiles.deleteForAgent(agentId);
        Files.createDirectories(AgentService.workspacePath(agentName).resolve("skills/demo"));
        Files.writeString(AgentService.workspacePath(agentName).resolve("skills/demo/SKILL.md"), "# Demo\n");
        login();
    }

    @AfterEach
    void cleanUp() throws Exception {
        clearCookies();
        commitInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            if (agent != null) AgentService.delete(agent);
            return null;
        });
        KeptSkillFiles.deleteForAgent(agentId);
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    private String base() {
        return "/api/agents/" + agentId + "/skills/demo/secrets";
    }

    private Http.Response put(String secret, String json) {
        return PUT(base() + "/" + secret, "application/json", json);
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static String errorOf(Http.Response response) {
        var body = json(response);
        return response.status + " " + body.get("code").getAsString() + " " + body.get("message").getAsString();
    }

    private String stored(String name) {
        return commitInFreshTx(() -> (String) JPA.em().createNativeQuery(
                        "SELECT s.secret_value FROM skill_secret s WHERE s.agent_id = ?1 AND s.name = ?2")
                .setParameter(1, agentId).setParameter(2, name).getResultList().stream().findFirst().orElse(null));
    }

    private long rows() {
        return commitInFreshTx(() -> SkillSecret.count("agent.id = ?1", agentId));
    }

    @Test
    void aSecretIsStoredSealedAndListedWithoutItsValue() {
        var saved = put("NAS_PASSWORD", "{\"value\":\"" + VALUE + "\",\"hosts\":[\"nas.example.com\"]}");
        assertIsOk(saved);
        var body = json(saved);
        assertEquals(List.of("capture", "hasValue", "hosts", "name", "renewedAt", "setAt"),
                body.keySet().stream().sorted().toList());
        assertEquals("true null null", body.get("hasValue") + " " + body.get("capture") + " " + body.get("renewedAt"));
        assertEquals("NAS_PASSWORD", body.get("name").getAsString());
        assertEquals("[\"nas.example.com\"]", body.get("hosts").toString());

        var listed = json(GET(base())).getAsJsonArray("secrets");
        assertEquals(1, listed.size());
        assertEquals(body, listed.get(0).getAsJsonObject());

        var raw = stored("NAS_PASSWORD");
        assertTrue(raw.startsWith("enc:v1:"), "sealed at rest");
        assertFalse(raw.contains(VALUE));
        assertEquals(VALUE, CredentialCipher.current().open(SkillSecret.VALUE_FIELD, raw).plaintext());
        assertFalse(CredentialCipher.current().open("telegram_binding.bot_token", raw).readable(),
                "a sealed value opens in its own column only");
    }

    @Test
    void replacingOnlyTheHostsKeepsTheValueAndANewValueReplacesIt() {
        assertIsOk(put("NAS_PASSWORD", "{\"value\":\"" + VALUE + "\",\"hosts\":[\"nas.example.com\"]}"));
        var hostsOnly = put("NAS_PASSWORD", "{\"hosts\":[\"nas.example.com\",\"backup.example.com\"]}");
        assertIsOk(hostsOnly);
        assertEquals("[\"nas.example.com\",\"backup.example.com\"]", json(hostsOnly).get("hosts").toString());
        assertEquals(VALUE, CredentialCipher.current().open(SkillSecret.VALUE_FIELD, stored("NAS_PASSWORD")).plaintext());

        assertIsOk(put("NAS_PASSWORD", "{\"value\":\"fake-second-value\",\"hosts\":[\"nas.example.com\"]}"));
        assertEquals("fake-second-value",
                CredentialCipher.current().open(SkillSecret.VALUE_FIELD, stored("NAS_PASSWORD")).plaintext());
        assertEquals(1, rows());
    }

    @Test
    void hostsAreStoredLowercaseOnceEachWithoutATrailingDotOrLeadingZeros() {
        var saved = put("API_KEY", "{\"value\":\"v\",\"hosts\":[\" API.Example.com. \",\"api.example.com\","
                + "\"b.example.com:08443\",\"10.0.0.5\",\"\"]}");
        assertIsOk(saved);
        assertEquals("[\"api.example.com\",\"b.example.com:8443\",\"10.0.0.5\"]", json(saved).get("hosts").toString());
    }

    @Test
    void aWriteTheStoreCannotTakeIsRefusedInPlainWordsAndStoresNothing() {
        var cases = new ArrayList<String[]>();
        cases.add(new String[] {"NAS_PASSWORD", "{\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request A new secret needs a value."});
        cases.add(new String[] {"nas_password", "{\"value\":\"v\",\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request 'nas_password' is not a secret name: use capital letters, digits and underscores,"
                        + " starting with a letter, at most 64 characters, as in NAS_PASSWORD."});
        cases.add(new String[] {"A" + "B".repeat(64), "{\"value\":\"v\",\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request '" + "A" + "B".repeat(64) + "' is not a secret name: use capital letters, digits"
                        + " and underscores, starting with a letter, at most 64 characters, as in NAS_PASSWORD."});
        cases.add(new String[] {"HTTPS_PROXY", "{\"value\":\"v\",\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request 'HTTPS_PROXY' is a name a command's environment already uses; choose another."});
        cases.add(new String[] {"JCLAW_TOKEN", "{\"value\":\"v\",\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request 'JCLAW_TOKEN' is a name a command's environment already uses; choose another."});
        cases.add(new String[] {"API_KEY", "{\"value\":\"\",\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request The value is empty."});
        cases.add(new String[] {"API_KEY", "{\"value\":\"" + "x".repeat(65_537) + "\",\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request The value is longer than 65536 characters."});
        cases.add(new String[] {"API_KEY", "{\"value\":\"v\",\"hosts\":[]}",
                "400 invalid_request Add at least one host the secret may be sent to."});
        cases.add(new String[] {"API_KEY", "{\"value\":\"v\",\"hosts\":[\" \"]}",
                "400 invalid_request Add at least one host the secret may be sent to."});
        for (var bad : List.of("https://nas.example.com", "*.example.com", "nas.example.com/login", "user@nas.example.com",
                "[::1]:443")) {
            cases.add(new String[] {"API_KEY", "{\"value\":\"v\",\"hosts\":[\"" + bad + "\"]}",
                    "400 invalid_request '" + bad + "' is not a host: give the name alone, such as api.example.com or"
                            + " api.example.com:8443, with no https://, path or *."});
        }
        cases.add(new String[] {"API_KEY", "{\"value\":\"v\",\"hosts\":[\"nas.example.com:70000\"]}",
                "400 invalid_request 'nas.example.com:70000' has no valid port."});
        var seventeen = new StringBuilder();
        for (int i = 0; i < 17; i++) seventeen.append(i == 0 ? "" : ",").append("\"h").append(i).append(".example.com\"");
        cases.add(new String[] {"API_KEY", "{\"value\":\"v\",\"hosts\":[" + seventeen + "]}",
                "400 invalid_request A secret may name at most 16 hosts."});
        cases.add(new String[] {"API_KEY", "{\"value\":\"v\",\"hosts\":\"nas.example.com\"}",
                "400 invalid_request 'hosts' must be a list of host names."});
        cases.add(new String[] {"API_KEY", "{\"value\":7,\"hosts\":[\"nas.example.com\"]}",
                "400 invalid_request 'value' must be a string."});
        cases.add(new String[] {"API_KEY", "not json",
                "400 invalid_request The body must be a JSON object with 'hosts' and, for a new secret, 'value'."});

        var wrong = new ArrayList<String>();
        for (var c : cases) {
            var got = errorOf(put(c[0], c[1]));
            if (!got.equals(c[2])) wrong.add(c[0] + " " + c[1].substring(0, Math.min(60, c[1].length())) + " -> " + got);
        }
        assertEquals(List.of(), wrong);
        assertEquals(0, rows());
    }

    @Test
    void sixteenHostsAreTheMostASecretTakes() {
        var sixteen = new StringBuilder();
        for (int i = 0; i < 16; i++) sixteen.append(i == 0 ? "" : ",").append("\"h").append(i).append(".example.com\"");
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[" + sixteen + "]}"));
    }

    @Test
    void anUnknownAgentSkillOrSecretIsNotFoundAndAPathIsNoSkillName() {
        assertEquals("404 not_found No agent 999999999.", errorOf(GET("/api/agents/999999999/skills/demo/secrets")));
        assertEquals("404 not_found Agent '" + agentName + "' has no skill 'absent'.",
                errorOf(GET("/api/agents/" + agentId + "/skills/absent/secrets")));
        assertEquals("400 invalid_request '..' is not a skill name: pass the skill's folder name, not a path.",
                errorOf(GET("/api/agents/" + agentId + "/skills/../secrets")));
        assertEquals("404 not_found No secret 'MISSING' on skill 'demo'.", errorOf(DELETE(base() + "/MISSING")));
    }

    private void declare(String skill, String lines) throws Exception {
        var dir = AgentService.workspacePath(agentName).resolve("skills/" + skill);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + skill + "\ndescription: test skill\n---\n# " + skill
                + "\n\n## Secrets\n\n```secrets\n" + lines + "```\n\nUse `$API_KEY` in each call.\n");
        agents.SkillLoader.clearCache();
    }

    private List<String> offeredSkills() {
        return commitInFreshTx(() -> agents.SkillLoader.loadSkills(agentName).stream().map(s -> s.name()).sorted().toList());
    }

    private String missingOf(String skill) {
        for (var e : JsonParser.parseString(getContent(GET("/api/agents/" + agentId + "/skills"))).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if (o.get("name").getAsString().equals(skill)) return o.get("needsValues").toString();
        }
        return "not listed";
    }

    /** JF-348: the list also names what the skill's SKILL.md declares, and whether the agent holds each. */
    @Test
    void theListNamesWhatTheSkillDeclaresAndWhetherEachIsSet() throws Exception {
        declare("demo", """
                # The secrets this skill needs. No value goes in this file.
                API_KEY=api.example.com          # The service's API key
                export NAS_PASS=NAS.example.com:5001, backup.example.com   # The password itself
                PROXY_PASS=nas.example.com       # Optional. The reverse proxy's password

                BARE=
                lowercase=x.example.com          # not a secret name
                HTTPS_PROXY=x.example.com        # a name the store refuses
                BAD_HOST=https://x.example.com/path   # its example is no host
                API_KEY=other.example.com        # declared twice
                not a declaration line
                """);
        assertIsOk(put("NAS_PASS", "{\"value\":\"v\",\"hosts\":[\"nas.example.com:5001\"]}"));
        assertIsOk(put("UNDECLARED", "{\"value\":\"v\",\"hosts\":[\"a.example.com\"]}"));

        var body = json(GET(base()));
        assertEquals(JsonParser.parseString("""
                [{"name":"API_KEY","description":"The service's API key","exampleHosts":["api.example.com"],"optional":false,"set":false,"setIn":null,"setInState":null,"filledBySignIn":false,"renewed":false,"suggested":null,"unreadableSuggestion":null},
                 {"name":"NAS_PASS","description":"The password itself","exampleHosts":["nas.example.com:5001","backup.example.com"],"optional":false,"set":true,"setIn":"demo","setInState":null,"filledBySignIn":false,"renewed":false,"suggested":null,"unreadableSuggestion":null},
                 {"name":"PROXY_PASS","description":"Optional. The reverse proxy's password","exampleHosts":["nas.example.com"],"optional":true,"set":false,"setIn":null,"setInState":null,"filledBySignIn":false,"renewed":false,"suggested":null,"unreadableSuggestion":null},
                 {"name":"BARE","description":"","exampleHosts":[],"optional":false,"set":false,"setIn":null,"setInState":null,"filledBySignIn":false,"renewed":false,"suggested":null,"unreadableSuggestion":null},
                 {"name":"BAD_HOST","description":"its example is no host","exampleHosts":[],"optional":false,"set":false,"setIn":null,"setInState":null,"filledBySignIn":false,"renewed":false,"suggested":null,"unreadableSuggestion":null}]
                """), body.get("expected"));
        assertEquals("[\"API_KEY\",\"BARE\",\"BAD_HOST\"]", body.get("missing").toString());
        var stored = new ArrayList<String>();
        for (var e : body.getAsJsonArray("secrets")) stored.add(e.getAsJsonObject().get("name").getAsString());
        assertEquals(List.of("NAS_PASS", "UNDECLARED"), stored, "a stored secret the file does not declare is still listed");
    }

    // ==================== a renewed secret's capture rule (JF-374) ====================

    /** The sign-in sends {@link #seedPassword}'s secret as a form field; spelled as an operator might type it. */
    private static final String RULE = "{\"method\":\"post\",\"path\":\"/v1//sessions/current/\",\"from\":\"json\","
            + "\"field\":\"session\",\"credential\":\"DEMO_PASSWORD\",\"credentialIn\":\"Form password\"}";
    private static final String CREDENTIAL = ",\"credential\":\"DEMO_PASSWORD\",\"credentialIn\":\"form password\"";

    /** The secret {@link #RULE}'s sign-in sends: a rule may name only a secret its skill holds. */
    private void seedPassword() {
        assertIsOk(put("DEMO_PASSWORD", "{\"value\":\"fake-demo-password-jf374\",\"hosts\":[\"api.example.com\"]}"));
    }

    /** The operator's rule is stored normalized, listed with the secret, and changed or removed only by a save. */
    @Test
    void aRenewedSecretTakesTheOperatorsRuleWithoutAValueAndListsIt() {
        seedPassword();
        var saved = put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}");
        assertIsOk(saved);
        assertEquals(JsonParser.parseString("""
                {"method":"POST","path":"/v1/sessions/current","from":"json","field":"session","credential":"DEMO_PASSWORD",
                 "credentialIn":"form password"}"""), json(saved).get("capture"));
        assertEquals("false null", json(saved).get("hasValue") + " " + json(saved).get("renewedAt"));
        assertEquals("", stored("SESSION_KEY"), "a renewed secret starts empty, and nothing seals an empty value");

        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\",\"b.example.com\"]}"));
        var listed = listedSecret("SESSION_KEY");
        assertEquals("POST /v1/sessions/current json session using DEMO_PASSWORD in form password", ruleOf(listed),
                "a save without 'capture' keeps the rule");

        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"GET\","
                + "\"path\":\"/v2/token\",\"from\":\"header\",\"field\":\"X-Session\",\"credential\":\"DEMO_PASSWORD\","
                + "\"credentialIn\":\"basic\"}}"));
        assertEquals("GET /v2/token header X-Session using DEMO_PASSWORD in basic", ruleOf(listedSecret("SESSION_KEY")));

        assertEquals("400 invalid_request A secret with no capture rule needs a value.",
                errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":null}")));
        assertIsOk(put("SESSION_KEY", "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"api.example.com\"],"
                + "\"capture\":null}"));
        var plain = listedSecret("SESSION_KEY");
        assertEquals("true null", plain.get("hasValue") + " " + plain.get("capture"));
    }

    private static String ruleOf(JsonObject listed) {
        var rule = listed.getAsJsonObject("capture");
        return rule.get("method").getAsString() + " " + rule.get("path").getAsString() + " " + rule.get("from").getAsString()
                + " " + rule.get("field").getAsString() + " using " + rule.get("credential").getAsString() + " in "
                + rule.get("credentialIn").getAsString();
    }

    @Test
    void aRuleThatIsIncompleteOrMalformedIsRefusedAndStoresNothing() {
        var refused = new ArrayList<String>();
        for (var rule : List.of(
                "{\"path\":\"/v1/s\",\"field\":\"session\"}",
                "{\"method\":\"POST\",\"field\":\"session\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\" \"}",
                "{\"method\":\"PO ST\",\"path\":\"/v1/s\",\"field\":\"session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"v1/s\",\"field\":\"session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"https://api.example.com/v1/s\",\"field\":\"session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"/v1/s?x=1\",\"field\":\"session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"/v1%2Fs\",\"field\":\"session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"from\":\"xml\",\"field\":\"session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"data.session\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"" + "f".repeat(129) + "\"" + CREDENTIAL + "}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credentialIn\":\"json password\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"session_key\","
                        + "\"credentialIn\":\"json password\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                        + "\"credentialIn\":\"xml password\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                        + "\"credentialIn\":\"json\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                        + "\"credentialIn\":\"basic password\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                        + "\"credentialIn\":\"form pass(word)\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                        + "\"credentialIn\":\"header Host\"}",
                "{\"method\":\"POST\",\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                        + "\"credentialIn\":\"header proxy-authorization\"}",
                "\"POST /v1/s json session\"")) {
            refused.add(errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + rule + "}")));
        }
        assertEquals(List.of(
                "400 invalid_request A capture rule needs a method, a path and a field.",
                "400 invalid_request A capture rule needs a method, a path and a field.",
                "400 invalid_request A capture rule needs a method, a path and a field.",
                "400 invalid_request 'PO ST' is not an HTTP method: give one such as POST.",
                "400 invalid_request 'v1/s' is not a path a rule can match: give it as the service receives it, starting"
                        + " with /, such as /v1/sessions/current, with no host, query, space, encoded slash or backslash.",
                "400 invalid_request 'https://api.example.com/v1/s' is not a path a rule can match: give it as the service"
                        + " receives it, starting with /, such as /v1/sessions/current, with no host, query, space, encoded"
                        + " slash or backslash.",
                "400 invalid_request '/v1/s?x=1' is not a path a rule can match: give it as the service receives it,"
                        + " starting with /, such as /v1/sessions/current, with no host, query, space, encoded slash or"
                        + " backslash.",
                "400 invalid_request '/v1%2Fs' is not a path a rule can match: give it as the service receives it, starting"
                        + " with /, such as /v1/sessions/current, with no host, query, space, encoded slash or backslash.",
                "400 invalid_request 'xml' is not where a value can be taken from: give json or header.",
                "400 invalid_request 'data.session' is not a field name: give the JSON field or the header by its name"
                        + " alone, such as session or X-Session.",
                "400 invalid_request '" + "f".repeat(129) + "' is not a field name: give the JSON field or the header by"
                        + " its name alone, such as session or X-Session.",
                "400 invalid_request A capture rule needs the secret its sign-in sends and where it sends it, such as"
                        + " BACKOFFICE_PASSWORD in json password: JClaw takes a value only from a sign-in made with it.",
                "400 invalid_request A capture rule needs the secret its sign-in sends and where it sends it, such as"
                        + " BACKOFFICE_PASSWORD in json password: JClaw takes a value only from a sign-in made with it.",
                "400 invalid_request 'session_key' is not a secret name: give the secret the sign-in sends, such as"
                        + " BACKOFFICE_PASSWORD.",
                "400 invalid_request 'xml password' is not where a sign-in sends a secret: give json, form or header and the"
                        + " field's or header's name, such as json password, form user[password] or header api-key, or"
                        + " basic for the password of Basic auth. A JSON field is one at the top level. A header the"
                        + " secrets proxy does not forward, such as Host, cannot be one.",
                "400 invalid_request 'json' is not where a sign-in sends a secret: give json, form or header and the"
                        + " field's or header's name, such as json password, form user[password] or header api-key, or"
                        + " basic for the password of Basic auth. A JSON field is one at the top level. A header the"
                        + " secrets proxy does not forward, such as Host, cannot be one.",
                "400 invalid_request 'basic password' is not where a sign-in sends a secret: give json, form or header and the"
                        + " field's or header's name, such as json password, form user[password] or header api-key, or"
                        + " basic for the password of Basic auth. A JSON field is one at the top level. A header the"
                        + " secrets proxy does not forward, such as Host, cannot be one.",
                "400 invalid_request 'form pass(word)' is not where a sign-in sends a secret: give json, form or header and the"
                        + " field's or header's name, such as json password, form user[password] or header api-key, or"
                        + " basic for the password of Basic auth. A JSON field is one at the top level. A header the"
                        + " secrets proxy does not forward, such as Host, cannot be one.",
                "400 invalid_request 'header Host' is not where a sign-in sends a secret: give json, form or header and the"
                        + " field's or header's name, such as json password, form user[password] or header api-key, or"
                        + " basic for the password of Basic auth. A JSON field is one at the top level. A header the"
                        + " secrets proxy does not forward, such as Host, cannot be one.",
                "400 invalid_request 'header proxy-authorization' is not where a sign-in sends a secret: give json, form or header and the"
                        + " field's or header's name, such as json password, form user[password] or header api-key, or"
                        + " basic for the password of Basic auth. A JSON field is one at the top level. A header the"
                        + " secrets proxy does not forward, such as Host, cannot be one.",
                "400 invalid_request 'capture' must be an object with 'method', 'path', 'from', 'field', 'credential' and"
                        + " 'credentialIn', or null."),
                refused);
        assertEquals(0L, rows());
        assertEquals("400 invalid_request A new secret needs a value.",
                errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"]}")), "no rule, no value");
        seedPassword();
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"POST\","
                + "\"path\":\"/" + "p".repeat(1023) + "\",\"field\":\"" + "f".repeat(128) + "\"" + CREDENTIAL + "}}"));
        assertEquals("400 invalid_request '/" + "p".repeat(1024) + "' is not a path a rule can match: give it as the service"
                + " receives it, starting with /, such as /v1/sessions/current, with no host, query, space, encoded slash or"
                + " backslash.", errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":"
                + "{\"method\":\"POST\",\"path\":\"/" + "p".repeat(1024) + "\",\"field\":\"session\"" + CREDENTIAL
                + "}}")));
    }

    /** A cookie is no session key a rule can take: the jar that holds it is the skill's own. */
    @Test
    void aRuleOnACookieHeaderIsRefused() {
        var refused = new ArrayList<String>();
        for (var field : List.of("Set-Cookie", "set-cookie", "Cookie", "SET-COOKIE2")) {
            refused.add(errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"POST\","
                    + "\"path\":\"/v1/s\",\"from\":\"header\",\"field\":\"" + field + "\"" + CREDENTIAL + "}}")));
        }
        assertEquals(List.of(
                "400 invalid_request 'Set-Cookie' is a cookie header: a rule cannot take a cookie, which the skill keeps in"
                        + " its own cookie jar.",
                "400 invalid_request 'set-cookie' is a cookie header: a rule cannot take a cookie, which the skill keeps in"
                        + " its own cookie jar.",
                "400 invalid_request 'Cookie' is a cookie header: a rule cannot take a cookie, which the skill keeps in"
                        + " its own cookie jar.",
                "400 invalid_request 'SET-COOKIE2' is a cookie header: a rule cannot take a cookie, which the skill keeps in"
                        + " its own cookie jar."), refused);
        assertEquals(0L, rows());
        seedPassword();
        assertEquals(200, (int) put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"POST\","
                + "\"path\":\"/v1/s\",\"from\":\"json\",\"field\":\"Set-Cookie\"" + CREDENTIAL + "}}").status,
                "a JSON field of that name is no cookie");
    }

    /** A line whose suggested rule the store would refuse still declares the secret, and says what it suggested. */
    @Test
    void aSuggestionThatCannotBeReadIsListedAsText() throws Exception {
        declare("demo", """
                renewed SESSION_KEY=api.example.com from POST /v1/sessions xml session using SESSION_KEY in header api-key   # The session key
                renewed COOKIE_KEY=api.example.com from POST /v1/sessions header Set-Cookie using COOKIE_KEY in basic
                renewed OLD_KEY=api.example.com from POST /v1/sessions json session   # No credential
                renewed BARE_KEY=api.example.com   # No rule
                """);
        var expected = json(GET(base())).getAsJsonArray("expected");
        var got = new ArrayList<String>();
        for (var e : expected) {
            var o = e.getAsJsonObject();
            got.add(o.get("name").getAsString() + " " + o.get("suggested") + " " + o.get("unreadableSuggestion"));
        }
        assertEquals(List.of("SESSION_KEY null \"POST /v1/sessions xml session using SESSION_KEY in header api-key\"",
                "COOKIE_KEY null \"POST /v1/sessions header Set-Cookie using COOKIE_KEY in basic\"",
                "OLD_KEY null \"POST /v1/sessions json session\"", "BARE_KEY null null"), got);
    }

    /**
     * A renewed secret with no value yet is its own skill's alone: another skill declaring the same name as a plain
     * secret still needs it until a value is stored, and is told the first skill's sign-in fills it.
     */
    @Test
    void anEmptyRenewedSecretIsNotProvidedToAnotherSkillUntilItHasAValue() throws Exception {
        declare("demo", "renewed SESSION_KEY=api.example.com from POST /v1/sessions json session   # The session key\n");
        declare("other", "SESSION_KEY=api.example.com   # The session key\n");
        seedPassword();
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));

        assertEquals("[]", missingOf("demo"));
        assertEquals("[\"SESSION_KEY\"]", missingOf("other"));
        var otherView = json(GET("/api/agents/" + agentId + "/skills/other/secrets"));
        var row = otherView.getAsJsonArray("expected").get(0).getAsJsonObject();
        assertEquals("false \"demo\" \"offered\" true", row.get("set") + " " + row.get("setIn") + " "
                        + row.get("setInState") + " " + row.get("filledBySignIn"),
                "named as filled by demo's sign-in, so the panel offers no Add the store would refuse");
        assertEquals(List.of("demo"), offeredSkills());
        assertEquals("{\"needsValues\":[\"SESSION_KEY\"],\"heldBySwitchedOff\":{},\"heldByNotOffered\":{},"
                + "\"filledBySignIn\":{\"SESSION_KEY\":{\"skill\":\"demo\",\"state\":\"offered\"}},\"withheld\":true}",
                needsOf("other"), "the Chat notice names demo's sign-in, not a value to add");

        assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo", "application/json", "{\"enabled\":false}"));
        assertEquals("{\"needsValues\":[\"SESSION_KEY\"],\"heldBySwitchedOff\":{},\"heldByNotOffered\":{},"
                + "\"filledBySignIn\":{\"SESSION_KEY\":{\"skill\":\"demo\",\"state\":\"switchedOff\"}},\"withheld\":true}",
                needsOf("other"));
        assertEquals("\"switchedOff\"", json(GET("/api/agents/" + agentId + "/skills/other/secrets"))
                .getAsJsonArray("expected").get(0).getAsJsonObject().get("setInState").toString());
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo", "application/json", "{\"enabled\":true}"));

        var own = json(GET(base())).getAsJsonArray("expected").get(0).getAsJsonObject();
        assertEquals("true \"demo\" null true", own.get("set") + " " + own.get("setIn") + " " + own.get("setInState")
                        + " " + own.get("filledBySignIn"), "demo's own empty renewed secret is demo's to fill, with no state");
        declare("demo", "SESSION_KEY=api.example.com   # The session key, declared plain\n");
        assertEquals("[]", missingOf("demo"), "a plain line for its own empty renewed secret leaves demo runnable");
        assertEquals(List.of("demo"), offeredSkills());

        assertIsOk(put("SESSION_KEY", "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("[]", missingOf("other"));
        assertEquals(List.of("demo", "other"), offeredSkills());
    }

    /** A renewed secret starts empty, so the skill is runnable without it, and its line's rule is offered as a hint. */
    @Test
    void aDeclaredRenewedSecretIsNeverMissingAndItsSuggestedRuleIsListed() throws Exception {
        declare("demo", """
                API_KEY=api.example.com   # The service's API key
                renewed SESSION_KEY=api.example.com from POST /v1/sessions/current json session using API_KEY in form key   # The session key
                """);
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        var body = json(GET(base()));
        assertEquals(JsonParser.parseString("""
                {"name":"SESSION_KEY","description":"The session key","exampleHosts":["api.example.com"],"optional":false,
                 "set":false,"setIn":null,"setInState":null,"filledBySignIn":false,"renewed":true,
                 "suggested":{"method":"POST","path":"/v1/sessions/current","from":"json","field":"session",
                              "credential":"API_KEY","credentialIn":"form key"},
                 "unreadableSuggestion":null}"""),
                body.getAsJsonArray("expected").get(1));
        assertEquals("[]", body.get("missing").toString());
        assertEquals(List.of("demo"), offeredSkills());
        assertEquals(0L, (long) commitInFreshTx(() -> SkillSecret.count("agent.id = ?1 AND name = 'SESSION_KEY'", agentId)),
                "a suggestion stores nothing");
    }

    /** A save is logged with its rule and never a value, and says the hosts changed only when the set of them did. */
    @Test
    void savingARuleIsLoggedWithTheRuleAndNoValue() {
        seedPassword();
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));
            assertIsOk(put("SESSION_KEY", "{\"value\":\"" + VALUE + "\",\"hosts\":[\"api.example.com\"]}"));
            assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":null}"));
            assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\",\"b.example.com\"],\"capture\":" + RULE + "}"));
            assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"b.example.com\",\"api.example.com\"]}"));
        });
        assertEquals(List.of(
                "Secret SESSION_KEY added for skill demo, sent only to api.example.com, renewed from the answer to POST"
                        + " /v1/sessions/current json session using DEMO_PASSWORD in form password",
                "Secret SESSION_KEY replaced for skill demo, sent only to api.example.com",
                "Secret SESSION_KEY kept for skill demo, sent only to api.example.com, no longer renewed",
                "Secret SESSION_KEY kept, hosts changed for skill demo, sent only to api.example.com, b.example.com, renewed"
                        + " from the answer to POST /v1/sessions/current json session using DEMO_PASSWORD in form password",
                "Secret SESSION_KEY kept for skill demo, sent only to b.example.com, api.example.com"),
                events.stream().map(EventLogger.Captured::message).toList());
    }

    @Test
    void aSkillWithoutTheBlockDeclaresNothingAndIsRunnable() throws Exception {
        var dir = AgentService.workspacePath(agentName).resolve("skills/demo");
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: demo\ndescription: test skill\n---\n# demo\n");
        agents.SkillLoader.clearCache();
        var body = json(GET(base()));
        assertEquals("[]", body.get("expected").toString());
        assertEquals("[]", body.get("missing").toString());
        assertEquals("[]", missingOf("demo"));
        assertEquals(List.of("demo"), offeredSkills());
    }

    /** A skill is not runnable, and the agent is not offered it, until every required secret it declares is provided. */
    @Test
    void aSkillIsNotRunnableUntilEachRequiredSecretIsProvided() throws Exception {
        declare("demo", """
                API_KEY=api.example.com     # The service's API key
                PROXY_PASS=api.example.com  # optional: the proxy's password
                """);
        assertEquals("[\"API_KEY\"]", missingOf("demo"));
        assertEquals(List.of(), offeredSkills());

        assertIsOk(put("PROXY_PASS", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("[\"API_KEY\"]", missingOf("demo"), "an optional secret does not make a skill runnable");
        assertEquals(List.of(), offeredSkills());

        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("[]", missingOf("demo"));
        assertEquals(List.of("demo"), offeredSkills());

        assertIsOk(DELETE(base() + "/API_KEY"));
        assertEquals("[\"API_KEY\"]", missingOf("demo"));
        assertEquals(List.of(), offeredSkills(), "deleting a required secret makes the skill not runnable again");
    }

    /** A name is used once on an agent, so a secret another skill holds counts as provided. */
    @Test
    void aSecretHeldByAnotherSkillOfTheAgentCountsAsProvided() throws Exception {
        declare("demo", "SHARED_PASS=api.example.com   # The shared password\n");
        declare("other", "SHARED_PASS=api.example.com   # The shared password\n");
        assertEquals(List.of(), offeredSkills());

        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/SHARED_PASS", "application/json",
                "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        var expected = json(GET(base())).getAsJsonArray("expected").get(0).getAsJsonObject();
        assertEquals("{\"name\":\"SHARED_PASS\",\"description\":\"The shared password\",\"exampleHosts\":[\"api.example.com\"],"
                + "\"optional\":false,\"set\":true,\"setIn\":\"other\",\"setInState\":\"offered\",\"filledBySignIn\":false,\"renewed\":false,\"suggested\":null,\"unreadableSuggestion\":null}",
                expected.toString());
        assertEquals("[]", missingOf("demo"));
        assertEquals(List.of("demo", "other"), offeredSkills());
    }

    /** A command gets a secret only from a skill that is switched on, so a holder that is off provides nothing. */
    @Test
    void aSecretHeldByASkillThatIsSwitchedOffDoesNotCount() throws Exception {
        declare("demo", "SHARED_PASS=api.example.com   # The shared password\n");
        declare("other", "SHARED_PASS=api.example.com   # The shared password\n");
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/SHARED_PASS", "application/json",
                "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("[]", missingOf("demo"));
        assertEquals(List.of("SHARED_PASS"), granted("echo $SHARED_PASS"));

        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other", "application/json", "{\"enabled\":false}"));

        assertEquals(List.of(), granted("echo $SHARED_PASS"), "the skill holding it is switched off");
        assertEquals("[\"SHARED_PASS\"]", missingOf("demo"));
        var body = json(GET(base()));
        assertEquals("{\"name\":\"SHARED_PASS\",\"description\":\"The shared password\",\"exampleHosts\":[\"api.example.com\"],"
                + "\"optional\":false,\"set\":false,\"setIn\":\"other\",\"setInState\":\"switchedOff\",\"filledBySignIn\":false,\"renewed\":false,\"suggested\":null,\"unreadableSuggestion\":null}",
                body.getAsJsonArray("expected").get(0).getAsJsonObject().toString());
        assertEquals("[\"SHARED_PASS\"]", body.get("missing").toString());
        assertEquals(List.of(), offeredSkills());
        assertEquals("[]", missingOf("other"), "the skill that holds the secret needs nothing, switched off or not");
    }

    /** The secret a rule says the sign-in sends must be one of the skill's own: another skill's, or none, is refused. */
    @Test
    void aRuleNamingASecretTheSkillDoesNotHoldIsRefused() throws Exception {
        declare("other", "OTHER_PASS=api.example.com   # Another skill's password\n");
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/OTHER_PASS", "application/json",
                "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        var refused = new ArrayList<String>();
        for (var credential : List.of("OTHER_PASS", "NO_SUCH_PASS")) {
            refused.add(errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"POST\","
                    + "\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"" + credential + "\","
                    + "\"credentialIn\":\"json password\"}}")));
        }
        assertEquals(List.of(
                "400 invalid_request OTHER_PASS is not a secret of skill 'demo': the rule names the secret its sign-in"
                        + " sends, so add that secret to this skill first, or name SESSION_KEY itself for a sign-in that"
                        + " sends the renewed key.",
                "400 invalid_request NO_SUCH_PASS is not a secret of skill 'demo': the rule names the secret its sign-in"
                        + " sends, so add that secret to this skill first, or name SESSION_KEY itself for a sign-in that"
                        + " sends the renewed key."), refused);
        assertEquals(1L, rows(), "only the other skill's secret is stored");

        assertIsOk(put("DEMO_PASS", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        var saved = put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"POST\","
                + "\"path\":\"/v1/s\",\"field\":\"session\",\"credential\":\"DEMO_PASS\",\"credentialIn\":\"FORM pass\"}}");
        assertIsOk(saved);
        assertEquals("POST /v1/s json session using DEMO_PASS in form pass", ruleOf(json(saved)));
    }

    /** When the proxy last renewed the value is cleared by a new rule, which took no value yet, and kept by the same one. */
    @Test
    void aChangedRuleClearsWhenTheValueWasLastRenewed() {
        seedPassword();
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));
        assertTrue(commitInFreshTx(() -> services.SkillSecrets.storeCaptured(Agent.findById(agentId), "demo",
                "SESSION_KEY", services.SkillSecrets.Capture.of("POST", "/v1/sessions/current", "json", "session",
                        "DEMO_PASSWORD", "form password"), "fake-captured-session-jf374")));
        assertFalse(listedSecret("SESSION_KEY").get("renewedAt").isJsonNull());

        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));
        assertFalse(listedSecret("SESSION_KEY").get("renewedAt").isJsonNull(), "the same rule saved again");
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":{\"method\":\"POST\","
                + "\"path\":\"/v1/sessions/current\",\"field\":\"token\"" + CREDENTIAL + "}}"));
        var changed = listedSecret("SESSION_KEY");
        assertEquals("true null", changed.get("hasValue") + " " + changed.get("renewedAt"),
                "the value stays, no longer one this rule renewed");
    }

    /**
     * A captured value and then a typed one saved in the same session: the typed one is the operator's save, so the
     * secret's set-at moves, which a mark left over from the capture would stop.
     */
    @Test
    void aValueTypedAfterACaptureInOneSessionMovesWhenItWasSet() {
        seedPassword();
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));
        var captured = java.time.Instant.parse("2026-11-13T09:00:00Z");
        var typed = java.time.Instant.parse("2026-11-13T10:00:00Z");
        commitInFreshTx(() -> {
            Agent owner = Agent.findById(agentId);
            var rule = services.SkillSecrets.Capture.of("POST", "/v1/sessions/current", "json", "session", "DEMO_PASSWORD",
                    "form password");
            utils.AppClock.runWith(java.time.Clock.fixed(captured, java.time.ZoneOffset.UTC), () -> {
                services.SkillSecrets.storeCaptured(owner, "demo", "SESSION_KEY", rule, "fake-captured-session-jf374");
                JPA.em().flush();
            });
            utils.AppClock.runWith(java.time.Clock.fixed(typed, java.time.ZoneOffset.UTC), () -> {
                services.SkillSecrets.put(owner, "demo", "SESSION_KEY", "fake-typed-session-jf374",
                        List.of("api.example.com"));
                JPA.em().flush();
            });
            return null;
        });
        var listed = listedSecret("SESSION_KEY");
        assertEquals(typed + " null", listed.get("setAt").getAsString() + " " + listed.get("renewedAt"));
    }

    private JsonObject listedSecret(String name) {
        for (var e : json(GET(base())).getAsJsonArray("secrets")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject();
        }
        throw new AssertionError("not listed: " + name);
    }

    /** A sign-in that sends the renewed key itself can renew only a key the secret already holds. */
    @Test
    void aRuleWhoseSignInSendsTheRenewedSecretItselfNeedsAValue() {
        var self = "{\"method\":\"POST\",\"path\":\"/v1/refresh\",\"field\":\"session\",\"credential\":\"SESSION_KEY\","
                + "\"credentialIn\":\"header api-key\"}";
        assertEquals("400 invalid_request A rule whose sign-in sends SESSION_KEY itself needs a value for SESSION_KEY to"
                + " send: enter its current value too, or name the secret the sign-in sends, such as its password.",
                errorOf(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + self + "}")));
        assertEquals(0L, rows());

        var saved = put("SESSION_KEY", "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"api.example.com\"],"
                + "\"capture\":" + self + "}");
        assertIsOk(saved);
        assertEquals("true POST /v1/refresh json session using SESSION_KEY in header api-key",
                json(saved).get("hasValue") + " " + ruleOf(json(saved)));
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"b.example.com\"],\"capture\":" + self + "}"));
        assertEquals("[\"b.example.com\"] true", listedSecret("SESSION_KEY").get("hosts") + " "
                + listedSecret("SESSION_KEY").get("hasValue"), "a save keeping the value it holds needs none typed");
    }

    /** The secret a rule's sign-in sends cannot be deleted while the rule names it: the rule could never take a value. */
    @Test
    void deletingTheSecretARulesSignInSendsIsRefusedUntilTheRuleGoes() {
        seedPassword();
        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));

        assertEquals("400 invalid_request DEMO_PASSWORD is the secret the capture rule of SESSION_KEY says its sign-in"
                + " sends: change or remove that rule first.", errorOf(DELETE(base() + "/DEMO_PASSWORD")));
        assertEquals(2L, rows());
        assertEquals("POST /v1/sessions/current json session using DEMO_PASSWORD in form password",
                ruleOf(listedSecret("SESSION_KEY")));

        assertIsOk(DELETE(base() + "/SESSION_KEY"));
        assertIsOk(DELETE(base() + "/DEMO_PASSWORD"));
        assertEquals(0L, rows());
    }

    /**
     * A name set in a skill whose folder is gone still belongs to it, so another skill cannot add it: the panel names
     * that skill as no longer installed, and offers no Add the store would refuse.
     */
    @Test
    void aNameSetInASkillNoLongerInstalledIsNamedSoAndCannotBeAdded() throws Exception {
        declare("demo", "SHARED_KEY=api.example.com   # The shared key\n");
        declare("other", "SHARED_KEY=api.example.com   # The shared key\n");
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/SHARED_KEY", "application/json",
                "{\"value\":\"fake-shared-key-jf374\",\"hosts\":[\"api.example.com\"]}"));
        services.SkillPromotionService.deleteRecursive(AgentService.workspacePath(agentName).resolve("skills/other"));
        agents.SkillLoader.clearCache();

        var row = json(GET(base())).getAsJsonArray("expected").get(0).getAsJsonObject();
        assertEquals("false \"other\" \"notInstalled\" false", row.get("set") + " " + row.get("setIn") + " "
                + row.get("setInState") + " " + row.get("filledBySignIn"));
        assertEquals("400 invalid_request Skill 'other' on this agent already has a secret named SHARED_KEY. A command"
                + " finds a secret by its name, so each name is used once on an agent; that skill's commands can use it"
                + " too.", errorOf(put("SHARED_KEY", "{\"value\":\"fake-own-key-jf374\",\"hosts\":[\"api.example.com\"]}")),
                "the Add the panel no longer offers");
    }

    /**
     * A holder whose sign-in fills a name is not offered while a tool it needs is off: the Secrets panel and the skill
     * list say so, rather than that its sign-in has yet to run.
     */
    @Test
    void aSignInHolderWhoseToolIsOffIsNamedNotOffered() throws Exception {
        ToolRegistrySync.canonicalForTest();
        try {
            declareWithTool("demo", "filesystem",
                    "renewed SESSION_KEY=api.example.com   # The session key\n");
            declare("other", "SESSION_KEY=api.example.com   # The session key\n");
            seedPassword();
            assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));
            assertEquals("\"offered\"", json(GET("/api/agents/" + agentId + "/skills/other/secrets"))
                    .getAsJsonArray("expected").get(0).getAsJsonObject().get("setInState").toString());

            switchToolOff("filesystem");

            assertEquals("\"notOffered\"", json(GET("/api/agents/" + agentId + "/skills/other/secrets"))
                    .getAsJsonArray("expected").get(0).getAsJsonObject().get("setInState").toString());
            assertEquals("{\"needsValues\":[\"SESSION_KEY\"],\"heldBySwitchedOff\":{},\"heldByNotOffered\":{},"
                    + "\"filledBySignIn\":{\"SESSION_KEY\":{\"skill\":\"demo\",\"state\":\"notOffered\"}},\"withheld\":true}",
                    needsOf("other"));
        } finally {
            ToolRegistrySync.release();
        }
    }

    /** What the agent's skill list says the skill needs, who holds each value, and whether it is withheld for them. */
    private String needsOf(String skill) {
        for (var e : JsonParser.parseString(getContent(GET("/api/agents/" + agentId + "/skills"))).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if (!o.get("name").getAsString().equals(skill)) continue;
            var out = new JsonObject();
            for (var key : List.of("needsValues", "heldBySwitchedOff", "heldByNotOffered", "filledBySignIn", "withheld")) {
                out.add(key, o.get(key));
            }
            return out.toString();
        }
        return "not listed";
    }

    private void declareWithTool(String skill, String tool, String lines) throws Exception {
        var dir = AgentService.workspacePath(agentName).resolve("skills/" + skill);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + skill + "\ndescription: test skill\nmetadata:\n"
                + "  jclaw-tools: " + tool + "\n---\n# " + skill + "\n\n```secrets\n" + lines + "```\n");
        agents.SkillLoader.clearCache();
    }

    private void switchToolOff(String tool) {
        commitInFreshTx(() -> {
            var off = new models.AgentToolConfig();
            off.agent = Agent.findById(agentId);
            off.toolName = tool;
            off.enabled = false;
            off.save();
            agents.ToolRegistry.invalidateDisabledToolsCache(off.agent);
            return null;
        });
        agents.SkillLoader.clearCache();
    }

    /**
     * JF-327: the list names who holds each missing value, switched off or on but not offered, and flags a skill the
     * agent is withheld only for values, not one it also lacks a tool for.
     */
    @Test
    void theAgentsSkillListNamesWhoHoldsAMissingValueAndWhichSkillsAreWithheldForValues() throws Exception {
        ToolRegistrySync.canonicalForTest();
        try {
            declare("demo", "SHARED_PASS=api.example.com   # The shared password\nAPI_KEY=api.example.com   # Its own key\n");
            declare("other", "SHARED_PASS=api.example.com   # The shared password\n");
            declareWithTool("tooled", "filesystem", "TOOL_KEY=tool.example.com   # The key\n");
            assertIsOk(PUT("/api/agents/" + agentId + "/skills/other/secrets/SHARED_PASS", "application/json",
                    "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
            assertEquals("{\"needsValues\":[\"API_KEY\"],\"heldBySwitchedOff\":{},\"heldByNotOffered\":{},\"filledBySignIn\":{},\"withheld\":true}",
                    needsOf("demo"), "a value a switched-on skill holds is given to every skill");

            assertIsOk(PUT("/api/agents/" + agentId + "/skills/other", "application/json", "{\"enabled\":false}"));

            assertEquals("{\"needsValues\":[\"SHARED_PASS\",\"API_KEY\"],\"heldBySwitchedOff\":{\"SHARED_PASS\":\"other\"},"
                    + "\"heldByNotOffered\":{},\"filledBySignIn\":{},\"withheld\":true}", needsOf("demo"));
            assertEquals("{\"needsValues\":[],\"heldBySwitchedOff\":{},\"heldByNotOffered\":{},\"filledBySignIn\":{},\"withheld\":false}",
                    needsOf("other"));

            assertIsOk(PUT("/api/agents/" + agentId + "/skills/other", "application/json", "{\"enabled\":true}"));
            declareWithTool("other", "filesystem", "SHARED_PASS=api.example.com   # The shared password\n");
            switchToolOff("filesystem");

            assertEquals("{\"needsValues\":[\"SHARED_PASS\",\"API_KEY\"],\"heldBySwitchedOff\":{},"
                    + "\"heldByNotOffered\":{\"SHARED_PASS\":\"other\"},\"filledBySignIn\":{},\"withheld\":true}", needsOf("demo"));
            assertEquals("{\"needsValues\":[\"TOOL_KEY\"],\"heldBySwitchedOff\":{},\"heldByNotOffered\":{},\"filledBySignIn\":{},\"withheld\":false}",
                    needsOf("tooled"), "a skill that also lacks a tool is not withheld for its value alone");
        } finally {
            ToolRegistrySync.release();
        }
    }

    /** Switching a skill off does not make it look as if its own secrets were missing. */
    @Test
    void aSwitchedOffSkillIsNotFlaggedForTheSecretsItHolds() throws Exception {
        declare("demo", "API_KEY=api.example.com   # The service's API key\n");
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo", "application/json", "{\"enabled\":false}"));

        assertEquals("[]", missingOf("demo"));
        assertEquals("[]", json(GET(base())).get("missing").toString());
        assertEquals(List.of(), offeredSkills(), "it is still switched off");
    }

    /**
     * JF-388: the switch takes effect at once, for what the agent is offered and for the secrets and settings the
     * next command is given.
     */
    @Test
    void switchingASkillOffStopsItAndItsSecretsAtOnceAndSwitchingItOnBringsThemBack() throws Exception {
        declare("demo", "API_KEY=api.example.com   # The service's API key\n"
                + "setting DEMO_URL=https://demo.example.com   # The demo address\n");
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertIsOk(putSetting("DEMO_URL", "{\"value\":\"https://demo.example.com\"}"));
        // Read once first, so a cached scan is what the switch has to replace.
        assertEquals(List.of("demo"), offeredSkills());
        assertEquals(List.of("API_KEY"), granted("bash skills/demo/run.sh"));
        assertEquals(Map.of("DEMO_URL", "https://demo.example.com"), settingsGiven("bash skills/demo/run.sh"));

        assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo", "application/json", "{\"enabled\":false}"));
        assertEquals(List.of(), offeredSkills());
        assertEquals(List.of(), granted("bash skills/demo/run.sh"));
        assertEquals(Map.of(), settingsGiven("bash skills/demo/run.sh"));

        assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo", "application/json", "{\"enabled\":true}"));
        assertEquals(List.of("demo"), offeredSkills());
        assertEquals(List.of("API_KEY"), granted("bash skills/demo/run.sh"));
        assertEquals(Map.of("DEMO_URL", "https://demo.example.com"), settingsGiven("bash skills/demo/run.sh"));
    }

    private Map<String, String> settingsGiven(String command) {
        return commitInFreshTx(() -> SecretGrants.settingsFor(Agent.findById(agentId), command,
                AgentService.workspacePath(agentName)));
    }

    private List<String> granted(String command) {
        return commitInFreshTx(() -> {
            try (SecretGrant grant = SecretGrants.forCommand(Agent.findById(agentId), command,
                    AgentService.workspacePath(agentName), false)) {
                return grant == null ? List.<String>of() : grant.names();
            }
        });
    }

    // ==================== a running command's secrets (JF-390) ====================

    private static String secretJson(String value, SecretProxyFixture stub) {
        return "{\"value\":\"" + value + "\",\"hosts\":[\"" + SecretProxyFixture.BOUND + ":" + stub.port() + "\"]}";
    }

    /** The grant a running command holds, opened as the shell tool opens one, its service the stub. */
    @MustBeClosed
    private SecretGrant running(SecretProxyFixture stub, String command) {
        return running(stub, agentId, agentName, command, () -> {});
    }

    /** As {@link #running(SecretProxyFixture, String)}, for agent {@code id}, with {@code midScan} run in its skill scan. */
    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // opened in a fresh transaction and returned for the test to close
    private static SecretGrant running(SecretProxyFixture stub, Long id, String name, String command, Runnable midScan) {
        var grant = commitInFreshTx(() -> agents.SkillLoader.callWithScanHookForTest(midScan, () ->
                SecretGrants.callWithUpstreamForTest(stub.upstream(), () -> SecretGrants.forCommand(
                        Agent.findById(id), command, AgentService.workspacePath(name), false))));
        assertNotNull(grant, "the fixture must give the command a secret, or the test proves nothing");
        return grant;
    }

    /** The command sends {@code name}'s stand-in and the stub receives {@code value}. */
    private static void assertSent(SecretGrant grant, SecretProxyFixture stub, String name, String value) throws Exception {
        var placeholder = grant.environment().get(name);
        assertEquals("200 method=GET\ntarget=/echo\nx-api-key=" + placeholder + "\nauthorization=null\nbody=",
                stub.send(grant, placeholder));
        assertEquals(value, stub.received.getLast().header("X-Api-Key"));
    }

    /** The command sends {@code name}'s stand-in, the proxy refuses it naming the secret, and nothing reaches the stub. */
    private static void assertNoLongerGiven(SecretGrant grant, SecretProxyFixture stub, String name) throws Exception {
        int before = stub.received.size();
        assertEquals("403 JClaw refused this request: secret " + name + " is no longer given to this command: what gave it"
                + " changed after the command started, or could not be re-checked.\n",
                stub.send(grant, grant.environment().get(name)));
        assertEquals(before, stub.received.size(), "a refused request reaches nothing");
    }

    private Http.Response switchSkill(String skill, boolean enabled) {
        return PUT("/api/agents/" + agentId + "/skills/" + skill, "application/json", "{\"enabled\":" + enabled + "}");
    }

    @Test
    void switchingASkillOffStopsARunningCommandSendingItsSecretAndTheValueStaysMasked() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            try (var grant = running(stub, "bash skills/demo/run.sh")) {
                assertSent(grant, stub, "API_KEY", VALUE);

                assertIsOk(switchSkill("demo", false));

                assertNoLongerGiven(grant, stub, "API_KEY");
                assertEquals("200 method=GET\ntarget=/echo\nx-api-key=" + grant.environment().get("API_KEY")
                        + "\nauthorization=null\nbody=", stub.send(grant, VALUE),
                        "a value a service sends back still reaches the command as its stand-in");
                assertEquals(List.of(), granted("bash skills/demo/run.sh"));
            }
        }
    }

    @Test
    void switchingOneSkillOffLeavesARunningCommandTheSecretsOfAnother() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            declare("demo-two", "TWO_KEY=two.example.com   # The other service's key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo-two/secrets/TWO_KEY", "application/json",
                    secretJson("fake-two-value-jf390", stub)));
            try (var grant = running(stub, "echo $API_KEY $TWO_KEY")) {
                assertEquals(List.of("API_KEY", "TWO_KEY"), grant.names());

                assertIsOk(switchSkill("demo", false));

                assertNoLongerGiven(grant, stub, "API_KEY");
                assertSent(grant, stub, "TWO_KEY", "fake-two-value-jf390");
            }
        }
    }

    @Test
    void deletingASecretStopsARunningCommandSendingIt() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            try (var grant = running(stub, "echo $API_KEY")) {
                assertSent(grant, stub, "API_KEY", VALUE);

                assertIsOk(DELETE(base() + "/API_KEY"));

                assertNoLongerGiven(grant, stub, "API_KEY");
            }
        }
    }

    /** A new value or new hosts stop the old stand-in; storing the same value and hosts again stops nothing. */
    @Test
    void replacingASecretStopsItsOldStandInAndStoringItUnchangedKeepsIt() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            try (var first = running(stub, "echo $API_KEY")) {
                assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
                assertSent(first, stub, "API_KEY", VALUE);

                assertIsOk(put("API_KEY", secretJson("fake-second-value-jf390", stub)));
                assertNoLongerGiven(first, stub, "API_KEY");
            }
            try (var second = running(stub, "echo $API_KEY")) {
                assertSent(second, stub, "API_KEY", "fake-second-value-jf390");

                assertIsOk(put("API_KEY", "{\"hosts\":[\"" + SecretProxyFixture.BOUND + ":" + stub.port()
                        + "\",\"backup.example.com\"]}"));
                assertNoLongerGiven(second, stub, "API_KEY");
            }
        }
    }

    @Test
    void removingASkillStopsARunningCommandSendingItsSecret() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            try (var grant = running(stub, "echo $API_KEY")) {
                assertSent(grant, stub, "API_KEY", VALUE);

                assertIsOk(DELETE("/api/agents/" + agentId + "/skills/demo/delete"));

                assertNoLongerGiven(grant, stub, "API_KEY");
            }
        }
    }

    @Test
    void deletingTheAgentStopsItsRunningCommandsSecrets() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            try (var grant = running(stub, "echo $API_KEY")) {
                assertSent(grant, stub, "API_KEY", VALUE);

                assertIsOk(DELETE("/api/agents/" + agentId));

                assertNoLongerGiven(grant, stub, "API_KEY");
            }
        }
    }

    /**
     * A change that commits after the command's secrets were read and before its grant opens: the grant is checked
     * again once it is open, so the old value's stand-in is refused.
     */
    @Test
    void aChangeCommittedWhileACommandsSecretsAreChosenLeavesItsOldStandInRefused() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            agents.SkillLoader.clearCache();
            var replaced = new AtomicBoolean();
            try (var grant = running(stub, agentId, agentName, "echo $API_KEY", () -> {
                if (replaced.compareAndSet(false, true)) assertIsOk(put("API_KEY", secretJson("fake-second-value-jf390", stub)));
            })) {
                assertTrue(replaced.get(), "the secret must be replaced while the command's secrets are chosen");
                assertEquals(List.of(), grant.names(), "the command holds none of what it was chosen");
                assertNoLongerGiven(grant, stub, "API_KEY");
            }
            try (var next = running(stub, "echo $API_KEY")) {
                assertSent(next, stub, "API_KEY", "fake-second-value-jf390");
            }
        }
    }

    @Test
    void switchingASkillOffOnOneAgentLeavesAnotherAgentsRunningCommandItsSecret() throws Exception {
        var otherName = "jf390-other-" + UUID.randomUUID().toString().substring(0, 8);
        Long otherId = commitInFreshTx(() -> AgentService.create(otherName, "openrouter", "gpt-4.1").id);
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            var otherDemo = AgentService.workspacePath(otherName).resolve("skills/demo");
            Files.createDirectories(otherDemo);
            Files.copy(AgentService.workspacePath(agentName).resolve("skills/demo/SKILL.md"), otherDemo.resolve("SKILL.md"));
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            assertIsOk(PUT("/api/agents/" + otherId + "/skills/demo/secrets/API_KEY", "application/json",
                    secretJson("fake-other-agent-value-jf390", stub)));
            try (var mine = running(stub, "echo $API_KEY");
                 var theirs = running(stub, otherId, otherName, "echo $API_KEY", () -> {})) {
                assertIsOk(switchSkill("demo", false));

                assertNoLongerGiven(mine, stub, "API_KEY");
                assertSent(theirs, stub, "API_KEY", "fake-other-agent-value-jf390");
            }
        } finally {
            commitInFreshTx(() -> {
                Agent other = Agent.findById(otherId);
                if (other != null) AgentService.delete(other);
                return null;
            });
            KeptSkillFiles.deleteForAgent(otherId);
        }
    }

    /** The write and the after-commit check the switch route makes, in a transaction of the test's own. */
    private void switchDemoOff(boolean rollBack) {
        commitInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            var config = new AgentSkillConfig();
            config.agent = agent;
            config.skillName = "demo";
            config.enabled = false;
            config.save();
            SecretGrants.reconcileAfterCommit(agent);
            if (rollBack) throw new IllegalStateException("rolled back after the switch");
            return null;
        });
    }

    @Test
    void aSwitchThatRollsBackLeavesARunningCommandItsSecret() throws Exception {
        try (var stub = new SecretProxyFixture()) {
            declare("demo", "API_KEY=api.example.com   # The service's API key\n");
            assertIsOk(put("API_KEY", secretJson(VALUE, stub)));
            try (var grant = running(stub, "echo $API_KEY")) {
                var thrown = assertThrows(RuntimeException.class, () -> switchDemoOff(true));
                assertEquals("rolled back after the switch", thrown.getCause().getMessage());

                assertSent(grant, stub, "API_KEY", VALUE);
                assertEquals(List.of("API_KEY"), granted("echo $API_KEY"));

                switchDemoOff(false);
                assertNoLongerGiven(grant, stub, "API_KEY");
            }
        }
    }

    /**
     * A scan that read the config before a switch and stores its result after the switch cleared the cache: that
     * result is never served, and the next read scans again.
     */
    @Test
    void aScanRunningAcrossASwitchIsNotServedAfterIt() throws Exception {
        declare("demo", "API_KEY=api.example.com   # The service's API key\n");
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var scan = Thread.ofPlatform().start(() -> {
            try {
                agents.SkillLoader.callWithScanHookForTest(() -> {
                    if (!paused.compareAndSet(false, true)) return;
                    read.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }, () -> agents.SkillLoader.enabledSkillFolders(agentName));
            } catch (Throwable t) {
                failure.set(t);
                read.countDown();
            }
        });
        try {
            assertTrue(read.await(10, TimeUnit.SECONDS), "the scan must reach its hook");
            assertNull(failure.get());
            assertIsOk(switchSkill("demo", false));
        } finally {
            release.countDown();
            scan.join(15_000);
        }
        assertFalse(scan.isAlive(), "the scan must finish");
        assertNull(failure.get());

        assertEquals(Set.of(), commitInFreshTx(() -> agents.SkillLoader.enabledSkillFolders(agentName)));
        assertEquals(List.of(), offeredSkills());
    }

    // ==================== settings (JF-357) ====================

    private Http.Response putSetting(String setting, String json) {
        return PUT("/api/agents/" + agentId + "/skills/demo/settings/" + setting, "application/json", json);
    }

    /** A setting is not secret: the panel lists its value, and the operator can change or delete it. */
    @Test
    void aSettingIsListedWithItsValueAndCanBeChangedAndDeleted() throws Exception {
        declare("demo", "setting SITE_URL=https://example.com   # The site's address\n");
        var saved = putSetting("SITE_URL", "{\"value\":\"https://site.example.org/app\"}");
        assertIsOk(saved);
        assertEquals(List.of("name", "setAt", "value"), json(saved).keySet().stream().sorted().toList());

        var body = json(GET(base()));
        var listed = body.getAsJsonArray("settings").get(0).getAsJsonObject();
        assertEquals("SITE_URL", listed.get("name").getAsString());
        assertEquals("https://site.example.org/app", listed.get("value").getAsString());
        assertEquals(JsonParser.parseString("""
                [{"name":"SITE_URL","description":"The site's address","example":"https://example.com","optional":false,
                  "set":true,"setIn":"demo"}]"""), body.get("expectedSettings"));
        assertEquals("[]", body.get("missing").toString());

        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://other.example.org\"}"));
        assertEquals("https://other.example.org",
                json(GET(base())).getAsJsonArray("settings").get(0).getAsJsonObject().get("value").getAsString());

        assertIsOk(DELETE("/api/agents/" + agentId + "/skills/demo/settings/SITE_URL"));
        assertEquals("[]", json(GET(base())).get("settings").toString());
        assertEquals(404, DELETE("/api/agents/" + agentId + "/skills/demo/settings/SITE_URL").status);
    }

    @Test
    void aSkillIsNotRunnableUntilEachRequiredSettingHasAValue() throws Exception {
        declare("demo", """
                API_KEY=api.example.com          # The key
                setting SITE_URL=https://example.com   # The site's address
                setting SITE_USER=admin          # Optional. The sign-in user
                """);
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("[\"SITE_URL\"]", missingOf("demo"));
        assertEquals(List.of(), offeredSkills());

        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://site.example.org\"}"));
        assertEquals("[]", missingOf("demo"));
        assertEquals(List.of("demo"), offeredSkills());
    }

    @Test
    void aNameIsUsedOnceAcrossSecretsAndSettings() {
        assertIsOk(put("SITE_ID", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("400 invalid_request Skill 'demo' on this agent has a secret named SITE_ID. A command finds a value"
                + " by its name, so each name is used once on an agent.", errorOf(putSetting("SITE_ID", "{\"value\":\"x\"}")));
        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://site.example.org\"}"));
        assertEquals("400 invalid_request Skill 'demo' on this agent has a setting named SITE_URL. A command finds a value"
                + " by its name, so each name is used once on an agent.",
                errorOf(put("SITE_URL", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}")));
    }

    @Test
    void aNameThatReadsAsACredentialCanOnlyBeASecret() {
        for (var name : List.of("NAS_PASS", "SONARR_API_KEY", "JIRA_PERSONAL_TOKEN", "APP_SECRET", "ADMIN_PWD", "SITE_COOKIE")) {
            assertEquals("400 invalid_request " + name + " reads as a key, token or password, so it can only be a secret:"
                    + " add it under Secrets, with the hosts it may be sent to.",
                    errorOf(putSetting(name, "{\"value\":\"hunter2hunter2\"}")), name);
        }
        assertIsOk(putSetting("TRAKT_CLIENT_ID", "{\"value\":\"0123abcd\"}"));
        assertEquals(1, json(GET(base())).getAsJsonArray("settings").size());
    }

    /** A name can be chosen to look innocent: a setting may not copy a stored secret or hold a key-shaped value. */
    @Test
    void aSettingCannotCopyAStoredSecretOrHoldAKeyShapedValue() {
        assertIsOk(put("NAS_PASS", "{\"value\":\"hunter2-correct-horse\",\"hosts\":[\"nas.example.com\"]}"));
        assertEquals("400 invalid_request This value is already stored as the secret NAS_PASS. A setting would show it in"
                + " clear and give it to commands as it is.",
                errorOf(putSetting("NAS_LOGIN", "{\"value\":\"hunter2-correct-horse\"}")));
        for (var value : List.of("sk-proj-abcdefghijklmnopqrstuvwxyz0123456789", "https://admin:hunter2@nas.example.com",
                "ghp_abcdefghijklmnopqrstuvwxyz0123456789")) {
            assertEquals("400 invalid_request This value looks like a key, token or password, so it can only be a secret:"
                    + " add it under Secrets, with the hosts it may be sent to.",
                    errorOf(putSetting("SITE_URL", "{\"value\":\"" + value + "\"}")), value);
        }
        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://nas.example.com:5001/webapi\"}"));
        assertIsOk(putSetting("NAS_LOGIN", "{\"value\":\"admin\"}"));
        assertEquals(2, json(GET(base())).getAsJsonArray("settings").size());
    }

    @Test
    void aSettingTheStoreCannotTakeIsRefusedAndStoresNothing() {
        assertEquals("400 invalid_request A setting is one line: remove the line breaks.",
                errorOf(putSetting("SITE_URL", "{\"value\":\"https://a.example.org\\nexport X=1\"}")));
        assertEquals("400 invalid_request The value is empty.", errorOf(putSetting("SITE_URL", "{\"value\":\"\"}")));
        assertEquals("400 invalid_request The body must be a JSON object with 'value' or 'fromKept'.", errorOf(putSetting("SITE_URL", "{}")));
        assertEquals("400 invalid_request 'site_url' is not a secret name: use capital letters, digits and underscores,"
                + " starting with a letter, at most 64 characters, as in NAS_PASSWORD.",
                errorOf(putSetting("site_url", "{\"value\":\"x\"}")));
        assertEquals(400, putSetting("PATH", "{\"value\":\"/tmp\"}").status);
        assertEquals("[]", json(GET(base())).get("settings").toString());
    }

    @Test
    void anAgentCannotReadOrWriteASetting() {
        for (var action : List.of("putSetting", "deleteSetting")) {
            var method = java.util.Arrays.stream(ApiSkillSecretsController.class.getMethods())
                    .filter(m -> m.getName().equals(action)).findFirst().orElseThrow();
            assertEquals(AgentAccess.Level.OPERATOR_ONLY, method.getAnnotation(AgentAccess.class).value(), action);
        }
        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://site.example.org\"}"));
        clearCookies();
        var codes = new ArrayList<String>();
        try {
            codes.add(json(PUT(agentRequest(), "/api/agents/" + agentId + "/skills/demo/settings/SITE_URL", "application/json",
                    "{\"value\":\"https://evil.example.com\"}")).get("code").getAsString());
            codes.add(json(DELETE(agentRequest(), "/api/agents/" + agentId + "/skills/demo/settings/SITE_URL"))
                    .get("code").getAsString());
        } finally {
            clearCookies();
        }
        assertEquals(List.of("operator_only", "operator_only"), codes);
        login();
        assertEquals("https://site.example.org",
                json(GET(base())).getAsJsonArray("settings").get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void removingTheSkillFromTheAgentDeletesItsSettingsAndWhatItSaved() throws Exception {
        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://site.example.org\"}"));
        var state = AgentService.workspacePath(agentName).resolve("skill-state/demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("session.txt"), "x\n");
        Files.createDirectories(AgentService.workspacePath(agentName).resolve("skill-state/other"));

        assertIsOk(DELETE("/api/agents/" + agentId + "/skills/demo/delete"));

        assertEquals(0L, (long) commitInFreshTx(() -> models.SkillSetting.count("agent.id = ?1", agentId)));
        assertEquals(false, Files.exists(state));
        assertEquals(true, Files.isDirectory(AgentService.workspacePath(agentName).resolve("skill-state/other")),
                "another skill's saved state stays");
    }

    /** The agent writes its workspace, links included: a linked state folder is never followed into a delete. */
    @Test
    void removingTheSkillNeverDeletesWhereALinkedStateFolderLeads() throws Exception {
        var workspace = AgentService.workspacePath(agentName);
        var outside = Files.createTempDirectory("jf357-outside-");
        try {
            Files.createDirectories(outside.resolve("demo"));
            Files.writeString(outside.resolve("demo/keep.txt"), "keep\n");
            Files.createSymbolicLink(workspace.resolve("skill-state"), outside);
            assertIsOk(DELETE("/api/agents/" + agentId + "/skills/demo/delete"));
            assertEquals("keep\n", Files.readString(outside.resolve("demo/keep.txt")), "the state root was a link");

            Files.delete(workspace.resolve("skill-state"));
            Files.createDirectories(workspace.resolve("skills/demo"));
            Files.writeString(workspace.resolve("skills/demo/SKILL.md"), "# Demo\n");
            Files.createDirectories(workspace.resolve("notes"));
            Files.writeString(workspace.resolve("notes/keep.txt"), "keep\n");
            Files.createDirectories(workspace.resolve("skill-state"));
            Files.createSymbolicLink(workspace.resolve("skill-state/demo"), workspace.resolve("notes"));
            assertIsOk(DELETE("/api/agents/" + agentId + "/skills/demo/delete"));
            assertEquals("keep\n", Files.readString(workspace.resolve("notes/keep.txt")), "the skill's state folder was a link");
        } finally {
            agents.SkillLoader.clearCache();
            services.SkillPromotionService.deleteRecursive(outside);
        }
    }

    @Test
    void deletingASecretRemovesItAndOnlyIt() {
        assertIsOk(put("ONE", "{\"value\":\"v1\",\"hosts\":[\"a.example.com\"]}"));
        assertIsOk(put("TWO", "{\"value\":\"v2\",\"hosts\":[\"b.example.com\"]}"));
        var deleted = DELETE(base() + "/ONE");
        assertIsOk(deleted);
        assertEquals("{\"deleted\":true}", getContent(deleted));
        var listed = json(GET(base())).getAsJsonArray("secrets");
        assertEquals(1, listed.size());
        assertEquals("TWO", listed.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void everyRouteIsTheOperatorsAloneAndAnAgentChangesNothing() {
        for (var action : List.of("list", "put", "delete")) {
            var method = java.util.Arrays.stream(ApiSkillSecretsController.class.getMethods())
                    .filter(m -> m.getName().equals(action)).findFirst().orElseThrow();
            assertEquals(AgentAccess.Level.OPERATOR_ONLY, method.getAnnotation(AgentAccess.class).value(), action);
        }
        assertIsOk(put("KEEP", "{\"value\":\"v\",\"hosts\":[\"a.example.com\"]}"));
        clearCookies();
        var codes = new ArrayList<String>();
        try {
            codes.add(json(GET(agentRequest(), base())).get("code").getAsString());
            codes.add(json(PUT(agentRequest(), base() + "/NEW", "application/json",
                    "{\"value\":\"v\",\"hosts\":[\"evil.example.com\"]}")).get("code").getAsString());
            codes.add(json(PUT(agentRequest(), base() + "/KEEP", "application/json",
                    "{\"hosts\":[\"evil.example.com\"]}")).get("code").getAsString());
            codes.add(json(DELETE(agentRequest(), base() + "/KEEP")).get("code").getAsString());
        } finally {
            clearCookies();
        }
        assertEquals(List.of("operator_only", "operator_only", "operator_only", "operator_only"), codes);
        login();
        var listed = json(GET(base())).getAsJsonArray("secrets");
        assertEquals(1, listed.size());
        assertEquals("[\"a.example.com\"]", listed.get(0).getAsJsonObject().get("hosts").toString());
    }

    private Http.Request agentRequest() {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    @Test
    void removingTheSkillFromTheAgentDeletesItsSecrets() {
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"a.example.com\"]}"));
        assertIsOk(DELETE("/api/agents/" + agentId + "/skills/demo/delete"));
        assertEquals(0, rows());
    }

    @Test
    void deletingTheAgentDeletesItsSecrets() {
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"a.example.com\"]}"));
        commitInFreshTx(() -> {
            AgentService.delete(Agent.findById(agentId));
            return null;
        });
        assertEquals(0L, (long) commitInFreshTx(() -> SkillSecret.count("agent.id = ?1", agentId)));
    }

    @Test
    void noLogLineOrResponseCarriesTheValue() {
        var bodies = new ArrayList<String>();
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            bodies.add(getContent(put("NAS_PASSWORD", "{\"value\":\"" + VALUE + "\",\"hosts\":[\"nas.example.com\"]}")));
            bodies.add(getContent(put("NAS_PASSWORD", "{\"value\":\"" + VALUE + "-2\",\"hosts\":[\"nas.example.com\"]}")));
            bodies.add(getContent(put("NAS_PASSWORD", "{\"hosts\":[\"nas.example.com\",\"b.example.com\"]}")));
            bodies.add(getContent(GET(base())));
            bodies.add(getContent(DELETE(base() + "/NAS_PASSWORD")));
        });
        assertEquals(List.of(
                "Secret NAS_PASSWORD added for skill demo, sent only to nas.example.com",
                "Secret NAS_PASSWORD replaced for skill demo, sent only to nas.example.com",
                "Secret NAS_PASSWORD kept, hosts changed for skill demo, sent only to nas.example.com, b.example.com",
                "Secret NAS_PASSWORD deleted from skill demo"), events.stream().map(EventLogger.Captured::message).toList());
        for (var e : events) assertNull(e.details(), e.message());
        for (var body : bodies) assertFalse(body.contains(VALUE), body);
    }

    @Test
    void aValueThatWillNotOpenIsListedAmongTheUnreadableCredentials() {
        assertIsOk(put("LOST_KEY", "{\"value\":\"v\",\"hosts\":[\"a.example.com\"]}"));
        var other = CredentialCipher.of("fake-secret-of-another-install-0001", null);
        commitInFreshTx(() -> JPA.em().createNativeQuery(
                        "UPDATE skill_secret SET secret_value = ?1 WHERE agent_id = ?2 AND name = 'LOST_KEY'")
                .setParameter(1, other.encrypt(SkillSecret.VALUE_FIELD, "v")).setParameter(2, agentId).executeUpdate());
        var unreadable = json(GET("/api/credentials/unreadable")).getAsJsonArray("unreadable");
        var mine = new ArrayList<String>();
        for (var item : unreadable) {
            var o = item.getAsJsonObject();
            if (agentName.equals(o.get("owner").getAsString())) mine.add(o.toString());
        }
        assertEquals(List.of("{\"store\":\"skill\",\"owner\":\"" + agentName
                + "\",\"field\":\"demo LOST_KEY\",\"page\":\"/skills\"}"), mine);
        assertEquals("LOST_KEY", json(GET(base())).getAsJsonArray("secrets").get(0).getAsJsonObject()
                .get("name").getAsString(), "the panel still names it, to replace");
    }

    @Test
    void theStartUpPassSealsTheStoreAgainUnderARotatedSecret() throws Exception {
        var before = CredentialCipher.of(CredentialEncryptionJobTest.OLD, null);
        try (Connection conn = CredentialEncryptionJobTest.throwaway(); var s = conn.createStatement()) {
            s.execute("CREATE TABLE skill_secret (id BIGINT PRIMARY KEY, secret_value TEXT NOT NULL)");
            try (var insert = conn.prepareStatement("INSERT INTO skill_secret (id, secret_value) VALUES (1, ?)")) {
                insert.setString(1, before.encrypt(SkillSecret.VALUE_FIELD, VALUE));
                insert.executeUpdate();
            }
            var pass = CredentialEncryptionJob.reseal(conn, CredentialCipher.of(Play.secretKey, CredentialEncryptionJobTest.OLD));
            conn.commit();
            assertEquals(1, pass.rekeyed());
            try (var rs = s.executeQuery("SELECT secret_value FROM skill_secret WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(VALUE, CredentialCipher.of(Play.secretKey, null)
                        .open(SkillSecret.VALUE_FIELD, rs.getString(1)).plaintext());
            }
        }
    }

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
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    // ==================== a value from a kept file (JF-352) ====================

    /** Keep {@code path} of an old copy of demo holding {@code content}, as an update would, and return its id. */
    private String keep(String path, String content) throws Exception {
        return keep(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private String keep(String path, byte[] content) throws Exception {
        var old = Files.createTempDirectory("jf352-old-");
        var fresh = Files.createTempDirectory("jf352-new-");
        try {
            Files.createDirectories(old.resolve(path).getParent());
            Files.write(old.resolve(path), content);
            if (!path.startsWith("credentials/")) Files.writeString(old.resolve(path + ".example"), "");
            commitInFreshTx(() -> {
                try {
                    return KeptSkillFiles.copyOut(Agent.findById(agentId), "demo", old, fresh);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } finally {
            services.SkillPromotionService.deleteRecursive(old);
            services.SkillPromotionService.deleteRecursive(fresh);
        }
        for (var e : kept()) {
            if (e.getAsJsonObject().get("path").getAsString().equals(path)) return e.getAsJsonObject().get("id").getAsString();
        }
        throw new AssertionError("not kept: " + path);
    }

    private com.google.gson.JsonArray kept() {
        return json(GET("/api/agents/" + agentId + "/skills/demo/kept")).getAsJsonArray("kept");
    }

    private static String keptJson(String id) {
        return "\"fromKept\":\"" + id + "\"";
    }

    private List<String> keptPaths() {
        var paths = new ArrayList<String>();
        for (var e : kept()) paths.add(e.getAsJsonObject().get("path").getAsString());
        return paths.stream().sorted().toList();
    }

    private static String plaintext(String sealed) {
        return CredentialCipher.current().open(SkillSecret.VALUE_FIELD, sealed).plaintext();
    }

    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void aSecretTakesTheWholeKeptFileTrimmedAndTheFileIsThenDeleted() throws Exception {
        var id = keep("credentials/gamma-api-key.txt", "  " + VALUE + "\n\n");

        var saved = put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}");

        assertIsOk(saved);
        assertEquals(List.of("capture", "hasValue", "hosts", "name", "renewedAt", "setAt"),
                json(saved).keySet().stream().sorted().toList());
        assertEquals(VALUE, CredentialCipher.current().open(SkillSecret.VALUE_FIELD, stored("GAMMA_API_KEY")).plaintext());
        assertEquals("[]", kept().toString(), "the kept file is deleted once its value is stored");
    }

    /** JF-374: a value from a kept file comes with the capture rule saved beside it. */
    @Test
    void aKeptFileCanFillARenewedSecretSavedWithItsRule() throws Exception {
        declare("demo", "renewed SESSION_KEY=api.example.com from POST /v1/sessions json session   # The session key\n");
        var id = keep("credentials/session-key.txt", VALUE + "\n");
        seedPassword();

        var saved = put("SESSION_KEY", "{" + keptJson(id) + ",\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}");

        assertIsOk(saved);
        assertEquals("true POST /v1/sessions/current json session using DEMO_PASSWORD in form password",
                json(saved).get("hasValue") + " " + ruleOf(json(saved)));
        assertEquals(VALUE, plaintext(stored("SESSION_KEY")));
        assertEquals("[]", kept().toString(), "the kept file is deleted once its value is stored");
    }

    /** Saving a renewed secret's rule stores no value, so a kept file assigning it stays until one is stored. */
    @Test
    void savingOnlyARuleKeepsTheKeptFileThatAssignsTheSecret() throws Exception {
        declare("demo", "renewed SESSION_KEY=api.example.com from POST /v1/sessions json session   # The session key\n");
        keep("config.env", "SESSION_KEY=" + VALUE + "\n");
        seedPassword();

        assertIsOk(put("SESSION_KEY", "{\"hosts\":[\"api.example.com\"],\"capture\":" + RULE + "}"));
        assertEquals(List.of("config.env"), keptPaths(), "the secret still has no value");

        assertIsOk(put("SESSION_KEY", "{\"value\":\"fake-typed-session-jf374\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals(List.of(), keptPaths());
    }

    @Test
    void aReplacedSecretCanTakeItsNewValueFromAKeptFile() throws Exception {
        assertIsOk(put("GAMMA_API_KEY", "{\"value\":\"fake-old-value\",\"hosts\":[\"public-api.gamma.app\"]}"));
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");

        assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}"));

        assertEquals(VALUE, CredentialCipher.current().open(SkillSecret.VALUE_FIELD, stored("GAMMA_API_KEY")).plaintext());
        assertEquals(1, rows());
    }

    /**
     * Each setting takes its own line, read as a shell reads it, and the file stays until every name in it the skill
     * declares has a value: OTHER and JIRA_URL_OLD are not declared, so they never hold it back.
     */
    @Test
    void aSettingIsReadFromItsOwnLineAsAShellReadsItAndTheFileGoesOnceEachDeclaredNameInItIsStored() throws Exception {
        declare("demo", "setting JIRA_URL=https://jira.example.com   # The Jira address\n"
                + "setting SITE_URL=https://site.example.com   # The site\n"
                + "setting SITE_PATH=/srv   # Optional. Where the site lives\n");
        var id = keep("config.env", "# The Jira address\n"
                + "JIRA_URL=https://first.example.org\n"
                + "export JIRA_URL='https://jira.example.org/a#b'   # the current one\n"
                + "JIRA_URL_OLD=https://old.example.org\n"
                + "SITE_URL=\"https://site.example.org/a \\\"b\\\"\"  # quoted, with a comment after\n"
                + "SITE_PATH=/srv/site   # unquoted, with a comment\n"
                + "OTHER=1\n");

        var jira = putSetting("JIRA_URL", "{" + keptJson(id) + "}");
        assertIsOk(jira);
        assertEquals("https://jira.example.org/a#b", json(jira).get("value").getAsString());
        assertEquals(List.of("config.env"), keptPaths(), "SITE_URL and SITE_PATH have no value yet");

        var site = putSetting("SITE_URL", "{" + keptJson(id) + "}");
        assertIsOk(site);
        assertEquals("https://site.example.org/a \"b\"", json(site).get("value").getAsString());
        assertEquals(List.of("config.env"), keptPaths(), "SITE_PATH, though optional, is declared and has no value yet");

        var path = putSetting("SITE_PATH", "{" + keptJson(id) + "}");
        assertIsOk(path);
        assertEquals("/srv/site", json(path).get("value").getAsString());
        assertEquals(List.of(), keptPaths());
    }

    /** The matrix's multi-value secret: an env-style key file is never stored whole, and goes once it is used up. */
    @Test
    void anEnvStyleKeyFileGivesEachNameItsLineAndIsDeletedWhenItsLastDeclaredNameIsStored() throws Exception {
        declare("demo", "NAS_PASS=nas.example.com   # The DSM password\n"
                + "setting NAS_USER=admin   # The DSM user\n"
                + "setting NAS_PORT=5001   # Optional. The DSM port\n");
        var bodies = new ArrayList<String>();
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try {
                var id = keep("credentials/nas.env", "export NAS_PASS='" + VALUE + "'   # the password\nNAS_USER=operator\n");

                var pass = put("NAS_PASS", "{" + keptJson(id) + ",\"hosts\":[\"nas.example.com\"]}");
                assertIsOk(pass);
                bodies.add(getContent(pass));
                assertEquals(VALUE, plaintext(stored("NAS_PASS")), "only the NAS_PASS line's value is stored");
                assertEquals(List.of("credentials/nas.env"), keptPaths(), "NAS_USER still waits for its value");

                assertEquals("400 invalid_request The kept file credentials/nas.env has no line NAS_PORT=….",
                        errorOf(putSetting("NAS_PORT", "{" + keptJson(id) + "}")));
                assertEquals(List.of("credentials/nas.env"), keptPaths());

                var user = putSetting("NAS_USER", "{" + keptJson(id) + "}");
                assertIsOk(user);
                bodies.add(getContent(user));
                assertEquals("operator", json(user).get("value").getAsString());
                assertEquals(List.of(), keptPaths(), "every name in it the skill declares now has a value");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(List.of(
                "Kept 1 file(s) of skill demo's old copy before the update: credentials/nas.env",
                "Secret NAS_PASS added for skill demo, sent only to nas.example.com",
                "Kept file credentials/nas.env of skill demo stored as secret NAS_PASS and kept for NAS_USER",
                "Setting NAS_USER added for skill demo",
                "Kept file credentials/nas.env of skill demo stored as setting NAS_USER and deleted"),
                events.stream().map(EventLogger.Captured::message).toList());
        for (var body : bodies) assertFalse(body.contains(VALUE), body);
    }

    @Test
    void typingTheLastDeclaredValueOfAKeptFileDeletesIt() throws Exception {
        declare("demo", "NAS_PASS=nas.example.com   # The DSM password\nsetting NAS_USER=admin   # The DSM user\n");
        var id = keep("credentials/nas.env", "NAS_PASS=" + VALUE + "\nNAS_USER=operator\n");
        keep("config.env", "OTHER=1\n");
        assertIsOk(put("NAS_PASS", "{" + keptJson(id) + ",\"hosts\":[\"nas.example.com\"]}"));
        assertEquals(List.of("config.env", "credentials/nas.env"), keptPaths());

        assertIsOk(putSetting("NAS_USER", "{\"value\":\"typed-operator\"}"));

        assertEquals(List.of("config.env"), keptPaths(), "a file naming nothing the skill declares is never used up");
    }

    // ==================== the skill's own copy goes with a used-up kept file (JF-405) ====================

    /** Put {@code content} at {@code path} in demo's own folder, as an update carries a kept file back into it. */
    private java.nio.file.Path putBack(String path, String content) throws Exception {
        var copy = AgentService.workspacePath(agentName).resolve("skills/demo").resolve(path);
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, content);
        return copy;
    }

    @Test
    void movingADeclaredKeyFileIntoTheVaultDeletesTheSkillsUnchangedCopyToo() throws Exception {
        declare("demo", "GAMMA_API_KEY=public-api.gamma.app   # The Gamma key\n");
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");
        var copy = putBack("credentials/gamma-api-key.txt", VALUE + "\n");

        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()),
                seen -> assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}")));

        assertEquals(List.of(), keptPaths());
        assertFalse(Files.exists(copy, java.nio.file.LinkOption.NOFOLLOW_LINKS), "no plain copy of the key is left in the skill");
        assertEquals(List.of(
                "Secret GAMMA_API_KEY added for skill demo, sent only to public-api.gamma.app",
                "Skill demo's own copy of credentials/gamma-api-key.txt deleted: each name in it has a value",
                "Kept file credentials/gamma-api-key.txt of skill demo stored as secret GAMMA_API_KEY and deleted"),
                events.stream().map(EventLogger.Captured::message).toList());
    }

    @Test
    void aKeyFileStoredUnderANameTheSkillDoesNotDeclareLeavesTheSkillsCopy() throws Exception {
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");
        var copy = putBack("credentials/gamma-api-key.txt", VALUE + "\n");

        assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}"));

        assertEquals(List.of(), keptPaths());
        assertEquals(VALUE + "\n", Files.readString(copy), "a skill that declares nothing may still read its file");
    }

    @Test
    void aSkillCopyChangedSinceTheUpdateStays() throws Exception {
        declare("demo", "GAMMA_API_KEY=public-api.gamma.app   # The Gamma key\n");
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");
        var copy = putBack("credentials/gamma-api-key.txt", "fake-newer-key-jf405\n");

        assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}"));

        assertEquals(List.of(), keptPaths());
        assertEquals("fake-newer-key-jf405\n", Files.readString(copy));
    }

    @Test
    void anEnvFileTakesTheSkillsCopyWithItOnlyOnceItsLastDeclaredNameHasAValue() throws Exception {
        declare("demo", "NAS_PASS=nas.example.com   # The DSM password\nsetting NAS_USER=admin   # The DSM user\n");
        var text = "# DSM sign-in\nexport NAS_PASS='" + VALUE + "'\nNAS_USER=operator\n";
        var id = keep("credentials/nas.env", text);
        var copy = putBack("credentials/nas.env", text);

        assertIsOk(put("NAS_PASS", "{" + keptJson(id) + ",\"hosts\":[\"nas.example.com\"]}"));
        assertEquals(text, Files.readString(copy), "NAS_USER still waits for its value");

        assertIsOk(putSetting("NAS_USER", "{\"value\":\"typed-operator\"}"));

        assertEquals(List.of(), keptPaths());
        assertFalse(Files.exists(copy, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void anEnvFileWithALineTheSkillDoesNotDeclareLeavesTheSkillsCopy() throws Exception {
        declare("demo", "NAS_PASS=nas.example.com   # The DSM password\n");
        var text = "NAS_PASS=" + VALUE + "\nNAS_HOST=nas.lan\n";
        var id = keep("credentials/nas.env", text);
        var copy = putBack("credentials/nas.env", text);

        assertIsOk(put("NAS_PASS", "{" + keptJson(id) + ",\"hosts\":[\"nas.example.com\"]}"));

        assertEquals(List.of(), keptPaths(), "NAS_HOST is not declared, so it never holds the kept file back");
        assertEquals(text, Files.readString(copy), "a script may still read NAS_HOST from it");
    }

    @Test
    void aLinkAtTheSkillsPathIsNotDeleted() throws Exception {
        declare("demo", "GAMMA_API_KEY=public-api.gamma.app   # The Gamma key\n");
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");
        var outside = Files.createTempFile("jf405-outside-", ".txt");
        try {
            Files.writeString(outside, VALUE + "\n");
            var link = AgentService.workspacePath(agentName).resolve("skills/demo/credentials/gamma-api-key.txt");
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, outside);

            assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}"));

            assertTrue(Files.isSymbolicLink(link), "the link stays");
            assertEquals(VALUE + "\n", Files.readString(outside), "and what it points at");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aBase64KeyWithPaddingIsImportedWholeAndThenDeleted() throws Exception {
        var id = keep("credentials/gamma-api-key.txt", "ZmFrZS1nYW1tYS1rZXktamYzNTI=\n");

        assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}"));

        assertEquals("ZmFrZS1nYW1tYS1rZXktamYzNTI=", plaintext(stored("GAMMA_API_KEY")));
        assertEquals(List.of(), keptPaths());
    }

    @Test
    void aValueOnlyAShellCouldReadIsRefusedAndTheFileKept() throws Exception {
        var id = keep("credentials/key.env", "PRIVATE_KEY=\"-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY-----\"\n"
                + "API_URL=https://$HOST/api\n");

        assertEquals("400 invalid_request The kept file credentials/key.env gives PRIVATE_KEY a quoted value with no"
                + " closing quote on its line, which JClaw does not read: type the value instead.",
                errorOf(put("PRIVATE_KEY", "{" + keptJson(id) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals("400 invalid_request The kept file credentials/key.env gives API_URL a value with $ outside single"
                + " quotes, which only a shell would expand: type the value instead.",
                errorOf(putSetting("API_URL", "{" + keptJson(id) + "}")));

        assertEquals(0, rows());
        assertEquals(List.of("credentials/key.env"), keptPaths());
    }

    /** A whole file is most often a key, which a setting would show in clear. */
    @Test
    void aSettingIsNeverTakenFromAWholeFile() throws Exception {
        var id = keep("credentials/token.txt", "fake-site-user-jf352\n");

        assertEquals("400 invalid_request A setting takes its value from a NAME=value line.",
                errorOf(putSetting("SITE_USER", "{" + keptJson(id) + "}")));

        assertEquals("[]", json(GET(base())).getAsJsonArray("settings").toString());
        assertEquals(List.of("credentials/token.txt"), keptPaths());
    }

    @Test
    void aWholeFileValueLosesALeadingByteOrderMark() throws Exception {
        var withMark = new java.io.ByteArrayOutputStream();
        withMark.write(BYTE_ORDER_MARK);
        withMark.write((VALUE + "\r\n").getBytes(StandardCharsets.UTF_8));
        var id = keep("credentials/gamma-api-key.txt", withMark.toByteArray());

        assertIsOk(put("GAMMA_API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"public-api.gamma.app\"]}"));

        assertEquals(VALUE, plaintext(stored("GAMMA_API_KEY")));
        assertEquals(List.of(), keptPaths());
    }

    @Test
    void aLineFileWithAByteOrderMarkStillReadsItsFirstLine() throws Exception {
        var withMark = new java.io.ByteArrayOutputStream();
        withMark.write(BYTE_ORDER_MARK);
        withMark.write("SITE_URL=https://bom.example.org\n".getBytes(StandardCharsets.UTF_8));
        var id = keep("config.env", withMark.toByteArray());

        var saved = putSetting("SITE_URL", "{" + keptJson(id) + "}");

        assertIsOk(saved);
        assertEquals("https://bom.example.org", json(saved).get("value").getAsString());
    }

    @Test
    void aKeptFileIsReadUpToTheImportLimitAndRefusedOneByteOver() throws Exception {
        var limit = Math.toIntExact(KeptSkillFiles.MAX_IMPORT_BYTES);
        var atLimit = keep("credentials/at-limit.txt", VALUE + " ".repeat(limit - VALUE.length()));
        var overLimit = keep("credentials/over-limit.txt", VALUE + " ".repeat(limit - VALUE.length() + 1));

        assertIsOk(put("AT_LIMIT_KEY", "{" + keptJson(atLimit) + ",\"hosts\":[\"a.example.com\"]}"));
        assertEquals("400 invalid_request The kept file credentials/over-limit.txt is larger than any value JClaw stores.",
                errorOf(put("OVER_LIMIT_KEY", "{" + keptJson(overLimit) + ",\"hosts\":[\"a.example.com\"]}")));

        assertEquals(VALUE, plaintext(stored("AT_LIMIT_KEY")));
        assertEquals(1, rows());
        assertEquals(List.of("credentials/over-limit.txt"), keptPaths());
    }

    @Test
    void aKeptFileThatIsNotTextIsRefusedAndKept() throws Exception {
        var id = keep("credentials/key.bin", new byte[]{(byte) 0xC3, (byte) 0x28, 0x00, 0x01});

        assertEquals("400 invalid_request The kept file credentials/key.bin is not text.",
                errorOf(put("API_KEY", "{" + keptJson(id) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals("400 invalid_request The kept file credentials/key.bin is not text.",
                errorOf(putSetting("SITE_URL", "{" + keptJson(id) + "}")));

        assertEquals(0, rows());
        assertEquals(List.of("credentials/key.bin"), keptPaths());
    }

    /** The import's store and its delete are one outcome: a store that rolls back leaves the file to try again. */
    @Test
    void aStoreThatRollsBackKeepsTheKeptFile() throws Exception {
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");
        var skillDir = AgentService.workspacePath(agentName).resolve("skills/demo");

        var thrown = assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> {
            Agent owner = Agent.findById(agentId);
            try {
                KeptSkillFiles.importSecret(owner, "demo", skillDir, KeptSkillFiles.find(owner, "demo", id),
                        "GAMMA_API_KEY", List.of("public-api.gamma.app"), false, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IllegalStateException("rolled back after the store");
        }));

        assertEquals("rolled back after the store", thrown.getCause().getMessage());
        assertEquals(0, rows());
        assertEquals(List.of("credentials/gamma-api-key.txt"), keptPaths());
    }

    @Test
    void anImportTheStoreRefusesKeepsTheKeptFile() throws Exception {
        var config = keep("config.env", "OTHER=1\n");
        var empty = keep("credentials/empty.txt", " \n");
        var key = keep("credentials/gamma-api-key.txt", VALUE + "\n");

        assertEquals("400 invalid_request The kept file config.env has no line JIRA_URL=….",
                errorOf(putSetting("JIRA_URL", "{" + keptJson(config) + "}")));
        assertEquals("400 invalid_request The value is empty.",
                errorOf(put("EMPTY_KEY", "{" + keptJson(empty) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals("400 invalid_request Add at least one host the secret may be sent to.",
                errorOf(put("GAMMA_API_KEY", "{" + keptJson(key) + ",\"hosts\":[]}")));
        assertEquals("400 invalid_request Send 'value' or 'fromKept', not both.",
                errorOf(put("GAMMA_API_KEY", "{\"value\":\"v\"," + keptJson(key) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals("400 invalid_request Send 'value' or 'fromKept', not both.",
                errorOf(putSetting("JIRA_URL", "{\"value\":\"https://x.example.org\"," + keptJson(config) + "}")));

        assertEquals(0, rows());
        var paths = new ArrayList<String>();
        for (var e : kept()) paths.add(e.getAsJsonObject().get("path").getAsString());
        assertEquals(List.of("config.env", "credentials/empty.txt", "credentials/gamma-api-key.txt"), paths.stream().sorted().toList());
    }

    @Test
    void aKeptIdThatLeadsOutOfTheKeptFolderIsRefusedAndAMissingOneIsNotFound() {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        var outward = encoder.encodeToString("../../../../certs/.env".getBytes(StandardCharsets.UTF_8));
        var missing = encoder.encodeToString("20261113T000000.000Z/credentials/x.txt".getBytes(StandardCharsets.UTF_8));

        assertEquals("400 invalid_request '" + outward + "' is not a kept file's id.",
                errorOf(put("API_KEY", "{" + keptJson(outward) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals("400 invalid_request '" + outward + "' is not a kept file's id.",
                errorOf(putSetting("SITE_URL", "{" + keptJson(outward) + "}")));
        assertEquals("404 not_found No kept file '" + missing + "' on skill 'demo'.",
                errorOf(put("API_KEY", "{" + keptJson(missing) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals(0, rows());
    }

    /** A kept file of one skill is no kept file of another, as its secrets are not. */
    @Test
    void aKeptFileOfAnotherSkillIsNotFound() throws Exception {
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");
        declare("other", "");

        assertEquals("404 not_found No kept file '" + id + "' on skill 'other'.",
                errorOf(PUT("/api/agents/" + agentId + "/skills/other/secrets/API_KEY", "application/json",
                        "{" + keptJson(id) + ",\"hosts\":[\"a.example.com\"]}")));
        assertEquals(1, kept().size());
    }

    @Test
    void deletingAKeptFileOfAnotherSkillIsNotFoundAndDeletesNothing() throws Exception {
        var id = keep("credentials/gamma-api-key.txt", VALUE + "\n");

        assertEquals("404 not_found No kept file '" + id + "' on skill 'other'.",
                errorOf(DELETE("/api/agents/" + agentId + "/skills/other/kept/" + id)));
        assertEquals(List.of("credentials/gamma-api-key.txt"), keptPaths());
    }

    @Test
    void noLogLineOrResponseCarriesAKeptFilesContent() {
        var bodies = new ArrayList<String>();
        var events = EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
            try {
                var key = keep("credentials/gamma-api-key.txt", VALUE + "\n");
                var config = keep("config.env", "SITE_URL=https://site.example.org\n");
                bodies.add(kept().toString());
                bodies.add(getContent(put("GAMMA_API_KEY", "{" + keptJson(key) + ",\"hosts\":[\"public-api.gamma.app\"]}")));
                bodies.add(getContent(putSetting("SITE_URL", "{" + keptJson(config) + "}")));
                bodies.add(getContent(GET(base())));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(List.of(
                "Kept 1 file(s) of skill demo's old copy before the update: credentials/gamma-api-key.txt",
                "Kept 1 file(s) of skill demo's old copy before the update: config.env",
                "Secret GAMMA_API_KEY added for skill demo, sent only to public-api.gamma.app",
                "Kept file credentials/gamma-api-key.txt of skill demo stored as secret GAMMA_API_KEY and deleted",
                "Setting SITE_URL added for skill demo",
                "Kept file config.env of skill demo stored as setting SITE_URL and deleted"),
                events.stream().map(EventLogger.Captured::message).toList());
        for (var e : events) assertNull(e.details(), e.message());
        for (var body : bodies) assertFalse(body.contains(VALUE), body);
    }

    @Test
    void aRolledBackAgentDeleteKeepsItsKeptFiles() throws Exception {
        keep("credentials/gamma-api-key.txt", VALUE + "\n");

        var thrown = assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> {
            AgentService.delete(Agent.findById(agentId));
            throw new IllegalStateException("rolled back after the delete");
        }));

        assertEquals("rolled back after the delete", thrown.getCause().getMessage());
        assertEquals(List.of("credentials/gamma-api-key.txt"), keptPaths());
    }

    @Test
    void deletingTheAgentDeletesItsKeptFiles() throws Exception {
        keep("credentials/gamma-api-key.txt", VALUE + "\n");
        assertTrue(Files.isDirectory(KeptSkillFiles.root().resolve(String.valueOf(agentId))));

        commitInFreshTx(() -> {
            AgentService.delete(Agent.findById(agentId));
            return null;
        });

        assertFalse(Files.exists(KeptSkillFiles.root().resolve(String.valueOf(agentId))));
    }
}
