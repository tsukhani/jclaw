package llm;

import com.google.gson.JsonObject;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;

/**
 * Standard OpenAI-compatible provider. Handles direct OpenAI API and any
 * provider that follows the OpenAI chat completions spec without extensions.
 *
 * <p>Also the catch-all: {@code ProviderRegistry} routes every provider it does
 * not recognize here, so this class is what actually serves LM Studio, vLLM,
 * SGLang, Groq and Azure.
 *
 * Reasoning: sends {@code reasoning_effort} as a top-level request parameter,
 * and streams thinking from {@code reasoning_content} on the delta (JCLAW-850).
 * Usage: reads {@code completion_tokens_details.reasoning_tokens} from the response.
 */
public final class OpenAiProvider extends LlmProvider {

    /** Provider name for the real OpenAI API, as opposed to the compat catch-all. */
    private static final String OPENAI_PROVIDER = "openai";

    public OpenAiProvider(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        request.addProperty("reasoning_effort", thinkingMode);
    }

    @Override
    protected int extractReasoningTokens(JsonObject usageObj) {
        // OpenAI nests reasoning tokens under completion_tokens_details; the
        // shared top-then-nested chain resolves to that (no top-level field).
        return readReasoningTokens(usageObj);
    }

    @Override
    protected void disableReasoning(JsonObject request) {
        // JCLAW-851: the base is a no-op, so before this the Think toggle sent no
        // off-signal at all on any OpenAI-compatible provider — the same omission
        // as extractReasoningFromDelta above, in the opposite direction.
        //
        // Two guards, because a naive emit here would be a regression rather than
        // a fix. serializeRequest calls this on EVERY request whose thinking mode
        // is unset, and thinkingMode is null both when the operator turned
        // thinking off AND when the model never supported it — indistinguishable
        // at this layer.
        //
        // 1. Real OpenAI is served by this same class. Its reasoning models accept
        //    low/medium/high/minimal for reasoning_effort but NOT "none", so the
        //    field would be rejected. Only the compat endpoints get it.
        // 2. Gate on the model actually advertising thinking. Otherwise ordinary
        //    non-reasoning traffic would carry reasoning_effort, which OpenAI-shaped
        //    servers reject on models that have no reasoning mode.
        if (OPENAI_PROVIDER.equalsIgnoreCase(config().name())) return;
        if (!modelSupportsThinking(request)) return;
        request.addProperty("reasoning_effort", "none");
    }

    /** True when the request's model is configured as thinking-capable. Unknown
     *  models return false: silence is the safe default, since an unrecognized
     *  model is more likely a plain chat model than a reasoner. */
    private boolean modelSupportsThinking(JsonObject request) {
        var modelEl = request.get("model");
        if (modelEl == null || modelEl.isJsonNull()) return false;
        var modelId = modelEl.getAsString();
        var models = config().models();
        if (models == null) return false;
        return models.stream()
                .filter(m -> modelId.equals(m.id()))
                .anyMatch(ModelInfo::supportsThinking);
    }

    @Override
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        // JCLAW-850: ProviderRegistry routes every unrecognized provider here,
        // which is how LM Studio, vLLM, SGLang and Groq are served. They stream
        // thinking as `reasoning_content` on the delta — verified against a live
        // LM Studio capture:
        //   "delta":{"role":"assistant","reasoning_content":"Thinking"}
        // Without this override the base returned null, so onReasoning never
        // fired and a reasoning model showed a silent multi-second gap followed
        // by a bare answer. Token counts were unaffected; they come off usage.
        //
        // Fall back to the plain `reasoning` string, which some OpenAI-compatible
        // servers emit instead. OpenAI proper streams no reasoning at all, so
        // both fields are absent there and this stays null.
        if (delta.reasoningContent() != null) return delta.reasoningContent();
        return delta.reasoning();
    }
}
