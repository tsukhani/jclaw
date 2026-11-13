import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.AgentAccess;
import controllers.ApiSkillSecretsController;
import controllers.RequestPrincipal;
import jobs.CredentialEncryptionJob;
import models.Agent;
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
import utils.CredentialCipher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        assertEquals(List.of("hosts", "name", "setAt"), body.keySet().stream().sorted().toList());
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
                [{"name":"API_KEY","description":"The service's API key","exampleHosts":["api.example.com"],"optional":false,"set":false,"setIn":null},
                 {"name":"NAS_PASS","description":"The password itself","exampleHosts":["nas.example.com:5001","backup.example.com"],"optional":false,"set":true,"setIn":"demo"},
                 {"name":"PROXY_PASS","description":"Optional. The reverse proxy's password","exampleHosts":["nas.example.com"],"optional":true,"set":false,"setIn":null},
                 {"name":"BARE","description":"","exampleHosts":[],"optional":false,"set":false,"setIn":null},
                 {"name":"BAD_HOST","description":"its example is no host","exampleHosts":[],"optional":false,"set":false,"setIn":null}]
                """), body.get("expected"));
        assertEquals("[\"API_KEY\",\"BARE\",\"BAD_HOST\"]", body.get("missing").toString());
        var stored = new ArrayList<String>();
        for (var e : body.getAsJsonArray("secrets")) stored.add(e.getAsJsonObject().get("name").getAsString());
        assertEquals(List.of("NAS_PASS", "UNDECLARED"), stored, "a stored secret the file does not declare is still listed");
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
                + "\"optional\":false,\"set\":true,\"setIn\":\"other\"}", expected.toString());
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

        assertIsOk(PUT("/api/agents/" + agentId + "/skills/other", "application/json", "{\"enabled\":false}"));
        agents.SkillLoader.clearCache();

        assertEquals("[\"SHARED_PASS\"]", missingOf("demo"));
        var body = json(GET(base()));
        assertEquals("{\"name\":\"SHARED_PASS\",\"description\":\"The shared password\",\"exampleHosts\":[\"api.example.com\"],"
                + "\"optional\":false,\"set\":false,\"setIn\":\"other\"}",
                body.getAsJsonArray("expected").get(0).getAsJsonObject().toString());
        assertEquals("[\"SHARED_PASS\"]", body.get("missing").toString());
        assertEquals(List.of(), offeredSkills());
        assertEquals("[]", missingOf("other"), "the skill that holds the secret needs nothing, switched off or not");
    }

    /** Switching a skill off does not make it look as if its own secrets were missing. */
    @Test
    void aSwitchedOffSkillIsNotFlaggedForTheSecretsItHolds() throws Exception {
        declare("demo", "API_KEY=api.example.com   # The service's API key\n");
        assertIsOk(put("API_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertIsOk(PUT("/api/agents/" + agentId + "/skills/demo", "application/json", "{\"enabled\":false}"));
        agents.SkillLoader.clearCache();

        assertEquals("[]", missingOf("demo"));
        assertEquals("[]", json(GET(base())).get("missing").toString());
        assertEquals(List.of(), offeredSkills(), "it is still switched off");
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
    void aNameIsUsedOnceAcrossSecretsAndSettings() throws Exception {
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
    void anAgentCannotReadOrWriteASetting() throws Exception {
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
    void everyRouteIsTheOperatorsAloneAndAnAgentChangesNothing() throws Exception {
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
        assertEquals(List.of("hosts", "name", "setAt"), json(saved).keySet().stream().sorted().toList());
        assertEquals(VALUE, CredentialCipher.current().open(SkillSecret.VALUE_FIELD, stored("GAMMA_API_KEY")).plaintext());
        assertEquals("[]", kept().toString(), "the kept file is deleted once its value is stored");
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
                        "GAMMA_API_KEY", List.of("public-api.gamma.app"));
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
    void aKeptIdThatLeadsOutOfTheKeptFolderIsRefusedAndAMissingOneIsNotFound() throws Exception {
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
    void noLogLineOrResponseCarriesAKeptFilesContent() throws Exception {
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
