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
import services.Tx;
import utils.CredentialCipher;

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
        Files.createDirectories(AgentService.workspacePath(agentName).resolve("skills/demo"));
        Files.writeString(AgentService.workspacePath(agentName).resolve("skills/demo/SKILL.md"), "# Demo\n");
        login();
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
        commitInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            if (agent != null) AgentService.delete(agent);
            return null;
        });
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
        assertIsOk(put("SITE_KEY", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}"));
        assertEquals("400 invalid_request Skill 'demo' on this agent has a secret named SITE_KEY. A command finds a value"
                + " by its name, so each name is used once on an agent.", errorOf(putSetting("SITE_KEY", "{\"value\":\"x\"}")));
        assertIsOk(putSetting("SITE_URL", "{\"value\":\"https://site.example.org\"}"));
        assertEquals("400 invalid_request Skill 'demo' on this agent has a setting named SITE_URL. A command finds a value"
                + " by its name, so each name is used once on an agent.",
                errorOf(put("SITE_URL", "{\"value\":\"v\",\"hosts\":[\"api.example.com\"]}")));
    }

    @Test
    void aSettingTheStoreCannotTakeIsRefusedAndStoresNothing() {
        assertEquals("400 invalid_request A setting is one line: remove the line breaks.",
                errorOf(putSetting("SITE_URL", "{\"value\":\"https://a.example.org\\nexport X=1\"}")));
        assertEquals("400 invalid_request The value is empty.", errorOf(putSetting("SITE_URL", "{\"value\":\"\"}")));
        assertEquals("400 invalid_request The body must be a JSON object with 'value'.", errorOf(putSetting("SITE_URL", "{}")));
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
}
