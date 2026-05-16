package agents;

import java.util.List;

import llm.LlmProvider;
import models.Agent;
import models.Conversation;
import models.MessageAttachment;
import services.EventLogger;
import services.Tx;

/**
 * Runtime retry policy for the JCLAW-165 audio-passthrough path.
 * Extracted from {@link AgentRunner} as part of JCLAW-299. The four
 * members here are what the streaming loop and the sync tool loop
 * consult when a provider rejects an audio-bearing request, plus the
 * structured outcome log that grows the provider/format/outcome
 * field-data matrix.
 *
 * <h3>Where the cluster sits in the retry flow</h3>
 * <ol>
 *   <li>Streaming or sync call to an audio-capable provider fails.</li>
 *   <li>{@link #isAudioFormatRejection} decides whether the failure is
 *   a "we don't accept this audio format" rejection vs a generic
 *   client error. False positives are tolerated — the worst case
 *   downgrades to a usable transcript-text retry.</li>
 *   <li>{@link #anyTranscriptAvailable} decides whether to retry with
 *   the cached transcripts or fail with a user-visible error.</li>
 *   <li>{@link #logAudioPassthroughOutcome} records the result
 *   ({@code accepted} / {@code downgraded} / {@code error}) with a
 *   short {@link #shortErrorTag} on the error path.</li>
 * </ol>
 *
 * <p>The build-time piece of JCLAW-165 — assembling the user message
 * as audio bytes or transcript text — lives in
 * {@link VisionAudioAssembler}. This class is purely the
 * runtime-error reaction.
 */
public final class AudioRetryStrategy {

    private AudioRetryStrategy() {}

    /**
     * JCLAW-165 heuristic: detect provider 400-class errors that are
     * actually "we don't accept this audio format" rejections rather
     * than generic client errors. Looks for the keywords providers
     * spell this concept with: OpenAI's {@code unsupported_format},
     * Gemini's {@code invalid_argument}, and the more generic
     * {@code format} + {@code not supported} / {@code unsupported}
     * combos. False positives downgrade to a usable transcript-text
     * retry — worse than a passthrough success but better than a flat
     * error — so the heuristic is intentionally lenient.
     */
    public static boolean isAudioFormatRejection(Throwable t) {
        if (t == null) return false;
        var msg = t.getMessage();
        if (msg == null) return false;
        var lower = msg.toLowerCase();
        if (!lower.contains("http 4")) return false; // 400-class only
        if (lower.contains("unsupported_format")) return true;
        if (lower.contains("invalid_argument") || lower.contains("invalid argument")) return true;
        if (lower.contains("format") && (lower.contains("not supported") || lower.contains("unsupported"))) return true;
        return lower.contains("audio") && lower.contains("format");
    }

    /**
     * Check whether at least one of the audio attachments has a
     * non-empty transcript persisted. Used by the rejection-retry
     * path to decide whether to retry with text or fail with a
     * user-visible error.
     */
    static boolean anyTranscriptAvailable(List<VisionAudioAssembler.AudioBearer> audioBearers) {
        if (audioBearers == null || audioBearers.isEmpty()) return false;
        return Tx.run(() -> {
            for (var b : audioBearers) {
                for (var attId : b.audioAttachmentIds()) {
                    var att = (MessageAttachment) MessageAttachment.findById(attId);
                    if (att != null && att.transcript != null && !att.transcript.isBlank()) return true;
                }
            }
            return false;
        });
    }

    /**
     * Compact one-token tag for the {@code error_tag} field of the
     * {@code AUDIO_PASSTHROUGH_OUTCOME} log event. We only emit the
     * exception class + a short status hint to keep the field
     * searchable; full error bodies stay out of structured logs to
     * avoid leaking provider-side prose.
     */
    static String shortErrorTag(Throwable t) {
        if (t == null) return "unknown";
        var name = t.getClass().getSimpleName();
        var msg = t.getMessage();
        if (msg == null) return name;
        var lower = msg.toLowerCase();
        if (lower.contains("http 4")) return name + ":4xx";
        if (lower.contains("http 5")) return name + ":5xx";
        if (lower.contains("timeout") || lower.contains("timed out")) return name + ":timeout";
        return name;
    }

    /**
     * JCLAW-165 / absorbed JCLAW-169: structured log event emitted on
     * every audio-bearing LLM call so the field-data set grows a
     * provider/format/outcome matrix we can act on later. Only format
     * + timing metadata is logged; no message content, no PII.
     *
     * @param outcome one of {@code "accepted"} (passthrough success),
     *                {@code "downgraded"} (success after retry), or
     *                {@code "error"} (failed even after retry).
     * @param errorTag short tag from {@link #shortErrorTag} when
     *                 {@code outcome="error"}; null otherwise.
     * @param transcriptAwaited whether any branch awaited a Whisper
     *                          future during this call — true on the
     *                          text-only branch and on rejection-retry,
     *                          false on the audio-capable happy path.
     */
    static void logAudioPassthroughOutcome(Agent agent, Conversation conversation,
                                            LlmProvider provider, String outcome,
                                            String errorTag, boolean transcriptAwaited) {
        var providerName = provider != null && provider.config() != null
                ? provider.config().name() : "unknown";
        var modelId = ModelResolver.effectiveModelId(agent, conversation);
        var channel = conversation != null ? conversation.channelType : null;
        var detail = "provider=%s model=%s outcome=%s transcript_awaited=%s%s".formatted(
                providerName, modelId, outcome, transcriptAwaited,
                errorTag != null ? " error_tag=" + errorTag : "");
        EventLogger.info("AUDIO_PASSTHROUGH_OUTCOME",
                agent != null ? agent.name : null, channel, detail);
    }
}
