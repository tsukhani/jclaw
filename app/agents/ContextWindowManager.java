package agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolDef;
import models.Agent;
import models.Conversation;
import services.EventLogger;

/**
 * Token-estimation and context-window arithmetic for the agent loop.
 * Extracted from {@link AgentRunner} as part of JCLAW-299; the cluster
 * here is the chars/4 heuristic plus the JCLAW-291 reservation policy
 * that keeps the assistant reply from collapsing into
 * {@code finish_reason=length}.
 *
 * <h3>The chars/4 heuristic</h3>
 * {@link #estimateTokens} and {@link #estimateToolTokens} both sum
 * relevant characters and divide by 4 — a rough approximation for
 * English tokenizers. The {@link #OUTPUT_SAFETY_MARGIN_TOKENS} fudge
 * absorbs the slack between this heuristic and the provider's real
 * tokenizer (plus role-tag and JSON-framing overhead the char count
 * doesn't see).
 *
 * <h3>Reply-budget reservation (JCLAW-291)</h3>
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

    private ContextWindowManager() {}

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
        var modelInfo = AgentRunner.resolveModelInfo(agent, conv, provider).orElse(null);
        if (modelInfo == null || modelInfo.maxTokens() <= 0) return null;

        int configured = modelInfo.maxTokens();
        if (modelInfo.contextWindow() <= 0) return configured;

        int promptTokens = estimateTokens(messages) + estimateToolTokens(tools);
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
        var modelInfo = AgentRunner.resolveModelInfo(agent, conv, provider).orElse(null);
        if (modelInfo == null || modelInfo.contextWindow() <= 0) return messages;

        int contextWindow = modelInfo.contextWindow();
        int estimatedTokens = estimateTokens(messages);
        // JCLAW-291: trim target reserves RESERVED_OUTPUT_TOKENS so the reply has
        // a real budget — without this, headroom in effectiveMaxTokens collapses
        // and the model truncates with finish_reason=length on plain replies.
        // Reservation is capped at half the window so tiny-context models don't
        // get trimmed to nothing.
        int reservation = Math.min(RESERVED_OUTPUT_TOKENS, contextWindow / 2);
        int trimTarget = contextWindow - reservation;

        if (estimatedTokens <= trimTarget) return messages;

        // Find how many oldest non-system messages to drop. Scan forward from index 1
        // (first after system prompt) and accumulate tokens to remove until we fit.
        int total = estimatedTokens;
        int dropCount = 0;
        for (int i = 1; i < messages.size() - 1 && total > trimTarget; i++) {
            total -= estimateTokens(List.of(messages.get(i)));
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

    /**
     * Sum chars across string content, vision text-parts, and tool-call
     * names/arguments, then divide by 4 — the chars/4 heuristic for
     * English tokenizers. Image data is base64 and doesn't meaningfully
     * correspond to {@code chars/4} (providers count image tokens
     * separately), so multi-part content's image segments are skipped.
     */
    static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (var msg : messages) {
            if (msg.content() instanceof String s) {
                chars += s.length();
            } else if (msg.content() instanceof List<?> parts) {
                // Multi-part content (vision/image blocks): sum text parts only.
                // Image data is base64 and doesn't meaningfully correspond to
                // chars/4 token estimation — providers count image tokens separately.
                for (var part : parts) {
                    if (part instanceof Map<?,?> m) {
                        var text = m.get("text");
                        if (text instanceof String t) chars += t.length();
                    }
                }
            }
            // Tool call names + arguments also consume input tokens.
            if (msg.toolCalls() != null) {
                for (var tc : msg.toolCalls()) {
                    if (tc.function() != null) {
                        if (tc.function().name() != null) chars += tc.function().name().length();
                        if (tc.function().arguments() != null) chars += tc.function().arguments().length();
                    }
                }
            }
        }
        return chars / 4; // rough approximation: ~4 chars per token
    }
}
