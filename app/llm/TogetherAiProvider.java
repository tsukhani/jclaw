package llm;

import com.google.gson.JsonObject;
import llm.LlmTypes.*;

/**
 * Together AI provider. OpenAI-compatible chat completions at
 * {@code https://api.together.xyz/v1}, with one deviation: the reasoning
 * parameter is a {@code {enabled: bool}} object rather than OpenAI's
 * top-level {@code reasoning_effort} string or OpenRouter's
 * {@code {effort: "low|medium|high"}} object.
 *
 * <p>Together's thinking knob is binary (on/off) — there's no per-effort
 * gradient. JClaw's per-agent {@code thinkingMode} string still flows in
 * (so the agent UI looks identical across providers), but the value is
 * collapsed to a boolean here: any non-null/non-blank value enables
 * reasoning, null/blank disables it.
 *
 * <p>{@link #disableReasoning} emits {@code reasoning: {enabled: false}}
 * explicitly rather than the base-class no-op so models that may default
 * to reasoning-on (e.g. Kimi-K2.5 hosted variants) can be forcibly turned
 * off when the agent has no thinkingMode set. Same defensive stance as
 * {@link OpenRouterProvider#disableReasoning}.
 *
 * @see <a href="https://docs.together.ai/docs/reasoning-overview">Together Reasoning Docs</a>
 * @see <a href="https://docs.together.ai/docs/openai-api-compatibility">Together OpenAI Compatibility</a>
 */
public final class TogetherAiProvider extends LlmProvider {

    public TogetherAiProvider(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        var reasoning = new JsonObject();
        reasoning.addProperty("enabled", true);
        request.add("reasoning", reasoning);
    }

    @Override
    protected void disableReasoning(JsonObject request) {
        var reasoning = new JsonObject();
        reasoning.addProperty("enabled", false);
        request.add("reasoning", reasoning);
    }

    @Override
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        // Together streams reasoning content as a plain `reasoning` string
        // on each chunk delta — mirrors OpenRouter's simple-string fallback
        // path, not its structured reasoning_details[] array.
        return delta.reasoning();
    }

    @Override
    protected int extractReasoningTokens(JsonObject usageObj) {
        // Together puts reasoning_tokens at the top level of the usage
        // object, matching OpenRouter's shape. Fall back to OpenAI's
        // nested completion_tokens_details path defensively in case
        // Together begins proxying OpenAI-style usage for some models.
        int top = readUsageInt(usageObj, "reasoning_tokens");
        return top > 0 ? top : readUsageInt(usageObj, "completion_tokens_details", "reasoning_tokens");
    }
}
