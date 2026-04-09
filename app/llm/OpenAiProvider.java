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
public class OpenAiProvider extends LlmProvider {

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
