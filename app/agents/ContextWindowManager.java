package agents;

import llm.LlmProvider;
import llm.LlmTypes;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolDef;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import services.ConfigService;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Token-estimation and context-window arithmetic for the agent loop.
 * Extracted from {@link AgentRunner} as part of JCLAW-299; the cluster
 * here is provider-facing token measurement plus the JCLAW-291 reservation
 * policy that keeps the assistant reply from collapsing into
 * {@code finish_reason=length}.
 *
 * <h2>Token measurement</h2>
 * Runtime context-window arithmetic ({@link #effectiveMaxTokens},
 * {@link #trimToContextWindow}, {@link CompactionGate}, and
 * {@link TruncationDiagnostics}) routes through
 * {@link llm.TokenUsageEstimator} so prompt headroom is measured with
 * the same tokenizer family as the provider request. The legacy
 * {@link #estimateTokens} and {@link #estimateToolTokens} helpers are
 * retained solely as references for {@code AgentRunnerContextWindowTest}'s
 * regression coverage of the chars/4 baseline; no production code path
 * still consumes them.
 *
 * <h2>Reply-budget reservation (JCLAW-291)</h2>
 * {@link #trimToContextWindow} drops oldest non-system history until
 * the prompt fits in {@code contextWindow - RESERVED_OUTPUT_TOKENS}.
 * Without this, headroom in {@link #effectiveMaxTokens} collapses to
 * {@link #MIN_OUTPUT_TOKENS} for prompts that nearly fill the window
 * and the reply silently truncates — repro: chat-page run 1602. The
 * reservation is capped at half the window so tiny-context models
 * don't get trimmed to nothing.
 */
public final class ContextWindowManager {

    /**
     * Absorbs slack between the chars/4 token heuristic and the provider's
     * real tokenizer, plus the overhead of role tags, JSON punctuation,
     * and streaming framing that promptTokens accounting doesn't cover.
     */
    static final int OUTPUT_SAFETY_MARGIN_TOKENS = 512;

    /**
     * Floor on the clamped max_tokens. If the prompt nearly fills the
     * window, we'd rather the provider truncate a too-long prompt than
     * ship a max_tokens so small the reply is useless.
     */
    static final int MIN_OUTPUT_TOKENS = 256;

    /**
     * JCLAW-291: minimum output budget the runner reserves for the
     * assistant reply. {@link #trimToContextWindow} drops oldest history
     * until the prompt fits in {@code contextWindow - RESERVED_OUTPUT_TOKENS},
     * so {@link #effectiveMaxTokens} headroom stays at least
     * {@code RESERVED_OUTPUT_TOKENS - OUTPUT_SAFETY_MARGIN_TOKENS} (~3584)
     * tokens after trimming.
     */
    static final int RESERVED_OUTPUT_TOKENS = 4096;

    /**
     * Global fallback safety-multiplier config key. Empirical Kimi-on-
     * Ollama-Cloud measurement showed cl100k_base under-counting plain-English
     * prompts by up to 25%, which let JClaw ship over-window payloads the
     * provider rejected with HTTP 400. Bumping the estimate before comparing
     * to trim/compact thresholds keeps JClaw on the safe side of that gap.
     * Override via {@code ConfigService.set("jtokkit.safetyMultiplier.unmatched", "1.5")}.
     *
     * <p>Per-provider and per-model overrides win over this global value;
     * see {@link #resolveSafetyMultiplier(String, String)}. The
     * {@code TokenizerCalibrationJob} writes per-(provider, model) entries
     * automatically from observed provider-vs-jtokkit deltas, so this global
     * acts as the cold-start default rather than the day-to-day setting.
     */
    public static final String SAFETY_MULTIPLIER_KEY = "jtokkit.safetyMultiplier.unmatched";
    public static final String SAFETY_MULTIPLIER_PREFIX = "jtokkit.safetyMultiplier.";
    public static final double DEFAULT_SAFETY_MULTIPLIER = 1.4;
    /** Clamp range so a misbehaving calibration can't starve the conversation entirely. */
    public static final double MIN_SAFETY_MULTIPLIER = 1.0;
    public static final double MAX_SAFETY_MULTIPLIER = 2.5;

    private ContextWindowManager() {}

    /**
     * Return the prompt-token estimate adjusted for known tokenizer-mismatch
     * bias. When jtokkit's encoding matches the model family
     * ({@link TokenUsageEstimator.ChatRequestTokens#modelMatched()} = true,
     * OpenAI-family models on the o200k_base / cl100k_base shipping encodings),
     * returns the raw estimate. When it does not (cl100k_base fallback for
     * non-OpenAI providers), multiplies by the resolved per-provider /
     * per-model safety multiplier — see {@link #resolveSafetyMultiplier}.
     */
    public static int adjustedPromptTokens(String providerName, String modelId,
                                            TokenUsageEstimator.ChatRequestTokens estimate) {
        if (estimate.modelMatched()) return estimate.promptTokens();
        return (int) Math.ceil(estimate.promptTokens() * resolveSafetyMultiplier(providerName, modelId));
    }

    /**
     * Backwards-compatible overload for callers that don't have provider /
     * model identity handy. Resolves the global multiplier only, missing the
     * per-(provider, model) and per-provider tiers. Prefer the 3-arg form for
     * the hot path; this overload exists for diagnostic-only call sites.
     */
    public static int adjustedPromptTokens(TokenUsageEstimator.ChatRequestTokens estimate) {
        return adjustedPromptTokens(null, null, estimate);
    }

    /**
     * Apply the same per-call safety multiplier to a per-message estimate so
     * the trim loop's accumulator stays consistent with the budget check
     * produced by {@link #adjustedPromptTokens}.
     */
    public static int adjustedMessageTokens(int rawTokens, boolean modelMatched,
                                             String providerName, String modelId) {
        if (modelMatched) return rawTokens;
        return (int) Math.ceil(rawTokens * resolveSafetyMultiplier(providerName, modelId));
    }

    /**
     * Three-tier safety-multiplier lookup. First match wins, all reads go
     * through ConfigService's Caffeine cache so the hot path stays O(1) after
     * warmup. Each value parsed defensively and clamped to
     * [{@link #MIN_SAFETY_MULTIPLIER}, {@link #MAX_SAFETY_MULTIPLIER}].
     *
     * <ol>
     *   <li>{@code jtokkit.safetyMultiplier.<provider>.<modelId>} — written by
     *       {@code TokenizerCalibrationJob} from observed provider-vs-jtokkit
     *       deltas; updated only when the change crosses a small threshold so
     *       Config writes are infrequent.</li>
     *   <li>{@code jtokkit.safetyMultiplier.<provider>} — operator-supplied
     *       per-provider override for fleets where every model in a provider
     *       shares the same tokenizer family (e.g. all of Anthropic on the
     *       Claude tokenizer).</li>
     *   <li>{@code jtokkit.safetyMultiplier.unmatched} — global default; falls
     *       back to {@value #DEFAULT_SAFETY_MULTIPLIER} when unset.</li>
     * </ol>
     */
    public static double resolveSafetyMultiplier(String providerName, String modelId) {
        if (providerName != null && modelId != null) {
            var specific = parseMultiplier(ConfigService.get(
                    SAFETY_MULTIPLIER_PREFIX + providerName + "." + modelId, null));
            if (specific != null) return specific;
        }
        if (providerName != null) {
            var perProvider = parseMultiplier(ConfigService.get(
                    SAFETY_MULTIPLIER_PREFIX + providerName, null));
            if (perProvider != null) return perProvider;
        }
        var global = parseMultiplier(ConfigService.get(SAFETY_MULTIPLIER_KEY, null));
        return global != null ? global : DEFAULT_SAFETY_MULTIPLIER;
    }

    private static Double parseMultiplier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Math.clamp(Double.parseDouble(raw), MIN_SAFETY_MULTIPLIER, MAX_SAFETY_MULTIPLIER);
        }
        catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Derive the effective {@code max_tokens} for a specific LLM call,
     * clamped so that {@code promptTokens + returnedValue + safetyMargin}
     * fits inside the model's context window.
     *
     * <p>Two bounds at play:
     * <ul>
     *   <li><b>Upper</b>: the operator-configured
     *   {@code ModelInfo.maxTokens} (policy cap on reply size).</li>
     *   <li><b>Context-fit</b>: {@code contextWindow - promptTokens
     *   - OUTPUT_SAFETY_MARGIN_TOKENS} (so providers don't reject with
     *   HTTP 400 "requested N tokens but context is M"). Returned value
     *   is {@code min(upper, contextFit)} and never below
     *   {@link #MIN_OUTPUT_TOKENS}.</li>
     * </ul>
     *
     * <p>Returns {@code null} when the model has no configured cap, in
     * which case we omit {@code max_tokens} from the request and let
     * the provider apply its own default.
     */
    static Integer effectiveMaxTokens(Agent agent, Conversation conv, LlmProvider provider,
                                       List<ChatMessage> messages, List<ToolDef> tools) {
        var modelInfo = ModelResolver.resolveModelInfo(agent, conv, provider).orElse(null);
        if (modelInfo == null || modelInfo.maxTokens() <= 0) return null;

        int configured = modelInfo.maxTokens();
        if (modelInfo.contextWindow() <= 0) return configured;

        int promptTokens = adjustedPromptTokens(providerNameFor(provider), modelIdFor(agent, conv, provider),
                estimateProviderPromptTokens(agent, conv, provider, messages, tools));
        int headroom = modelInfo.contextWindow() - promptTokens - OUTPUT_SAFETY_MARGIN_TOKENS;
        // NB: not Math.clamp — when configured < MIN_OUTPUT_TOKENS (small / mis-configured model) we still want MIN_OUTPUT_TOKENS, which clamp would reject as min>max.
        @SuppressWarnings("java:S6885")
        var result = Math.max(MIN_OUTPUT_TOKENS, Math.min(configured, headroom));
        return result;
    }

    /**
     * Rough token estimate for the tool-schema payload (names,
     * descriptions, parameter JSON). Mirrors {@link #estimateTokens}'s
     * {@code chars/4} approximation; {@code Map.toString()} differs from
     * the JSON wire format but is within that heuristic's margin.
     */
    static int estimateToolTokens(List<ToolDef> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        int chars = 0;
        for (var tool : tools) {
            var fn = tool.function();
            if (fn == null) continue;
            if (fn.name() != null) chars += fn.name().length();
            if (fn.description() != null) chars += fn.description().length();
            if (fn.parameters() != null) chars += fn.parameters().toString().length();
        }
        return chars / 4;
    }

    /**
     * Drop the oldest non-system messages until the prompt's estimated
     * token count fits inside {@code contextWindow - RESERVED_OUTPUT_TOKENS}
     * (JCLAW-291). Always preserves the system message (index 0) and the
     * latest message (last index) since those are required for any
     * meaningful continuation. Returns the input list unchanged when the
     * model has no configured context window, or when the prompt already
     * fits.
     */
    static List<ChatMessage> trimToContextWindow(List<ChatMessage> messages, Agent agent, Conversation conv, LlmProvider provider) {
        return trimToContextWindow(messages, agent, conv, provider, null);
    }

    static List<ChatMessage> trimToContextWindow(List<ChatMessage> messages, Agent agent, Conversation conv,
                                                  LlmProvider provider, List<ToolDef> tools) {
        var modelInfo = ModelResolver.resolveModelInfo(agent, conv, provider).orElse(null);
        if (modelInfo == null || modelInfo.contextWindow() <= 0) return messages;

        int contextWindow = modelInfo.contextWindow();
        var modelId = modelIdFor(agent, conv, provider);
        var providerName = providerNameFor(provider);
        var rawEstimate = estimateProviderPromptTokens(agent, conv, provider, messages, tools);
        int estimatedTokens = adjustedPromptTokens(providerName, modelId, rawEstimate);
        boolean modelMatched = rawEstimate.modelMatched();
        // JCLAW-291: trim target reserves RESERVED_OUTPUT_TOKENS so the reply has
        // a real budget — without this, headroom in effectiveMaxTokens collapses
        // and the model truncates with finish_reason=length on plain replies.
        // Reservation is capped at half the window so tiny-context models don't
        // get trimmed to nothing.
        int reservation = Math.min(RESERVED_OUTPUT_TOKENS, contextWindow / 2);
        int trimTarget = contextWindow - reservation;

        if (estimatedTokens <= trimTarget) return messages;

        // Stage 1 (ported from OpenClaw's preemptive-compaction route
        // "truncate_tool_results_only"): when a single oversized tool result is
        // doing most of the bloat, head/tail-truncate just that message
        // instead of dropping entire history turns. Cheaper and preserves
        // conversational structure — drop-oldest is the fallback below.
        var truncationResult = truncateOversizedToolResults(messages, providerName, modelId, modelMatched,
                trimTarget, estimatedTokens, agent);
        if (truncationResult != null) {
            if (truncationResult.adjustedEstimate() <= trimTarget) {
                // Truncating tool results alone got us under the budget.
                return truncationResult.messages();
            }
            // Truncation reduced the deficit but didn't close it — proceed to
            // drop-oldest with the already-truncated list as the new baseline.
            messages = truncationResult.messages();
            estimatedTokens = truncationResult.adjustedEstimate();
        }

        // Stage 2: find how many oldest non-system messages to drop. Scan
        // forward from index 1 (first after system prompt) and accumulate
        // tokens to remove until we fit. Per-message tokens scaled by the
        // same safety multiplier so the running accumulator stays consistent
        // with the budget check above.
        int total = estimatedTokens;
        int dropCount = 0;
        for (int i = 1; i < messages.size() - 1 && total > trimTarget; i++) {
            int rawMsgTokens = TokenUsageEstimator.estimateMessage(modelId, messages.get(i)).tokens();
            total -= adjustedMessageTokens(rawMsgTokens, modelMatched, providerName, modelId);
            dropCount++;
        }
        if (dropCount > 0) {
            EventLogger.warn("llm", agent.name, null,
                    "Trimmed %d messages to fit context window (window=%d, reservation=%d, target=%d, estimated=%d, post-trim=%d)"
                            .formatted(dropCount, contextWindow, reservation, trimTarget, estimatedTokens, total));
            // Build result: system message + surviving history (skip dropped range)
            var trimmed = new ArrayList<ChatMessage>(messages.size() - dropCount);
            trimmed.add(messages.getFirst());
            trimmed.addAll(messages.subList(1 + dropCount, messages.size()));
            return trimmed;
        }
        return messages;
    }

    // ─── Tool-result truncation ─────────────────────────────────────────────

    static final String TOOL_TRUNCATE_MIN_CHARS_KEY = "chat.truncateToolResultsMinChars";
    static final String TOOL_TRUNCATE_KEEP_HEAD_CHARS_KEY = "chat.truncateToolResultsKeepHeadChars";
    static final String TOOL_TRUNCATE_KEEP_TAIL_CHARS_KEY = "chat.truncateToolResultsKeepTailChars";
    static final String TOOL_TRUNCATE_PRESERVE_RECENT_KEY = "chat.truncateToolResultsPreserveRecent";

    /** Below this content length, truncating wouldn't save enough to be worth it. */
    static final int DEFAULT_TOOL_TRUNCATE_MIN_CHARS = 5000;
    /** Bytes kept from the start of an oversized tool result. */
    static final int DEFAULT_TOOL_TRUNCATE_KEEP_HEAD_CHARS = 500;
    /** Bytes kept from the end of an oversized tool result. */
    static final int DEFAULT_TOOL_TRUNCATE_KEEP_TAIL_CHARS = 500;
    /** Don't touch tool results in the last N messages — recent results are most relevant. */
    static final int DEFAULT_TOOL_TRUNCATE_PRESERVE_RECENT = 4;

    /** Outcome of a truncation pass: the rebuilt messages list and the running adjusted estimate. */
    private record TruncationOutcome(List<ChatMessage> messages, int adjustedEstimate) {}

    /**
     * Head/tail-truncate the largest tool-result messages until the prompt
     * estimate fits in {@code trimTarget}, or no more candidates are
     * available. Returns {@code null} when no truncation was needed or
     * possible; otherwise returns the rebuilt list together with the running
     * adjusted estimate so the caller can decide whether to short-circuit
     * or fall through to drop-oldest.
     *
     * <p>Per-truncation delta accounting: instead of re-tokenizing the full
     * prompt after each truncation, only tokenize the message that changed
     * and subtract {@code (old_msg_tokens - new_msg_tokens) × multiplier}
     * from the running total. Drops the loop from O(n × candidates) full
     * tokenizations to O(n + candidates × avg_message) — meaningful when
     * the prompt is large and there are multiple oversized tool results.
     *
     * <p>Always preserves tool results in the last
     * {@link #DEFAULT_TOOL_TRUNCATE_PRESERVE_RECENT} messages regardless of
     * size — recency wins over size for the model's immediate context.
     */
    private record Candidate(int index, int contentLength) {}

    private static TruncationOutcome truncateOversizedToolResults(
            List<ChatMessage> messages, String providerName, String modelId, boolean modelMatched,
            int trimTarget, int estimatedTokens, Agent agent) {
        int minChars = ConfigService.getInt(TOOL_TRUNCATE_MIN_CHARS_KEY, DEFAULT_TOOL_TRUNCATE_MIN_CHARS);
        int keepHead = ConfigService.getInt(TOOL_TRUNCATE_KEEP_HEAD_CHARS_KEY, DEFAULT_TOOL_TRUNCATE_KEEP_HEAD_CHARS);
        int keepTail = ConfigService.getInt(TOOL_TRUNCATE_KEEP_TAIL_CHARS_KEY, DEFAULT_TOOL_TRUNCATE_KEEP_TAIL_CHARS);
        int preserveRecent = ConfigService.getInt(TOOL_TRUNCATE_PRESERVE_RECENT_KEY,
                DEFAULT_TOOL_TRUNCATE_PRESERVE_RECENT);

        var candidates = collectCandidates(messages, minChars, preserveRecent);
        if (candidates.isEmpty()) return null;
        // Largest first — biggest savings per truncation, lowest count of
        // messages disturbed.
        candidates.sort((a, b) -> Integer.compare(b.contentLength(), a.contentLength()));

        var working = new ArrayList<>(messages);
        int truncatedCount = 0;
        long charsElided = 0;
        int runningEstimate = estimatedTokens;
        // Stop as soon as the running estimate fits — delta-based accounting
        // means we only tokenize the message that just changed, not the
        // whole prompt, per iteration.
        for (var cand : candidates) {
            var savings = attemptTruncate(working, cand, modelId, modelMatched, providerName,
                    keepHead, keepTail);
            if (savings != null) {
                truncatedCount++;
                charsElided += savings.charsElided();
                runningEstimate -= savings.adjustedDelta();
                if (runningEstimate <= trimTarget) break;
            }
        }

        if (truncatedCount == 0) return null;
        EventLogger.warn("llm", agent.name, null,
                "Truncated %d oversized tool result(s) (~%d chars elided, head=%d tail=%d, original estimate=%d, post-truncate=%d, target=%d)"
                        .formatted(truncatedCount, charsElided, keepHead, keepTail, estimatedTokens, runningEstimate, trimTarget));
        return new TruncationOutcome(working, runningEstimate);
    }

    /** Find tool-result messages eligible for head/tail truncation. */
    private static List<Candidate> collectCandidates(List<ChatMessage> messages, int minChars, int preserveRecent) {
        int cutoff = Math.max(0, messages.size() - preserveRecent);
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < cutoff; i++) {
            var m = messages.get(i);
            if (MessageRole.TOOL.value.equals(m.role())
                    && m.content() instanceof String s
                    && s.length() >= minChars) {
                candidates.add(new Candidate(i, s.length()));
            }
        }
        return candidates;
    }

    /** Outcome of a per-message truncation attempt. */
    private record TruncationSavings(int adjustedDelta, int charsElided) {}

    /**
     * Apply head/tail truncation to one candidate message in {@code working}.
     * Returns {@code null} if the truncation wouldn't actually save anything
     * (content already short enough, or tokenizer didn't see savings).
     * Otherwise mutates {@code working} in place and returns the deltas the
     * caller's running totals need.
     */
    private static TruncationSavings attemptTruncate(List<ChatMessage> working, Candidate cand,
                                                       String modelId, boolean modelMatched, String providerName,
                                                       int keepHead, int keepTail) {
        var original = working.get(cand.index());
        var originalText = (String) original.content();
        var truncated = truncateToolResultContent(originalText, keepHead, keepTail);
        // Local delta: tokenize only the old and new versions of THIS
        // message. The framing tokens (TOKENS_PER_MESSAGE + role) cancel out
        // across the diff because we're keeping the same message shape and
        // role, so the delta is purely content-driven.
        if (truncated.length() >= originalText.length()) return null;
        int oldMsgTokens = TokenUsageEstimator.estimateMessage(modelId, original).tokens();
        var newMsg = new ChatMessage(original.role(), truncated, original.toolCalls(),
                original.toolCallId(), original.toolName());
        int newMsgTokens = TokenUsageEstimator.estimateMessage(modelId, newMsg).tokens();
        int rawDelta = oldMsgTokens - newMsgTokens;
        if (rawDelta <= 0) return null;
        working.set(cand.index(), newMsg);
        return new TruncationSavings(
                adjustedMessageTokens(rawDelta, modelMatched, providerName, modelId),
                originalText.length() - truncated.length());
    }

    /**
     * Head/tail-truncate a tool-result body. Keeps the first
     * {@code keepHead} chars and the last {@code keepTail} chars, joined by
     * an inline elision marker that tells the model (and operators reading
     * the trace) exactly how much was removed. Returns the original string
     * if it's already short enough that truncation wouldn't save bytes.
     *
     * <p>Required non-null; callers in
     * {@link #truncateOversizedToolResults} already filter via
     * {@code instanceof String} so this never sees a null body.
     */
    public static String truncateToolResultContent(String original, int keepHead, int keepTail) {
        int len = original.length();
        // Don't bother if the elided fragment is shorter than the marker
        // itself — keep the original for readability.
        int markerOverhead = 96; // approximate length of the marker template
        if (len <= keepHead + keepTail + markerOverhead) return original;
        var head = original.substring(0, Math.min(keepHead, len));
        var tail = original.substring(Math.max(0, len - keepTail));
        int elidedChars = len - head.length() - tail.length();
        return head
                + "\n\n[...JClaw elided " + elidedChars
                + " chars of this tool result to fit the context window. "
                + "The full output is preserved in the conversation history database.]\n\n"
                + tail;
    }

    static TokenUsageEstimator.ChatRequestTokens estimateProviderPromptTokens(
            Agent agent, Conversation conv, LlmProvider provider,
            List<ChatMessage> messages, List<ToolDef> tools) {
        return TokenUsageEstimator.estimateChatRequest(modelIdFor(agent, conv, provider), messages, tools);
    }

    private static String modelIdFor(Agent agent, Conversation conv, LlmProvider provider) {
        var modelId = ModelResolver.effectiveModelId(agent, conv);
        if (modelId != null) return modelId;
        var models = provider != null && provider.config() != null ? provider.config().models() : null;
        if (models != null && models.size() == 1) return models.getFirst().id();
        return null;
    }

    private static String providerNameFor(LlmProvider provider) {
        return provider != null && provider.config() != null ? provider.config().name() : null;
    }

    /**
     * Sum chars across string content, vision text-parts, and tool-call
     * names/arguments, then divide by 4 — the chars/4 heuristic for
     * English tokenizers. Image data is base64 and doesn't meaningfully
     * correspond to {@code chars/4} (providers count image tokens
     * separately), so multi-part content's image segments are skipped.
     */
    public static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (var msg : messages) {
            chars += contentChars(msg.content());
            chars += toolCallChars(msg.toolCalls());
        }
        return chars / 4; // rough approximation: ~4 chars per token
    }

    /**
     * Count chars in a {@link ChatMessage#content()} field. Strings count
     * directly; multi-part content (vision/image blocks) sums text parts
     * only — image data is base64 and doesn't meaningfully correspond to
     * the chars/4 heuristic (providers count image tokens separately).
     */
    private static int contentChars(Object content) {
        if (content instanceof String s) return s.length();
        if (!(content instanceof List<?> parts)) return 0;
        int chars = 0;
        for (var part : parts) {
            if (part instanceof Map<?,?> m && m.get("text") instanceof String t) {
                chars += t.length();
            }
        }
        return chars;
    }

    /** Tool call names + arguments also consume input tokens. */
    private static int toolCallChars(List<LlmTypes.ToolCall> toolCalls) {
        if (toolCalls == null) return 0;
        int chars = 0;
        for (var tc : toolCalls) {
            var fn = tc.function();
            if (fn == null) continue;
            if (fn.name() != null) chars += fn.name().length();
            if (fn.arguments() != null) chars += fn.arguments().length();
        }
        return chars;
    }
}
