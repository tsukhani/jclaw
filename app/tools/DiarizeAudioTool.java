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

import java.io.IOException;
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

    private static final String ARG_ACTION = "action";
    private static final String ARG_UUID = "attachment_uuid";
    private static final String ARG_NUM_SPEAKERS = "num_speakers";
    private static final String ARG_LANGUAGE = "language";
    private static final String ARG_SPEAKER_NAME = "speaker_name";

    private static final String ACTION_DIARIZE = "diarize";
    private static final String ACTION_ENROLL = "enroll_speaker";

    /** Display names become enrollment folder names — letters/digits/space
     *  and a few name punctuation marks, no separators, no leading dot. */
    private static final java.util.regex.Pattern SPEAKER_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} _'.-]{0,63}$");

    @Override public String name() { return TOOL_NAME; }
    @Override public String category() { return "Utilities"; }
    @Override public String icon() { return "mic"; }

    @Override
    public String description() {
        return """
                Speaker tools for an uploaded audio recording. Action 'diarize' (default) identifies \
                who spoke when — use it only when the user asks to identify, separate, or label the \
                speakers in a recording; ordinary voice notes are already transcribed automatically. \
                Slow: expect roughly a tenth of the recording's duration. Returns a transcript with \
                one line per turn, labeled with enrolled speaker names where the voice matches, or \
                SPEAKER_00-style tags otherwise. Action 'enroll_speaker' saves the recording as a \
                voice reference for 'speaker_name', so future diarizations label that voice by name — \
                use it when the user asks to remember/enroll a voice or store an audio file under the \
                speaker-voices folder. Both actions default to the most recent audio attachment in \
                this conversation; pass 'attachment_uuid' to pick a specific one. For diarize, set \
                'num_speakers' when the user states the exact count and 'language' (ISO 639-1, e.g. \
                'ms') to force the transcription language.""";
    }

    @Override
    public String summary() {
        return "Identify who spoke when in a recording, or enroll a voice for future identification.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of(ACTION_DIARIZE, ACTION_ENROLL),
                                SchemaKeys.DESCRIPTION,
                                "What to do with the recording. Defaults to 'diarize'."),
                        ARG_UUID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "UUID of the audio attachment to use. Omit to use the most recent "
                                        + "audio attachment in this conversation."),
                        ARG_NUM_SPEAKERS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "diarize only: exact number of speakers, when the user states it. "
                                        + "Omit when unknown."),
                        ARG_LANGUAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "diarize only: optional ISO 639-1 language code to force (e.g. 'en', "
                                        + "'ms'). Omit to follow the configured whisper model's default."),
                        ARG_SPEAKER_NAME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "enroll_speaker only: the person's display name — future diarized "
                                        + "transcripts label this voice with it.")
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

        var action = optString(args, ARG_ACTION);
        if (ACTION_ENROLL.equals(action)) {
            return enrollSpeaker(path, att, optString(args, ARG_SPEAKER_NAME));
        }
        if (action != null && !ACTION_DIARIZE.equals(action)) {
            return "Error: unknown action '%s' (expected %s or %s).".formatted(
                    action, ACTION_DIARIZE, ACTION_ENROLL);
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

    /**
     * enroll_speaker (JCLAW-561): copy the attachment into
     * {@code data/speaker-voices/{name}/} so {@link SpeakerNamer} labels this
     * voice by name in future diarizations. This is the sanctioned crossing
     * of the workspace → data boundary that the workspace-jailed filesystem
     * tools deliberately can't make: the destination is fixed to the
     * enrollment root, the folder name is validated against
     * {@link #SPEAKER_NAME_PATTERN}, and the file leaf is regenerated from
     * the attachment uuid (never from user-controlled names).
     */
    private static String enrollSpeaker(java.nio.file.Path source, MessageAttachment att, String name) {
        if (name == null || name.isBlank()) {
            return "Error: 'speaker_name' is required for enroll_speaker.";
        }
        var trimmed = name.strip();
        if (!SPEAKER_NAME_PATTERN.matcher(trimmed).matches()) {
            return ("Error: '%s' is not a valid speaker name — use letters, digits, spaces or "
                    + "-_'. (max 64 chars, must not start with a dot).").formatted(trimmed);
        }

        var extension = att.originalFilename != null && att.originalFilename.lastIndexOf('.') > 0
                ? att.originalFilename.substring(att.originalFilename.lastIndexOf('.'))
                : ".wav";
        if (!extension.matches("\\.[A-Za-z0-9]{1,8}")) extension = ".wav";
        try {
            var personDir = SpeakerNamer.enrollmentRoot().resolve(trimmed);
            Files.createDirectories(personDir);
            var dest = personDir.resolve(att.uuid + extension);
            Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long clips;
            try (var files = Files.list(personDir)) {
                clips = files.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith(".")).count();
            }
            return ("Enrolled voice reference for \"%s\" (%d clip(s) on file). Future speaker "
                    + "diarizations will label this voice as %s.").formatted(trimmed, clips, trimmed);
        } catch (IOException e) {
            return "Error: failed to store the enrollment clip: " + e.getMessage();
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
