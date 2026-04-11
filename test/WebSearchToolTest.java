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
 *   <li>Preferred-provider requests report the specific failure reason
 *       (disabled vs missing key vs unknown id).</li>
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
    };

    @BeforeEach
    void setup() {
        tool = new WebSearchTool();
        // Reset all search keys to a known-blank state. ConfigService caches
        // aggressively, so both set-to-blank and cache-clear are required.
        for (var k : ALL_KEYS) ConfigService.set(k, "");
        // Disable everything by default so individual tests opt in.
        ConfigService.set("search.exa.enabled", "false");
        ConfigService.set("search.brave.enabled", "false");
        ConfigService.set("search.tavily.enabled", "false");
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
        // Auto-select falls through because no enabled provider has a key.
        assertTrue(result.contains("No search provider"));
    }

    @Test
    public void disabledPreferredProvider_reportsDisabled() {
        ConfigService.set("search.exa.apiKey", "fake-key");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\",\"provider\":\"exa\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("disabled"));
    }

    @Test
    public void enabledPreferredProviderMissingKey_reportsMissingKey() {
        ConfigService.set("search.brave.enabled", "true");
        ConfigService.clearCache();
        var result = tool.execute("{\"query\":\"test\",\"provider\":\"brave\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("search.brave.apiKey"));
    }

    @Test
    public void unknownPreferredProvider_reportsUnknown() {
        var result = tool.execute("{\"query\":\"test\",\"provider\":\"bing\"}", null);
        assertTrue(result.contains("Unknown search provider"));
    }

    @Test
    public void defaultEnabledWhenKeyAbsent() {
        // Delete the enabled key entirely; the provider should default to enabled
        // (matches WebSearchTool.SearchProvider.isEnabled default behavior).
        ConfigService.delete("search.tavily.enabled");
        ConfigService.clearCache();
        // Still no API key, so auto-select returns the usual error — but the
        // preferred-provider path surfaces the missing-key error rather than
        // a disabled error, proving the default is "enabled".
        var result = tool.execute("{\"query\":\"test\",\"provider\":\"tavily\"}", null);
        assertTrue(result.contains("search.tavily.apiKey"));
        assertFalse(result.contains("disabled"));
    }
}
