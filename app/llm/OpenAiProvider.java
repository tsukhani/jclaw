package llm;

import com.google.gson.JsonObject;
import llm.LlmTypes.*;

/**
 * Standard OpenAI-compatible provider. Handles direct OpenAI API and any
 * provider that follows the OpenAI chat completions spec without extensions.
 *
 * Reasoning: sends {@code reasoning_effort} as a top-level request parameter.
 * Usage: reads {@code completion_tokens_details.reasoning_tokens} from the response.
 */
public final class OpenAiProvider extends LlmProvider {

    public OpenAiProvider(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        request.addProperty("reasoning_effort", thinkingMode);
    }

    @Override
    protected int extractReasoningTokens(JsonObject usageObj) {
        // OpenAI nests reasoning tokens under completion_tokens_details
        return readUsageInt(usageObj, "completion_tokens_details", "reasoning_tokens");
    }
}
