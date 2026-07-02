import agents.SkillLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.SkillConformanceService;
import services.SkillPromotionService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Exercises the LLM-dependent halves of the skills pipeline against a local
 * mock OpenAI-compatible server (no network beyond 127.0.0.1):
 * <ul>
 *   <li>{@link SkillConformanceService#conform} generation success — the LLM
 *       proposal is parsed (plain, fenced, and prose-wrapped output), gated,
 *       and written back as a conforming SKILL.md; unusable output fails.</li>
 *   <li>{@code SkillPromotionService.sanitizeWithLlm} — redactions from the
 *       model are applied, files missing from the response keep their
 *       originals, and a non-JSON response falls back to originals.</li>
 * </ul>
 *
 * <p>The provider is registered the production way: {@code provider.<name>.*}
 * config rows + {@code ProviderRegistry.refresh()}, with
 * {@code skillsPromotion.provider}/{@code .model} pointing at it — the exact
 * resolution path both services use. All config rows are deleted (and the
 * registry re-refreshed) in teardown so no other test sees the mock provider.
 * All LLM-mocked skill tests live in this ONE class so the shared
 * {@code skillsPromotion.*} config keys are never contended across classes.
 */
class SkillLlmMockedPipelineTest extends UnitTest {

    private static final String PROVIDER_NAME = "mock-skills-llm";
    private static final String[] CONFIG_KEYS = {
            "provider." + PROVIDER_NAME + ".baseUrl",
            "provider." + PROVIDER_NAME + ".apiKey",
            "skillsPromotion.provider",
            "skillsPromotion.model"
    };

    private static HttpServer server;
    /** The assistant message content the mock returns for the next chat call. */
    private static volatile String nextAssistantContent = "{}";

    private Path stagedDir;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); // drain
            var body = completionJson(nextAssistantContent)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    /** A minimal OpenAI-shaped chat completion carrying {@code content}. */
    private static String completionJson(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        var choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        var choices = new JsonArray();
        choices.add(choice);
        var root = new JsonObject();
        root.addProperty("id", "mock-1");
        root.addProperty("model", "mock-model");
        root.add("choices", choices);
        return root.toString();
    }

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ConfigService.set("provider." + PROVIDER_NAME + ".baseUrl", baseUrl);
        ConfigService.set("provider." + PROVIDER_NAME + ".apiKey", "test-key");
        ConfigService.set("skillsPromotion.provider", PROVIDER_NAME);
        ConfigService.set("skillsPromotion.model", "mock-model");
        // refresh() is CAS-guarded and silently skips when another thread is
        // mid-refresh — retry until the mock provider is actually visible.
        for (int i = 0; i < 20 && llm.ProviderRegistry.get(PROVIDER_NAME) == null; i++) {
            llm.ProviderRegistry.refresh();
            Thread.sleep(50);
        }
        assertNotNull(llm.ProviderRegistry.get(PROVIDER_NAME),
                "mock LLM provider must be registered before the pipeline tests run");
        stagedDir = Files.createTempDirectory("skill-llm-mock-");
    }

    @AfterEach
    void teardown() throws Exception {
        for (var key : CONFIG_KEYS) {
            ConfigService.delete(key);
        }
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        if (stagedDir != null && Files.exists(stagedDir)) {
            SkillPromotionService.deleteRecursive(stagedDir);
        }
    }

    private void stageForeignSkill() throws IOException {
        Files.writeString(stagedDir.resolve("SKILL.md"), """
                ---
                name: My Cool Skill
                tools: Bash, Read
                ---
                # Cool Skill

                Run things with Bash.
                """);
    }

    // ==================== conform — generation success ====================

    @Test
    void conformRewritesSkillMdFromLlmProposal() throws Exception {
        stageForeignSkill();
        // A binary next to SKILL.md: commands must derive from it, not the LLM.
        Files.write(stagedDir.resolve("helper.bin"), new byte[]{1, 2, 3});
        nextAssistantContent =
                "{\"name\": \"cool-skill\", \"description\": \"Does cool things\", "
                        + "\"icon\": \"🧪\", \"tools\": [\"exec\"]}";

        var result = SkillConformanceService.conform(stagedDir, "fallback-id", "owner/repo");

        assertTrue(result.ok(), "conform must succeed: " + result.message());
        assertEquals("cool-skill", result.skillName());

        var rewritten = Files.readString(stagedDir.resolve("SKILL.md"));
        var info = SkillLoader.parseSkillContent(rewritten, null);
        assertNotNull(info, "conformed SKILL.md must parse");
        assertEquals("cool-skill", info.name());
        assertEquals("Does cool things", info.description());
        assertEquals("owner/repo", info.author(), "provenance recorded as author");
        assertEquals("🧪", info.icon());
        assertEquals(java.util.List.of("exec"), info.tools(), "LLM-mapped tools declared");
        assertEquals(java.util.List.of("helper.bin"), info.commands(),
                "commands derived from staged binaries, never from the model");
        assertTrue(rewritten.contains("Run things with Bash."),
                "original body preserved verbatim: " + rewritten);
    }

    @Test
    void conformParsesFencedAndProseWrappedLlmOutput() throws Exception {
        stageForeignSkill();
        // Prose + code fence + a null tools element: the lenient extractor must
        // find the JSON object and strList must skip the null.
        nextAssistantContent = """
                Sure! Here is the frontmatter you asked for:
                ```json
                {"name": "fenced-skill", "description": "d", "icon": "🧷", "tools": ["exec", null]}
                ```
                """;

        var result = SkillConformanceService.conform(stagedDir, "fallback-id", "owner/repo");

        assertTrue(result.ok(), "fenced/prose-wrapped output must still conform: " + result.message());
        assertEquals("fenced-skill", result.skillName());
        var info = SkillLoader.parseSkillContent(Files.readString(stagedDir.resolve("SKILL.md")), null);
        assertEquals(java.util.List.of("exec"), info.tools(), "null array elements skipped");
    }

    @Test
    void conformFailsWhenLlmReturnsNoJson() throws Exception {
        stageForeignSkill();
        var original = Files.readString(stagedDir.resolve("SKILL.md"));
        nextAssistantContent = "I cannot help with that request.";

        var result = SkillConformanceService.conform(stagedDir, "fallback-id", "owner/repo");

        assertFalse(result.ok(), "unparseable LLM output must fail conformance");
        assertTrue(result.message().contains("conformance pass failed"),
                "failure message: " + result.message());
        assertEquals(original, Files.readString(stagedDir.resolve("SKILL.md")),
                "SKILL.md untouched on failure");
    }

    @Test
    void conformRejectsWhenLlmProposesUnknownTool() throws Exception {
        // Generation succeeded but the model invented a tool: the deterministic
        // gate — not the model — must refuse, end-to-end through conform().
        stageForeignSkill();
        nextAssistantContent =
                "{\"name\": \"bad-tools\", \"description\": \"d\", \"icon\": \"🧪\", "
                        + "\"tools\": [\"made_up_tool_zzz\"]}";

        var result = SkillConformanceService.conform(stagedDir, "fallback-id", "owner/repo");

        assertFalse(result.ok(), "an unmappable tool must reject the import");
        assertTrue(result.message().contains("made_up_tool_zzz"),
                "rejection names the offending tool: " + result.message());
    }

    // ==================== sanitizeWithLlm ====================

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, String> invokeSanitize(LinkedHashMap<String, String> input)
            throws Exception {
        var m = SkillPromotionService.class.getDeclaredMethod("sanitizeWithLlm", LinkedHashMap.class);
        m.setAccessible(true);
        return (LinkedHashMap<String, String>) m.invoke(null, input);
    }

    @Test
    void sanitizeAppliesRedactionsAndKeepsFilesMissingFromResponse() throws Exception {
        var input = new LinkedHashMap<String, String>();
        input.put("SKILL.md", "Contact admin@example.com with key sk-12345");
        input.put("notes.md", "nothing secret here");
        // The model redacts SKILL.md but omits notes.md from its response —
        // the omitted file must keep its original content.
        nextAssistantContent = "{\"SKILL.md\": \"Contact [EMAIL] with key [API_KEY]\"}";

        var result = invokeSanitize(input);

        assertEquals("Contact [EMAIL] with key [API_KEY]", result.get("SKILL.md"),
                "the model's redaction must be applied");
        assertEquals("nothing secret here", result.get("notes.md"),
                "a file missing from the LLM response keeps its original");
        assertEquals(2, result.size(), "every input file appears in the output");
    }

    @Test
    void sanitizeFallsBackToOriginalsWhenResponseIsNotJson() throws Exception {
        var input = new LinkedHashMap<String, String>();
        input.put("SKILL.md", "body with sk-secret-999");
        nextAssistantContent = "definitely not a JSON object";

        var result = invokeSanitize(input);

        assertEquals("body with sk-secret-999", result.get("SKILL.md"),
                "a failed batch must fall back to the original content");
    }
}
