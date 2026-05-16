package agents;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import llm.LlmProvider;
import models.Agent;
import services.EventLogger;

/**
 * Client-disconnect cancellation primitives for the streaming agent loop.
 * Extracted from {@link AgentRunner} as part of JCLAW-299; the four
 * operations here are the checkpoint-detection plus early-exit-fallback
 * contract that the rest of the loop composes around.
 *
 * <p>Cancellation in JClaw flows from one source — the {@code AtomicBoolean
 * isCancelled} flag held by the SSE / Telegram transport — and is observed
 * at four moments:
 * <ol>
 *   <li>Between LLM rounds via {@link #checkCancelled} — the most common
 *       checkpoint, called at safe boundaries in the streaming loop.</li>
 *   <li>During accumulator polling via {@link #awaitAccumulatorOrCancel}
 *       — composed atop {@code checkCancelled} on a 5 s polling window so
 *       a long-running streamed response can be torn down without hammering
 *       the flag.</li>
 *   <li>On detection, the cancellation is logged via {@code EventLogger}
 *       with {@link #STREAM_CANCELLED_MSG} so operators can correlate the
 *       cancel with the round in which it landed.</li>
 *   <li>When synthesis was cancelled before the model produced any content,
 *       {@link #cancelledReturn} chooses between handing back prior content
 *       (if any) and emitting a labeled fallback so the client UI doesn't
 *       render an empty assistant turn.</li>
 * </ol>
 *
 * <p>The class is intentionally state-less — every method is a pure
 * function of its arguments, with side effects only via {@link EventLogger}
 * and the caller-supplied callbacks. The subagent-side cancellation path
 * (cooperative {@link services.SubagentRegistry} flag plus
 * {@link AgentRunner.RunCancelledException}) is a separate concern that
 * stays in {@code AgentRunner} alongside the subagent-scope code (JCLAW-291)
 * — it surfaces as a thrown exception rather than a boolean checkpoint.
 */
public final class CancellationManager {

    static final String STREAM_CANCELLED_MSG = "Stream cancelled by client disconnect";

    private CancellationManager() {}

    /**
     * Check whether the streaming client has disconnected. Logs the
     * cancellation when detected, fires {@code onCancel} so transports
     * can quiesce side-channel state (e.g. the Telegram typing heartbeat
     * — JCLAW-181 follow-up), and returns {@code true} so the caller
     * can short-circuit. {@code onCancel} itself is idempotent on every
     * wired implementation, so multiple checkpoints along an early-return
     * path are safe.
     */
    static boolean checkCancelled(AtomicBoolean isCancelled, Agent agent, String channelType,
                                   AgentRunner.StreamingCallbacks cb) {
        if (isCancelled.get()) {
            EventLogger.info("llm", agent.name, channelType, STREAM_CANCELLED_MSG);
            if (cb != null && cb.onCancel() != null) cb.onCancel().run();
            return true;
        }
        return false;
    }

    /**
     * Poll an accumulator for completion, checking for cancellation every
     * 5 s. Returns {@code true} if the accumulator completed, {@code false}
     * if cancelled (in which case the cancellation has already been
     * logged via {@link #checkCancelled}).
     */
    static boolean awaitAccumulatorOrCancel(LlmProvider.StreamAccumulator accumulator,
                                             AtomicBoolean isCancelled,
                                             Agent agent, String channelType,
                                             AgentRunner.StreamingCallbacks cb)
            throws InterruptedException {
        while (!accumulator.awaitCompletion(5000)) {
            if (checkCancelled(isCancelled, agent, channelType, cb)) return false;
        }
        return true;
    }

    /**
     * Resolve the return value for a cancellation early-exit inside
     * {@code handleToolCallsStreaming}. When {@code priorContent} is
     * non-blank (the round-1 model emitted a preamble before the tool
     * calls), preserve it untouched — the user already saw it streamed.
     *
     * <p>When {@code priorContent} is empty, the model emitted only
     * tool_calls on round 1 and the synthesis was about to run. Returning
     * {@code ""} here was the silent-data-loss bug behind "(empty
     * response)" reports: a heartbeat write fail, token write fail, or
     * 600s safety timeout flips {@code cancelled}, the tool result is
     * already on screen, and the synthesis disappears with no diagnostic.
     * Emit a labeled fallback instead so the persisted assistant row is
     * non-empty and the user sees what happened.
     *
     * <p>The fallback is also pushed via
     * {@link AgentRunner.StreamingCallbacks#onToken} for symmetry with
     * {@code handleToolCallsStreaming}'s empty-retry diagnostic. If the
     * SSE channel is already dead the underlying {@code sse.send} call
     * is a no-op (SseStream catches the write exception and auto-closes);
     * on Telegram the sink's {@code update} is similarly tolerant.
     */
    static String cancelledReturn(String priorContent, List<String> collectedImages,
                                   String channelType, AgentRunner.StreamingCallbacks cb,
                                   Agent agent, int round) {
        if (priorContent != null && !priorContent.isBlank()) {
            return priorContent;
        }
        EventLogger.warn("llm", agent != null ? agent.name : null, null,
                "Cancelled in round %d before any synthesis content — emitting labeled fallback"
                        .formatted(round + 1));
        var images = collectedImages != null ? collectedImages : List.<String>of();
        var fallbackPrefix = images.isEmpty() ? "" : String.join("\n\n", images) + "\n\n";
        var fallbackSuffix = MessageDeduplicator.buildDownloadSuffix(images, "", channelType);
        var fallback = fallbackPrefix
                + "*[Synthesis was cancelled before the model produced any output. Tool results are in the conversation history above — try resending the request.]*"
                + fallbackSuffix;
        cb.onToken().accept(fallback);
        return fallback;
    }
}
