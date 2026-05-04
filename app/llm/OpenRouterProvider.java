package llm;

import agents.SystemPromptAssembler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import llm.LlmTypes.*;
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
        // OpenRouter puts reasoning_tokens at the top level of usage. Fallback
        // to OpenAI nested format (for models proxied from OpenAI).
        int top = readUsageInt(usageObj, "reasoning_tokens");
        return top > 0 ? top : readUsageInt(usageObj, "completion_tokens_details", "reasoning_tokens");
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
        if (requiresExplicitCacheControl(chatRequest.model())) {
            splitSystemMessageAtCacheBoundary(request);
            injectTrailingUserMessageCacheBreakpoint(request);
        } else {
            // Non-caching providers still carry the boundary marker verbatim in
            // the system text, which is meaningless to them. Strip it so it
            // doesn't pollute the model's context (observed: some models echo
            // HTML comments back if asked to quote their instructions).
            stripCacheBoundaryMarker(request);
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
        var content = systemMsg.get("content");
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

        var text = content.getAsString();
        var markerIdx = text.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        var blocks = new JsonArray();
        if (markerIdx < 0) {
            // No marker: single cached block, preserving legacy behavior.
            var block = textBlock(text);
            attachCacheControl(block);
            blocks.add(block);
        } else {
            var prefix = text.substring(0, markerIdx);
            var suffix = text.substring(markerIdx + SystemPromptAssembler.CACHE_BOUNDARY_MARKER.length());
            var prefixBlock = textBlock(prefix);
            attachCacheControl(prefixBlock);
            blocks.add(prefixBlock);
            // Skip the suffix block entirely when empty — no reason to send an
            // empty text block just to hold a missing cache_control.
            if (!suffix.isEmpty()) {
                blocks.add(textBlock(suffix));
            }
        }
        systemMsg.add("content", blocks);
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
        if (!request.has("messages") || !request.get("messages").isJsonArray()) return;
        var messages = request.getAsJsonArray("messages");
        if (messages.isEmpty()) return;
        var last = messages.get(messages.size() - 1);
        if (!last.isJsonObject()) return;
        var msg = last.getAsJsonObject();
        if (!msg.has("role") || !MessageRole.USER.value.equals(msg.get("role").getAsString())) return;

        var blocks = ensureBlockArrayContent(msg);
        if (blocks == null || blocks.isEmpty()) return;
        attachCacheControl(blocks.get(blocks.size() - 1).getAsJsonObject());
    }

    /**
     * Remove the cache-boundary marker substring from the first system message
     * for providers that don't participate in our cache protocol. The marker
     * is an HTML comment inside the system text; cache-emitting routes
     * consume it via {@link #splitSystemMessageAtCacheBoundary}, but other
     * routes (OpenAI, DeepSeek, Grok, Gemini 2.5, Ollama) would otherwise
     * ship it verbatim to the model.
     */
    private static void stripCacheBoundaryMarker(JsonObject request) {
        var systemMsg = findFirstSystemMessage(request);
        if (systemMsg == null) return;
        var content = systemMsg.get("content");
        if (content == null || !content.isJsonPrimitive()) return;
        var text = content.getAsString();
        if (!text.contains(SystemPromptAssembler.CACHE_BOUNDARY_MARKER)) return;
        systemMsg.addProperty("content", text.replace(SystemPromptAssembler.CACHE_BOUNDARY_MARKER, ""));
    }

    /**
     * Convert a message's string content into a single-element block array,
     * or return the existing block array. Returns {@code null} when the
     * content is null/JsonNull/non-convertible. Mutates the message to hold
     * the new array form when conversion happens, so the caller can tag the
     * last block and have the change persist.
     */
    private static JsonArray ensureBlockArrayContent(JsonObject msg) {
        var content = msg.get("content");
        if (content == null || content.isJsonNull()) return null;
        if (content.isJsonArray()) return content.getAsJsonArray();
        if (!content.isJsonPrimitive()) return null;

        var blocks = new JsonArray();
        blocks.add(textBlock(content.getAsString()));
        msg.add("content", blocks);
        return blocks;
    }

    private static JsonObject findFirstSystemMessage(JsonObject request) {
        if (!request.has("messages") || !request.get("messages").isJsonArray()) return null;
        for (var el : request.getAsJsonArray("messages")) {
            if (!el.isJsonObject()) continue;
            var msg = el.getAsJsonObject();
            if (msg.has("role") && MessageRole.SYSTEM.value.equals(msg.get("role").getAsString())) {
                return msg;
            }
        }
        return null;
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
     */
    private static boolean requiresExplicitCacheControl(String model) {
        if (model == null) return false;
        if (model.startsWith("anthropic/")) return true;
        // Gemini 2.5 Pro/Flash cache implicitly; older Gemini variants need cache_control.
        if (model.startsWith("google/gemini-") && !model.startsWith("google/gemini-2.5-")) return true;
        return false;
    }
}
