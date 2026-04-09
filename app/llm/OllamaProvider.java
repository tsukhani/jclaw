package llm;

import com.google.gson.JsonObject;
import llm.LlmTypes.*;

/**
 * Ollama provider (local and Ollama Cloud). Uses the OpenAI-compatible
 * {@code /v1/chat/completions} endpoint.
 *
 * Reasoning: sends {@code reasoning_effort} (accepted values: "low", "medium", "high").
 * Streaming: reads the {@code reasoning} string field from deltas.
 *
 * @see <a href="https://docs.ollama.com/capabilities/thinking">Ollama Thinking Docs</a>
 */
public class OllamaProvider extends LlmProvider {

    public OllamaProvider(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        // Ollama's /v1 endpoint accepts reasoning_effort with values: low, medium, high
        request.addProperty("reasoning_effort", thinkingMode);
    }

    @Override
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        // Ollama sends reasoning text as a simple "reasoning" string field on the delta
        return delta.reasoning();
    }

    @Override
    protected int extractReasoningTokens(JsonObject usageObj) {
        // Ollama may report reasoning_tokens at the top level
        if (usageObj.has("reasoning_tokens") && !usageObj.get("reasoning_tokens").isJsonNull()) {
            return usageObj.get("reasoning_tokens").getAsInt();
        }
        return 0;
    }
}
