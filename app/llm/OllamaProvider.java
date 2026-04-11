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
    protected void disableReasoning(JsonObject request) {
        // Ollama models like Kimi K2.5 think by default. The native API uses "think": false
        // to disable it. Pass this through the /v1 endpoint — Ollama may forward it to the model.
        request.addProperty("think", false);
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

    @Override
    protected void applyCacheDirectives(JsonObject request, ChatRequest chatRequest) {
        // Ollama has no OpenAI/Anthropic-style prompt caching — just implicit KV-cache
        // reuse in the inference engine. The only client-visible knob is keep_alive, which
        // controls how long the model (and its KV cache) stays resident between requests.
        // Pass it as an extra top-level field; Ollama's OpenAI-compat shim forwards unknown
        // fields to the native scheduler.
        var keepAlive = services.ConfigService.get("ollama.keepAlive");
        request.addProperty("keep_alive", keepAlive != null && !keepAlive.isBlank() ? keepAlive : "30m");
    }
}
