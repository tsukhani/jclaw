package agents;

import com.google.gson.Gson;
import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import models.Agent;
import models.Conversation;
import services.EventLogger;

import static utils.GsonHolder.INSTANCE;

/**
 * Token-counting, reasoning-duration, and pricing-JSON assembly for one
 * agent turn. Extracted from {@link AgentRunner} as part of JCLAW-299;
 * pure functions on {@link LlmProvider.TurnUsage} plus a thin terminal
 * callback emit, no behavior change.
 *
 * <p>The JCLAW-76 invariant is owned here: token counts are turn-level
 * cumulative — every LLM round's per-round usage folds into the same
 * {@code TurnUsage} object during the run, and the usage JSON emitted
 * at terminal time reflects the whole turn (not just round 1). The
 * reasoning-duration measurement spans first-reasoning-chunk through
 * first-content-chunk across the turn (including any tool-execution
 * gap between rounds) so the stat matches the frontend's live
 * {@code _thinkingDurationMs} measurement.
 *
 * <p>Cost-attribution (JCLAW-107 / JCLAW-108) is also recorded here:
 * the usage JSON carries the resolved {@code modelProvider} and
 * {@code modelId} that actually ran the turn — the conversation-scoped
 * override when present, otherwise the agent's default. The aggregator
 * (JCLAW-28 dashboard) reads those without needing live provider
 * lookups, so the values must remain accurate across this extraction.
 */
public final class UsageMetricsBuilder {

    private static final Gson gson = INSTANCE;

    private UsageMetricsBuilder() {}

    /**
     * Resolve the reasoning-token count to surface in usage metrics.
     * Prefers the summed provider-reported {@code reasoning_tokens}
     * across every LLM round in the turn when available; falls back to
     * a character-length estimate over the streamed reasoning text
     * ({@code ~4 chars per token}, the standard English heuristic) when
     * every round reported zero despite reasoning having been detected
     * on at least one round. Returning an estimate — clearly non-zero
     * when reasoning ran — is better than showing no count at all,
     * since the UI's reasoning-count badge is gated on this value being
     * truthy.
     */
    static int effectiveReasoningTokens(LlmProvider.TurnUsage turnUsage) {
        if (turnUsage.reasoningTokens > 0) return turnUsage.reasoningTokens;
        if (!turnUsage.reasoningDetected) return 0;
        var chars = turnUsage.reasoningChars;
        if (chars <= 0) return 0;
        // Round up: a small amount of text still represents at least one
        // reasoning token on the wire, and rounding down would silently
        // swallow short traces.
        return Math.max(1, (chars + 3) / 4);
    }

    /**
     * Build the usage JSON string from the turn-level cumulative token
     * counts. Token fields are summed across every LLM round in the turn
     * (JCLAW-76); {@code reasoningDurationMs} is also turn-level — it
     * spans from the first reasoning chunk of the turn to the first
     * content chunk of the turn, including any tool-execution gap
     * between rounds. This matches the frontend's live
     * {@code _thinkingDurationMs} measurement so a turn shows the same
     * "Thought for X seconds" value while streaming and after reload.
     * Returns a compact JSON with just duration fields when no round
     * reported provider usage.
     */
    public static String buildUsageJson(LlmProvider.TurnUsage turnUsage,
                                        ModelInfo modelInfo, long streamStartMs, Agent agent,
                                        Conversation conversation) {
        return buildUsageJson(turnUsage, modelInfo, streamStartMs, agent, conversation, 0L);
    }

    /**
     * Six-arg overload that also records the time the LLM spent emitting
     * tokens ({@code STREAM_BODY_END} minus {@code FIRST_TOKEN}, in ms)
     * as {@code streamBodyMs} on the usage payload, when
     * {@code streamBodyMs > 0}. The loadtest analyzer uses this to
     * compute pure generation rate (tokens per second of emit time,
     * excluding TTFT). 0 is treated as "unavailable" by readers and the
     * field is simply omitted.
     */
    public static String buildUsageJson(LlmProvider.TurnUsage turnUsage,
                                        ModelInfo modelInfo, long streamStartMs, Agent agent,
                                        Conversation conversation, long streamBodyMs) {
        var durationMs = System.currentTimeMillis() - streamStartMs;
        var reasoningMs = turnUsage.reasoningDurationMs(System.nanoTime());
        if (!turnUsage.hasProviderUsage) {
            // Tail fragment optionally carries reasoning + streamBody durations
            // when known. Built here rather than via JsonObject because the
            // no-provider-usage path historically returns a hand-formatted
            // string for the smaller payload size.
            var tail = new StringBuilder();
            if (reasoningMs > 0L) tail.append(",\"reasoningDurationMs\":").append(reasoningMs);
            if (streamBodyMs > 0L) tail.append(",\"streamBodyMs\":").append(streamBodyMs);
            return "{\"durationMs\":%s%s}".formatted(durationMs, tail);
        }

        var reasoningCount = effectiveReasoningTokens(turnUsage);
        var usageMap = new com.google.gson.JsonObject();
        usageMap.addProperty("prompt", turnUsage.promptTokens);
        usageMap.addProperty("completion", turnUsage.completionTokens);
        usageMap.addProperty("total", turnUsage.totalTokens);
        usageMap.addProperty("reasoning", reasoningCount);
        usageMap.addProperty("cached", turnUsage.cachedTokens);
        usageMap.addProperty("cacheCreation", turnUsage.cacheCreationTokens);
        usageMap.addProperty("durationMs", durationMs);
        if (reasoningMs > 0L) usageMap.addProperty("reasoningDurationMs", reasoningMs);
        if (streamBodyMs > 0L) usageMap.addProperty("streamBodyMs", streamBodyMs);

        if (modelInfo != null) {
            if (modelInfo.promptPrice() >= 0) usageMap.addProperty("promptPrice", modelInfo.promptPrice());
            if (modelInfo.completionPrice() >= 0) usageMap.addProperty("completionPrice", modelInfo.completionPrice());
            if (modelInfo.cachedReadPrice() >= 0) usageMap.addProperty("cachedReadPrice", modelInfo.cachedReadPrice());
            if (modelInfo.cacheWritePrice() >= 0) usageMap.addProperty("cacheWritePrice", modelInfo.cacheWritePrice());
            if (modelInfo.contextWindow() > 0) usageMap.addProperty("contextWindow", modelInfo.contextWindow());
        }
        // JCLAW-107 / JCLAW-108: capture per-turn model identity so the
        // cost aggregator can attribute each turn without needing live
        // provider lookup. Writes the RESOLVED values (conversation
        // override when present, agent's default otherwise) — this is
        // the identity of the model that actually ran the turn, which
        // is what cost attribution needs.
        var resolvedProvider = ModelResolver.effectiveModelProvider(agent, conversation);
        var resolvedModelId = ModelResolver.effectiveModelId(agent, conversation);
        if (resolvedProvider != null) usageMap.addProperty("modelProvider", resolvedProvider);
        if (resolvedModelId != null) usageMap.addProperty("modelId", resolvedModelId);
        return gson.toJson(usageMap);
    }

    /**
     * Log usage, build the usage JSON payload (including pricing), and
     * invoke the status + complete callbacks. Caller is responsible for
     * having computed {@code usageJson} via {@link #buildUsageJson}
     * first — passing it in (rather than re-computing) preserves the
     * existing call-site sequencing where the JSON is built once and
     * also persisted to the assistant message in the same code path.
     */
    static void emitUsageAndComplete(Agent agent, String channelType, String content,
                                      LlmProvider.TurnUsage turnUsage,
                                      long streamStartMs, String usageJson,
                                      AgentRunner.StreamingCallbacks cb) {
        var durationMs = System.currentTimeMillis() - streamStartMs;
        if (turnUsage.hasProviderUsage) {
            var reasoningCount = effectiveReasoningTokens(turnUsage);
            var extras = new StringBuilder();
            if (reasoningCount > 0) extras.append(", %s reasoning".formatted(reasoningCount));
            if (turnUsage.cachedTokens > 0) extras.append(", %d cached".formatted(turnUsage.cachedTokens));
            if (turnUsage.cacheCreationTokens > 0) extras.append(", %d cache-write".formatted(turnUsage.cacheCreationTokens));
            var usageSummary = " [%d prompt, %d completion, %d total tokens%s, %.1fs]".formatted(
                    turnUsage.promptTokens, turnUsage.completionTokens, turnUsage.totalTokens,
                    extras.toString(),
                    durationMs / 1000.0);
            EventLogger.info("llm", agent.name, channelType,
                    "Streaming complete (%d chars)%s".formatted(content.length(), usageSummary));
        } else {
            EventLogger.info("llm", agent.name, channelType,
                    "Streaming complete (%d chars, %.1fs)".formatted(content.length(), durationMs / 1000.0));
        }
        cb.onStatus().accept("{\"usage\":%s}".formatted(usageJson));
        cb.onComplete().accept(content);
    }
}
