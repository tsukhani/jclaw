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
    protected void disableReasoning(JsonObject request) {
        // Explicitly disable reasoning for models that think by default
        var reasoning = new JsonObject();
        reasoning.addProperty("effort", "none");
        request.add("reasoning", reasoning);
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

    @Override
    protected void applyCacheDirectives(JsonObject request, ChatRequest chatRequest) {
        // Opt into usage accounting so the upstream cache-hit fields
        // (prompt_tokens_details.cached_tokens, cache_discount) come back in the
        // response. Without this, OpenRouter strips usage details.
        var usage = new JsonObject();
        usage.addProperty("include", true);
        request.add("usage", usage);

        // Top-level automatic cache_control: only honored by OpenRouter for Anthropic-
        // direct and non-2.5 Gemini routes. Anthropic interprets it as "place a
        // breakpoint on the stable prefix and auto-advance it across turns" — caches
        // the system message AND growing conversation history without per-block
        // manipulation. Bedrock/Vertex Anthropic routes silently ignore it and
        // require per-block breakpoints instead; we accept the miss there.
        //
        // OpenAI/DeepSeek/Grok/Gemini 2.5 cache implicitly and need no directive.
        if (requiresExplicitCacheControl(chatRequest.model())) {
            var cacheControl = new JsonObject();
            cacheControl.addProperty("type", "ephemeral");
            request.add("cache_control", cacheControl);
        }
    }

    /**
     * Returns true for OpenRouter model IDs whose upstream provider requires explicit
     * {@code cache_control} to activate prompt caching. Models not listed here either
     * cache implicitly (OpenAI, DeepSeek, Grok, Gemini 2.5) or have no caching.
     */
    private static boolean requiresExplicitCacheControl(String model) {
        if (model == null) return false;
        if (model.startsWith("anthropic/")) return true;
        // Gemini 2.5 Pro/Flash cache implicitly; older Gemini variants need cache_control.
        if (model.startsWith("google/gemini-") && !model.startsWith("google/gemini-2.5-")) return true;
        return false;
    }
}
