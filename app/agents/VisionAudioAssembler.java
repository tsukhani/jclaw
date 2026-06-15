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
 * <h2>Output shapes</h2>
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
 * <h2>JCLAW-165 capability handshake</h2>
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
     * {@code supportsAudio}.
     *
     * @param chatMessageIndex   position in the returned {@link ChatMessage}
     *                           list (which always begins with the system
     *                           prompt at index 0)
     * @param msgId              re-locates the persisted Message in a
     *                           fresh transaction at rewrite time
     * @param audioAttachmentIds the persisted audio attachment ids carried
     *                           on this user turn
     */
    public record AudioBearer(int chatMessageIndex, Long msgId, List<Long> audioAttachmentIds) {}

    /**
     * JCLAW-215: the vision analogue of {@link AudioBearer}. Tracks which user
     * messages carry image attachments so the post-Tx caption rewrite can
     * re-target them when the active model lacks {@code supportsVision}: each
     * image's persisted {@link models.MessageAttachment#caption} (computed on
     * demand by {@link #applyCaptionsForCapability}) rides inside the text part
     * in place of the {@code image_url} part a vision model would receive.
     *
     * @param chatMessageIndex   position in the returned {@link ChatMessage} list
     * @param msgId              re-locates the persisted Message at rewrite time
     * @param imageAttachmentIds the persisted image attachment ids on this turn
     */
    public record ImageBearer(int chatMessageIndex, Long msgId, List<Long> imageAttachmentIds) {}

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
        // Vision-capable by default: images ride as image_url parts, preserving
        // the pre-JCLAW-215 shape for every caller that doesn't opt into the
        // caption fallback.
        return userMessageFor(msg, supportsAudio, true);
    }

    /**
     * JCLAW-215 capability-aware overload covering both modalities.
     * {@code supportsVision=true} emits an {@code image_url} content part for
     * each image attachment (JCLAW-25); {@code supportsVision=false} emits the
     * image's cached {@link models.MessageAttachment#caption} as a text block
     * (or a clear "description unavailable" fallback note when the caption is
     * null/blank), exactly mirroring the audio transcript downgrade.
     *
     * <p>Like the audio path this method is pure with respect to async work: it
     * reads the {@code caption} field as-is. The caller is responsible for
     * having computed and persisted captions before calling with
     * {@code supportsVision=false} — {@link #applyCaptionsForCapability} runs
     * the (slow) caption model outside any blocking JPA transaction first.
     */
    public static ChatMessage userMessageFor(models.Message msg, boolean supportsAudio, boolean supportsVision) {
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
        var fileNotes = collectFileNotes(atts);
        // Transcript blocks for the !supportsAudio branch ride INSIDE the
        // text part so the LLM sees one cohesive prompt rather than fragmented
        // text + transcript. Append after the user's typed content (if any).
        var transcriptBlocks = supportsAudio ? "" : collectTranscriptBlocks(atts);
        // Caption blocks (JCLAW-215) ride in the text part the same way for the
        // !supportsVision branch, so a text-only model "sees" the image as a
        // description instead of an image_url part it can't accept.
        var captionBlocks = supportsVision ? "" : collectCaptionBlocks(atts);
        var combinedText = text + fileNotes + transcriptBlocks + captionBlocks;

        var parts = new ArrayList<Map<String, Object>>();
        if (!combinedText.isBlank()) {
            parts.add(Map.of("type", "text", "text", combinedText));
        }
        addMediaParts(parts, atts, supportsAudio, supportsVision);
        return new ChatMessage(MessageRole.USER.value, parts, null, null, null);
    }

    /**
     * FILE-kind attachments are surfaced to the LLM as a filename +
     * workspace path it can read via the filesystem tool. Images and
     * audio ride as structured content parts elsewhere, so they skip
     * this branch.
     */
    private static String collectFileNotes(List<models.MessageAttachment> atts) {
        var fileNotes = new StringBuilder();
        for (var a : atts) {
            if (a.isImage() || a.isAudio()) continue;
            fileNotes.append("\n[Attached file: ")
                    .append(a.originalFilename)
                    .append(" — workspace:")
                    .append(a.storagePath)
                    .append("]");
        }
        return fileNotes.toString();
    }

    /**
     * Build the {@code [Voice note transcription: ...]} blocks for the
     * !supportsAudio branch. Missing/blank transcripts fall back to a
     * "transcription unavailable" note that preserves the user's
     * original filename.
     */
    private static String collectTranscriptBlocks(List<models.MessageAttachment> atts) {
        var transcriptBlocks = new StringBuilder();
        for (var a : atts) {
            if (!a.isAudio()) continue;
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
        return transcriptBlocks.toString();
    }

    /**
     * Build the {@code [Image description ...]} blocks for the !supportsVision branch (JCLAW-215).
     * Each block carries the auto-generated caption (the only representation a non-vision model can
     * use — it can't read the raw bytes) <b>plus the image's workspace-relative path</b>, so the
     * agent can still file-manage it (move / copy / forward via the documents or filesystem tools)
     * with a path that actually resolves — instead of guessing one and tripping the containment
     * guard. Missing/blank captions fall back to a note that preserves the original filename and
     * the same workspace path — symmetric with {@link #collectTranscriptBlocks}.
     */
    private static String collectCaptionBlocks(List<models.MessageAttachment> atts) {
        var captionBlocks = new StringBuilder();
        for (var a : atts) {
            if (!a.isImage()) continue;
            var path = services.AttachmentService.workspaceRelativePath(a);
            var caption = a.caption;
            if (caption != null && !caption.isBlank()) {
                captionBlocks.append("\n\n[Image description: ")
                        .append(caption.trim())
                        .append(" (auto-generated — you cannot read the image contents directly; the file is in your workspace at \"")
                        .append(path)
                        .append("\", which you can move/copy/send with the documents or filesystem tools)]");
            } else {
                captionBlocks.append("\n\n[Image: auto-description unavailable")
                        .append(a.originalFilename != null ? " for \"" + a.originalFilename + "\"" : "")
                        .append(" — you cannot read the image contents directly; the file is in your workspace at \"")
                        .append(path)
                        .append("\", which you can move/copy/send with the documents or filesystem tools]");
            }
        }
        return captionBlocks.toString();
    }

    /**
     * Append {@code image_url} parts for every image attachment when the active
     * model supports vision (else the image rode as a caption text block via
     * {@link #collectCaptionBlocks}), plus {@code input_audio} parts for every
     * audio attachment when the active model supports audio.
     */
    private static void addMediaParts(List<Map<String, Object>> parts,
                                      List<models.MessageAttachment> atts,
                                      boolean supportsAudio, boolean supportsVision) {
        for (var a : atts) {
            if (a.isImage() && supportsVision) {
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
        // indefinitely.
        if (!awaitPendingTranscripts(audioBearers)) return messages;

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

    /**
     * JCLAW-215 outside-Tx helper, the vision analogue of
     * {@link #applyTranscriptsForCapability}: when the active model lacks
     * {@code supportsVision}, ensure every image attachment on the
     * {@code imageBearers} turns has a caption (computing + persisting any that
     * are missing via {@link services.caption.CaptionRouter}), then rebuild
     * those user messages as text-with-caption.
     *
     * <p>{@code supportsAudio} is threaded through so a message that carries
     * <i>both</i> a downgraded voice note and an image is rebuilt once with
     * both modalities resolved — the caption rewrite never clobbers a transcript
     * rewrite that {@link #applyTranscriptsForCapability} applied earlier in the
     * same chain.
     *
     * <p>The caption model call (cloud round-trip or in-JVM ONNX pass) runs in
     * {@link #ensureCaptions} with <b>no JPA transaction held</b>, matching the
     * codebase rule that slow model calls never occupy a pooled connection.
     * Returns {@code messages} unchanged when {@code supportsVision} is true or
     * {@code imageBearers} is empty — the vision-capable happy path pays zero
     * added latency.
     *
     * <p>Exposed as {@code public} (like {@link #userMessageFor}) so
     * {@code CaptionPipelineTest} can exercise the compute → persist → rewrite
     * orchestration directly — Play 1.x pins tests to the default package, so
     * package-private access is unreachable from the test.
     */
    public static List<ChatMessage> applyCaptionsForCapability(List<ChatMessage> messages,
            List<ImageBearer> imageBearers, boolean supportsVision, boolean supportsAudio) {
        if (supportsVision || imageBearers == null || imageBearers.isEmpty()) return messages;

        // Phase 1 (no Tx during the model call): compute + persist any missing
        // caption so Phase 2's pure rebuild can read MessageAttachment.caption
        // as-is.
        ensureCaptions(imageBearers);

        // Phase 2 (fresh Tx): refetch each affected message + rebuild as
        // text-with-caption (supportsVision=false), preserving the real
        // supportsAudio so a co-attached transcript downgrade survives.
        return Tx.run(() -> {
            var rewritten = new ArrayList<>(messages);
            for (var b : imageBearers) {
                var msg = (models.Message) models.Message.findById(b.msgId());
                if (msg == null) continue;
                rewritten.set(b.chatMessageIndex(), userMessageFor(msg, supportsAudio, false));
            }
            return rewritten;
        });
    }

    /**
     * For each image attachment lacking a caption, fetch its bytes inside a
     * short Tx, run the configured caption backend outside any Tx, then persist
     * the result. No-op when no caption backend is configured — the assembler's
     * "description unavailable" fallback note then carries the filename.
     */
    private static void ensureCaptions(List<ImageBearer> imageBearers) {
        var svc = services.caption.CaptionRouter.configuredService().orElse(null);
        if (svc == null) return;
        for (var b : imageBearers) {
            for (var attId : b.imageAttachmentIds()) {
                captionOne(svc, attId);
            }
        }
    }

    /**
     * Caption a single attachment: skip if already captioned; otherwise read its
     * data URL in a short Tx, run the (slow) model with no connection held, and
     * persist. Any failure is logged and swallowed — the fallback note covers it.
     */
    private static void captionOne(services.caption.ImageCaptionService svc, Long attId) {
        var dataUrl = Tx.run(() -> {
            var att = (models.MessageAttachment) models.MessageAttachment.findById(attId);
            if (att == null || (att.caption != null && !att.caption.isBlank())) return null;
            return AttachmentService.readAsDataUrl(att);
        });
        if (dataUrl == null) return; // missing row, or already captioned

        String caption;
        try {
            caption = svc.captionDataUrl(dataUrl);
        } catch (RuntimeException e) {
            EventLogger.warn("caption", "Captioning failed for attachment %d: %s".formatted(attId, e.getMessage()));
            return;
        }
        if (caption == null || caption.isBlank()) return;

        final var resolved = caption.trim();
        Tx.run(() -> {
            var att = (models.MessageAttachment) models.MessageAttachment.findById(attId);
            if (att != null) {
                att.caption = resolved;
                att.save();
            }
            return null;
        });
    }

    /**
     * Await each in-flight transcript future. Failures resolve with the
     * empty-string sentinel via the dispatcher's catch-all, so
     * {@code .get()} returns "" rather than throwing — the timeout
     * branch is the only one the catch handles. Returns {@code false}
     * when interrupted so the caller can bail out without rewriting.
     */
    private static boolean awaitPendingTranscripts(List<AudioBearer> audioBearers) {
        for (var b : audioBearers) {
            for (var attId : b.audioAttachmentIds()) {
                if (!awaitOneTranscript(attId)) return false;
            }
        }
        return true;
    }

    /** Await one transcript future. Returns {@code false} on interrupt. */
    private static boolean awaitOneTranscript(Long attId) {
        var future = PendingTranscripts.lookup(attId);
        if (future.isEmpty()) return true;
        try {
            // A resolved value (transcript or the empty-string failure
            // sentinel) has now been read under the single-reader
            // contract, so evict the entry to bound heap growth
            // (JCLAW-405). Timeout leaves the still-unresolved future in
            // the map; interrupt bails before consuming.
            future.get().get(TRANSCRIPT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PendingTranscripts.consume(attId);
        } catch (TimeoutException _) {
            EventLogger.warn("transcription",
                    "Transcript await timeout for attachment %d after %ds"
                            .formatted(attId, TRANSCRIPT_AWAIT_TIMEOUT_SECONDS));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception _) {
            // ExecutionException — the dispatcher's silent-failure
            // contract means this shouldn't fire, but treat any
            // surprise as "use the fallback note." The future resolved
            // exceptionally, so it is safe to evict here too.
            PendingTranscripts.consume(attId);
        }
        return true;
    }
}
