package agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import services.EventLogger;
import utils.LatencyTrace;

/**
 * Per-LLM-round tool-call orchestrators. Extracted from
 * {@link AgentRunner} as part of JCLAW-299. Two sibling entry points:
 * {@link #callWithToolLoop} drives the synchronous (non-streaming)
 * path; {@link #handleToolCallsStreaming} drives the streaming path's
 * tool-call recursion. Both share the {@link #yieldRequestedInLastRound}
 * helper for JCLAW-273 yield detection.
 *
 * <h3>Why two methods, not one</h3>
 * The sync loop consumes a single {@link ChatResponse} per round and
 * inspects {@code response.choices()} to pull out the assistant
 * message; the streaming loop consumes an SSE accumulator and walks
 * its incremental token stream + finish_reason. Bridging the two
 * shapes inside one method would either funnel both through a
 * pseudo-stream wrapper (adding indirection for the sync caller) or
 * fork on every step (adding branching to a hot path). Keeping the
 * two paths as siblings preserves clarity at the cost of a small
 * amount of duplication around max-rounds, truncation, and yield
 * detection — both of which delegate the heavy lifting to the
 * already-extracted helper classes.
 *
 * <h3>What the loop owns vs delegates</h3>
 * <ul>
 *   <li><b>Owned</b>: round dispatch, JCLAW-291 cooperative-cancel
 *   checkpoints, JCLAW-165 audio-format retry in the sync path, the
 *   empty-continuation synthesis-nudge retry in the streaming path,
 *   JCLAW-273 yield-on-success detection.</li>
 *   <li><b>Delegated</b>: token estimation
 *   ({@link ContextWindowManager}), model resolution
 *   ({@link ModelResolver}), tool batch dispatch
 *   ({@link ParallelToolExecutor}), audio-rejection detection
 *   ({@link AudioRetryStrategy}), client cancellation
 *   ({@link CancellationManager}), and message hydration
 *   ({@link MessageHydrator}).</li>
 * </ul>
 *
 * <h3>The yield handshake (JCLAW-273)</h3>
 * Both loops scan tool-result entries from the just-appended round
 * for {@link tools.YieldToSubagentTool#YIELD_SENTINEL_PREFIX}. When
 * the marker appears, the loop returns {@link AgentRunner#YIELDED_RESPONSE}
 * so the caller skips the final-assistant-message persist; the
 * parent's logical turn resumes later via
 * {@code SpawnSubagentTool.runAsyncAndAnnounce} once the child
 * terminates.
 */
public final class ToolCallLoopRunner {

    private ToolCallLoopRunner() {}

    /**
     * JCLAW-291: result wrapper for {@link #callWithToolLoop}. Carries
     * the model's reply text plus a {@code truncated} flag set when
     * the final non-tool-call assistant turn came back with
     * {@code finish_reason = length / max_tokens}. Caller plumbs the
     * flag into the persist site and (for subagents) into the
     * {@link AgentRunner.RunResult} so the announce card can surface
     * a truncation marker without the chat UI having to introspect
     * raw provider responses.
     */
    public record LoopOutcome(String content, boolean truncated) {
        public LoopOutcome(String content) { this(content, false); }
    }

    @SuppressWarnings({"java:S107", "java:S127"}) // S107: internal tool-loop dispatcher; S127: round-- in body is JCLAW-165's single-use audio-format retry
    static LoopOutcome callWithToolLoop(Agent agent, Conversation conversation, Long conversationId,
                                         List<ChatMessage> messages, List<ToolDef> tools,
                                         LlmProvider primary, LlmProvider secondary,
                                         List<VisionAudioAssembler.AudioBearer> audioBearers) {
        // Helpers like effectiveModelId / effectiveMaxTokens accept a nullable
        // conversation for use elsewhere, but this loop dereferences
        // conversation.channelType when handing off to the LLM provider —
        // the channel type is a required field on the call. Assert the
        // precondition explicitly so a future caller passing null gets a
        // clear failure here instead of an opaque NPE deeper in the stack.
        Objects.requireNonNull(conversation, "conversation");
        var currentMessages = new ArrayList<>(messages);
        var thinkingMode = ModelResolver.resolveThinkingMode(agent, conversation, primary);
        var effectiveModelId = ModelResolver.effectiveModelId(agent, conversation);
        var modelInfoForOutcome = ModelResolver.resolveModelInfo(agent, conversation, primary).orElse(null);
        var supportsAudioInitially = modelInfoForOutcome != null && modelInfoForOutcome.supportsAudio();
        boolean audioRetryAttempted = false;
        boolean transcriptAwaitedAlready = !supportsAudioInitially && !audioBearers.isEmpty();

        for (int round = 0; round < AgentRunner.maxToolRounds(); round++) {
            // JCLAW-291: cooperative-cancel checkpoint at the top of each
            // LLM round. Between-rounds is the natural safe point — we
            // never check inside a streaming chunk handler (too chatty)
            // or mid-tool-call (would orphan partial side effects).
            AgentRunner.checkSubagentCancel(conversation);
            // Recompute per-round so the clamp tracks the growing history.
            var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, currentMessages, tools);
            ChatResponse response;
            try {
                response = (secondary != null)
                        ? LlmProvider.chatWithFailover(primary, secondary, effectiveModelId, currentMessages, tools, maxTokens, thinkingMode, conversation.channelType)
                        : primary.chat(effectiveModelId, currentMessages, tools, maxTokens, thinkingMode, conversation.channelType);
            } catch (Exception e) {
                // JCLAW-165: provider-side audio-format rejection — fall back
                // to transcript-as-text and retry once. Only kicks in when the
                // request actually carried audio (audioBearers non-empty) and
                // we haven't already retried this turn.
                if (!audioRetryAttempted && !audioBearers.isEmpty() && AudioRetryStrategy.isAudioFormatRejection(e)) {
                    audioRetryAttempted = true;
                    transcriptAwaitedAlready = true;
                    if (!AudioRetryStrategy.anyTranscriptAvailable(audioBearers)) {
                        // No usable transcript means we'd just send fallback
                        // notes — better to fail with a clear error than
                        // ship a degraded prompt the user can't tell came
                        // from a transcription failure.
                        AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, "error",
                                "no_transcript_after_rejection", true);
                        EventLogger.warn("llm", agent.name, null,
                                "Audio format rejected and no transcript available — failing turn");
                        return new LoopOutcome("I'm sorry — the audio attachment couldn't be transcribed and the model rejected the audio format directly. Please try again.");
                    }
                    EventLogger.warn("llm", agent.name, null,
                            "Provider %s rejected audio format; retrying with transcript-as-text"
                                    .formatted(primary.config().name()));
                    currentMessages = new ArrayList<>(VisionAudioAssembler.applyTranscriptsForCapability(
                            currentMessages, audioBearers, false));
                    round--;  // JCLAW-165: re-issue this round with the rewritten messages (gated by audioRetryAttempted)
                    continue;
                }
                EventLogger.error("llm", agent.name, null, "LLM call failed: %s".formatted(e.getMessage()));
                if (!audioBearers.isEmpty()) {
                    AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, "error",
                            AudioRetryStrategy.shortErrorTag(e), transcriptAwaitedAlready);
                }
                return new LoopOutcome("I'm sorry, I encountered an error communicating with the AI provider. Please try again.");
            }
            // Successful response. Fire AUDIO_PASSTHROUGH_OUTCOME log when the
            // request carried audio so the field-data set we'll later use to
            // grow a known-good provider/format matrix has full coverage.
            if (round == 0 && !audioBearers.isEmpty()) {
                AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary,
                        audioRetryAttempted ? "downgraded" : "accepted",
                        null, transcriptAwaitedAlready);
            }

            if (response.choices() == null || response.choices().isEmpty()) {
                return new LoopOutcome("No response received from the AI provider.");
            }

            var choice = response.choices().getFirst();
            var assistantMsg = choice.message();
            boolean toolCallsEmpty = assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty();

            // No tool calls — return the content. JCLAW-291: when finish_reason
            // signals truncation on this branch, the model ran out of output
            // budget mid-reply (the prompt-fills-window scenario). Carry the
            // flag up to the persist site so the chat UI can mark the row.
            if (toolCallsEmpty) {
                if (TruncationDiagnostics.isTruncationFinish(choice.finishReason())) {
                    TruncationDiagnostics.logEmptyToolCallsTruncation("callWithToolLoop", agent, conversation, primary,
                            conversation.channelType, choice.finishReason(), currentMessages, tools);
                    return new LoopOutcome(MessageHydrator.contentAsString(assistantMsg.content()), true);
                }
                return new LoopOutcome(MessageHydrator.contentAsString(assistantMsg.content()));
            }

            // Check for truncated response (max tokens hit mid-tool-call)
            if (TruncationDiagnostics.isTruncationFinish(choice.finishReason())) {
                EventLogger.warn("llm", agent.name, null,
                        "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
                var content = assistantMsg.content() != null ? (String) assistantMsg.content()
                        : "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach.";
                return new LoopOutcome(content, true);
            }

            // Tool calls — execute (in parallel when multiple) and continue
            currentMessages.add(assistantMsg);
            int toolResultsAnchor = currentMessages.size();
            EventLogger.info("tool", agent.name, null,
                    "Round %d: executing %d tool call(s)".formatted(round + 1, assistantMsg.toolCalls().size()));

            ParallelToolExecutor.executeToolsParallel(assistantMsg.toolCalls(), agent, conversationId,
                    currentMessages, null, null, null, null);

            // JCLAW-291: cooperative-cancel checkpoint between tool calls
            // and the next LLM round. If /subagent kill landed during the
            // tool-call batch, abort here rather than spending another
            // round on the now-stale plan.
            AgentRunner.checkSubagentCancel(conversation);

            // JCLAW-273: detect a successful yield_to_subagent call and bail
            // out of the tool-call loop without continuing to the next LLM
            // round. The runner returns YIELDED_RESPONSE so the caller skips
            // its final-assistant-message persist; the parent's logical
            // turn resumes later from tools.SpawnSubagentTool#runAsyncAndAnnounce
            // once the child terminates.
            if (yieldRequestedInLastRound(currentMessages, toolResultsAnchor)) {
                EventLogger.info("tool", agent.name, null,
                        "Round %d: yield_to_subagent invoked — suspending parent turn".formatted(round + 1));
                return new LoopOutcome(AgentRunner.YIELDED_RESPONSE);
            }
        }

        return new LoopOutcome("I reached the maximum number of tool execution rounds. Please try a simpler request.");
    }

    /**
     * JCLAW-273: scan the just-appended tool-result entries (those at
     * index {@code >= fromIndex}) for the
     * {@link tools.YieldToSubagentTool#YIELD_SENTINEL_PREFIX} marker
     * that the yield companion tool returns on success. Returns
     * {@code true} if any tool-result content starts with the marker,
     * meaning the parent's loop should exit without emitting a final
     * assistant reply.
     *
     * <p>String-prefix scan rather than parsing JSON because the AC
     * sentinel shape is closed (the tool always returns the same
     * shape) and a prefix compare is robust against any future field
     * reordering.
     */
    private static boolean yieldRequestedInLastRound(List<ChatMessage> currentMessages, int fromIndex) {
        for (int i = fromIndex; i < currentMessages.size(); i++) {
            var m = currentMessages.get(i);
            if (m == null) continue;
            if (!MessageRole.TOOL.value.equals(m.role())) continue;
            var content = m.content();
            if (content instanceof String s
                    && s.startsWith(tools.YieldToSubagentTool.YIELD_SENTINEL_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("java:S107") // Tool-call streaming dispatcher — every parameter is required orchestration state
    static String handleToolCallsStreaming(Agent agent, Conversation conversation, Long conversationId,
                                            List<ChatMessage> messages, List<ToolDef> tools,
                                            List<ToolCall> toolCalls, String priorContent,
                                            LlmProvider provider,
                                            AgentRunner.StreamingCallbacks cb,
                                            String thinkingMode,
                                            int round, AtomicBoolean isCancelled,
                                            LatencyTrace trace,
                                            LlmProvider.TurnUsage turnUsage,
                                            List<String> collectedImages,
                                            String channelType) {
        if (round >= AgentRunner.maxToolRounds()) {
            return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
        }
        if (isCancelled.get()) {
            return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
        }
        EventLogger.info("tool", agent.name, null,
                "Streaming round %d: executing %d tool call(s)".formatted(round + 1, toolCalls.size()));

        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));

        int streamingToolResultsAnchor = currentMessages.size();
        var toolRoundStartNs = System.nanoTime();
        ParallelToolExecutor.executeToolsParallel(toolCalls, agent, conversationId, currentMessages,
                cb.onStatus(), cb.onToolCall(), collectedImages, isCancelled);
        trace.addToolRound((System.nanoTime() - toolRoundStartNs) / 1_000_000L);

        if (isCancelled.get()) return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);

        // JCLAW-291: cooperative-cancel checkpoint between the tool round
        // and the LLM continuation. Subagent-driven streaming runs aren't
        // the common case but the checkpoint is cheap and keeps the two
        // round-loops symmetric.
        AgentRunner.checkSubagentCancel(conversation);

        // JCLAW-273: yield_to_subagent detected in this round — exit the
        // streaming loop without continuing to the next LLM round and
        // without emitting a final assistant payload. Returning the
        // YIELDED_RESPONSE sentinel lets streamLlmLoop short-circuit its
        // persistence + terminal-callback path; the parent's logical turn
        // resumes later from tools.SpawnSubagentTool#runAsyncAndAnnounce.
        if (yieldRequestedInLastRound(currentMessages, streamingToolResultsAnchor)) {
            EventLogger.info("tool", agent.name, null,
                    "Streaming round %d: yield_to_subagent invoked — suspending parent turn"
                            .formatted(round + 1));
            return AgentRunner.YIELDED_RESPONSE;
        }

        cb.onStatus().accept("Processing results (round %d)...".formatted(round + 1));
        EventLogger.info("llm", agent.name, null,
                "Streaming round %d: continuing LLM call after tool results".formatted(round + 1));

        // Continue with streaming after tool results. JCLAW-108: effective
        // model id honors conversation override, same as the round-1 call.
        var effectiveModelIdForCall = ModelResolver.effectiveModelId(agent, conversation);
        // Recompute max_tokens against the grown message list so the clamp
        // tightens as the tool loop accumulates history.
        var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, provider, currentMessages, tools);
        var accumulator = provider.chatStreamAccumulate(
                effectiveModelIdForCall, currentMessages, tools, cb.onToken(), cb.onReasoning(),
                maxTokens, thinkingMode, channelType);

        try {
            if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, null, cb))
                return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
        }

        if (isCancelled.get()) return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);

        // Fold this round's usage into the turn-level cumulative (JCLAW-76).
        // Runs regardless of whether the round resolves to more tool calls,
        // truncation, synthesis, or empty-retry — every round contributes.
        turnUsage.addRound(accumulator);

        // Truncation guard: if the model hit max_tokens mid-tool-call, the tool arguments
        // will be an incomplete JSON fragment. Passing that to ToolRegistry.execute causes
        // a Gson EOFException and the user sees a cryptic "End of input" error. Instead,
        // surface a clear message so the LLM can retry with a more concise approach.
        if (TruncationDiagnostics.isTruncationFinish(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
            EventLogger.warn("tool", agent.name, null,
                    "Response truncated (finish_reason=%s) with pending tool calls in round %d — skipping execution of incomplete tool arguments"
                            .formatted(accumulator.finishReason, round + 1));
            var truncMsg = accumulator.content != null && !accumulator.content.isEmpty()
                    ? accumulator.content + "\n\n*[Response was truncated before the next tool call could complete. Try breaking the task into smaller steps.]*"
                    : "I tried to use a tool but the response exceeded the token limit before the tool arguments finished. Try breaking the task into smaller steps — for example, write large files in multiple append operations instead of one big write.";
            cb.onToken().accept(accumulator.content != null && !accumulator.content.isEmpty()
                    ? "\n\n*[Response was truncated before the next tool call could complete.]*"
                    : truncMsg);
            return truncMsg;
        }

        // Recursively handle if more tool calls. JCLAW-104: pass the SAME
        // collectedImages through so images from this round accumulate into
        // the deeper round's final buildImagePrefix call. channelType threads
        // through too so buildDownloadSuffix can stay channel-aware.
        if (!accumulator.toolCalls.isEmpty()) {
            return handleToolCallsStreaming(agent, conversation, conversationId, currentMessages, tools,
                    accumulator.toolCalls, accumulator.content, provider, cb, thinkingMode,
                    round + 1, isCancelled, trace, turnUsage, collectedImages, channelType);
        }

        // Some models (especially smaller/distilled ones) occasionally return zero tokens
        // on the continue-after-tool-results turn, treating the tool output as self-explanatory
        // even when the user clearly wants synthesis. Retry once with an explicit synthesis
        // nudge before giving up and emitting a diagnostic fallback.
        if (accumulator.content == null || accumulator.content.isBlank()) {
            EventLogger.warn("llm", agent.name, null,
                    "Empty continuation after tool calls in round %d — retrying with synthesis nudge"
                            .formatted(round + 1));
            cb.onStatus().accept("Synthesizing response (retry)...");

            var retryMessages = new ArrayList<>(currentMessages);
            retryMessages.add(ChatMessage.user(
                    "Synthesize the final response for me now using the tool results above. "
                            + "Do not call any more tools. Write the full answer as markdown."));

            var retryMaxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, provider, retryMessages, tools);
            var retry = provider.chatStreamAccumulate(
                    effectiveModelIdForCall, retryMessages, tools, cb.onToken(), cb.onReasoning(),
                    retryMaxTokens, thinkingMode, channelType);
            try {
                if (!CancellationManager.awaitAccumulatorOrCancel(retry, isCancelled, agent, null, cb))
                    return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
            }

            // Retry round is a real LLM call — its usage counts too (JCLAW-76).
            turnUsage.addRound(retry);

            if (retry.content != null && !retry.content.isBlank()) {
                return MessageDeduplicator.buildImagePrefix(collectedImages, retry.content)
                        + retry.content
                        + MessageDeduplicator.buildDownloadSuffix(collectedImages, retry.content, channelType);
            }

            // Retry also empty — emit a labeled diagnostic so the user knows why.
            EventLogger.warn("llm", agent.name, null,
                    "Retry also returned empty content — emitting diagnostic fallback");
            // No LLM content to dedupe against — prepend every collected image unchanged.
            var fallbackPrefix = collectedImages.isEmpty() ? ""
                    : String.join("\n\n", collectedImages) + "\n\n";
            var fallbackSuffix = MessageDeduplicator.buildDownloadSuffix(collectedImages, "", channelType);
            var fallback = fallbackPrefix
                    + "*[The model returned no synthesis after tool calls. Tool results are in the conversation history above — try rephrasing your request or switching to a larger model.]*"
                    + fallbackSuffix;
            cb.onToken().accept(fallback);
            return fallback;
        }

        return MessageDeduplicator.buildImagePrefix(collectedImages, accumulator.content)
                + accumulator.content
                + MessageDeduplicator.buildDownloadSuffix(collectedImages, accumulator.content, channelType);
    }
}
