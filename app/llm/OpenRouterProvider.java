package llm;

import com.google.gson.JsonObject;
import llm.LlmTypes.*;

/**
 * OpenRouter provider. Extends OpenAI-compatible behavior with:
 * - {@code reasoning} object in requests (OpenRouter's native format)
 * - {@code reasoning_details} array in streaming deltas
 * - {@code reasoning_tokens} at top level in usage
 *
 * @see <a href="https://openrouter.ai/docs/guides/best-practices/reasoning-tokens">OpenRouter Reasoning Docs</a>
 */
public class OpenRouterProvider extends LlmProvider {

    public OpenRouterProvider(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        // OpenRouter expects a reasoning object with an effort level
        var reasoning = new JsonObject();
        reasoning.addProperty("effort", thinkingMode);
        request.add("reasoning", reasoning);

        // Also send reasoning_effort for models that are proxied directly to OpenAI
        request.addProperty("reasoning_effort", thinkingMode);
    }

    @Override
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        // OpenRouter sends reasoning as reasoning_details array with type "reasoning.text"
        if (delta.reasoningDetails() != null) {
            var sb = new StringBuilder();
            for (var rd : delta.reasoningDetails()) {
                if (rd.text() != null) sb.append(rd.text());
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        // Some OpenRouter models also send a simple reasoning string
        return delta.reasoning();
    }

    @Override
    protected int extractReasoningTokens(JsonObject usageObj) {
        // OpenRouter puts reasoning_tokens at the top level of usage
        if (usageObj.has("reasoning_tokens") && !usageObj.get("reasoning_tokens").isJsonNull()) {
            return usageObj.get("reasoning_tokens").getAsInt();
        }
        // Fallback to OpenAI nested format (for models proxied from OpenAI)
        if (usageObj.has("completion_tokens_details")
                && !usageObj.get("completion_tokens_details").isJsonNull()) {
            var details = usageObj.getAsJsonObject("completion_tokens_details");
            if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isJsonNull()) {
                return details.get("reasoning_tokens").getAsInt();
            }
        }
        return 0;
    }
}
