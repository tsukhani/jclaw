package agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import llm.LlmTypes.ChatMessage;
import models.MessageRole;
import services.AttachmentService;
import services.EventLogger;
import services.MimeExtensions;
import services.Tx;
import services.transcription.PendingTranscripts;

/**
 * Build-time assembly of a historical user turn into the OpenAI-style
 * {@link ChatMessage} shape, including the multi-part content used for
 * images and audio (JCLAW-25 / JCLAW-132 / JCLAW-165). Extracted from
 * {@link AgentRunner} as part of JCLAW-299.
 *
 * <h3>Output shapes</h3>
 * Plain-text turns with no attachments emit the compact single-string
 * {@code {role,content:"..."}}. Turns with attachments emit the
 * content-parts array:
 * <ul>
 *   <li><b>Images</b> (JCLAW-25) ride as {@code image_url} parts using
 *   a {@code data:} URL from {@link AttachmentService#readAsDataUrl}.</li>
 *   <li><b>Audio</b> (JCLAW-132 / JCLAW-165) rides as {@code input_audio}
 *   parts on audio-capable models, or as transcript text inside the text
 *   part when the active model lacks {@code supportsAudio}.</li>
 *   <li><b>File attachments</b> show up as bracketed filename references
 *   inside the text part so the LLM can read them via the filesystem
 *   tool.</li>
 * </ul>
 *
 * <h3>JCLAW-165 capability handshake</h3>
 * The audio path has two modes: the audio-capable happy path (Gemini
 * accepting OGG Opus natively, GPT-4o accepting MP3) ships the bytes
 * inline; the no-audio fallback path awaits any in-flight transcripts
 * and rewrites the user messages to text-with-transcript via
 * {@link #applyTranscriptsForCapability}. {@link AudioBearer} records
 * the affected message slots so the rewrite can re-target them by
 * index without re-walking the whole history.
 *
 * <p>{@code userMessageFor} is intentionally pure with respect to
 * async work — it reads {@code MessageAttachment.transcript} as-is.
 * Callers using {@code supportsAudio=false} are responsible for
 * having awaited the relevant {@link PendingTranscripts} future
 * outside any JPA transaction first; {@link #applyTranscriptsForCapability}
 * is the orchestrator that does both phases together.
 */
public final class VisionAudioAssembler {

    /**
     * OpenAI {@code input_audio.format} values we know the shared
     * adapter can emit. JCLAW-132: {@link MimeExtensions} reverse-looks
     * a MIME against this list to derive the format hint; the underlying
     * data lives in Play's {@code mime-types.properties} +
     * {@code mimetype.*} application config, so adding a new audio
     * format is a config change, not a code edit.
     */
    private static final String[] AUDIO_FORMAT_CANDIDATES = {
            "mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus", "weba"
    };

    /**
     * Bounded ceiling for awaiting a Whisper future during message
     * reassembly. The text-only branch normally awaits a future whose
     * VT has been running since {@code appendUserMessage} committed
     * (well before this point), so timeouts should be rare; the cap
     * is defensive against pathological transcription stalls (e.g. a
     * stuck ffmpeg). After timeout, the empty-string sentinel is used
     * and the fallback "could not be transcribed" note appears in the
     * prompt.
     */
    private static final long TRANSCRIPT_AWAIT_TIMEOUT_SECONDS = 60;

    private VisionAudioAssembler() {}

    /**
     * JCLAW-165: tracks which user messages in the assembled list carry
     * audio attachments, so the post-Tx orchestrator can rewrite them
     * as text-with-transcript when the active model lacks
     * {@code supportsAudio}. The {@code chatMessageIndex} is the
     * position in the returned {@link ChatMessage} list (which always
     * begins with the system prompt at index 0); {@code msgId}
     * re-locates the persisted Message in a fresh transaction at
     * rewrite time.
     */
    public record AudioBearer(int chatMessageIndex, Long msgId, List<Long> audioAttachmentIds) {}

    /**
     * Build the {@link ChatMessage} for a historical user turn, lifting
     * it into OpenAI-style content parts when the row has attachments.
     * Images ride as {@code image_url} parts (JCLAW-25); audio rides as
     * {@code input_audio} parts (JCLAW-132); other files fall through
     * as bracketed filename references inside the text part. Plain-text
     * turns without attachments still emit the compact
     * {@code {role,content:"..."}} shape; the provider registry's shared
     * serializer routes either form correctly.
     *
     * <p>Exposed as {@code public} so the unit test
     * {@code VisionAudioAssemblyTest} can exercise the content-part
     * assembly directly — Play 1.x pins tests to the default package,
     * so package-private access is unreachable from the test.
     */
    public static ChatMessage userMessageFor(models.Message msg) {
        // Default behaviour preserves the pre-JCLAW-165 input_audio shape;
        // audio-capable models still go through this overload from
        // buildMessages and from any caller that wants the Telegram
        // happy-path (Gemini accepting OGG Opus natively).
        return userMessageFor(msg, true);
    }

    /**
     * JCLAW-165 capability-aware overload. {@code supportsAudio=true}
     * emits the OpenAI {@code input_audio} content part for each audio
     * attachment; {@code supportsAudio=false} emits a text part
     * containing the cached transcript (or a clear "could not be
     * transcribed" fallback note when the transcript field is
     * null/empty).
     *
     * <p>This method is pure with respect to async work: it reads the
     * {@link models.MessageAttachment#transcript} field as-is. The
     * caller is responsible for awaiting any in-flight
     * {@link PendingTranscripts} future and persisting the result
     * before calling with {@code supportsAudio=false} —
     * {@link #applyTranscriptsForCapability} handles that outside any
     * blocking JPA transaction.
     */
    public static ChatMessage userMessageFor(models.Message msg, boolean supportsAudio) {
        // Defensive fallback: in most paths Play's enhancer installs a
        // Hibernate lazy proxy on @OneToMany and the field is non-null;
        // VisionAudioAssemblyTest saw null collections after direct entity
        // manipulation, so the explicit query is cheap insurance against
        // the field being null for any reason.
        var atts = msg.attachments;
        if (atts == null) {
            atts = models.MessageAttachment.findByMessage(msg);
        }
        if (atts.isEmpty()) {
            return ChatMessage.user(msg.content);
        }

        var text = msg.content == null ? "" : msg.content;
        var fileNotes = new StringBuilder();
        var parts = new ArrayList<Map<String, Object>>();
        for (var a : atts) {
            if (!a.isImage() && !a.isAudio()) {
                // FILE-kind attachments are surfaced to the LLM as a
                // filename + workspace path it can read via the filesystem
                // tool. Images and audio ride as structured content parts
                // below, so they skip this branch.
                fileNotes.append("\n[Attached file: ")
                        .append(a.originalFilename)
                        .append(" — workspace:")
                        .append(a.storagePath)
                        .append("]");
            }
        }
        // Transcript blocks for the !supportsAudio branch ride INSIDE the
        // text part so the LLM sees one cohesive prompt rather than fragmented
        // text + transcript. Append after the user's typed content (if any).
        var transcriptBlocks = new StringBuilder();
        if (!supportsAudio) {
            for (var a : atts) {
                if (a.isAudio()) {
                    var transcript = a.transcript;
                    if (transcript != null && !transcript.isBlank()) {
                        transcriptBlocks.append("\n\n[Voice note transcription: ")
                                .append(transcript.trim())
                                .append("]");
                    } else {
                        transcriptBlocks.append("\n\n[Voice note ")
                                .append(a.originalFilename != null ? a.originalFilename : "unnamed")
                                .append(": transcription unavailable]");
                    }
                }
            }
        }
        var combinedText = text + fileNotes + transcriptBlocks;
        if (!combinedText.isBlank()) {
            parts.add(Map.of("type", "text", "text", combinedText));
        }
        for (var a : atts) {
            if (a.isImage()) {
                parts.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", AttachmentService.readAsDataUrl(a))));
            } else if (a.isAudio() && supportsAudio) {
                var format = MimeExtensions.forMime(a.mimeType, AUDIO_FORMAT_CANDIDATES);
                var inner = new LinkedHashMap<String, Object>();
                inner.put("data", AttachmentService.readAsBase64(a));
                if (!format.isEmpty()) inner.put("format", format);
                parts.add(Map.of("type", "input_audio", "input_audio", inner));
            }
        }
        return new ChatMessage(MessageRole.USER.value, parts, null, null, null);
    }

    /**
     * JCLAW-165 outside-Tx helper: when the active model lacks
     * {@code supportsAudio}, await any in-flight transcripts for the
     * messages identified by {@code audioBearers}, then rebuild those
     * user messages as text-with-transcript via
     * {@link #userMessageFor(models.Message, boolean) userMessageFor(msg, false)}.
     *
     * <p>Awaits run on the calling (typically virtual) thread with a
     * bounded timeout per future. The dispatcher in
     * {@link services.ConversationService} also persists the transcript
     * to {@link models.MessageAttachment#transcript} on success, so the
     * fresh re-fetch inside {@code userMessageFor} sees the field
     * populated. Failed/timed-out futures leave the field NULL and the
     * fallback note carries the user's original filename.
     *
     * <p>Returns {@code messages} unchanged when {@code supportsAudio}
     * is true or {@code audioBearers} is empty — the audio-capable
     * happy path keeps zero added latency.
     */
    static List<ChatMessage> applyTranscriptsForCapability(
            List<ChatMessage> messages, List<AudioBearer> audioBearers, boolean supportsAudio) {
        if (supportsAudio || audioBearers == null || audioBearers.isEmpty()) return messages;

        // Phase 1 (no Tx): await each in-flight future. Bounded timeout
        // protects against a stuck Whisper from blocking the LLM call
        // indefinitely. Failures resolve with empty-string sentinel via
        // the dispatcher's catch-all, so .get() returns "" rather than
        // throwing — the timeout branch is the only one the catch handles.
        for (var b : audioBearers) {
            for (var attId : b.audioAttachmentIds()) {
                var future = PendingTranscripts.lookup(attId);
                if (future.isEmpty()) continue;
                try {
                    future.get().get(TRANSCRIPT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    EventLogger.warn("transcription",
                            "Transcript await timeout for attachment %d after %ds"
                                    .formatted(attId, TRANSCRIPT_AWAIT_TIMEOUT_SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return messages;
                } catch (Exception _) {
                    // ExecutionException — the dispatcher's silent-failure
                    // contract means this shouldn't fire, but treat any
                    // surprise as "use the fallback note."
                }
            }
        }

        // Phase 2 (fresh Tx): refetch each affected message + rebuild.
        return Tx.run(() -> {
            var rewritten = new ArrayList<>(messages);
            for (var b : audioBearers) {
                var msg = (models.Message) models.Message.findById(b.msgId());
                if (msg == null) continue;
                rewritten.set(b.chatMessageIndex(), userMessageFor(msg, false));
            }
            return rewritten;
        });
    }
}
