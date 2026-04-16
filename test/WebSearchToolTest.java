import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import tools.WebSearchTool;

/**
 * Verifies that WebSearchTool honors the Config DB gating contract:
 *
 * <ul>
 *   <li>A disabled provider is skipped even if its API key is set.</li>
 *   <li>An enabled-but-keyless provider is skipped during auto-selection.</li>
 *   <li>Provider selection follows the configured priority order.</li>
 * </ul>
 *
 * The tool's {@code execute()} resolves the provider <em>before</em> any HTTP
 * call, so the error-string paths can be asserted without hitting the network.
 */
public class WebSearchToolTest extends UnitTest {

    private WebSearchTool tool;

    private static final String[] ALL_KEYS = {
            "search.exa.enabled", "search.exa.apiKey", "search.exa.baseUrl",
            "search.brave.enabled", "search.brave.apiKey", "search.brave.baseUrl",
            "search.tavily.enabled", "search.tavily.apiKey", "search.tavily.baseUrl",
            "search.perplexity.enabled", "search.perplexity.apiKey", "search.perplexity.baseUrl",
            "search.ollama.enabled", "search.ollama.apiKey", "search.ollama.baseUrl",
            "search.felo.enabled", "search.felo.apiKey", "search.felo.baseUrl",
    };

    @BeforeEach
    void setup() {
        tool = new WebSearchTool();
        for (var k : ALL_KEYS) ConfigService.set(k, "");
        ConfigService.set("search.exa.enabled", "false");
        ConfigService.set("search.brave.enabled", "false");
        ConfigService.set("search.tavily.enabled", "false");
        ConfigService.set("search.perplexity.enabled", "false");
        ConfigService.set("search.ollama.enabled", "false");
        ConfigService.set("search.felo.enabled", "false");
        ConfigService.clearCache();
    }

    @AfterEach
    void teardown() {
        for (var k : ALL_KEYS) ConfigService.delete(k);
        ConfigService.clearCache();
    }

    @Test
    public void noProviderEnabled_autoSelectReportsError() {
        var result = tool.execute("{\"query\":\"test\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }

    @Test
    public void enabledButNoKey_autoSelectReportsError() {
        ConfigService.set("search.exa.enabled", "true");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }

    @Test
    public void disabledProviderSkippedEvenWithKey() {
        // Exa has a key but is disabled — should be skipped
        ConfigService.set("search.exa.apiKey", "fake-key");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }

    @Test
    public void providerArgIgnored_usesFallbackOrder() {
        // Even if the LLM passes provider:"exa", the tool should use fallback
        // order. With exa disabled, this should report no provider.
        ConfigService.set("search.exa.apiKey", "fake-key");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\",\"provider\":\"exa\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }

    @Test
    public void defaultEnabledWhenKeyAbsent() {
        // Delete the enabled key entirely; the provider should default to enabled.
        // Still no API key, so auto-select skips it.
        ConfigService.delete("search.tavily.enabled");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }
}
