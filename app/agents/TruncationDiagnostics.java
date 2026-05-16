package agents;

import java.util.List;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolDef;
import models.Agent;
import models.Conversation;
import services.EventLogger;

/**
 * Provider {@code finish_reason} interpretation and the JCLAW-291
 * structured-warn diagnostic when a turn truncates. Extracted from
 * {@link AgentRunner} as part of JCLAW-299.
 *
 * <h3>Why "max_tokens" must alias to "length"</h3>
 * OpenAI-compatible routes emit {@code "length"} when the model
 * exhausted its output token budget; Anthropic-native (and
 * OpenRouter's Bedrock route for Anthropic models) emit
 * {@code "max_tokens"}. Both must be treated as truncation — if only
 * {@code "length"} is matched, Bedrock-routed Claude tool-call deltas
 * get dispatched with incomplete JSON args and the downstream tool
 * fails with a cryptic Gson EOFException.
 *
 * <h3>Why we log on truncation, not just retry</h3>
 * The empty-tool-calls truncation diagnostic (JCLAW-291) dumps the
 * headroom math — configured cap, context window, prompt tokens
 * estimate, clamped {@code max_tokens} — so operators can correlate
 * the truncation with the model's effective output budget. Without
 * this, the only signal that {@code max_tokens} was tight was
 * "responses look cut off" — slow to diagnose, easy to misattribute.
 */
public final class TruncationDiagnostics {

    private TruncationDiagnostics() {}

    /**
     * Return {@code true} when a streaming {@code finish_reason}
     * signals the model exhausted its output token budget
     * mid-response.
     */
    public static boolean isTruncationFinish(String finishReason) {
        return "length".equals(finishReason) || "max_tokens".equals(finishReason);
    }

    /**
     * JCLAW-291: emit a structured warn line whenever the model
     * truncates a plain (non-tool-call) reply via
     * {@code finish_reason = length / max_tokens}. Mirrors the
     * existing tool-call truncation guards but dumps the headroom
     * math so operators can correlate the truncation with the
     * model's effective output budget. Single call site shape so the
     * format stays canonical across streaming and non-streaming.
     */
    static void logEmptyToolCallsTruncation(String site, Agent agent, Conversation conversation,
                                             LlmProvider provider, String channelType,
                                             String finishReason, List<ChatMessage> messages,
                                             List<ToolDef> tools) {
        var modelInfo = ModelResolver.resolveModelInfo(agent, conversation, provider).orElse(null);
        int promptTokens = ContextWindowManager.estimateTokens(messages) + ContextWindowManager.estimateToolTokens(tools);
        int configured = modelInfo != null ? modelInfo.maxTokens() : -1;
        int contextWindow = modelInfo != null ? modelInfo.contextWindow() : -1;
        int headroom = contextWindow > 0
                ? contextWindow - promptTokens - ContextWindowManager.OUTPUT_SAFETY_MARGIN_TOKENS
                : -1;
        Integer clamped = ContextWindowManager.effectiveMaxTokens(agent, conversation, provider, messages, tools);
        EventLogger.warn("llm", agent.name, channelType,
                "Truncated reply (site=%s, finish=%s, configured=%d, contextWindow=%d, prompt~%d, headroom=%d, clamped=%s)"
                        .formatted(site, finishReason, configured, contextWindow, promptTokens, headroom,
                                clamped == null ? "null" : clamped.toString()));
    }
}
