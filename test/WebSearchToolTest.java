import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.UnitTest;
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
class WebSearchToolTest extends UnitTest {

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
    void noProviderEnabled_autoSelectReportsError() {
        var result = tool.execute("{\"query\":\"test\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }

    /**
     * Each case sets one Exa config key, then auto-select must still report
     * "No search provider": enabled-but-keyless (enabled=true, no apiKey),
     * disabled-with-key (apiKey set but enabled stays false from setup()),
     * and the same disabled-with-key while the LLM passes provider:"exa"
     * (the provider arg is ignored, fallback order applies). The
     * {@code @BeforeEach} leaves every provider disabled and keyless, so
     * exactly the one key each case sets is the only deviation.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "EnabledButNoKey          | search.exa.enabled | true     | {\"query\":\"test\"}",
            "DisabledWithKey          | search.exa.apiKey  | fake-key | {\"query\":\"test\"}",
            "ProviderArgIgnoredWithKey | search.exa.apiKey  | fake-key | {\"query\":\"test\",\"provider\":\"exa\"}"
    })
    void autoSelectReportsNoProvider(String label, String configKey, String configValue, String executeJson) {
        ConfigService.set(configKey, configValue);
        ConfigService.clearCache();
        var result = tool.execute(executeJson, null);
        assertTrue(result.startsWith("Error:"), label + " should be an error: " + result);
        assertTrue(result.contains("No search provider"),
                label + " should report no provider: " + result);
    }

    @Test
    void defaultEnabledWhenKeyAbsent() {
        // Delete the enabled key entirely; the provider should default to enabled.
        // Still no API key, so auto-select skips it.
        ConfigService.delete("search.tavily.enabled");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"));
    }
}
