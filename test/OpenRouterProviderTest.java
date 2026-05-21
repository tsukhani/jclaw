import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import llm.OpenRouterProvider;
import llm.LlmTypes;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ReasoningDetail;

import java.util.List;

/**
 * Branch coverage for OpenRouterProvider's protected reasoning hooks.
 * OpenRouterProvider is {@code final}, so we can't subclass — reflection
 * is the documented escape hatch.
 */
class OpenRouterProviderTest extends UnitTest {

    private final OpenRouterProvider provider = new OpenRouterProvider(
            new ProviderConfig("openrouter", "https://openrouter.ai/api/v1",
                    "sk-test", List.of()));

    private String extractReasoning(ChunkDelta delta) throws Exception {
        var m = OpenRouterProvider.class.getDeclaredMethod(
                "extractReasoningFromDelta", ChunkDelta.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, delta);
    }

    @Test
    void extractReasoningPrefersReasoningDetailsArray() throws Exception {
        var details = List.of(
                new ReasoningDetail("reasoning.text", "first thought"),
                new ReasoningDetail("reasoning.text", " plus more"));
        var delta = new ChunkDelta(null, null, null, "fallback", details);
        var result = extractReasoning(delta);
        assertEquals("first thought plus more", result,
                "non-null reasoningDetails must concatenate text fields and win over reasoning string");
    }

    @Test
    void extractReasoningReturnsNullWhenAllDetailTextsAreNull() throws Exception {
        // reasoningDetails present but every entry's text() is null — the
        // builder stays empty and isEmpty() → return null.
        var details = List.of(
                new ReasoningDetail("reasoning.signature", null),
                new ReasoningDetail("reasoning.text", null));
        var delta = new ChunkDelta(null, null, null, "ignored-fallback", details);
        assertNull(extractReasoning(delta),
                "all-null detail texts must yield null, not empty string");
    }

    @Test
    void extractReasoningFallsBackToReasoningStringWhenDetailsAbsent() throws Exception {
        var delta = new ChunkDelta(null, null, null, "from string field", null);
        assertEquals("from string field", extractReasoning(delta));
    }

    @Test
    void extractReasoningReturnsNullWhenBothFieldsAbsent() throws Exception {
        var delta = new ChunkDelta(null, null, null, null, null);
        assertNull(extractReasoning(delta));
    }
}
