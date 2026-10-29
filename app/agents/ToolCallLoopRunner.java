package agents;

import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import tools.SubagentYieldTool;
import utils.ChannelOriginTrust;
import utils.ErrorRendering;
import utils.LatencyTrace;
import utils.LlmErrorTemplates;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Per-LLM-round tool-call orchestrators. Extracted from
 * {@link AgentRunner} as part of JCLAW-299. Two sibling entry points:
 * {@link #callWithToolLoop} drives the synchronous (non-streaming)
 * path; {@link #handleToolCallsStreaming} drives the streaming path's
 * tool-call recursion. Both detect a JCLAW-273 yield in the round's
 * {@code subagent_yield} results ({@link #yieldedRunIds},
 * {@link #yieldRequestedInLastRound}).
 *
 * <h2>Why two methods, not one</h2>
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
 * <h2>What the loop owns vs delegates</h2>
 * <ul>
 *   <li><b>Owned</b>: round dispatch, JCLAW-291 cooperative-cancel
 *   checkpoints, JCLAW-165 audio-format retry in the sync path, the
 *   empty-continuation synthesis-nudge retry, the once-per-turn resend of a
 *   tool call cut off at the output limit,
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
 * <h2>The yield handshake (JCLAW-273)</h2>
 * Both loops scan the just-appended round's results from {@code subagent_yield}
 * itself for {@link tools.SubagentYieldTool#YIELD_SENTINEL_PREFIX}. When
 * the marker appears, the loop returns {@link AgentRunner#YIELDED_RESPONSE}
 * so the caller skips the final-assistant-message persist; the
 * parent's logical turn resumes later via
 * {@code SubagentSpawnTool.runAsyncAndAnnounce} once the child
 * terminates. The sync loop also carries out the run ids the sentinels
 * name, on {@link LoopOutcome#yieldedRunIds}, so a route can answer which
 * runs the turn waits on (JF-166).
 */
public final class ToolCallLoopRunner {

    private ToolCallLoopRunner() {}

    /** Passthrough-outcome tag for the audio/image structured logs (AudioRetryStrategy / ImageRetryStrategy). */
    private static final String OUTCOME_ERROR = "error";
    private static final String NO_MODEL_CONFIGURED = "agent has no model configured";
    private static final String PROVIDER_ERROR_REPLY =
            "I'm sorry, I encountered an error communicating with the AI provider. Please try again.";
    /** The reply of a turn that used every tool round when the closing call failed or answered nothing. */
    private static final String ROUND_CAP_REPLY =
            "I reached the maximum number of tool execution rounds. Please try a simpler request.";

    /**
     * The tool names this turn actually put in front of the model, for the dispatch
     * guard in {@link ToolRegistry#executeRich(String, String, Agent, Set)}
     * (JCLAW-883).
     *
     * <p>Derived from the same {@code tools} list the request carried, so "what the
     * model was told it could call" and "what it is allowed to call" cannot drift.
     * Computed here rather than in the executor because the tool-dispatch threads
     * hold no JPA transaction, and recomputing per-agent config there throws.
     */
    private static Set<String> offeredToolNames(@Nullable List<ToolDef> tools) {
        if (tools == null) return Set.of();
        return tools.stream().map(t -> t.function().name()).collect(Collectors.toSet());
    }

    /**
     * JCLAW-291: result wrapper for {@link #callWithToolLoop}. Caller
     * plumbs {@code truncated} into the persist site and (for subagents)
     * into the {@link AgentRunner.RunResult} so the announce card can
     * surface a truncation marker without the chat UI having to
     * introspect raw provider responses.
     *
     * @param content   the model's final reply text
     * @param truncated true when the final non-tool-call assistant turn
     *                  came back with {@code finish_reason = length /
     *                  max_tokens}
     * @param yieldedRunIds the runs a {@code subagent_yield} suspended the sync
     *                  loop on, in call order; empty otherwise, and always on the streaming loop
     */
    public record LoopOutcome(String content, boolean truncated, List<Long> yieldedRunIds) {
        public LoopOutcome(String content) { this(content, false); }

        public LoopOutcome(String content, boolean truncated) { this(content, truncated, List.of()); }
    }

    /**
     * Mutable per-turn audio-retry bookkeeping for {@link #callWithToolLoop}.
     * Tracks the single-shot JCLAW-165 retry and whether the request was
     * already shipped as text (so the AUDIO_PASSTHROUGH_OUTCOME log carries
     * the right {@code transcript_awaited} flag).
     */
    private static final class AudioRetryState {
        boolean retryAttempted;
        boolean transcriptAwaited;

        AudioRetryState(boolean transcriptAwaited) {
            this.transcriptAwaited = transcriptAwaited;
        }
    }

    /**
     * Mutable per-turn vision-retry bookkeeping (JCLAW-216), mirroring {@link AudioRetryState}.
     * Tracks the single-shot image-format-rejection retry for {@link #callWithToolLoop}.
     */
    private static final class VisionRetryState {
        boolean retryAttempted;
    }

    @SuppressWarnings("java:S107") // internal tool-loop dispatcher
    static LoopOutcome callWithToolLoop(Agent agent, Conversation conversation,
                                         @Nullable Long conversationId,
                                         List<ChatMessage> messages, List<ToolDef> tools,
                                         LlmProvider primary, LlmProvider.@Nullable Fallback fallback,
                                         List<VisionAudioAssembler.AudioBearer> audioBearers,
                                         List<VisionAudioAssembler.ImageBearer> imageBearers,
                                         AgentExecutionSink sink, @Nullable Long taskRunId) {
        return callWithToolLoop(agent, conversation, conversationId, messages, tools, primary, fallback,
                audioBearers, imageBearers, sink, taskRunId, AgentRunner.maxToolRounds());
    }

    /** {@link #callWithToolLoop} with the round cap passed in, so a test need not change the shared config key. */
    @SuppressWarnings({"java:S107", "java:S127"}) // S107: internal tool-loop dispatcher; S127: round-- in body is the single-use audio-format (JCLAW-165) / image-format (JCLAW-216) retry and the single cut-off resend
    static LoopOutcome callWithToolLoop(Agent agent, Conversation conversation,
                                         @Nullable Long conversationId,
                                         List<ChatMessage> messages, List<ToolDef> tools,
                                         LlmProvider primary, LlmProvider.@Nullable Fallback fallback,
                                         List<VisionAudioAssembler.AudioBearer> audioBearers,
                                         List<VisionAudioAssembler.ImageBearer> imageBearers,
                                         AgentExecutionSink sink, @Nullable Long taskRunId, int maxRounds) {
        // Sibling helpers accept a null conversation, but this loop dereferences
        // conversation.channelType for the provider call — fail here rather than
        // with an opaque NPE deeper in the stack.
        Objects.requireNonNull(conversation, "conversation");
        var currentMessages = new ArrayList<>(messages);
        var thinkingMode = ModelResolver.resolveThinkingMode(agent, conversation, primary);
        var effectiveModelId = Objects.requireNonNull(
                ModelResolver.effectiveModelId(agent, conversation), NO_MODEL_CONFIGURED);
        var modelInfoForOutcome = ModelResolver.resolveModelInfo(agent, conversation, primary).orElse(null);
        var supportsAudioInitially = modelInfoForOutcome != null && modelInfoForOutcome.supportsAudio();
        var audioState = new AudioRetryState(!supportsAudioInitially && !audioBearers.isEmpty());
        var visionState = new VisionRetryState(); // JCLAW-216: single-shot image-format-rejection retry
        var overflow = new ContextWindowManager.OverflowBudget();
        boolean cutOffResent = false;
        String note = null;

        for (int round = 0; round < maxRounds; round++) {
            // JCLAW-291: cooperative-cancel checkpoint at the top of each
            // LLM round. Between-rounds is the natural safe point — we
            // never check inside a streaming chunk handler (too chatty)
            // or mid-tool-call (would orphan partial side effects).
            AgentRunner.checkSubagentCancel(conversation);
            AgentRunner.checkTaskRunCancel(taskRunId);  // JCLAW-414: task-fire cancel
            var attempt = invokeOneRound(agent, conversation, primary, fallback, effectiveModelId, thinkingMode,
                    currentMessages, tools, audioBearers, imageBearers, audioState, visionState, supportsAudioInitially,
                    overflow, note);
            if (attempt.retry()) {
                currentMessages = attempt.retryMessages();
                round--;  // re-issue this round with the rewritten messages (gated by audio/vision retryAttempted)
                continue;
            }
            note = null;
            if (attempt.terminal() != null) return attempt.terminal();
            if (!cutOffResent && isCutOffToolCall(attempt.okResponse())) {
                cutOffResent = true;
                logCutOffResend(agent, conversation.channelType,
                        attempt.okResponse().choices().getFirst().finishReason(), round);
                note = CUT_OFF_NUDGE;
                round--;  // the cut-off call ran no tools, so sending again does not use a tool round
                continue;
            }

            logRoundZeroPassthroughOutcomes(round, agent, conversation, primary,
                    audioBearers, imageBearers, audioState, visionState);

            var roundOutcome = handleSyncRoundResponse(attempt.okResponse(), agent, conversation, conversationId, primary,
                    fallback, currentMessages, tools, sink, round, taskRunId, overflow);
            if (roundOutcome != null) return roundOutcome;
        }

        return closeAtRoundCap(agent, conversation, primary, fallback, currentMessages, overflow, maxRounds);
    }

    /**
     * The reply of a sync turn that used every tool round: the model's closing answer from one more
     * call offered no tools, else {@link #ROUND_CAP_REPLY}.
     */
    private static LoopOutcome closeAtRoundCap(Agent agent, Conversation conversation, LlmProvider primary,
                                               LlmProvider.@Nullable Fallback fallback,
                                               List<ChatMessage> currentMessages,
                                               ContextWindowManager.OverflowBudget overflow, int maxRounds) {
        logRoundCap(agent, conversation.channelType, maxRounds);
        var view = sendView(currentMessages, agent, conversation, primary, null, overflow);
        var closing = retrySyncSynthesis(agent, conversation, primary, fallback, view, null, CLOSING_NUDGE);
        return closing != null
                ? new LoopOutcome(closing.content() + ROUND_CAP_NOTE, closing.truncated())
                : new LoopOutcome(ROUND_CAP_REPLY);
    }

    /** True when the round's response stopped at the output limit while writing a tool call. */
    private static boolean isCutOffToolCall(ChatResponse response) {
        var choice = response.choices().getFirst();
        var calls = choice.message().toolCalls();
        return TruncationDiagnostics.isTruncationFinish(choice.finishReason()) && calls != null && !calls.isEmpty();
    }

    /** True when the streamed response stopped at the output limit while writing a tool call. */
    static boolean isCutOffToolCall(LlmProvider.StreamAccumulator acc) {
        return TruncationDiagnostics.isTruncationFinish(acc.finishReason()) && !acc.toolCalls().isEmpty();
    }

    /**
     * Logs the turn's one resend of a cut-off call. The call itself is never added to the history: its
     * arguments are an incomplete JSON fragment, which a provider may refuse.
     */
    private static void logCutOffResend(Agent agent, @Nullable String channel, @Nullable String finishReason,
                                        int round) {
        EventLogger.warn("llm", agent.name, channel,
                "Response stopped at the output limit (finish_reason=%s) while writing a tool call in round %d — not running it; asking the model to split it"
                        .formatted(finishReason, round + 1));
    }

    /** {@code messages} followed by {@code note} as a user message, in a copy; {@code messages} itself when there is no note. */
    private static List<ChatMessage> withNote(List<ChatMessage> messages, @Nullable String note) {
        if (note == null) return messages;
        var out = new ArrayList<>(messages);
        out.add(ChatMessage.user(note));
        return out;
    }

    private static void logRoundCap(Agent agent, @Nullable String channel, int maxRounds) {
        EventLogger.warn("llm", agent.name, channel,
                "Reached the tool-round limit (%d) — asking the model for a closing answer with no tools offered"
                        .formatted(maxRounds));
    }

    /**
     * What a round sends: tool results pruned and compressed (JCLAW-465), then trimmed to the turn's
     * overflow budget once a refusal set one. The caller's list is left as it was, keeping the
     * originals for retries, yield detection and the next round's appends.
     */
    private static List<ChatMessage> sendView(List<ChatMessage> messages, Agent agent, Conversation conversation,
                                              LlmProvider provider, @Nullable List<ToolDef> tools,
                                              ContextWindowManager.OverflowBudget overflow) {
        return overflow.apply(CompressionPipeline.compress(
                ToolResultPruner.prune(messages, agent, conversation), agent, conversation),
                agent, conversation, provider, tools);
    }

    /**
     * Fire the audio / image passthrough-outcome structured logs (JCLAW-165 / JCLAW-216) on the
     * first round, so the field-data matrix covers accepted-vs-downgraded per provider. No-op past
     * round 0 or when the turn carried no media. Extracted to keep {@link #callWithToolLoop} under
     * the cognitive-complexity bound (Sonar S3776).
     */
    @SuppressWarnings("java:S107") // mirrors the loop's per-round media-retry state
    private static void logRoundZeroPassthroughOutcomes(int round, Agent agent, Conversation conversation,
            LlmProvider primary, List<VisionAudioAssembler.AudioBearer> audioBearers,
            List<VisionAudioAssembler.ImageBearer> imageBearers,
            AudioRetryState audioState, VisionRetryState visionState) {
        if (round != 0) return;
        if (!audioBearers.isEmpty()) {
            AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary,
                    audioState.retryAttempted ? "downgraded" : "accepted",
                    null, audioState.transcriptAwaited);
        }
        if (!imageBearers.isEmpty()) {
            ImageRetryStrategy.logImagePassthroughOutcome(agent, conversation, primary,
                    visionState.retryAttempted ? "downgraded" : "accepted", null);
        }
    }

    /**
     * Outcome of a single LLM-round attempt. Exactly one of {@code response},
     * {@code terminal}, or {@code retry} is meaningful:
     * <ul>
     *   <li>{@code response != null} — round completed; caller processes the response.</li>
     *   <li>{@code terminal != null} — terminal failure (or empty-choices guard); caller returns it.</li>
     *   <li>{@code retry == true} — JCLAW-165 audio-format retry; caller rewrites messages and re-issues the round.</li>
     * </ul>
     */
    private record RoundAttempt(@Nullable ChatResponse response, @Nullable LoopOutcome terminal,
                                 boolean retry,
                                 @Nullable ArrayList<ChatMessage> rewrittenMessages) {
        static RoundAttempt ok(ChatResponse r) { return new RoundAttempt(r, null, false, null); }
        static RoundAttempt terminal(LoopOutcome o) { return new RoundAttempt(null, o, false, null); }
        static RoundAttempt retry(ArrayList<ChatMessage> rewritten) { return new RoundAttempt(null, null, true, rewritten); }

        /** The response of an attempt that is neither a retry nor terminal — the only state that has one. */
        ChatResponse okResponse() { return Objects.requireNonNull(response); }

        /** The rewritten history of a retry attempt — the only state that carries one. */
        ArrayList<ChatMessage> retryMessages() { return Objects.requireNonNull(rewrittenMessages); }
    }

    /**
     * Issue one LLM round (with optional failover to a secondary provider), translating
     * exceptions into either a single-shot audio-format retry or a terminal
     * {@link LoopOutcome}. Also enforces the empty-choices guard. A {@code note} is sent after
     * the round's view and never enters {@code currentMessages}.
     */
    @SuppressWarnings("java:S107") // Round invocation surface mirrors the loop's per-round state
    private static RoundAttempt invokeOneRound(Agent agent, Conversation conversation, LlmProvider primary,
                                                LlmProvider.@Nullable Fallback fallback, String effectiveModelId,
                                                @Nullable String thinkingMode,
                                                ArrayList<ChatMessage> currentMessages, List<ToolDef> tools,
                                                List<VisionAudioAssembler.AudioBearer> audioBearers,
                                                List<VisionAudioAssembler.ImageBearer> imageBearers,
                                                AudioRetryState audioState, VisionRetryState visionState,
                                                boolean supportsAudioInitially,
                                                ContextWindowManager.OverflowBudget overflow, @Nullable String note) {
        var sendMessages = sendView(currentMessages, agent, conversation, primary, tools, overflow);
        ChatResponse response;
        try {
            response = sendRound(agent, conversation, primary, fallback, effectiveModelId, thinkingMode,
                    sendMessages, tools, overflow, note);
        } catch (Exception e) {
            var retryOutcome = handleLlmCallException(e, agent, conversation, primary, audioBearers, imageBearers,
                    audioState, visionState, supportsAudioInitially, currentMessages);
            return retryOutcome.retry()
                    ? RoundAttempt.retry(retryOutcome.retryMessages())
                    : RoundAttempt.terminal(retryOutcome.terminalOutcome());
        }
        if (response.choices() == null || response.choices().isEmpty()) {
            return RoundAttempt.terminal(new LoopOutcome("No response received from the AI provider."));
        }
        return RoundAttempt.ok(response);
    }

    /**
     * One round's call on {@code view} followed by {@code note}, failover included. A context-length
     * refusal that still surfaces after the failover is resent once per turn with {@code view} trimmed to
     * the turn's overflow budget; a second refusal propagates.
     */
    @SuppressWarnings("java:S107") // the round's call surface plus the turn's overflow budget
    private static ChatResponse sendRound(Agent agent, Conversation conversation, LlmProvider primary,
                                          LlmProvider.@Nullable Fallback fallback, String modelId,
                                          @Nullable String thinkingMode, List<ChatMessage> view,
                                          List<ToolDef> tools, ContextWindowManager.OverflowBudget overflow,
                                          @Nullable String note) {
        var sendMessages = withNote(view, note);
        // Recompute per-round so the clamp tracks the growing history.
        var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, sendMessages, tools);
        try {
            return LlmProvider.chatStreamedWithFailover(primary, fallback, modelId, sendMessages, tools,
                    maxTokens, thinkingMode, conversation.channelType);
        } catch (LlmProvider.LlmException e) {
            if (ContextWindowManager.contextWindowRefusal(e) == null) throw e;
            // Trimmed without the note, which the trim would keep as the turn's question in place of the real one.
            var trimmed = overflow.trimForRetry(view, agent, conversation, primary, tools);
            if (trimmed == null) throw e;
            var resend = withNote(trimmed, note);
            logOverflowRetry(agent, conversation.channelType, sendMessages, resend);
            var retryMaxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, resend, tools);
            return LlmProvider.chatStreamedWithFailover(primary, fallback, modelId, resend, tools,
                    retryMaxTokens, thinkingMode, conversation.channelType);
        }
    }

    private static void logOverflowRetry(Agent agent, @Nullable String channel, List<ChatMessage> refused,
                                         List<ChatMessage> trimmed) {
        EventLogger.warn("llm", agent.name, channel,
                "The provider refused the request as longer than the context window; resending it once with %d of %d messages"
                        .formatted(trimmed.size(), refused.size()));
    }

    /**
     * The reply that ends a turn whose call failed. A context-window refusal shows the operator its
     * template in web chat; a channel reader keeps the generic reply, since the template names the provider and
     * the model (see {@link utils.ChannelErrorTemplates}), and so does voice, which reads its markdown aloud.
     */
    private static String providerErrorReply(Exception e, Conversation conversation) {
        var refusal = ContextWindowManager.contextWindowRefusal(e);
        var channel = conversation.channelType;
        if (refusal == null || (channel != null && !ChannelOriginTrust.WEB.equals(channel))) {
            return PROVIDER_ERROR_REPLY;
        }
        return ErrorRendering.RICH.render(LlmErrorTemplates.forFailure(refusal.failure()));
    }

    /**
     * Result of {@link #handleLlmCallException}: either a terminal
     * {@link LoopOutcome} or an instruction to retry the current round
     * with rewritten messages (JCLAW-165 transcript-as-text fallback).
     */
    private record LlmCallExceptionOutcome(@Nullable LoopOutcome outcome, boolean retry,
                                           @Nullable ArrayList<ChatMessage> rewrittenMessages) {
        static LlmCallExceptionOutcome terminal(LoopOutcome outcome) {
            return new LlmCallExceptionOutcome(outcome, false, null);
        }

        static LlmCallExceptionOutcome retry(ArrayList<ChatMessage> rewritten) {
            return new LlmCallExceptionOutcome(null, true, rewritten);
        }

        /** The rewritten history of a retry outcome — the only state that carries one. */
        ArrayList<ChatMessage> retryMessages() { return Objects.requireNonNull(rewrittenMessages); }

        /** The outcome of a non-retry result — the only state that carries one. */
        LoopOutcome terminalOutcome() { return Objects.requireNonNull(outcome); }
    }

    /**
     * Map an exception thrown by the LLM call to either a single-shot
     * JCLAW-165 transcript-as-text retry (rewrites the messages and
     * tells the caller to re-issue the round) or a terminal failure
     * {@link LoopOutcome}.
     */
    @SuppressWarnings("java:S107") // exception-mapper mirrors the loop's per-round audio + vision retry state
    private static LlmCallExceptionOutcome handleLlmCallException(Exception e, Agent agent, Conversation conversation,
                                                                   LlmProvider primary,
                                                                   List<VisionAudioAssembler.AudioBearer> audioBearers,
                                                                   List<VisionAudioAssembler.ImageBearer> imageBearers,
                                                                   AudioRetryState audioState, VisionRetryState visionState,
                                                                   boolean supportsAudioInitially,
                                                                   ArrayList<ChatMessage> currentMessages) {
        // JCLAW-165: provider-side audio-format rejection — fall back
        // to transcript-as-text and retry once. Only kicks in when the
        // request actually carried audio (audioBearers non-empty) and
        // we haven't already retried this turn.
        if (!audioState.retryAttempted && !audioBearers.isEmpty() && AudioRetryStrategy.isAudioFormatRejection(e)) {
            audioState.retryAttempted = true;
            audioState.transcriptAwaited = true;
            if (!AudioRetryStrategy.anyTranscriptAvailable(audioBearers)) {
                // No usable transcript means we'd just send fallback
                // notes — better to fail with a clear error than
                // ship a degraded prompt the user can't tell came
                // from a transcription failure.
                AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, OUTCOME_ERROR,
                        "no_transcript_after_rejection", true);
                EventLogger.warn("llm", agent.name, null,
                        "Audio format rejected and no transcript available — failing turn");
                return LlmCallExceptionOutcome.terminal(new LoopOutcome(
                        "I'm sorry — the audio attachment couldn't be transcribed and the model rejected the audio format directly. Please try again."));
            }
            EventLogger.warn("llm", agent.name, null,
                    "Provider %s rejected audio format; retrying with transcript-as-text"
                            .formatted(primary.config().name()));
            var rewritten = new ArrayList<>(VisionAudioAssembler.applyTranscriptsForCapability(
                    currentMessages, audioBearers, false));
            return LlmCallExceptionOutcome.retry(rewritten);
        }
        // JCLAW-216: provider-side image-format rejection on a vision-capable
        // model — downgrade the image to a caption and retry once. The caption
        // is computed on demand (or a "description unavailable" note), so unlike
        // audio there's no "nothing to fall back to" failure branch.
        if (!visionState.retryAttempted && !imageBearers.isEmpty() && ImageRetryStrategy.isImageFormatRejection(e)) {
            visionState.retryAttempted = true;
            EventLogger.warn("llm", agent.name, null,
                    "Provider %s rejected image; retrying with caption-as-text".formatted(primary.config().name()));
            // Preserve the current audio state so a co-attached voice note that was
            // already downgraded isn't re-inflated to native input_audio by the rebuild.
            var effectiveSupportsAudio = supportsAudioInitially && !audioState.retryAttempted;
            var rewritten = new ArrayList<>(VisionAudioAssembler.applyCaptionsForCapability(
                    currentMessages, imageBearers, false, effectiveSupportsAudio));
            return LlmCallExceptionOutcome.retry(rewritten);
        }
        EventLogger.error("llm", agent.name, null, "LLM call failed: %s".formatted(e.getMessage()));
        if (!audioBearers.isEmpty()) {
            AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, OUTCOME_ERROR,
                    AudioRetryStrategy.shortErrorTag(e), audioState.transcriptAwaited);
        }
        if (!imageBearers.isEmpty()) {
            ImageRetryStrategy.logImagePassthroughOutcome(agent, conversation, primary, OUTCOME_ERROR,
                    AudioRetryStrategy.shortErrorTag(e));
        }
        return LlmCallExceptionOutcome.terminal(new LoopOutcome(providerErrorReply(e, conversation)));
    }

    /**
     * Sync twin of {@link #recoverEmptyReply} (JCLAW-1203): a first reply that hit the output
     * cap with no content spent the budget reasoning, so retry with reasoning off, then on
     * the fallback model, before reporting the cut. A failing retry call is logged and
     * treated as empty rather than aborting the turn. {@code base} is the turn's history within
     * its overflow budget.
     */
    @SuppressWarnings("java:S107")
    private static LoopOutcome recoverEmptySyncReply(Agent agent, Conversation conversation, LlmProvider primary,
                                                     LlmProvider.@Nullable Fallback fallback,
                                                     List<ChatMessage> base,
                                                     @Nullable List<ToolDef> tools, @Nullable String finishReason) {
        EventLogger.warn("llm", agent.name, conversation.channelType,
                "Reply stopped with no content on the first call (finish_reason=%s) — retrying with reasoning off"
                        .formatted(finishReason));
        var answered = retrySyncSynthesis(agent, conversation, primary, fallback, base, tools, ANSWER_NUDGE);
        return answered != null ? answered : new LoopOutcome("*[The model stopped before writing an answer. "
                + "Try again with thinking off or a larger output budget.]*", true);
    }

    /**
     * Sync twin of {@link #retryEmptyContinuation} (JCLAW-1199): a subagent runs on this path, and an
     * empty reply after tool calls would otherwise end its run COMPLETED with no answer. {@code base} is
     * the turn's history within its overflow budget.
     */
    @SuppressWarnings("java:S107")
    private static LoopOutcome recoverEmptySyncContinuation(Agent agent, Conversation conversation, LlmProvider primary,
                                                            LlmProvider.@Nullable Fallback fallback,
                                                            List<ChatMessage> base,
                                                            @Nullable List<ToolDef> tools, int round,
                                                            @Nullable String finishReason) {
        EventLogger.warn("llm", agent.name, conversation.channelType,
                "Empty continuation after tool calls in round %d (finish_reason=%s) — retrying with synthesis nudge, reasoning off"
                        .formatted(round + 1, finishReason));
        var answered = retrySyncSynthesis(agent, conversation, primary, fallback, base, tools, SYNTHESIS_NUDGE);
        return answered != null ? answered : new LoopOutcome("*[The model returned no synthesis after tool calls. "
                + "Tool results are in the conversation history above — try rephrasing your request or switching to a larger model.]*");
    }

    /** The nudge with reasoning off on the primary, then on the fallback; null when every retry came back empty. */
    @SuppressWarnings("java:S107")
    private static @Nullable LoopOutcome retrySyncSynthesis(Agent agent, Conversation conversation, LlmProvider primary,
                                                            LlmProvider.@Nullable Fallback fallback,
                                                            List<ChatMessage> base,
                                                            @Nullable List<ToolDef> tools, String nudge) {
        var retryMessages = new ArrayList<>(base);
        retryMessages.add(ChatMessage.user(nudge));
        var modelId = Objects.requireNonNull(
                ModelResolver.effectiveModelId(agent, conversation), NO_MODEL_CONFIGURED);
        var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, retryMessages, tools);

        var retry = syncRetry(agent, primary, modelId, retryMessages, tools, maxTokens, conversation.channelType);
        if (retry != null) return retry;
        if (fallback != null) {
            EventLogger.warn("llm", agent.name, conversation.channelType,
                    "Synthesis retry also empty — retrying on fallback %s / %s"
                            .formatted(fallback.provider().config().name(), fallback.modelId()));
            var second = syncRetry(agent, fallback.provider(), fallback.modelId(), retryMessages, tools, maxTokens,
                    conversation.channelType);
            if (second != null) return second;
        }
        EventLogger.warn("llm", agent.name, conversation.channelType,
                "Every synthesis retry returned empty content — emitting diagnostic fallback");
        return null;
    }

    /** One reasoning-off retry on {@code provider}; null when it answered nothing or failed. */
    private static @Nullable LoopOutcome syncRetry(Agent agent, LlmProvider provider, String modelId,
                                                   List<ChatMessage> messages, @Nullable List<ToolDef> tools,
                                                   @Nullable Integer maxTokens, @Nullable String channel) {
        try {
            var response = provider.chatStreamed(modelId, messages, tools, maxTokens, null, channel);
            if (response == null || response.choices() == null || response.choices().isEmpty()) return null;
            var choice = response.choices().getFirst();
            var text = MessageHydrator.contentAsString(choice.message().content());
            if (text == null || text.isBlank()) return null;
            return new LoopOutcome(text, TruncationDiagnostics.isTruncationFinish(choice.finishReason()));
        } catch (Exception e) {
            EventLogger.warn("llm", agent.name, channel,
                    "Synthesis retry on %s failed: %s".formatted(provider.config().name(), e.getMessage()));
            return null;
        }
    }

    /**
     * Process a successful per-round {@link ChatResponse}: either return
     * a terminal {@link LoopOutcome} (no tool calls, truncation, or
     * yield) or execute the tool calls and return {@code null} so the
     * caller continues to the next round.
     */
    @SuppressWarnings("java:S107") // round-response dispatcher mirrors the loop's per-round state + the JCLAW-414 task-run id
    private static @Nullable LoopOutcome handleSyncRoundResponse(
                                                       ChatResponse response, Agent agent,
                                                       Conversation conversation,
                                                       @Nullable Long conversationId, LlmProvider primary,
                                                       LlmProvider.@Nullable Fallback fallback,
                                                       ArrayList<ChatMessage> currentMessages,
                                                       @Nullable List<ToolDef> tools,
                                                       AgentExecutionSink sink, int round,
                                                       @Nullable Long taskRunId,
                                                       ContextWindowManager.OverflowBudget overflow) {
        var choice = response.choices().getFirst();
        var assistantMsg = choice.message();
        var assistantToolCalls = assistantMsg.toolCalls();

        // No tool calls — return the content. JCLAW-291: when finish_reason
        // signals truncation on this branch, the model ran out of output
        // budget mid-reply (the prompt-fills-window scenario). Carry the
        // flag up to the persist site so the chat UI can mark the row.
        if (assistantToolCalls == null || assistantToolCalls.isEmpty()) {
            if (TruncationDiagnostics.isTruncationFinish(choice.finishReason())) {
                TruncationDiagnostics.logEmptyToolCallsTruncation("callWithToolLoop", agent, conversation, primary,
                        conversation.channelType, choice.finishReason(), currentMessages, tools);
                var text = MessageHydrator.contentAsString(assistantMsg.content());
                if (text == null || text.isBlank()) {
                    return recoverEmptySyncReply(agent, conversation, primary, fallback,
                            overflow.apply(currentMessages, agent, conversation, primary, tools), tools,
                            choice.finishReason());
                }
                return new LoopOutcome(text, true);
            }
            var text = MessageHydrator.contentAsString(assistantMsg.content());
            if (round > 0 && text.isBlank()) {
                return recoverEmptySyncContinuation(agent, conversation, primary, fallback,
                        overflow.apply(currentMessages, agent, conversation, primary, tools), tools,
                        round, choice.finishReason());
            }
            return new LoopOutcome(text);
        }

        // A second cut-off in the turn (max tokens hit mid-tool-call): its arguments are incomplete, so it is not run.
        if (TruncationDiagnostics.isTruncationFinish(choice.finishReason())) {
            EventLogger.warn("llm", agent.name, null,
                    "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
            var content = assistantMsg.content() != null ? (String) assistantMsg.content()
                    : "I tried to use a tool but my response was too long and got cut off, so the tool call was not run.";
            return new LoopOutcome(content, true);
        }

        // Tool calls — execute (in parallel when multiple) and continue
        currentMessages.add(assistantMsg);
        int toolResultsAnchor = currentMessages.size();
        EventLogger.info("tool", agent.name, null,
                "Round %d: executing %d tool call(s)".formatted(round + 1, assistantToolCalls.size()));

        ParallelToolExecutor.executeToolsParallel(assistantToolCalls, agent, conversationId,
                currentMessages, null, null, null, null, sink, offeredToolNames(tools));

        // JCLAW-291: cooperative-cancel checkpoint between tool calls
        // and the next LLM round. If /subagent kill landed during the
        // tool-call batch, abort here rather than spending another
        // round on the now-stale plan.
        AgentRunner.checkSubagentCancel(conversation);
        AgentRunner.checkTaskRunCancel(taskRunId);  // JCLAW-414: task-fire cancel

        // JCLAW-273: detect a successful subagent_yield call and bail out of the
        // tool-call loop without continuing to the next LLM round. The runner
        // returns YIELDED_RESPONSE so the caller skips its final-assistant-message
        // persist; the parent's logical turn resumes later from
        // tools.SubagentSpawnTool#runAsyncAndAnnounce once the child terminates.
        // JCLAW-497: in a task fire subagent_yield block-awaits and returns the
        // child result inline (no sentinel), so this never triggers there.
        var yieldedRunIds = yieldedRunIds(currentMessages, toolResultsAnchor);
        if (!yieldedRunIds.isEmpty()) {
            EventLogger.info("tool", agent.name, null,
                    "Round %d: subagent_yield invoked — suspending parent turn".formatted(round + 1));
            return new LoopOutcome(AgentRunner.YIELDED_RESPONSE, false, yieldedRunIds);
        }
        return null;
    }

    /**
     * JCLAW-273: whether this round's results (those at index {@code >= fromIndex})
     * include the {@link tools.SubagentYieldTool#YIELD_SENTINEL_PREFIX} marker that
     * {@code subagent_yield} returns on success, meaning the loop should exit without
     * emitting a final assistant reply. Only a result from {@code subagent_yield}
     * itself counts: a fetched page or a file read can begin with the same text (JF-166).
     *
     * @param currentMessages the per-turn message list, with tool-result
     *                        rows freshly appended
     * @param fromIndex       index into {@code currentMessages} marking the
     *                        start of this round's appended tool-result rows
     * @return true when a {@code subagent_yield} result of this round carries
     *         the yield sentinel prefix
     */
    // Visible (public) for ToolCallLoopRunnerEdgeCasesTest in the default package
    public static boolean yieldRequestedInLastRound(List<ChatMessage> currentMessages, int fromIndex) {
        return !yieldSentinels(currentMessages, fromIndex).isEmpty();
    }

    /**
     * The run each of this round's yield sentinels names, read from its
     * {@link SubagentYieldTool#PARAM_RUN_ID}, in call order; empty when the round did not yield.
     */
    public static List<Long> yieldedRunIds(List<ChatMessage> currentMessages, int fromIndex) {
        return yieldSentinels(currentMessages, fromIndex).stream()
                .map(s -> JsonParser.parseString(s).getAsJsonObject().get(SubagentYieldTool.PARAM_RUN_ID).getAsLong())
                .toList();
    }

    private static List<String> yieldSentinels(List<ChatMessage> currentMessages, int fromIndex) {
        var sentinels = new ArrayList<String>();
        for (int i = fromIndex; i < currentMessages.size(); i++) {
            var m = currentMessages.get(i);
            if (m != null && MessageRole.TOOL.value.equals(m.role())
                    && SubagentYieldTool.TOOL_NAME.equals(m.toolName())
                    && m.content() instanceof String s
                    && s.startsWith(SubagentYieldTool.YIELD_SENTINEL_PREFIX)) {
                sentinels.add(s);
            }
        }
        return sentinels;
    }

    /**
     * Stable per-turn state for the streaming tool-call recursion
     * ({@link #handleToolCallsStreaming} and its {@link #retryEmptyContinuation}
     * helper). Every field is invariant across the recursion; only the
     * genuinely-varying {@code messages}, {@code toolCalls}, {@code priorContent},
     * and {@code round} stay as method parameters. Mirrors the
     * {@code ParallelToolExecutor.DispatchContext} record pattern, collapsing what
     * was a 17-positional-parameter recursion.
     *
     * <p>{@code collectedImages} is the JCLAW-104 turn-scope image accumulator —
     * the SAME list threads through every recursion level so images captured in an
     * early round still reach the final {@code buildImagePrefix} call. {@code overflow}
     * and {@code cutOffResent} (set once the turn has sent a cut-off tool call again) are
     * turn-scoped the same way, shared with round 1.
     */
    // Visible (public) for ToolCallLoopRunnerStreamingTest in the default package
    public record StreamingTurnContext(Agent agent, Conversation conversation,
                                       @Nullable Long conversationId,
                                       @Nullable List<ToolDef> tools, LlmProvider provider,
                                       LlmProvider.@Nullable Fallback fallback,
                                       AgentRunner.StreamingCallbacks cb, @Nullable String thinkingMode,
                                       AtomicBoolean isCancelled, LatencyTrace trace,
                                       LlmProvider.TurnUsage turnUsage, List<String> collectedImages,
                                       @Nullable String channelType, AgentExecutionSink sink,
                                       ContextWindowManager.OverflowBudget overflow,
                                       AtomicBoolean cutOffResent) {

        /** A context for a turn that has not yet sent a cut-off tool call again. */
        @SuppressWarnings("java:S107") // the record's components less the resend flag
        public StreamingTurnContext(Agent agent, Conversation conversation, @Nullable Long conversationId,
                                    @Nullable List<ToolDef> tools, LlmProvider provider,
                                    LlmProvider.@Nullable Fallback fallback,
                                    AgentRunner.StreamingCallbacks cb, @Nullable String thinkingMode,
                                    AtomicBoolean isCancelled, LatencyTrace trace,
                                    LlmProvider.TurnUsage turnUsage, List<String> collectedImages,
                                    @Nullable String channelType, AgentExecutionSink sink,
                                    ContextWindowManager.OverflowBudget overflow) {
            this(agent, conversation, conversationId, tools, provider, fallback, cb, thinkingMode, isCancelled,
                    trace, turnUsage, collectedImages, channelType, sink, overflow, new AtomicBoolean());
        }

        /** A context for a turn with no overflow budget yet. */
        @SuppressWarnings("java:S107") // the record's components less the budget
        public StreamingTurnContext(Agent agent, Conversation conversation, @Nullable Long conversationId,
                                    @Nullable List<ToolDef> tools, LlmProvider provider,
                                    LlmProvider.@Nullable Fallback fallback,
                                    AgentRunner.StreamingCallbacks cb, @Nullable String thinkingMode,
                                    AtomicBoolean isCancelled, LatencyTrace trace,
                                    LlmProvider.TurnUsage turnUsage, List<String> collectedImages,
                                    @Nullable String channelType, AgentExecutionSink sink) {
            this(agent, conversation, conversationId, tools, provider, fallback, cb, thinkingMode, isCancelled,
                    trace, turnUsage, collectedImages, channelType, sink, new ContextWindowManager.OverflowBudget());
        }
    }

    static LoopOutcome handleToolCallsStreaming(StreamingTurnContext ctx, List<ChatMessage> messages,
                                                List<ToolCall> toolCalls, String priorContent, int round) {
        return handleToolCallsStreaming(ctx, messages, toolCalls, priorContent, round, AgentRunner.maxToolRounds());
    }

    /** {@link #handleToolCallsStreaming} with the round cap passed in, so a test need not change the shared config key. */
    static LoopOutcome handleToolCallsStreaming(StreamingTurnContext ctx, List<ChatMessage> messages,
                                                List<ToolCall> toolCalls, String priorContent, int round, int maxRounds) {
        if (ctx.isCancelled().get()) return cancelled(ctx, priorContent, round);
        if (round >= maxRounds) return closeStreamAtRoundCap(ctx, messages, priorContent, round);
        EventLogger.info("tool", ctx.agent().name, null,
                "Streaming round %d: executing %d tool call(s)".formatted(round + 1, toolCalls.size()));

        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));

        int streamingToolResultsAnchor = currentMessages.size();
        var toolRoundStartNs = System.nanoTime();
        ParallelToolExecutor.executeToolsParallel(toolCalls, ctx.agent(), ctx.conversationId(), currentMessages,
                ctx.cb().onStatus(), ctx.cb().onToolCall(), ctx.collectedImages(), ctx.isCancelled(), ctx.sink(),
                offeredToolNames(ctx.tools()));
        ctx.trace().addToolRound((System.nanoTime() - toolRoundStartNs) / 1_000_000L);

        if (ctx.isCancelled().get()) return cancelled(ctx, priorContent, round);

        // JCLAW-291: cooperative-cancel checkpoint between the tool round
        // and the LLM continuation. Subagent-driven streaming runs aren't
        // the common case but the checkpoint is cheap and keeps the two
        // round-loops symmetric.
        AgentRunner.checkSubagentCancel(ctx.conversation());

        // JCLAW-273: subagent_yield detected in this round — exit the
        // streaming loop without continuing to the next LLM round and
        // without emitting a final assistant payload. Returning the
        // YIELDED_RESPONSE sentinel lets streamLlmLoop short-circuit its
        // persistence + terminal-callback path; the parent's logical turn
        // resumes later from tools.SubagentSpawnTool#runAsyncAndAnnounce.
        if (yieldRequestedInLastRound(currentMessages, streamingToolResultsAnchor)) {
            EventLogger.info("tool", ctx.agent().name, null,
                    "Streaming round %d: subagent_yield invoked — suspending parent turn"
                            .formatted(round + 1));
            return new LoopOutcome(AgentRunner.YIELDED_RESPONSE);
        }

        ctx.cb().onStatus().accept("Processing results (round %d)...".formatted(round + 1));
        EventLogger.info("llm", ctx.agent().name, null,
                "Streaming round %d: continuing LLM call after tool results".formatted(round + 1));

        LlmProvider.StreamAccumulator accumulator;
        try {
            accumulator = streamContinuation(ctx, currentMessages, null);
            if (accumulator != null) accumulator = resendCutOffStream(ctx, currentMessages, accumulator, round);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            accumulator = null;
        }
        if (accumulator == null) return cancelled(ctx, priorContent, round);

        // A second cut-off in the turn: the tool arguments are an incomplete JSON fragment, so the call is
        // not run and the reader is told why.
        if (isCutOffToolCall(accumulator)) {
            return new LoopOutcome(handleTruncatedToolCallAccumulator(accumulator, ctx.agent(), ctx.cb(), round), true);
        }

        // Recursively handle if more tool calls. JCLAW-104: pass the SAME
        // collectedImages through so images from this round accumulate into
        // the deeper round's final buildImagePrefix call. channelType threads
        // through too so buildDownloadSuffix can stay channel-aware.
        if (!accumulator.toolCalls().isEmpty()) {
            return handleToolCallsStreaming(ctx, currentMessages, accumulator.toolCalls(), accumulator.content(),
                    round + 1, maxRounds);
        }

        // Some models (especially smaller/distilled ones) occasionally return zero tokens
        // on the continue-after-tool-results turn, treating the tool output as self-explanatory
        // even when the user clearly wants synthesis. Retry once with an explicit synthesis
        // nudge before giving up and emitting a diagnostic fallback.
        if (accumulator.content() == null || accumulator.content().isBlank()) {
            return retryEmptyContinuation(ctx, round, currentMessages, priorContent, accumulator);
        }

        boolean truncated = TruncationDiagnostics.isTruncationFinish(accumulator.finishReason());
        if (truncated) {
            TruncationDiagnostics.logEmptyToolCallsTruncation("handleToolCallsStreaming", ctx.agent(), ctx.conversation(),
                    ctx.provider(), ctx.channelType(), accumulator.finishReason(), currentMessages, ctx.tools());
        }
        return new LoopOutcome(withImages(ctx, accumulator.content()), truncated);
    }

    private static LoopOutcome cancelled(StreamingTurnContext ctx, String priorContent, int round) {
        return new LoopOutcome(CancellationManager.cancelledReturn(priorContent, ctx.collectedImages(),
                ctx.channelType(), ctx.cb(), ctx.agent(), round));
    }

    /**
     * One streamed call on {@code messages} after tool results or a cut-off, followed by {@code note} when
     * there is one: the round's view, the turn's failover and overflow recovery, and its usage folded into
     * the turn's whatever it resolves to (JCLAW-76). Null when the turn was canceled while waiting. A
     * context-window refusal the recovery cannot clear is thrown, so the turn ends through onError and each
     * transport renders it for its own reader; any other failed call is returned to the caller.
     */
    private static LlmProvider.@Nullable StreamAccumulator streamContinuation(StreamingTurnContext ctx,
                                                                               List<ChatMessage> messages,
                                                                               @Nullable String note)
            throws InterruptedException {
        // JCLAW-108: the effective model id honors the conversation override, same as the round-1 call.
        var modelId = Objects.requireNonNull(
                ModelResolver.effectiveModelId(ctx.agent(), ctx.conversation()), NO_MODEL_CONFIGURED);
        var view = sendView(messages, ctx.agent(), ctx.conversation(), ctx.provider(), ctx.tools(), ctx.overflow());
        var sendMessages = withNote(view, note);
        // Recompute max_tokens against the grown message list so the clamp
        // tightens as the tool loop accumulates history.
        var maxTokens = ContextWindowManager.effectiveMaxTokens(ctx.agent(), ctx.conversation(), ctx.provider(), sendMessages, ctx.tools());
        // JCLAW-1184: secondary-aware like round 1, so a breaker that opened between rounds
        // routes the continuation instead of erroring the turn.
        var accumulator = LlmProvider.chatStreamAccumulateWithFailover(ctx.provider(), ctx.fallback(),
                modelId, sendMessages, ctx.tools(), ctx.cb().onToken(), ctx.cb().onReasoning(),
                maxTokens, ctx.thinkingMode(), ctx.channelType());
        if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, ctx.isCancelled(), ctx.agent(), null, ctx.cb())
                || ctx.isCancelled().get()) {
            return null;
        }
        if (refusedForLength(accumulator) != null) {
            var recovered = recoverOverflowedStream(ctx, view, note, accumulator);
            if (recovered == null) return null;
            var refusal = refusedForLength(recovered);
            if (refusal != null) throw refusal;
            accumulator = recovered;
        }
        UsageMetricsBuilder.addRoundAndReport(ctx.turnUsage(), accumulator, ctx.cb());
        return accumulator;
    }

    /**
     * The turn's one resend of a streamed call that {@link #isCutOffToolCall(LlmProvider.StreamAccumulator)
     * stopped at the output limit while writing a tool call}: {@code messages} followed by
     * {@link #CUT_OFF_NUDGE}, leaving {@code messages} as it was. Returns {@code acc} unchanged when it was
     * not cut off or the turn already resent once, else the resend, or null when the turn was canceled while
     * waiting.
     */
    static LlmProvider.@Nullable StreamAccumulator resendCutOffStream(StreamingTurnContext ctx,
                                                                       List<ChatMessage> messages,
                                                                       LlmProvider.StreamAccumulator acc, int round)
            throws InterruptedException {
        if (!isCutOffToolCall(acc) || !ctx.cutOffResent().compareAndSet(false, true)) return acc;
        logCutOffResend(ctx.agent(), ctx.channelType(), acc.finishReason(), round);
        ctx.cb().onStatus().accept(CUT_OFF_STATUS);
        return streamContinuation(ctx, messages, CUT_OFF_NUDGE);
    }

    /**
     * The context-length refusal that ended {@code acc} before it produced any output, else null.
     * Once output has reached the reader, a resend would repeat it.
     */
    static LlmProvider.@Nullable LlmException refusedForLength(LlmProvider.StreamAccumulator acc) {
        if (!acc.content().isEmpty() || !acc.toolCalls().isEmpty() || acc.reasoningChars() > 0) return null;
        return ContextWindowManager.contextWindowRefusal(acc.error());
    }

    /**
     * Recovery for a stream {@link #refusedForLength}: the same list on the fallback when the
     * primary refused it, since the fallback's window may be larger, then the list trimmed to the
     * turn's overflow budget, once per turn, through the normal path and again on the fallback when
     * the primary refuses that too, as the sync path's failover does. Returns the last attempt, which
     * may be refused still, or null when the turn was canceled while waiting.
     */
    static LlmProvider.@Nullable StreamAccumulator recoverOverflowedStream(StreamingTurnContext ctx,
                                                                         List<ChatMessage> sent,
                                                                         LlmProvider.StreamAccumulator refused)
            throws InterruptedException {
        return recoverOverflowedStream(ctx, sent, null, refused);
    }

    /**
     * {@link #recoverOverflowedStream} for a call that sent {@code view} followed by {@code note}. Only
     * {@code view} is trimmed: the trim would keep the note as the turn's question in place of the real one.
     */
    private static LlmProvider.@Nullable StreamAccumulator recoverOverflowedStream(StreamingTurnContext ctx,
                                                                                  List<ChatMessage> view,
                                                                                  @Nullable String note,
                                                                                  LlmProvider.StreamAccumulator refused)
            throws InterruptedException {
        if (refusedForLength(refused) == null) return refused;
        var sent = withNote(view, note);
        var attempt = onFallbackIfPrimaryRefused(ctx, sent, refused);
        if (attempt == null || refusedForLength(attempt) == null) return attempt;
        var trimmed = ctx.overflow().trimForRetry(view, ctx.agent(), ctx.conversation(), ctx.provider(), ctx.tools());
        if (trimmed == null) return attempt;
        var resend = withNote(trimmed, note);
        logOverflowRetry(ctx.agent(), ctx.channelType(), sent, resend);
        var modelId = Objects.requireNonNull(
                ModelResolver.effectiveModelId(ctx.agent(), ctx.conversation()), NO_MODEL_CONFIGURED);
        var maxTokens = ContextWindowManager.effectiveMaxTokens(ctx.agent(), ctx.conversation(), ctx.provider(),
                resend, ctx.tools());
        var retry = LlmProvider.chatStreamAccumulateWithFailover(ctx.provider(), ctx.fallback(), modelId, resend,
                ctx.tools(), ctx.cb().onToken(), ctx.cb().onReasoning(), maxTokens, ctx.thinkingMode(),
                ctx.channelType());
        if (!CancellationManager.awaitAccumulatorOrCancel(retry, ctx.isCancelled(), ctx.agent(),
                ctx.channelType(), ctx.cb())) return null;
        return onFallbackIfPrimaryRefused(ctx, resend, retry);
    }

    /**
     * {@code acc}, unless the primary refused it for length and the agent's fallback is another model: then
     * {@code messages} on the fallback, or null when the turn was canceled while waiting. The streamed
     * failover covers only an open breaker, so the fallback is asked here.
     */
    private static LlmProvider.@Nullable StreamAccumulator onFallbackIfPrimaryRefused(StreamingTurnContext ctx,
                                                                                     List<ChatMessage> messages,
                                                                                     LlmProvider.StreamAccumulator acc)
            throws InterruptedException {
        var refusal = refusedForLength(acc);
        var failure = refusal != null ? refusal.failure() : null;
        var fallback = ctx.fallback();
        if (failure == null || fallback == null || !fallback.coversFor(ctx.provider())
                || !failure.provider().equals(ctx.provider().config().name())) {
            return acc;
        }
        EventLogger.warn("llm", ctx.agent().name, ctx.channelType(),
                "Failing over from %s to %s / %s: the request is longer than the context window"
                        .formatted(failure.provider(), fallback.provider().config().name(), fallback.modelId()));
        var maxTokens = ContextWindowManager.effectiveMaxTokens(ctx.agent(), ctx.conversation(), ctx.provider(),
                messages, ctx.tools());
        var attempt = fallback.provider().chatStreamAccumulate(fallback.modelId(), messages, ctx.tools(),
                ctx.cb().onToken(), ctx.cb().onReasoning(), maxTokens, ctx.thinkingMode(), ctx.channelType());
        if (!CancellationManager.awaitAccumulatorOrCancel(attempt, ctx.isCancelled(), ctx.agent(),
                ctx.channelType(), ctx.cb())) return null;
        return attempt;
    }

    /**
     * Streaming-side counterpart to the sync truncation guard: report the
     * incomplete tool-call to the user via {@code cb.onToken} and return
     * the LoopOutcome content. Used when {@code finish_reason=length}
     * with non-empty {@code toolCalls} (the model ran out of output
     * budget mid-arguments-JSON) and the turn has already resent once.
     */
    private static String handleTruncatedToolCallAccumulator(LlmProvider.StreamAccumulator accumulator,
                                                              Agent agent,
                                                              AgentRunner.StreamingCallbacks cb,
                                                              int round) {
        EventLogger.warn("tool", agent.name, null,
                "Response truncated (finish_reason=%s) with pending tool calls in round %d — skipping execution of incomplete tool arguments"
                        .formatted(accumulator.finishReason(), round + 1));
        var truncMsg = accumulator.content() != null && !accumulator.content().isEmpty()
                ? accumulator.content() + "\n\n*[Response was truncated before the next tool call could complete. Try breaking the task into smaller steps.]*"
                : "I tried to use a tool but the response exceeded the token limit before the tool arguments finished. Try breaking the task into smaller steps — for example, write large files in multiple append operations instead of one big write.";
        cb.onToken().accept(accumulator.content() != null && !accumulator.content().isEmpty()
                ? "\n\n*[Response was truncated before the next tool call could complete.]*"
                : truncMsg);
        return truncMsg;
    }

    private static final String SYNTHESIS_NUDGE =
            "Synthesize the final response for me now using the tool results above. "
                    + "Do not call any more tools. Write the full answer as markdown.";
    /** Chars of reasoning quoted when the model reasoned but never answered. */
    private static final int REASONING_TAIL_CHARS = 240;

    /** Nudge for a first call that stopped with no content: there are no tool results to point at. */
    private static final String ANSWER_NUDGE = "Write the full answer now as markdown. Keep any reasoning brief.";

    /** Nudge for the closing call of a turn that used every tool round; the call is offered no tools. */
    private static final String CLOSING_NUDGE = "You have used every tool round this turn allows, so no more tools can run. "
            + "Tell me what you did, what is left to do, and anything I need to decide. Write it as markdown.";

    /** Follows a closing answer, so the reader knows the work stopped short. */
    private static final String ROUND_CAP_NOTE = "\n\n*[This turn stopped at the tool-round limit.]*";

    /**
     * Sent once per turn after a response stopped at the output limit mid tool call. Raising the limit is no
     * remedy: {@link ContextWindowManager#effectiveMaxTokens} already asks for the model's own maximum. It goes
     * with the resend only: in the turn's history, the overflow trim and {@link ToolResultPruner} would take it
     * for the turn's question.
     */
    private static final String CUT_OFF_NUDGE = "Your last response stopped at the output limit while it was "
            + "writing a tool call, so the call was not run. Split its content across smaller calls: for example, "
            + "writeFile with the first part, then appendFile for each further part.";

    private static final String CUT_OFF_STATUS = "Asking the model to split a tool call cut off at the output limit...";

    private static final String SYNTHESIS_STATUS = "Synthesizing response (retry)...";

    /** Outcome of the synthesis retries: the content that answered, the canceled-turn reply, or neither. */
    record SynthesisRecovery(@Nullable String content, boolean truncated, @Nullable String cancelled) {
        static final SynthesisRecovery EMPTY = new SynthesisRecovery(null, false, null);
        static SynthesisRecovery answered(String content, @Nullable String finishReason) {
            return new SynthesisRecovery(content, TruncationDiagnostics.isTruncationFinish(finishReason), null);
        }
        static SynthesisRecovery cancelledWith(String reply) { return new SynthesisRecovery(null, false, reply); }
    }

    /**
     * Recover from an empty continuation (JCLAW-1199) through {@link #retrySynthesis}, then
     * emit a labeled diagnostic. The usual shape is a reasoning-capable model spending its
     * whole output budget thinking and stopping mid-thought before any content.
     */
    private static LoopOutcome retryEmptyContinuation(StreamingTurnContext ctx, int round,
                                                      ArrayList<ChatMessage> currentMessages, String priorContent,
                                                      LlmProvider.StreamAccumulator empty) {
        EventLogger.warn("llm", ctx.agent().name, null,
                "Empty continuation after tool calls in round %d (%s) — retrying with synthesis nudge, reasoning off"
                        .formatted(round + 1, describeEmpty(empty)));
        var recovery = retrySynthesis(ctx, withinBudget(ctx, currentMessages), ctx.tools(), SYNTHESIS_NUDGE,
                SYNTHESIS_STATUS, priorContent, round);
        if (recovery.cancelled() != null) return new LoopOutcome(recovery.cancelled());
        if (recovery.content() != null) return new LoopOutcome(withImages(ctx, recovery.content()), recovery.truncated());

        // No LLM content to dedupe against — prepend every collected image unchanged.
        var fallbackPrefix = ctx.collectedImages().isEmpty() ? ""
                : String.join("\n\n", ctx.collectedImages()) + "\n\n";
        var fallbackSuffix = MessageDeduplicator.buildDownloadSuffix(ctx.collectedImages(), "", ctx.channelType());
        var tail = reasoningTail(ctx.turnUsage().reasoningText());
        var fallbackText = fallbackPrefix
                + "*[The model returned no synthesis after tool calls"
                + (tail.isEmpty() ? "" : " — its reasoning ended with: \u201c" + tail + "\u201d")
                + ". Tool results are in the conversation history above — try rephrasing your request or switching to a larger model.]*"
                + fallbackSuffix;
        ctx.cb().onToken().accept(fallbackText);
        return new LoopOutcome(fallbackText);
    }

    /**
     * The reply of a streamed turn that used every tool round: the model's closing answer from one
     * more call offered no tools, else {@link #ROUND_CAP_REPLY}. {@code messages} ends with the last
     * results; the tool calls that reached the cap are neither run nor sent.
     */
    private static LoopOutcome closeStreamAtRoundCap(StreamingTurnContext ctx, List<ChatMessage> messages,
                                                     String priorContent, int round) {
        logRoundCap(ctx.agent(), ctx.channelType(), round);
        var view = sendView(messages, ctx.agent(), ctx.conversation(), ctx.provider(), null, ctx.overflow());
        var recovery = retrySynthesis(ctx, view, null, CLOSING_NUDGE,
                "Writing a closing answer (tool-round limit reached)...", priorContent, round);
        if (recovery.cancelled() != null) return new LoopOutcome(recovery.cancelled());
        if (recovery.content() == null) return new LoopOutcome(ROUND_CAP_REPLY);
        ctx.cb().onToken().accept(ROUND_CAP_NOTE);
        return new LoopOutcome(withImages(ctx, recovery.content() + ROUND_CAP_NOTE), recovery.truncated());
    }

    /**
     * A first call that stopped with no content (JCLAW-1203): the same retries as an empty
     * continuation, with a nudge that does not point at tool results.
     */
    static SynthesisRecovery recoverEmptyReply(StreamingTurnContext ctx, List<ChatMessage> messages,
                                               LlmProvider.StreamAccumulator empty) {
        EventLogger.warn("llm", ctx.agent().name, ctx.channelType(),
                "Reply stopped with no content on the first call (%s) — retrying with reasoning off"
                        .formatted(describeEmpty(empty)));
        return retrySynthesis(ctx, withinBudget(ctx, messages), ctx.tools(), ANSWER_NUDGE, SYNTHESIS_STATUS, "", 0);
    }

    /** {@code messages} trimmed to the turn's overflow budget once a refusal set one, so a retry is not refused again. */
    private static List<ChatMessage> withinBudget(StreamingTurnContext ctx, List<ChatMessage> messages) {
        return ctx.overflow().apply(messages, ctx.agent(), ctx.conversation(), ctx.provider(), ctx.tools());
    }

    /** What the reader sees when every retry of a first call came back empty. */
    static String emptyReplyDiagnostic(StreamingTurnContext ctx) {
        var tail = reasoningTail(ctx.turnUsage().reasoningText());
        return "*[The model stopped before writing an answer"
                + (tail.isEmpty() ? "" : " — its reasoning ended with: \u201c" + tail + "\u201d")
                + ". Try again with thinking off or a larger output budget.]*";
    }

    /**
     * The retries shared by both empty shapes and the round-cap closing call: the nudge with
     * reasoning off on the primary (an always-thinking model gets its lowest rung), then the
     * agent's fallback model when configured. Each attempt counts in the turn's usage.
     */
    private static SynthesisRecovery retrySynthesis(StreamingTurnContext ctx, List<ChatMessage> base,
                                                    @Nullable List<ToolDef> tools, String nudge, String status,
                                                    String priorContent, int round) {
        ctx.cb().onStatus().accept(status);
        var retryMessages = new ArrayList<>(base);
        retryMessages.add(ChatMessage.user(nudge));
        var modelId = Objects.requireNonNull(
                ModelResolver.effectiveModelId(ctx.agent(), ctx.conversation()), NO_MODEL_CONFIGURED);
        var maxTokens = ContextWindowManager.effectiveMaxTokens(ctx.agent(), ctx.conversation(), ctx.provider(), retryMessages, tools);

        var retry = LlmProvider.chatStreamAccumulateWithFailover(ctx.provider(), ctx.fallback(),
                modelId, retryMessages, tools, ctx.cb().onToken(), ctx.cb().onReasoning(),
                maxTokens, null, ctx.channelType());
        var cancelled = awaitOrCancel(ctx, retry, priorContent, round);
        if (cancelled != null) return SynthesisRecovery.cancelledWith(cancelled);
        UsageMetricsBuilder.addRoundAndReport(ctx.turnUsage(), retry, ctx.cb());
        if (hasContent(retry)) return SynthesisRecovery.answered(retry.content(), retry.finishReason());

        var last = retry;
        var fallback = ctx.fallback();
        if (fallback != null) {
            EventLogger.warn("llm", ctx.agent().name, null,
                    "Synthesis retry also empty (%s) — retrying on fallback %s"
                            .formatted(describeEmpty(retry), fallback.provider().config().name() + " / " + fallback.modelId()));
            ctx.cb().onStatus().accept("Synthesizing response (fallback model)...");
            var second = fallback.provider().chatStreamAccumulate(fallback.modelId(), retryMessages, tools,
                    ctx.cb().onToken(), ctx.cb().onReasoning(), maxTokens, null, ctx.channelType());
            cancelled = awaitOrCancel(ctx, second, priorContent, round);
            if (cancelled != null) return SynthesisRecovery.cancelledWith(cancelled);
            UsageMetricsBuilder.addRoundAndReport(ctx.turnUsage(), second, ctx.cb());
            if (hasContent(second)) return SynthesisRecovery.answered(second.content(), second.finishReason());
            last = second;
        }
        EventLogger.warn("llm", ctx.agent().name, null,
                "Every synthesis retry returned empty content (%s) — emitting diagnostic fallback"
                        .formatted(describeEmpty(last)));
        return SynthesisRecovery.EMPTY;
    }

    /** Waits for {@code acc}; returns the canceled-turn reply when the operator stopped the turn, else null. */
    private static @Nullable String awaitOrCancel(StreamingTurnContext ctx, LlmProvider.StreamAccumulator acc,
                                                  String priorContent, int round) {
        try {
            if (CancellationManager.awaitAccumulatorOrCancel(acc, ctx.isCancelled(), ctx.agent(), null, ctx.cb())) return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return CancellationManager.cancelledReturn(priorContent, ctx.collectedImages(), ctx.channelType(), ctx.cb(), ctx.agent(), round);
    }

    private static boolean hasContent(LlmProvider.StreamAccumulator acc) {
        return acc.content() != null && !acc.content().isBlank();
    }

    private static String withImages(StreamingTurnContext ctx, String content) {
        return MessageDeduplicator.buildImagePrefix(ctx.collectedImages(), content)
                + content
                + MessageDeduplicator.buildDownloadSuffix(ctx.collectedImages(), content, ctx.channelType());
    }

    /** What the log needs to tell a reasoning-only stop from a parse failure from a silent model. */
    private static String describeEmpty(LlmProvider.StreamAccumulator acc) {
        var calls = acc.toolCalls();
        return "finish_reason=%s, reasoning_chars=%d, tool_call_fragments=%d".formatted(
                acc.finishReason(), acc.reasoningChars(), calls == null ? 0 : calls.size());
    }

    private static String reasoningTail(@Nullable String reasoning) {
        if (reasoning == null) return "";
        var trimmed = reasoning.strip();
        if (trimmed.length() <= REASONING_TAIL_CHARS) return trimmed;
        return "\u2026" + trimmed.substring(trimmed.length() - REASONING_TAIL_CHARS);
    }
}
