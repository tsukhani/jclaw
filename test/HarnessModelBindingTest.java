import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.HarnessModel;
import tools.HarnessModelBinding;

import java.util.List;

/**
 * The per-harness translation of an acp model override. Each case pins what the
 * installed CLI accepts (its {@code --help}) and, for claude and codex, what a live
 * run against a local Ollama endpoint needed to succeed.
 */
class HarnessModelBindingTest extends UnitTest {

    private static final HarnessModel MODEL_ONLY = new HarnessModel(null, null, null, "qwen3.5:9b");
    private static final HarnessModel OLLAMA = new HarnessModel("ollama", "http://localhost:11434/v1", "", "qwen3.5:9b");
    private static final HarnessModel KEYED = new HarnessModel("openrouter", "https://openrouter.ai/api/v1/", "sk-or-1", "qwen/qwen3");

    @Test
    void claudeTakesTheModelFlagAndTheAnthropicEnv() {
        assertEquals(List.of("--model", "qwen3.5:9b"), HarnessModelBinding.wrapperArgs("claude", OLLAMA));
        assertEquals(List.of(), HarnessModelBinding.acpArgs("claude", OLLAMA),
                "claude-agent-acp has no model flag — the env carries the override");
        var env = HarnessModelBinding.env("claude", OLLAMA);
        assertEquals("qwen3.5:9b", env.get("ANTHROPIC_MODEL"));
        // Claude Code appends /v1/messages itself, so the provider's /v1 is dropped.
        assertEquals("http://localhost:11434", env.get("ANTHROPIC_BASE_URL"));
        assertEquals(HarnessModelBinding.PLACEHOLDER_TOKEN, env.get("ANTHROPIC_AUTH_TOKEN"),
                "a keyless local provider still needs a non-empty token so claude prefers the endpoint over its login");
        assertEquals("sk-or-1", HarnessModelBinding.env("claude", KEYED).get("ANTHROPIC_AUTH_TOKEN"));
        assertEquals("https://openrouter.ai/api", HarnessModelBinding.env("claude", KEYED).get("ANTHROPIC_BASE_URL"));
    }

    @Test
    void claudeModelOnlyLeavesTheEndpointAlone() {
        var env = HarnessModelBinding.env("claude", MODEL_ONLY);
        assertEquals("qwen3.5:9b", env.get("ANTHROPIC_MODEL"));
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"), "no provider → the harness keeps its own endpoint: " + env);
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"), "no provider → the harness keeps its own login: " + env);
        assertNull(HarnessModelBinding.rejection("claude", MODEL_ONLY));
    }

    @Test
    void codexDeclaresAnInlineProviderOnTheResponsesWireApi() {
        var args = HarnessModelBinding.wrapperArgs("codex", OLLAMA);
        assertEquals(List.of("-m", "qwen3.5:9b"), args.subList(0, 2));
        assertTrue(args.contains("model_provider=jclaw"), args.toString());
        assertTrue(args.contains("model_providers.jclaw.base_url=\"http://localhost:11434/v1\""), args.toString());
        assertTrue(args.contains("model_providers.jclaw.wire_api=\"responses\""),
                "codex refuses wire_api=\"chat\" for a custom provider: " + args);
        assertFalse(String.join(" ", args).contains("env_key"), "a keyless provider declares no env_key: " + args);
        assertTrue(HarnessModelBinding.env("codex", OLLAMA).isEmpty());
        assertEquals(args, HarnessModelBinding.acpArgs("codex", OLLAMA), "codex app-server takes the same overrides");
    }

    @Test
    void codexCarriesTheKeyThroughItsOwnEnvVar() {
        var args = HarnessModelBinding.wrapperArgs("codex", KEYED);
        assertTrue(args.contains("model_providers.jclaw.env_key=\"" + HarnessModelBinding.CODEX_KEY_ENV + "\""), args.toString());
        assertTrue(args.contains("model_providers.jclaw.base_url=\"https://openrouter.ai/api/v1\""),
                "trailing slash trimmed, /v1 kept — codex appends /responses: " + args);
        assertEquals("sk-or-1", HarnessModelBinding.env("codex", KEYED).get(HarnessModelBinding.CODEX_KEY_ENV));
        assertEquals(List.of("-m", "qwen3.5:9b"), HarnessModelBinding.wrapperArgs("codex", MODEL_ONLY));
    }

    @Test
    void tomlStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\"", HarnessModelBinding.toml("a\"b\\c"));
        assertEquals("http://h:1", HarnessModelBinding.anthropicRoot("http://h:1/v1/"));
        assertEquals("http://h:1/api", HarnessModelBinding.anthropicRoot("http://h:1/api"));
    }

    @Test
    void piAndGeminiTakeTheModelOnly() {
        assertEquals(List.of("--model", "qwen3.5:9b"), HarnessModelBinding.wrapperArgs("pi", MODEL_ONLY));
        assertEquals(List.of("-m", "qwen3.5:9b"), HarnessModelBinding.wrapperArgs("gemini", MODEL_ONLY));
        assertEquals(List.of("-m", "qwen3.5:9b"), HarnessModelBinding.acpArgs("gemini", MODEL_ONLY));
        assertNull(HarnessModelBinding.rejection("pi", MODEL_ONLY));
        assertNull(HarnessModelBinding.rejection("gemini", MODEL_ONLY));
        for (var id : List.of("pi", "gemini")) {
            var why = HarnessModelBinding.rejection(id, OLLAMA);
            assertNotNull(why, id + " cannot be pointed at a JClaw provider");
            assertTrue(why.contains(id) && why.contains("ollama"), why);
            assertTrue(HarnessModelBinding.env(id, OLLAMA).isEmpty(), "no env surface: " + id);
        }
    }

    @Test
    void harnessesWithoutAModelSurfaceRefuseAnyOverride() {
        for (var id : List.of("opencode", "antigravity", "generic")) {
            var why = HarnessModelBinding.rejection(id, MODEL_ONLY);
            assertNotNull(why, id + " must refuse even a model-only override");
            assertTrue(why.contains(id), why);
            assertEquals(List.of(), HarnessModelBinding.wrapperArgs(id, OLLAMA));
            assertEquals(List.of(), HarnessModelBinding.acpArgs(id, OLLAMA));
            assertTrue(HarnessModelBinding.env(id, OLLAMA).isEmpty());
        }
    }

    @Test
    void describeNamesEnvVarsButNeverTheirValues() {
        var line = HarnessModelBinding.describe("claude", KEYED, false);
        assertTrue(line.contains("openrouter / qwen/qwen3") && line.contains("--model qwen/qwen3"), line);
        assertTrue(line.contains("ANTHROPIC_AUTH_TOKEN") && !line.contains("sk-or-1"), line);
        var acpLine = HarnessModelBinding.describe("claude", MODEL_ONLY, true);
        assertTrue(acpLine.contains("env ANTHROPIC_MODEL") && !acpLine.contains("--model"), acpLine);
    }
}
