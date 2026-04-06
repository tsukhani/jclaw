import org.junit.jupiter.api.*;
import play.test.*;
import llm.LlmTypes.*;
import llm.OpenAiCompatibleClient;
import llm.ProviderRegistry;
import services.ConfigService;

import java.util.List;
import java.util.Map;

public class LlmClientTest extends UnitTest {

    @Test
    public void chatMessageFactoryMethods() {
        var sys = ChatMessage.system("You are helpful");
        assertEquals("system", sys.role());
        assertEquals("You are helpful", sys.content());

        var user = ChatMessage.user("Hello");
        assertEquals("user", user.role());

        var asst = ChatMessage.assistant("Hi there");
        assertEquals("assistant", asst.role());

        var tool = ChatMessage.toolResult("call-1", "result data");
        assertEquals("tool", tool.role());
        assertEquals("call-1", tool.toolCallId());
    }

    @Test
    public void toolDefCreation() {
        var tool = ToolDef.of("web_fetch", "Fetch a URL",
                Map.of("type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "The URL to fetch")
                        ),
                        "required", List.of("url")));

        assertEquals("function", tool.type());
        assertEquals("web_fetch", tool.function().name());
        assertEquals("Fetch a URL", tool.function().description());
    }

    @Test
    public void providerConfigRecord() {
        var config = new ProviderConfig(
                "openrouter",
                "https://openrouter.ai/api/v1",
                "sk-test-key",
                List.of(
                        new ModelInfo("openai/gpt-4.1", "GPT-4.1", 1047576, 32768),
                        new ModelInfo("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6", 200000, 8192)
                ));

        assertEquals("openrouter", config.name());
        assertEquals(2, config.models().size());
        assertEquals("GPT-4.1", config.models().getFirst().name());
    }

    @Test
    public void assistantMessageWithToolCalls() {
        var toolCalls = List.of(
                new ToolCall("call-1", "function", new FunctionCall("web_fetch", "{\"url\":\"https://example.com\"}"))
        );
        var msg = ChatMessage.assistant(null, toolCalls);
        assertEquals("assistant", msg.role());
        assertNull(msg.content());
        assertEquals(1, msg.toolCalls().size());
        assertEquals("web_fetch", msg.toolCalls().getFirst().function().name());
    }

    @Test
    public void streamAccumulatorStartsEmpty() {
        var acc = new OpenAiCompatibleClient.StreamAccumulator();
        assertFalse(acc.complete);
        assertEquals("", acc.content);
        assertTrue(acc.toolCalls.isEmpty());
        assertNull(acc.error);
    }

    @Test
    public void llmExceptionPreservesMessage() {
        var ex = new OpenAiCompatibleClient.LlmException("Provider down");
        assertEquals("Provider down", ex.getMessage());

        var caused = new OpenAiCompatibleClient.LlmException("Retry failed", new RuntimeException("network"));
        assertEquals("Retry failed", caused.getMessage());
        assertNotNull(caused.getCause());
    }

    @Test
    public void providerRegistryLoadsFromConfig() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();

        ConfigService.set("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        ConfigService.set("provider.openrouter.apiKey", "sk-test");
        ConfigService.set("provider.openrouter.models", """
                [{"id":"openai/gpt-4.1","name":"GPT-4.1","contextWindow":1000000,"maxTokens":32768}]
                """);

        ConfigService.set("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        ConfigService.set("provider.ollama-cloud.apiKey", "ollama-key");

        ProviderRegistry.refresh();

        var providers = ProviderRegistry.listAll();
        assertEquals(2, providers.size());

        var openrouter = ProviderRegistry.get("openrouter");
        assertNotNull(openrouter);
        assertEquals("https://openrouter.ai/api/v1", openrouter.baseUrl());
        assertEquals(1, openrouter.models().size());

        var ollama = ProviderRegistry.get("ollama-cloud");
        assertNotNull(ollama);
        assertEquals("https://ollama.com/v1", ollama.baseUrl());
    }

    @Test
    public void providerRegistryPrimaryAndSecondary() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();

        ConfigService.set("provider.primary.baseUrl", "https://primary.ai/v1");
        ConfigService.set("provider.primary.apiKey", "pk-1");
        ConfigService.set("provider.secondary.baseUrl", "https://secondary.ai/v1");
        ConfigService.set("provider.secondary.apiKey", "pk-2");

        ProviderRegistry.refresh();

        assertNotNull(ProviderRegistry.getPrimary());
        assertNotNull(ProviderRegistry.getSecondary());
    }
}
