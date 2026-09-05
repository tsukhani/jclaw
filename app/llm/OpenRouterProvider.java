package llm;

import agents.CurrentTimeInjector;
import agents.SystemPromptAssembler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.ProviderConfig;
import models.MessageRole;

/**
 * OpenRouter provider. Extends OpenAI-compatible behavior with:
 * - {@code reasoning} object in requests (OpenRouter's native format)
 * - {@code reasoning_details} array in streaming deltas
 * - {@code reasoning_tokens} at top level in usage
 *
 * @see <a href="https://openrouter.ai/docs/guides/best-practices/reasoning-tokens">OpenRouter Reasoning Docs</a>
 */
public final class OpenRouterProvider extends LlmProvider {

    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_MESSAGES = "messages";

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

    /**
     * JCLAW-794: {@code minimal}, not {@code none}, is how reasoning is turned off here.
     *
     * <p>OpenRouter rejects {@code effort: "none"} outright on a growing set of models —
     * "Reasoning is mandatory for this endpoint and cannot be disabled", HTTP 400, not
     * retried — and the set is no longer exotic: measured 2026-08-05 it covers
     * gemini-3.5-flash, gemini-3.5-flash-lite, gemini-3.6-flash, deepseek-r1 and
     * gpt-oss-120b. Where it is still accepted it changes nothing, because those models
     * report zero reasoning tokens with the parameter omitted anyway. So {@code none} is a
     * directive that either fails the request or does nothing.
     *
     * <p>{@code minimal} was accepted by every model probed and keeps the token saving the
     * off-switch exists for: zero reasoning tokens across the Gemini line, 3 on
     * gpt-oss-120b, 20 on deepseek-r1 — against a hard failure today.
     *
     * <p>Deliberately NOT lifted into {@link LlmProvider} or copied to the other providers.
     * Ollama rejects {@code minimal} ("invalid reasoning value: 'minimal' (must be high,
     * medium, low, ...)") and accepts {@code none}; LM Studio accepts {@code none} and
     * genuinely suppresses reasoning with it. The correct value is per-endpoint, which is
     * why this is a per-provider override.
     */
    @Override
    protected void disableReasoning(JsonObject request) {
        var reasoning = new JsonObject();
        reasoning.addProperty("effort", "minimal");
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
        // OpenRouter puts reasoning_tokens at the top level of usage, falling
        // back to the OpenAI nested format for proxied models — the shared chain.
        return readReasoningTokens(usageObj);
    }

    @Override
    protected void applyCacheDirectives(JsonObject request, ChatRequest chatRequest) {
        // Opt into usage accounting so the upstream cache-hit fields
        // (prompt_tokens_details.cached_tokens, cache_discount) come back in the
        // response. Without this, OpenRouter strips usage details.
        var usage = new JsonObject();
        usage.addProperty("include", true);
        request.add("usage", usage);

        // JCLAW-128: two-breakpoint strategy for Anthropic-routed models. Under
        // Anthropic's canonical tools → system → messages ordering, a breakpoint
        // on the stable system prefix caches (tools + stable system), and a
        // second breakpoint on the trailing user message extends the cached
        // prefix through the full conversation history. Each turn only re-
        // prefills the one new user message; multi-round tool loops within a
        // turn reuse the breakpoint from the previous round. Anthropic permits
        // up to 4 cache breakpoints per request — we emit 2.
        //
        // OpenAI/DeepSeek/Grok/Gemini 2.5 cache implicitly and need no directive.
        // The else-branch leaves the marker in place deliberately — LlmProvider scrubs it
        // after this hook returns, for every provider at once.
        if (requiresExplicitCacheControl(chatRequest.model())) {
            splitSystemMessageAtCacheBoundary(request);
            injectTrailingUserMessageCacheBreakpoint(request);
        }
    }

    /**
     * Find the first system message and split its text at
     * {@link SystemPromptAssembler#CACHE_BOUNDARY_MARKER} into two blocks:
     * a stable-prefix block tagged with {@code cache_control: ephemeral} and a
     * dynamic-suffix block without cache_control. The marker text itself is
     * consumed by the split.
     *
     * When the marker is absent (older prompts, test fixtures, or the edge
     * case where the assembler ran without the memories section), falls back
     * to the pre-JCLAW-128 behavior: single block containing the full system
     * text with cache_control attached. This preserves the cache-write
     * behavior for callers that predate the marker convention.
     */
    private static void splitSystemMessageAtCacheBoundary(JsonObject request) {
        var systemMsg = findFirstSystemMessage(request);
        if (systemMsg == null) return;
        var content = systemMsg.get(FIELD_CONTENT);
        if (content == null || content.isJsonNull()) return;

        // Only handle string content here — block-array content means an
        // upstream caller already structured the system message and we
        // shouldn't second-guess their layout.
        if (!content.isJsonPrimitive()) {
            // Fall back to the legacy single-cache-control behavior on the
            // last existing block, matching pre-JCLAW-128 semantics.
            if (content.isJsonArray()) {
                var blocks = content.getAsJsonArray();
                if (!blocks.isEmpty()) {
                    attachCacheControl(blocks.get(blocks.size() - 1).getAsJsonObject());
                }
            }
            return;
        }

        systemMsg.add(FIELD_CONTENT, splitIntoCachedBlocks(content.getAsString()));
    }

    /**
     * Cut the system text at its markers into cached and uncached blocks.
     *
     * <p>Both markers present gives three segments: the static prefix and the core-memory
     * block each carry a breakpoint, so a core-memory write re-prefills only the core
     * block instead of the workspace files, skills and tool catalog above it (JCLAW-978).
     * Only the cache boundary present gives the two-segment split — the shape for an agent
     * with no core memories. Neither present falls back to one cached block, which is what
     * prompts predating the marker convention produce.
     *
     * <p>Empty segments are dropped rather than sent as empty text blocks, and a breakpoint
     * is never attached to the trailing segment: it is the per-turn-variable tail, so
     * caching it would write a block that can never be read back.
     */
    private static JsonArray splitIntoCachedBlocks(String text) {
        var coreIdx = text.indexOf(SystemPromptAssembler.CORE_MEMORY_BOUNDARY_MARKER);
        var boundaryIdx = text.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        var blocks = new JsonArray();

        if (boundaryIdx < 0) {
            var block = textBlock(text);
            attachCacheControl(block);
            blocks.add(block);
            return blocks;
        }

        // A core marker only counts when it precedes the cache boundary; anything else
        // means the text was assembled by something other than buildPrompt.
        if (coreIdx >= 0 && coreIdx < boundaryIdx) {
            addCached(blocks, text.substring(0, coreIdx));
            addCached(blocks, text.substring(
                    coreIdx + SystemPromptAssembler.CORE_MEMORY_BOUNDARY_MARKER.length(), boundaryIdx));
        } else {
            addCached(blocks, text.substring(0, boundaryIdx));
        }

        var suffix = text.substring(boundaryIdx + SystemPromptAssembler.CACHE_BOUNDARY_MARKER.length());
        if (!suffix.isEmpty()) {
            blocks.add(textBlock(suffix));
        }
        return blocks;
    }

    /** Append {@code segment} as a cache-tagged block, skipping it when empty. */
    private static void addCached(JsonArray blocks, String segment) {
        if (segment.isEmpty()) return;
        var block = textBlock(segment);
        attachCacheControl(block);
        blocks.add(block);
    }

    /**
     * Attach {@code cache_control: ephemeral} to the last block of the final
     * message when that message's role is {@code user}. No-op otherwise —
     * during multi-round tool loops the final message is {@code tool}, and
     * mid-stream the final message may be an empty {@code assistant} draft;
     * neither should receive a cache tag.
     *
     * Effect: the cached prefix extends through the full conversation history
     * up to and including the current user turn. On the next turn (or the
     * next tool-loop round), everything up to this breakpoint is eligible for
     * a cache read.
     */
    private static void injectTrailingUserMessageCacheBreakpoint(JsonObject request) {
        if (!request.has(FIELD_MESSAGES) || !request.get(FIELD_MESSAGES).isJsonArray()) return;
        var messages = request.getAsJsonArray(FIELD_MESSAGES);
        if (messages.isEmpty()) return;
        // JCLAW-900: skip the trailing clock block. Anchoring to it would put a
        // value that changes every turn INSIDE the cached prefix, which is the
        // defect this pairing fixes — the breakpoint has to land on the last
        // message whose content is stable, and the clock then rides past it
        // uncached and therefore always fresh.
        int idx = messages.size() - 1;
        if (messages.get(idx).isJsonObject()
                && isClockMessage(messages.get(idx).getAsJsonObject())) {
            idx--;
        }
        if (idx < 0) return;
        var last = messages.get(idx);
        if (!last.isJsonObject()) return;
        var msg = last.getAsJsonObject();
        if (!msg.has("role") || !MessageRole.USER.value.equals(msg.get("role").getAsString())) return;

        var blocks = ensureBlockArrayContent(msg);
        if (blocks == null || blocks.isEmpty()) return;
        attachCacheControl(blocks.get(blocks.size() - 1).getAsJsonObject());
    }

    /**
     * True when this message is the trailing clock block appended by
     * {@link agents.CurrentTimeInjector}. Content-based rather than positional:
     * the tool loop appends assistant and tool messages after it, so "last" is
     * not a reliable test once a turn has run a tool round.
     */
    private static boolean isClockMessage(JsonObject msg) {
        if (!msg.has("role") || !MessageRole.USER.value.equals(msg.get("role").getAsString())) {
            return false;
        }
        var content = msg.get(FIELD_CONTENT);
        return content != null && content.isJsonPrimitive()
                && CurrentTimeInjector.isClockBlock(content.getAsString());
    }

    /**
     * Convert a message's string content into a single-element block array,
     * or return the existing block array. Returns {@code null} when the
     * content is null/JsonNull/non-convertible. Mutates the message to hold
     * the new array form when conversion happens, so the caller can tag the
     * last block and have the change persist.
     */
    private static JsonArray ensureBlockArrayContent(JsonObject msg) {
        var content = msg.get(FIELD_CONTENT);
        if (content == null || content.isJsonNull()) return null;
        if (content.isJsonArray()) return content.getAsJsonArray();
        if (!content.isJsonPrimitive()) return null;

        var blocks = new JsonArray();
        blocks.add(textBlock(content.getAsString()));
        msg.add(FIELD_CONTENT, blocks);
        return blocks;
    }

    private static JsonObject textBlock(String text) {
        var block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static void attachCacheControl(JsonObject block) {
        var cc = new JsonObject();
        cc.addProperty("type", "ephemeral");
        block.add("cache_control", cc);
    }

    /**
     * Returns true for OpenRouter model IDs whose upstream provider requires explicit
     * {@code cache_control} to activate prompt caching. Models not listed here either
     * cache implicitly (OpenAI, DeepSeek, Grok, Gemini 2.5) or have no caching.
     *
     * <p>Membership is decided by probe, not by docs (JCLAW-980). A model that already
     * caches implicitly must stay out: the directive buys nothing and costs a cache-write
     * premium on the cold call. Probed 2026-08-05 with an identical 9k-token prompt, cold
     * then warm, reading {@code prompt_tokens_details} and {@code cost} back:
     *
     * <ul>
     *   <li>{@code qwen/} — in. Implicit caching reaches 8448/9028 tokens at 0.000734
     *       warm; the directive reaches 9010 at 0.000302, so a warm read is 2.4x cheaper.
     *       The cold write costs 25% more (0.003617 against 0.002897), repaid inside two
     *       warm turns.</li>
     *   <li>{@code x-ai/grok-} — out. Implicit caching already returns the full 9216
     *       tokens warm; adding the directive changed neither the cached count nor the
     *       cost, so it would be pure cache-write premium.</li>
     * </ul>
     */
    private static boolean requiresExplicitCacheControl(String model) {
        if (model == null) return false;
        if (model.startsWith("anthropic/") || model.startsWith("qwen/")) return true;
        // Gemini 2.5 Pro/Flash cache implicitly; older Gemini variants need cache_control.
        return model.startsWith("google/gemini-") && !model.startsWith("google/gemini-2.5-");
    }
}
