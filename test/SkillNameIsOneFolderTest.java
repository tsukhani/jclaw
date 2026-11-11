import agents.SkillLoader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import controllers.RequestPrincipal;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.SkillPromotionService;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A skill route's {@code {name}} is one folder directly inside the skills folder (JF-291): any other name is a
 * 400 before a file is read, listed, moved or deleted, so the mask on a skill's {@code credentials/} is always
 * decided against that skill's own folder.
 *
 * <p>The file reads are sent as {@code jclaw_api} sends them, with the bearer and the agent header; the
 * operator-only routes with the operator's session.
 */
class SkillNameIsOneFolderTest extends FunctionalTest {

    private static final String SECRET = "sk-live-123";
    private static final String API_JSON = "{\"token\": \"" + SECRET + "\"}";
    private static final String MASKED_JSON = "{\n  \"token\": \"[CREDENTIAL]\"\n}";
    private static final String SKILL_MD = "---\nname: %s\ndescription: A probe\nversion: 1.0.0\n---\n# %s\n";

    /** A route's name segment as sent, and the name the router decodes it to. */
    private record Name(String sent, String decoded) {}

    private static final List<Name> NOT_ONE_FOLDER = List.of(
            new Name("%2e", "."), new Name(".", "."), new Name("%2e%2e", ".."), new Name("..", ".."),
            new Name("probe%2Fcredentials", "probe/credentials"), new Name("x%2F..%2Fprobe", "x/../probe"),
            new Name("probe%2F", "probe/"), new Name("%2Fprobe", "/probe"),
            new Name("probe%5Ccredentials", "probe\\credentials"), new Name("%00", "\0"));

    private Path globalSkills;
    private Path agentSkills;
    private Long agentId;
    private String agentName;
    private String bearer;

    // No Fixtures.deleteDatabase(): the one row here is this class's, by a name no other class uses.
    @BeforeEach
    void seed() throws IOException {
        AuthFixture.seedAdminPassword("changeme");
        bearer = AuthFixture.seedBearerToken();
        globalSkills = Files.createTempDirectory("jf291-skills-");
        Play.configuration.setProperty("jclaw.skills.path", globalSkills.toString());
        SkillLoader.clearCache();
        seedSkill(globalSkills, "probe");

        agentName = "jf291-" + UUID.randomUUID().toString().substring(0, 8);
        agentId = commit(() -> {
            var agent = new Agent();
            agent.name = agentName;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
        agentSkills = AgentService.workspacePath(agentName).resolve("skills");
        seedSkill(agentSkills, "probe");
    }

    @AfterEach
    void cleanUp() throws IOException {
        clearCookies();
        Play.configuration.remove("jclaw.skills.path");
        SkillLoader.clearCache();
        if (Files.exists(globalSkills)) SkillPromotionService.deleteRecursive(globalSkills);
        var workspace = AgentService.workspacePath(agentName);
        if (Files.exists(workspace)) SkillPromotionService.deleteRecursive(workspace);
    }

    // --- the file reads -----------------------------------------------------------------

    @Test
    void aNameForTheSkillsFolderItselfNeverReadsACredentialFile() {
        var root = new Name("%2e", ".");
        assertNotASkillName(root, agentGet("/api/skills/%2e/files/probe/credentials/api.json"));
    }

    @Test
    void aNameForTheInsideOfASkillNeverReadsACredentialFile() {
        var inside = new Name("probe%2Fcredentials", "probe/credentials");
        assertNotASkillName(inside, agentGet("/api/skills/probe%2Fcredentials/files/api.json"));
    }

    @Test
    void aNameForAnAgentsSkillsFolderItselfNeverReadsACredentialFile() {
        var root = new Name("%2e", ".");
        assertNotASkillName(root, agentGet("/api/agents/" + agentId + "/skills/%2e/files/probe/credentials/api.json"));
    }

    @Test
    void aNameForTheInsideOfAnAgentsSkillNeverReadsACredentialFile() {
        var inside = new Name("probe%2Fcredentials", "probe/credentials");
        assertNotASkillName(inside,
                agentGet("/api/agents/" + agentId + "/skills/probe%2Fcredentials/files/api.json"));
    }

    @Test
    void theGlobalFileReadRefusesANameThatIsNotOneFolder() {
        for (var name : NOT_ONE_FOLDER) {
            for (var file : List.of("SKILL.md", "api.json", "credentials/api.json", "probe/credentials/api.json")) {
                assertNotASkillName(name, agentGet("/api/skills/" + name.sent() + "/files/" + file));
            }
        }
    }

    @Test
    void theAgentFileReadRefusesANameThatIsNotOneFolder() {
        for (var name : NOT_ONE_FOLDER) {
            for (var file : List.of("SKILL.md", "api.json", "credentials/api.json", "probe/credentials/api.json")) {
                assertNotASkillName(name,
                        agentGet("/api/agents/" + agentId + "/skills/" + name.sent() + "/files/" + file));
            }
        }
    }

    @Test
    void aCredentialFileReadsMaskedThroughBothRoutesHoweverItsPathIsSpelled() throws IOException {
        for (var skill : List.of(globalSkills.resolve("probe"), agentSkills.resolve("probe"))) {
            Files.createSymbolicLink(skill.resolve("notes.json"), skill.resolve("credentials/api.json"));
            Files.createSymbolicLink(skill.resolve("linked"), skill.resolve("credentials"));
        }
        for (var base : List.of("/api/skills/probe/files/", "/api/agents/" + agentId + "/skills/probe/files/")) {
            for (var file : List.of("credentials/api.json", "./credentials/api.json", "credentials/./api.json",
                    "credentials//api.json", "credentials/../credentials/api.json", "x/../credentials/api.json",
                    "%63redentials/api.json", "credentials%2Fapi.json", "notes.json", "linked/api.json")) {
                var response = agentGet(base + file);
                assertStatus(200, response);
                assertEquals(MASKED_JSON, json(response).get("content").getAsString(), base + file);
            }
            var skillMd = agentGet(base + "SKILL.md");
            assertEquals(SKILL_MD.formatted("probe", "probe"), json(skillMd).get("content").getAsString(), base);
        }
    }

    @Test
    void aCredentialFileReadsMaskedUnderACaseVariantOfTheSkillAndTheFolder() {
        Assumptions.assumeTrue(Files.exists(globalSkills.resolve("PROBE/CREDENTIALS/api.json")),
                "needs a case-insensitive file system");

        for (var url : List.of("/api/skills/PROBE/files/CREDENTIALS/api.json",
                "/api/agents/" + agentId + "/skills/PROBE/files/CREDENTIALS/api.json")) {
            var response = agentGet(url);
            assertStatus(200, response);
            assertEquals(MASKED_JSON, json(response).get("content").getAsString(), url);
        }
    }

    @Test
    void aNameLinkedIntoASkillsCredentialsFolderIsRefused() throws IOException {
        Files.createSymbolicLink(globalSkills.resolve("alias"), globalSkills.resolve("probe/credentials"));
        Files.createSymbolicLink(agentSkills.resolve("alias"), agentSkills.resolve("probe/credentials"));

        var alias = new Name("alias", "alias");
        assertNotASkillName(alias, agentGet("/api/skills/alias/files/api.json"));
        assertNotASkillName(alias, agentGet("/api/agents/" + agentId + "/skills/alias/files/api.json"));
    }

    @Test
    void aNameLinkedToAnotherSkillFolderReadsThatSkillMasked() throws IOException {
        Files.createSymbolicLink(globalSkills.resolve("alias"), globalSkills.resolve("probe"));
        Files.createSymbolicLink(agentSkills.resolve("alias"), agentSkills.resolve("probe"));

        for (var url : List.of("/api/skills/alias/files/credentials/api.json",
                "/api/agents/" + agentId + "/skills/alias/files/credentials/api.json")) {
            var response = agentGet(url);
            assertStatus(200, response);
            assertEquals(MASKED_JSON, json(response).get("content").getAsString(), url);
        }
    }

    @Test
    void aFolderNameWithDotsAndSpacesIsStillASkillName() throws IOException {
        seedSkill(globalSkills, "probe.v2 beta");
        seedSkill(agentSkills, "probe.v2 beta");

        for (var base : List.of("/api/skills/probe.v2%20beta/files", "/api/agents/" + agentId + "/skills/probe.v2%20beta/files")) {
            assertStatus(200, agentGet(base));
            var response = agentGet(base + "/credentials/api.json");
            assertStatus(200, response);
            assertEquals(MASKED_JSON, json(response).get("content").getAsString(), base);
        }
        assertStatus(404, agentGet("/api/skills/no-such-skill/files/SKILL.md"));
        assertStatus(404, agentGet("/api/agents/" + agentId + "/skills/no-such-skill/files/SKILL.md"));
    }

    @Test
    void anotherAgentsIdIsRefusedBeforeTheNameIsJudged() {
        var other = commit(() -> {
            var agent = new Agent();
            agent.name = agentName + "-other";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            return agent.id;
        });

        var response = agentGet("/api/agents/" + other + "/skills/%2e/files/probe/credentials/api.json");

        assertStatus(403, response);
        assertEquals("agent_scope", json(response).get("code").getAsString());
    }

    // --- every other route that takes a name ----------------------------------------------

    @Test
    void everyGlobalRouteRefusesANameThatIsNotOneFolderAndTheRegistryIsUntouched() throws IOException {
        signIn();
        for (var name : NOT_ONE_FOLDER) {
            var base = "/api/skills/" + name.sent();
            assertNotASkillName(name, GET(base));
            assertNotASkillName(name, GET(base + "/files"));
            assertNotASkillName(name, GET(base + "/export"));
            assertNotASkillName(name, PUT(base + "/rename", "application/json", "{\"newName\":\"renamed\"}"));
            assertNotASkillName(name, DELETE(base));
        }
        assertEquals(API_JSON, Files.readString(globalSkills.resolve("probe/credentials/api.json")));
        assertFalse(Files.exists(globalSkills.resolve("renamed")));
    }

    @Test
    void everyPerAgentRouteRefusesANameThatIsNotOneFolderAndTheWorkspaceIsUntouched() throws IOException {
        signIn();
        for (var name : NOT_ONE_FOLDER) {
            var base = "/api/agents/" + agentId + "/skills/" + name.sent();
            assertNotASkillName(name, PUT(base, "application/json", "{\"enabled\":false}"));
            assertNotASkillName(name, GET(base + "/files"));
            assertNotASkillName(name, POST(base + "/copy", "application/json", ""));
            assertNotASkillName(name, DELETE(base + "/delete"));
        }
        assertEquals(API_JSON, Files.readString(agentSkills.resolve("probe/credentials/api.json")));
        assertEquals(List.of("probe"), folderNames(agentSkills));
    }

    @Test
    void aSkillWhoseFolderIsALinkOutOfTheSkillsFolderIsStillSwitchedOffAndOn() throws IOException {
        signIn();
        var outside = Files.createTempDirectory("jf291-linked-");
        try {
            Files.writeString(outside.resolve("SKILL.md"), SKILL_MD.formatted("linked", "linked"));
            Files.createSymbolicLink(agentSkills.resolve("linked"), outside);
            var base = "/api/agents/" + agentId + "/skills";
            assertTrue(listedSkill(base, "linked").get("enabled").getAsBoolean(), "the agent loads the linked skill");

            for (var enabled : List.of(false, true)) {
                var response = PUT(base + "/linked", "application/json", "{\"enabled\":" + enabled + "}");
                assertStatus(200, response);
                assertEquals("{\"name\":\"linked\",\"enabled\":" + enabled + ",\"status\":\"ok\"}", getContent(response));
                assertEquals(enabled, listedSkill(base, "linked").get("enabled").getAsBoolean());
            }
            // The limit: the switch takes the folder's name, and its files stay refused.
            assertNotASkillName(new Name("linked", "linked"), GET(base + "/linked/files"));
            assertNotASkillName(new Name("linked", "linked"), agentGet(base + "/linked/files/SKILL.md"));
        } finally {
            SkillPromotionService.deleteRecursive(outside);
        }
    }

    @Test
    void aSkillIsRenamedOnlyToOneFolderName() throws IOException {
        signIn();
        seedSkill(globalSkills, "other");

        for (var newName : List.of("other/inner", "..", ".", "a\\b")) {
            var response = PUT("/api/skills/probe/rename", "application/json",
                    "{\"newName\":" + new JsonPrimitive(newName) + "}");
            assertNotASkillName(new Name(newName, newName), response);
        }
        assertEquals(List.of("other", "probe"), folderNames(globalSkills));
        assertFalse(Files.exists(globalSkills.resolve("other/inner")));

        assertIsOk(PUT("/api/skills/probe/rename", "application/json", "{\"newName\":\"probe.v2\"}"));
        assertEquals(List.of("other", "probe.v2"), folderNames(globalSkills));
    }

    @Test
    void aSkillIsPromotedOnlyByOneFolderName() throws IOException {
        signIn();
        // A SKILL.md at the skills root and one in a subfolder, which a path-shaped name would reach.
        Files.writeString(agentSkills.resolve("SKILL.md"), SKILL_MD.formatted("root", "root"));
        Files.createDirectories(agentSkills.resolve("probe/inner"));
        Files.writeString(agentSkills.resolve("probe/inner/SKILL.md"), SKILL_MD.formatted("inner", "inner"));

        for (var skillName : List.of(".", "probe/inner", "probe/..", "x/../probe")) {
            var response = POST("/api/skills/promote", "application/json",
                    "{\"agentId\":" + agentId + ",\"skillName\":\"" + skillName + "\"}");
            assertStatus(404, response);
            assertEquals(List.of("not_found", "Skill '" + skillName + "' not found in agent workspace"),
                    List.of(json(response).get("code").getAsString(), json(response).get("message").getAsString()));
        }
        assertEquals(List.of("probe"), folderNames(globalSkills));
    }

    @Test
    void theBuiltInSkillIsNotDeletedThroughAnotherNameForItsFolder() throws IOException {
        signIn();
        seedSkill(globalSkills, "skill-creator");
        Files.createSymbolicLink(globalSkills.resolve("alias"), globalSkills.resolve("skill-creator"));

        for (var name : Files.exists(globalSkills.resolve("SKILL-CREATOR"))
                ? List.of("skill-creator", "alias", "SKILL-CREATOR") : List.of("skill-creator", "alias")) {
            var response = DELETE("/api/skills/" + name);
            assertStatus(403, response);
            assertEquals("The skill-creator skill is a built-in skill and cannot be deleted.",
                    json(response).get("message").getAsString(), name);
        }
        assertTrue(Files.exists(globalSkills.resolve("skill-creator/SKILL.md")));

        assertIsOk(DELETE("/api/skills/probe"));
        assertFalse(Files.exists(globalSkills.resolve("probe")));
    }

    // --- helpers ------------------------------------------------------------------------

    private static void assertNotASkillName(Name name, Http.Response response) {
        var body = getContent(response);
        assertFalse(body.contains(SECRET), "the credential leaked for " + name.sent());
        assertStatus(400, response);
        assertEquals(List.of("invalid_request",
                        "'" + name.decoded() + "' is not a skill name: pass the skill's folder name, not a path."),
                List.of(json(response).get("code").getAsString(), json(response).get("message").getAsString()),
                name.sent());
    }

    /** {@code root/<folder>} with a SKILL.md and one credential file. */
    private static void seedSkill(Path root, String folder) throws IOException {
        var dir = Files.createDirectories(root.resolve(folder).resolve("credentials")).getParent();
        Files.writeString(dir.resolve("SKILL.md"), SKILL_MD.formatted(folder, folder));
        Files.writeString(dir.resolve("credentials/api.json"), API_JSON);
        SkillLoader.clearCache();
    }

    private static List<String> folderNames(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** The row for {@code skillName} in an agent's installed-skills list. */
    private static JsonObject listedSkill(String agentSkillsUrl, String skillName) {
        var listed = GET(agentSkillsUrl);
        assertStatus(200, listed);
        for (var row : JsonParser.parseString(getContent(listed)).getAsJsonArray()) {
            if (skillName.equals(row.getAsJsonObject().get("name").getAsString())) return row.getAsJsonObject();
        }
        throw new AssertionError(skillName + " is not in " + getContent(listed));
    }

    private void signIn() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    /** A bearer GET stamped as this class's agent. Its answer sets the agent principal's cookie, which is dropped. */
    private Http.Response agentGet(String url) {
        clearCookies();
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + bearer));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        try {
            return GET(request, url);
        } finally {
            clearCookies();
        }
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static <T> T commit(Supplier<T> block) {
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
