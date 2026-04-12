package llm;

import com.google.gson.JsonArray;
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
public final class OpenRouterProvider extends LlmProvider {

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

        // For providers that need explicit cache_control, walk the already-serialized
        // messages array and attach a breakpoint to the system message. We used to
        // send a top-level cache_control: {type: "ephemeral"} as a shortcut, but
        // OpenRouter returns HTTP 404 ("No endpoints found that support Anthropic
        // automatic caching (top-level cache_control)") when the routed endpoint does
        // not support the shortcut — which turns out to be most Anthropic routes, not
        // just Bedrock/Vertex as the docs suggest. Per-block injection works on every
        // route.
        //
        // OpenAI/DeepSeek/Grok/Gemini 2.5 cache implicitly and need no directive.
        if (requiresExplicitCacheControl(chatRequest.model())) {
            injectSystemMessageCacheBreakpoint(request);
        }
    }

    /**
     * Find the first system message in the already-serialized request and attach
     * {@code cache_control: {type: "ephemeral"}} to its last content block, converting
     * a string content field into block-array form if necessary. The cache breakpoint
     * marks the end of the cacheable prefix; everything after the system message
     * (the conversation history and current user turn) remains fresh input.
     */
    private static void injectSystemMessageCacheBreakpoint(JsonObject request) {
        if (!request.has("messages") || !request.get("messages").isJsonArray()) return;
        var messages = request.getAsJsonArray("messages");
        for (var el : messages) {
            if (!el.isJsonObject()) continue;
            var msg = el.getAsJsonObject();
            if (!msg.has("role") || !"system".equals(msg.get("role").getAsString())) continue;

            var content = msg.get("content");
            if (content == null || content.isJsonNull()) return;

            JsonArray blocks;
            if (content.isJsonPrimitive()) {
                // String content: convert to block-array form so we have somewhere to
                // attach the cache_control directive.
                blocks = new JsonArray();
                var block = new JsonObject();
                block.addProperty("type", "text");
                block.addProperty("text", content.getAsString());
                blocks.add(block);
                msg.add("content", blocks);
            } else if (content.isJsonArray()) {
                blocks = content.getAsJsonArray();
            } else {
                return;
            }

            if (blocks.isEmpty()) return;
            var lastBlock = blocks.get(blocks.size() - 1).getAsJsonObject();
            var cacheControl = new JsonObject();
            cacheControl.addProperty("type", "ephemeral");
            lastBlock.add("cache_control", cacheControl);
            return; // Only the first system message gets the breakpoint.
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
