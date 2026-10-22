import agents.ToolRegistry;
import llm.LlmTypes.ToolDef;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The tool definitions sent to the model read the same, in the same order, on every restart. */
class ToolDefinitionsTest extends UnitTest {

    private static final List<String> SORTED = List.of("alpha", "beta", "delta", "kappa", "mu", "omega", "zeta");

    @Test
    void aSchemaBuiltWithMapOfAndSetOfIsSentSorted() {
        // Map.of and Set.of iterate in an order the JDK salts per JVM, so unsorted they reorder on every restart.
        var params = ToolDef.of("probe", "a probe", Map.of(
                "type", "object",
                "properties", Map.of("zeta", Map.of(), "alpha", Map.of(), "mu", Map.of(), "beta", Map.of(),
                        "omega", Map.of(), "kappa", Map.of(), "delta", Map.of()),
                "required", Set.of("zeta", "alpha", "mu", "beta", "omega", "kappa", "delta"))).function().parameters();
        assertEquals(List.of("properties", "required", "type"), List.copyOf(params.keySet()));
        assertEquals(SORTED, List.copyOf(((Map<?, ?>) params.get("properties")).keySet()));
        assertEquals(SORTED, params.get("required"));
    }

    @Test
    void aSchemaWithADeclaredOrderKeepsIt() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("url", Map.of());
        properties.put("format", Map.of());
        properties.put("extract", Map.of());
        var params = ToolDef.of("probe", "a probe", Map.of("properties", properties, "enum", List.of("send", "reply", "edit")))
                .function().parameters();
        assertEquals(List.of("url", "format", "extract"), List.copyOf(((Map<?, ?>) params.get("properties")).keySet()));
        assertEquals(List.of("send", "reply", "edit"), params.get("enum"));
    }

    @Test
    void noToolShowsTheModelAnUnfilledPlaceholder() throws Exception {
        // "a %d " + "b".formatted(n) formats only the last literal: web_scrape sent '%d' and '%s' that way.
        var placeholder = Pattern.compile("%(\\d+\\$)?[sd]\\b");
        var offenders = new ArrayList<String>();
        ToolRegistrySync.withCanonicalTools(() -> {
            for (var tool : ToolRegistry.listTools()) {
                if (placeholder.matcher(tool.description() + " " + GsonHolder.GSON.toJson(tool.parameters())).find()) {
                    offenders.add(tool.name());
                }
            }
        });
        assertEquals(List.of(), offenders);
    }
}
