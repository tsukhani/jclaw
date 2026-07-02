package tools;

import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.MessageAttachment;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import services.transcription.DiarizedTranscript;
import services.transcription.SherpaDiarizer;
import services.transcription.SpeakerNamer;
import services.transcription.TranscriptionException;
import services.transcription.WhisperJniTranscriber;
import services.transcription.WhisperModel;

import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * {@code diarize_audio} (JCLAW-559): on-demand speaker identification for an
 * audio attachment in the current conversation. The ordinary inbound-audio
 * path stays on the fast single-speaker transcription service; this tool is
 * how "who said what in this recording?" gets answered — the model invokes
 * it, the full JCLAW-556 pipeline runs (whisper segments + sherpa-onnx
 * diarization + enrolled-speaker naming when {@code data/speaker-voices/}
 * has enrollment), and the speaker-attributed transcript comes back as the
 * tool result for the model to reason over.
 *
 * <p>Attachment resolution: an explicit {@code attachment_uuid} wins;
 * otherwise the newest non-deleted audio attachment in the scoped
 * conversation ({@link ToolContext#conversationId()}). Task fires run with
 * no conversation scope and get a clear error instead.
 */
public class DiarizeAudioTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "diarize_audio";

    private static final String ARG_UUID = "attachment_uuid";
    private static final String ARG_NUM_SPEAKERS = "num_speakers";
    private static final String ARG_LANGUAGE = "language";

    @Override public String name() { return TOOL_NAME; }
    @Override public String category() { return "Utilities"; }
    @Override public String icon() { return "mic"; }

    @Override
    public String description() {
        return """
                Identify who spoke when in an uploaded audio recording (speaker diarization). Use this \
                only when the user asks to identify, separate, or label the speakers in a recording — \
                ordinary voice notes are already transcribed automatically and do not need this tool. \
                By default the most recent audio attachment in this conversation is analyzed; pass \
                'attachment_uuid' to pick a specific one. Set 'num_speakers' when the user states the \
                exact count, and 'language' (ISO 639-1, e.g. 'ms') to force the transcription language. \
                Slow: expect roughly a tenth of the recording's duration. Returns a transcript with one \
                line per turn, labeled with enrolled speaker names where the voice matches an enrolled \
                sample, or SPEAKER_00-style tags otherwise.""";
    }

    @Override
    public String summary() {
        return "Identify who spoke when in an uploaded recording (speaker diarization).";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_UUID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "UUID of the audio attachment to analyze. Omit to use the most recent "
                                        + "audio attachment in this conversation."),
                        ARG_NUM_SPEAKERS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "Exact number of speakers, when the user states it. Omit when unknown."),
                        ARG_LANGUAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Optional ISO 639-1 language code to force (e.g. 'en', 'ms'). Omit to "
                                        + "follow the configured whisper model's default behavior.")
                ),
                SchemaKeys.REQUIRED, List.of()
        );
    }

    /** Minutes of serialized native inference; never worth racing in a parallel round. */
    @Override public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson)
                    .getAsJsonObject();
        } catch (RuntimeException _) {
            return "Error: invalid arguments for " + TOOL_NAME + ".";
        }

        // Tool dispatch runs on a bare virtual thread with no JPA context
        // (JCLAW-559 UAT finding) — DB reads need their own transaction, the
        // same Tx.run discipline the other DB-touching tools use. Only the
        // lookup runs inside it; the minutes of native inference below must
        // never hold a transaction open.
        var attachment = Tx.run(() -> resolveAttachment(optString(args, ARG_UUID)));
        if (attachment.error() != null) return attachment.error();
        var att = attachment.value();

        var path = AgentService.workspaceRoot().resolve(att.storagePath);
        if (!Files.isRegularFile(path)) {
            return "Error: the audio file for attachment %s is missing from storage.".formatted(att.uuid);
        }

        var model = WhisperModel.byId(ConfigService.get("transcription.localModel"))
                .orElse(WhisperModel.DEFAULT);
        float clusterThreshold = (float) ConfigService.getDouble("transcription.diarization.threshold", 0.3);
        Integer numSpeakers = optInt(args, ARG_NUM_SPEAKERS);

        try {
            var transcript = WhisperJniTranscriber.transcribeSegments(
                    path, model, optString(args, ARG_LANGUAGE));
            var speakers = SherpaDiarizer.diarize(path, clusterThreshold,
                    numSpeakers == null ? -1 : numSpeakers);
            var names = SpeakerNamer.enrollmentPresent()
                    ? SpeakerNamer.nameSpeakers(path, speakers, (float) ConfigService.getDouble(
                            "transcription.diarization.speakerMatchThreshold", 0.6))
                    : Map.<Integer, String>of();
            var entries = DiarizedTranscript.merge(transcript, speakers, names);

            var labels = new LinkedHashSet<String>();
            entries.forEach(e -> labels.add(e.speaker()));
            return "Diarized transcript of %s — %d speaker(s) detected: %s%n%n%s".formatted(
                    att.originalFilename, labels.size(), String.join(", ", labels),
                    DiarizedTranscript.toText(entries));
        } catch (TranscriptionException e) {
            return "Speaker diarization failed: " + e.getMessage();
        }
    }

    private record Resolved(MessageAttachment value, String error) {
        static Resolved of(MessageAttachment value) { return new Resolved(value, null); }
        static Resolved fail(String error) { return new Resolved(null, error); }
    }

    private static Resolved resolveAttachment(String uuid) {
        // Both paths are scoped to the active conversation. The uuid argument
        // is model-controlled and agents can be group-reachable through
        // channel bindings, so an unscoped uuid lookup would let a crafted
        // prompt pull audio out of an unrelated conversation (IDOR). The
        // argument only exists to disambiguate among THIS conversation's
        // attachments; cross-conversation reach is deliberately impossible.
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) {
            return Resolved.fail("Error: no conversation in scope — this tool works on audio "
                    + "uploaded to the current chat conversation.");
        }

        if (uuid != null && !uuid.isBlank()) {
            MessageAttachment att = MessageAttachment.find(
                    "uuid = ?1 and message.conversation.id = ?2 and deleted = false",
                    uuid, conversationId).first();
            if (att == null) {
                return Resolved.fail(
                        "Error: no attachment with uuid %s exists in this conversation.".formatted(uuid));
            }
            if (!MessageAttachment.KIND_AUDIO.equals(att.kind)) {
                return Resolved.fail("Error: attachment %s is %s, not audio.".formatted(uuid, att.kind));
            }
            return Resolved.of(att);
        }

        MessageAttachment att = MessageAttachment.find(
                "message.conversation.id = ?1 and kind = ?2 and deleted = false order by id desc",
                conversationId, MessageAttachment.KIND_AUDIO).first();
        if (att == null) {
            return Resolved.fail("Error: this conversation has no audio attachments to diarize. "
                    + "Ask the user to upload the recording first.");
        }
        return Resolved.of(att);
    }

    private static String optString(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }

    private static Integer optInt(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : null;
    }
}
