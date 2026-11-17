import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkillConfig;
import models.SkillSecret;
import models.SkillSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.KeptSkillFiles;
import services.SkillSecrets;
import services.Tx;
import utils.CredentialCipher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-356: deleting an agent's skill that holds secrets, settings or saved state needs the plan its removal preview
 * showed, and each value another skill of the agent declares moves to that skill instead of being deleted, so the
 * skills that read it by name keep working. Switching such a skill off deletes nothing and names the skills that stop.
 */
class ApiSkillRemovalTest extends FunctionalTest {

    private static final String KEY = "fake-radarr-key-jf356-4b2d";

    private Long agentId;
    private String agentName;

    @BeforeEach
    void seed() throws Exception {
        AuthFixture.seedAdminPassword("changeme");
        agentName = "jf356-" + UUID.randomUUID().toString().substring(0, 8);
        agentId = commitInFreshTx(() -> AgentService.create(agentName, "openrouter", "gpt-4.1").id);
        KeptSkillFiles.deleteForAgent(agentId);
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
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
        agents.SkillLoader.clearCache();
    }

    /** A skill whose SKILL.md declares {@code lines}, one declaration each, in the secrets block. */
    private Path skill(String name, String lines) throws Exception {
        var dir = AgentService.workspacePath(agentName).resolve("skills/" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: test skill\n---\n# " + name
                + "\n\n## Secrets\n\n```secrets\n" + lines + "```\n");
        agents.SkillLoader.clearCache();
        return dir;
    }

    private void switchOff(String skill) {
        commitInFreshTx(() -> {
            var config = new AgentSkillConfig();
            config.agent = Agent.findById(agentId);
            config.skillName = skill;
            config.enabled = false;
            config.save();
            return null;
        });
        agents.SkillLoader.clearCache();
    }

    private String base(String skill) {
        return "/api/agents/" + agentId + "/skills/" + skill;
    }

    private void putSecret(String skill, String name, String value) {
        assertIsOk(PUT(base(skill) + "/secrets/" + name, "application/json",
                "{\"value\":\"" + value + "\",\"hosts\":[\"radarr.example.com\"]}"));
    }

    private void putSetting(String skill, String name, String value) {
        assertIsOk(PUT(base(skill) + "/settings/" + name, "application/json", "{\"value\":\"" + value + "\"}"));
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    /** The preview without its plan, which is a digest, and the plan, checked for its shape. */
    private JsonObject preview(String skill) {
        var body = json(GET(base(skill) + "/removal"));
        var plan = body.remove("plan").getAsString();
        assertTrue(plan.matches("[0-9a-f]{16}"), "plan: " + plan);
        return body;
    }

    private String plan(String skill) {
        return json(GET(base(skill) + "/removal")).get("plan").getAsString();
    }

    private Http.Response delete(String skill, String plan) {
        return DELETE(base(skill) + "/delete" + (plan == null ? "" : "?plan=" + plan));
    }

    private static String refusal(String skill) {
        return "{\"type\":\"error\",\"code\":\"conflict\",\"message\":\"Skill '" + skill + "' holds secrets, settings, key"
                + " files or saved state, and what deleting it does was not confirmed or has changed since it was shown:"
                + " review it again and confirm.\",\"template\":{\"whatBroke\":\"The skill was not deleted: what deleting"
                + " it removes or moves was not confirmed, or has changed since it was shown.\",\"whatToCheck\":\"A skill"
                + " that holds secrets, settings, key files or saved state is deleted only with the plan its removal preview"
                + " gave, and a value added or a skill switched since then changes that plan.\",\"howToRetry\":\"Delete the"
                + " skill again, read what it removes and moves, and confirm.\"}}";
    }

    /** Each of the agent's secrets and settings as {@code kind skill NAME}, in order. */
    private List<String> held() {
        return commitInFreshTx(() -> {
            var out = new java.util.ArrayList<String>();
            for (Object o : SkillSecret.find("agent.id = ?1 ORDER BY skillName, name", agentId).fetch()) {
                var row = (SkillSecret) o;
                out.add("secret " + row.skillName + " " + row.name);
            }
            for (Object o : SkillSetting.find("agent.id = ?1 ORDER BY skillName, name", agentId).fetch()) {
                var row = (SkillSetting) o;
                out.add("setting " + row.skillName + " " + row.name);
            }
            return List.copyOf(out);
        });
    }

    /** The radarr family: radarr holds a key three skills read, a setting sonarr reads, and a secret no one else does. */
    private void radarrFamily() throws Exception {
        skill("radarr", "RADARR_API_KEY=radarr.example.com   # API key\nRADARR_WEBHOOK=radarr.example.com   # webhook token\n"
                + "setting TRANSMISSION_URL=http://nas.example.com:9091   # Transmission\n");
        skill("radarr-delete", "RADARR_API_KEY=radarr.example.com   # API key\n");
        skill("radarr-library", "RADARR_API_KEY=radarr.example.com   # API key\n");
        skill("sonarr", "setting TRANSMISSION_URL=http://nas.example.com:9091   # Transmission\n");
        switchOff("radarr-delete");
        putSecret("radarr", "RADARR_API_KEY", KEY);
        putSecret("radarr", "RADARR_WEBHOOK", "fake-webhook-jf356");
        putSetting("radarr", "TRANSMISSION_URL", "http://nas.example.com:9091");
    }

    @Test
    void aSkillWhoseValuesOtherSkillsUseAsksFirstAndMovesThemToASwitchedOnOne() throws Exception {
        radarrFamily();

        assertEquals(JsonParser.parseString("{"
                + "\"secrets\":[{\"name\":\"RADARR_API_KEY\",\"movesTo\":\"radarr-library\",\"usedBy\":[\"radarr-delete\",\"radarr-library\"]},"
                + "{\"name\":\"RADARR_WEBHOOK\",\"movesTo\":null,\"usedBy\":[]}],"
                + "\"settings\":[{\"name\":\"TRANSMISSION_URL\",\"movesTo\":\"sonarr\",\"usedBy\":[\"sonarr\"]}],"
                + "\"savedState\":false,\"credentialFiles\":[],\"keptFiles\":0,\"asks\":true,"
                + "\"stopsIfSwitchedOff\":[{\"skill\":\"radarr-library\",\"names\":[\"RADARR_API_KEY\"]},"
                + "{\"skill\":\"sonarr\",\"names\":[\"TRANSMISSION_URL\"]}]}"), preview("radarr"));

        var unconfirmed = delete("radarr", null);
        assertEquals(409, unconfirmed.status.intValue());
        assertEquals(refusal("radarr"), getContent(unconfirmed));
        assertEquals(List.of("secret radarr RADARR_API_KEY", "secret radarr RADARR_WEBHOOK", "setting radarr TRANSMISSION_URL"),
                held(), "a refused delete deletes and moves nothing");
        assertTrue(Files.isDirectory(AgentService.workspacePath(agentName).resolve("skills/radarr")));

        assertIsOk(delete("radarr", plan("radarr")));

        assertEquals(List.of("secret radarr-library RADARR_API_KEY", "setting sonarr TRANSMISSION_URL"), held());
        var moved = commitInFreshTx(() -> (SkillSecret) SkillSecret.find("agent.id = ?1 AND name = ?2", agentId,
                "RADARR_API_KEY").first());
        assertEquals(KEY, CredentialCipher.current().open(SkillSecret.VALUE_FIELD, rawValue("RADARR_API_KEY")).plaintext());
        assertEquals("radarr.example.com", moved.hosts);
        assertEquals(List.of(), commitInFreshTx(() -> SkillSecrets.provided(Agent.findById(agentId))
                .missing(AgentService.workspacePath(agentName).resolve("skills/sonarr"))), "sonarr keeps its setting");
        assertEquals(List.of(), commitInFreshTx(() -> SkillSecrets.provided(Agent.findById(agentId))
                .missing(AgentService.workspacePath(agentName).resolve("skills/radarr-library"))), "and radarr-library its key");
        assertFalse(Files.exists(AgentService.workspacePath(agentName).resolve("skills/radarr")));
    }

    private String rawValue(String name) {
        return commitInFreshTx(() -> (String) play.db.jpa.JPA.em().createNativeQuery(
                        "SELECT s.secret_value FROM skill_secret s WHERE s.agent_id = ?1 AND s.name = ?2")
                .setParameter(1, agentId).setParameter(2, name).getResultList().stream().findFirst().orElse(null));
    }

    @Test
    void aSkillWhoseValuesNoOtherSkillUsesAsksFirstAndDeletesThem() throws Exception {
        skill("demo", "API_KEY=api.example.com   # API key\n");
        putSecret("demo", "API_KEY", KEY);

        assertEquals(JsonParser.parseString("{\"secrets\":[{\"name\":\"API_KEY\",\"movesTo\":null,\"usedBy\":[]}],"
                + "\"settings\":[],\"savedState\":false,\"credentialFiles\":[],\"keptFiles\":0,\"asks\":true,"
                + "\"stopsIfSwitchedOff\":[]}"), preview("demo"));
        assertEquals(409, delete("demo", "0000000000000000").status.intValue());
        assertEquals(List.of("secret demo API_KEY"), held());

        assertIsOk(delete("demo", plan("demo")));
        assertEquals(List.of(), held());
    }

    @Test
    void aSkillWithOnlySavedStateAsksFirstAndDeletesTheState() throws Exception {
        skill("demo", "");
        var state = AgentService.workspacePath(agentName).resolve("skill-state/demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("cookies.txt"), "x\n");

        assertEquals(JsonParser.parseString("{\"secrets\":[],\"settings\":[],\"savedState\":true,\"credentialFiles\":[],"
                + "\"keptFiles\":0,\"asks\":true,\"stopsIfSwitchedOff\":[]}"), preview("demo"));
        assertEquals(409, delete("demo", null).status.intValue());
        assertTrue(Files.exists(state.resolve("cookies.txt")));

        assertIsOk(delete("demo", plan("demo")));
        assertFalse(Files.exists(state));
    }

    @Test
    void aSkillWhoseKeyIsOnlyInItsCredentialsFolderAsksFirst() throws Exception {
        var dir = skill("demo", "");
        Files.createDirectories(dir.resolve("Credentials/nested"));
        Files.writeString(dir.resolve("Credentials/api-key.txt"), KEY + "\n");
        Files.writeString(dir.resolve("Credentials/nested/token"), "fake-token-jf356\n");
        Files.writeString(dir.resolve("Credentials/blank.txt"), "");

        assertEquals(JsonParser.parseString("{\"secrets\":[],\"settings\":[],\"savedState\":false,"
                + "\"credentialFiles\":[\"Credentials/api-key.txt\",\"Credentials/nested/token\"],\"keptFiles\":0,"
                + "\"asks\":true,\"stopsIfSwitchedOff\":[]}"), preview("demo"), "an empty file holds no key");
        assertEquals(409, delete("demo", null).status.intValue());
        assertTrue(Files.exists(dir.resolve("Credentials/api-key.txt")));

        assertIsOk(delete("demo", plan("demo")));
        assertFalse(Files.exists(dir));
    }

    @Test
    void aSkillWithOnlyFilesKeptFromAnUpdateAsksFirst() throws Exception {
        skill("demo", "");
        var kept = KeptSkillFiles.root().resolve(String.valueOf(agentId)).resolve("demo/20261117T120000.000Z/credentials");
        Files.createDirectories(kept);
        Files.writeString(kept.resolve("api-key.txt"), KEY + "\n");

        assertEquals(1, preview("demo").get("keptFiles").getAsInt());
        assertEquals(409, delete("demo", null).status.intValue());

        assertIsOk(delete("demo", plan("demo")));
        assertEquals("{\"kept\":[]}", getContent(GET(base("demo") + "/kept")));
    }

    @Test
    void aSkillHoldingNothingDeletesWithoutAPlanAsBefore() throws Exception {
        var dir = skill("demo", "");
        Files.createDirectories(AgentService.workspacePath(agentName).resolve("skill-state/demo"));
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("credentials/placeholder.txt"), "");

        assertEquals(JsonParser.parseString("{\"secrets\":[],\"settings\":[],\"savedState\":false,\"credentialFiles\":[],"
                + "\"keptFiles\":0,\"asks\":false,\"stopsIfSwitchedOff\":[]}"), preview("demo"),
                "an empty state folder and an empty credentials file hold nothing");
        assertIsOk(delete("demo", null));
        assertFalse(Files.exists(AgentService.workspacePath(agentName).resolve("skills/demo")));
    }

    @Test
    void aPlanShownBeforeAValueWasAddedIsRefusedAndDeletesNothing() throws Exception {
        skill("demo", "API_KEY=api.example.com   # API key\nsetting SITE_URL=https://site.example.org   # site\n");
        putSecret("demo", "API_KEY", KEY);
        var shown = plan("demo");
        putSetting("demo", "SITE_URL", "https://site.example.org");

        var stale = delete("demo", shown);

        assertEquals(409, stale.status.intValue());
        assertEquals(refusal("demo"), getContent(stale));
        assertEquals(List.of("secret demo API_KEY", "setting demo SITE_URL"), held());
    }

    @Test
    void aValueWhoseOtherUsersAreAllSwitchedOffMovesToTheFirstByName() throws Exception {
        skill("radarr", "RADARR_API_KEY=radarr.example.com   # API key\n");
        skill("radarr-library", "RADARR_API_KEY=radarr.example.com   # API key\n");
        skill("radarr-delete", "RADARR_API_KEY=radarr.example.com   # API key\n");
        switchOff("radarr-library");
        switchOff("radarr-delete");
        putSecret("radarr", "RADARR_API_KEY", KEY);

        assertEquals("{\"name\":\"RADARR_API_KEY\",\"movesTo\":\"radarr-delete\",\"usedBy\":[\"radarr-delete\",\"radarr-library\"]}",
                preview("radarr").getAsJsonArray("secrets").get(0).toString());
        assertIsOk(delete("radarr", plan("radarr")));
        assertEquals(List.of("secret radarr-delete RADARR_API_KEY"), held());
    }

    @Test
    void switchingOffASkillOtherSkillsNeedNamesThemAndDeletesNothing() throws Exception {
        radarrFamily();

        var off = PUT(base("radarr"), "application/json", "{\"enabled\":false}");

        assertIsOk(off);
        assertEquals("{\"name\":\"radarr\",\"enabled\":false,\"status\":\"ok\",\"stops\":["
                + "{\"skill\":\"radarr-library\",\"names\":[\"RADARR_API_KEY\"]},"
                + "{\"skill\":\"sonarr\",\"names\":[\"TRANSMISSION_URL\"]}]}", getContent(off));
        assertEquals(List.of("secret radarr RADARR_API_KEY", "secret radarr RADARR_WEBHOOK", "setting radarr TRANSMISSION_URL"),
                held());
        assertEquals("{\"name\":\"radarr\",\"enabled\":true,\"status\":\"ok\",\"stops\":[]}",
                getContent(PUT(base("radarr"), "application/json", "{\"enabled\":true}")));
    }

    @Test
    void switchingOffASkillNoOtherSkillNeedsStopsNothing() throws Exception {
        skill("demo", "API_KEY=api.example.com   # API key\n");
        skill("other", "OTHER_KEY=other.example.com   # its own key\n");
        putSecret("demo", "API_KEY", KEY);

        assertEquals("{\"name\":\"demo\",\"enabled\":false,\"status\":\"ok\",\"stops\":[]}",
                getContent(PUT(base("demo"), "application/json", "{\"enabled\":false}")));
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
            throw new IllegalStateException(e);
        }
        if (err.get() instanceof RuntimeException re) throw re;
        if (err.get() instanceof Error er) throw er;
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
