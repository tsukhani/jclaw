package tools;

import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import models.Agent;
import models.MessageAttachment;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import services.transcription.DiarizationFusion;
import services.transcription.DiarizeSidecarClient;
import services.transcription.LlmAudio;
import services.transcription.WhisperModel;
import services.transcription.WhisperTranscriber;
import utils.HttpFactories;
import utils.JsonArgs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code diarize_audio} (JCLAW-654): speaker-attributed transcription via an
 * audio-capable chat model — cloud (OpenRouter/OpenAI) or a local audio-native
 * model served by Ollama ({@code ollama-local}). The former fully-on-device
 * diarization sidecar was removed after the measured tier comparison — an
 * audio-native LLM through a single {@code input_audio} chat call beat every
 * on-device sidecar configuration on turn attribution (18/18 vs 13/17 on the
 * human-arbitrated benchmark) at a fraction of the wall-clock; a local
 * audio-native LLM via Ollama is that same single-call shape, run locally.
 * This tool is the thin wrapper: it sends the recording plus a
 * verbatim-diarization prompt to the model the operator picked in Settings →
 * Transcription → Diarization ({@code transcription.diarization.provider} /
 * {@code .model}, filtered to audio-capable models) and returns the model's
 * transcript.
 *
 * <p>Attachment resolution: an explicit {@code attachment_uuid} wins;
 * otherwise the newest non-deleted, non-generated audio attachment in the
 * scoped conversation ({@link ToolContext#conversationId()}). Task fires
 * run with no conversation scope and get a clear error instead.
 *
 * <p>Large uploads are transcoded to mono 128k MP3 before the call —
 * PCM WAVs balloon 4/3× under base64 and blow provider request limits;
 * 3 minutes of MP3 is ~2.8 MB.
 */
public class DiarizeAudioTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "diarize_audio";

    public static final String PROVIDER_KEY = "transcription.diarization.provider";
    public static final String MODEL_KEY = "transcription.diarization.model";

    /** Sentinel {@code provider} value selecting the fully-local pyannote
     *  diarization sidecar instead of a cloud audio-model (JCLAW-565 revival). */
    public static final String PROVIDER_LOCAL = "pyannote-local";

    /** Appended to a local transcript when the on-device diarizer collapsed a
     *  multi-turn recording onto one speaker (the short/narrowband failure
     *  mode) — tells the agent to attribute speakers from conversational
     *  context, the one signal acoustic diarization can't use. */
    private static final String DEGENERATE_NOTE =
            "[Speaker-separation note: the on-device diarizer attributed (nearly) all turns to a "
            + "single speaker. If this recording is actually a multi-party conversation, acoustic "
            + "separation was low-confidence — common on short or narrowband (e.g. 8 kHz phone) "
            + "audio with similar-sounding voices. Attribute speakers from conversational context "
            + "(who greets, who asks vs. answers) rather than the SPEAKER_NN labels above.]";

    private static final String ARG_ACTION = "action";
    private static final String ARG_UUID = "attachment_uuid";
    private static final String ARG_SPEAKER_NAMES = "speaker_names";
    private static final String ARG_LANGUAGE = "language";
    private static final String ARG_EMOTIONS = "emotions";
    private static final String ARG_SPEAKER_NAME = "speaker_name";
    private static final String ARG_NUM_SPEAKERS = "num_speakers";

    private static final String ACTION_DIARIZE = "diarize";
    private static final String ACTION_ENROLL = "enroll_voice";

    /** Speaker names become file names — path traversal is rejected here. */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[\\p{L}\\p{N} ._-]{1,60}$");

    /** Reference clips capped to keep the multi-audio request bounded. */
    private static final int MAX_REFERENCE_CLIPS = 8;
    private static final int REFERENCE_SECONDS = 8;

    /** Diarization output-token ceiling and per-call deadline for the audio LLM. */
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int AUDIO_CALL_TIMEOUT_SECONDS = 600;

    /** Chars of an upstream/ffmpeg error kept when surfacing a failure. */
    private static final int ERROR_BODY_SNIPPET_CHARS = 300;
    private static final int FFMPEG_ERROR_TAIL_CHARS = 200;


    private static final Gson GSON = new Gson();

    /** S1192 constants — config-key prefix and OpenAI wire-format literals. */
    private static final String PROVIDER_PREFIX = "provider.";
    private static final String INPUT_AUDIO = "input_audio";
    private static final String SUPPORTS_AUDIO = "supportsAudio";

    @Override public String name() { return TOOL_NAME; }
    @Override public String category() { return "Utilities"; }
    @Override public String icon() { return "mic"; }

    @Override
    public String description() {
        return """
                Speaker-attributed transcription of an uploaded audio recording, produced by the \
                audio-capable model configured in Settings (Transcription → Diarization). Use it \
                when the user asks to identify, separate, or label the speakers in a recording — \
                ordinary voice notes are already transcribed automatically. The result is a \
                verbatim transcript, one line per speaker turn. Defaults to the most recent audio \
                attachment in this conversation; pass 'attachment_uuid' to pick a specific one. \
                Pass 'speaker_names' when the user has said who is in the recording (e.g. "the \
                host is Anthony, the guest is Firdaus") so turns carry real names instead of \
                Speaker 1/Speaker 2. Set 'emotions' true when the user asks for emotions or tone \
                — each turn gets a perceived-emotion tag judged from the voice. 'language' \
                (ISO 639-1) hints the recording's language when known. Pass 'num_speakers' when \
                you know how many people are talking — for a phone call that's usually 2 — so the \
                on-device diarizer doesn't merge similar voices into one speaker. Known voices are matched \
                automatically: action 'enroll_voice' with 'speaker_name' saves a short reference \
                sample of the attachment's voice (use it when the user asks to remember/enroll a \
                voice — the attachment should contain ONLY that person speaking), and every later \
                diarization sends the stored samples along so the model labels matching voices by \
                name without guessing. Requires a configured diarization model; if none is set the \
                tool says so — relay that to the user rather than retrying.""";
    }

    @Override
    public String summary() {
        return "Who-said-what transcript of a recording via the configured audio-capable model.";
    }

    /** Two callable actions, mirroring the {@code action} enum in {@link #parameters()}. */
    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_DIARIZE, "Produce a verbatim who-said-what transcript of a recording, one line per speaker turn"),
                new ToolAction(ACTION_ENROLL,  "Save a short reference sample of the attachment's voice so later transcripts label that speaker by name")
        );
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of(ACTION_DIARIZE, ACTION_ENROLL),
                                SchemaKeys.DESCRIPTION,
                                "Defaults to 'diarize'. 'enroll_voice' saves the attachment as a "
                                        + "voice reference for 'speaker_name'."),
                        ARG_SPEAKER_NAME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "enroll_voice only: the person's name — future diarized "
                                        + "transcripts label this voice with it."),
                        ARG_UUID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "UUID of the audio attachment to use. Omit to use the most recent "
                                        + "audio attachment in this conversation."),
                        ARG_SPEAKER_NAMES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Optional: who is in the recording, when the user has said so — "
                                        + "free text like 'the interviewer is Priya; the deep voice "
                                        + "is Marcus'. The transcript labels turns with these names."),
                        ARG_LANGUAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Optional ISO 639-1 language hint (e.g. 'en', 'ms') when the "
                                        + "recording's language is known."),
                        ARG_NUM_SPEAKERS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "Optional: how many people are in the recording, when known. "
                                        + "Used by the on-device diarizer to avoid merging "
                                        + "similar voices — phone calls are usually 2. Omit to "
                                        + "auto-detect."),
                        ARG_EMOTIONS, Map.of(SchemaKeys.TYPE, "boolean",
                                SchemaKeys.DESCRIPTION,
                                "Tag each turn with the speaker's perceived emotion (from tone of "
                                        + "voice, not word choice). Set when the user asks for "
                                        + "emotions.")
                ),
                SchemaKeys.REQUIRED, List.of()
        );
    }

    /** One outbound HTTP call; safe to run alongside other tools. */
    @Override public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson)
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException _) {
            return "Error: invalid arguments for " + TOOL_NAME + ".";
        }

        var action = JsonArgs.optString(args, ARG_ACTION);
        if (action != null && !action.isBlank()
                && !ACTION_DIARIZE.equals(action) && !ACTION_ENROLL.equals(action)) {
            return "Error: unknown action '%s' (expected %s or %s).".formatted(
                    action, ACTION_DIARIZE, ACTION_ENROLL);
        }

        var provider = ConfigService.get(PROVIDER_KEY, "");
        var model = ConfigService.get(MODEL_KEY, "");
        boolean local = PROVIDER_LOCAL.equals(provider);
        if (provider.isBlank() || (!local && model.isBlank())) {
            return "Error: no diarization model is configured. The operator must pick an "
                    + "audio-capable model (or the local pyannote diarizer) under "
                    + "Settings → Transcription → Diarization before recordings can be "
                    + "speaker-labeled.";
        }
        String baseUrl = "";
        if (!local) {
            baseUrl = ConfigService.get(PROVIDER_PREFIX + provider + ".baseUrl", "");
            if (baseUrl.isBlank()) {
                return "Error: provider '%s' has no base URL configured.".formatted(provider);
            }
            // Guard: a non-audio model fails upstream with a cryptic 400
            // ("content blocks must be text or image_url"). When the provider's
            // model registry knows the model and does NOT tag it audio-capable,
            // say so actionably instead of making the call.
            if (audioCapability(provider, model) == AudioCapability.NOT_AUDIO) {
                return ("Error: the configured diarization model '%s' (%s) is not audio-capable — "
                        + "it cannot listen to recordings. The operator must pick an audio-capable "
                        + "model under Settings → Transcription → Diarization.").formatted(model, provider);
            }
        }

        var attachment = Tx.run(() -> resolveAttachment(JsonArgs.optString(args, ARG_UUID)));
        if (attachment.error() != null) return attachment.error();
        var att = attachment.value();
        var path = AgentService.workspaceRoot().resolve(att.storagePath);
        if (!Files.isRegularFile(path)) {
            return "Error: the audio file for attachment %s is missing from storage.".formatted(att.uuid);
        }

        if (ACTION_ENROLL.equals(action)) {
            if (local) {
                return "Voice enrollment is only used by the cloud audio-model diarizer. The "
                        + "local pyannote diarizer labels speakers by in-recording voice "
                        + "similarity (Speaker 1, Speaker 2, …) and ignores enrolled references.";
            }
            return enrollVoice(path, JsonArgs.optString(args, ARG_SPEAKER_NAME));
        }

        boolean emotionsArg = JsonArgs.optBool(args, ARG_EMOTIONS);
        if (local) {
            return diarizeLocal(path, att.originalFilename, JsonArgs.optString(args, ARG_LANGUAGE),
                    JsonArgs.optInteger(args, ARG_NUM_SPEAKERS), emotionsArg);
        }

        try {
            var audio = LlmAudio.prepare(path, att.mimeType);
            boolean emotions = emotionsArg;
            var references = loadReferences();
            var prompt = buildPrompt(JsonArgs.optString(args, ARG_SPEAKER_NAMES),
                    JsonArgs.optString(args, ARG_LANGUAGE), emotions, references.keySet());
            var transcript = chatWithAudioRetrying(baseUrl, provider, model, prompt,
                    audio.base64(), audio.format(), references);
            return "Diarized transcript of %s (via %s %s):\n\n%s"
                    .formatted(att.originalFilename, provider, model, transcript);
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizeAudioTool: %s", e.getMessage());
            return "Speaker diarization failed: " + e.getMessage();
        }
    }

    /**
     * Fully-local speaker-attributed transcript (JCLAW-565 revival): the ASR
     * sidecar transcribes (WHAT + WHEN), the diarize sidecar produces speaker
     * turns (WHO + WHEN), and {@link DiarizationFusion} aligns them by time.
     * No audio leaves the host — the privacy/cost path. No per-turn emotion
     * (pyannote yields turns only); the cloud path stays for that case.
     */
    private String diarizeLocal(Path path, String filename, String language,
                                Integer numSpeakers, boolean emotions) {
        try {
            var model = WhisperModel.byId(ConfigService.get("transcription.localModel"))
                    .orElse(WhisperModel.DEFAULT);
            var segments = WhisperTranscriber.transcribeSegments(
                    path, model, language != null && !language.isBlank() ? language : null);
            var turns = new DiarizeSidecarClient().diarize(path, numSpeakers, emotions);
            var transcript = DiarizationFusion.fuse(segments, turns);
            if (transcript.isBlank()) {
                return "No speech was found in %s.".formatted(filename);
            }
            var result = "Diarized transcript of %s (local: pyannote + %s):\n\n%s"
                    .formatted(filename, model.id(), transcript);
            if (DiarizationFusion.isDegenerate(turns)) {
                result += "\n\n" + DEGENERATE_NOTE;
            }
            return result;
        } catch (RuntimeException e) {
            Logger.warn("DiarizeAudioTool local: %s", e.getMessage());
            return "Speaker diarization failed: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ //

    private static String buildPrompt(String speakerNames, String language, boolean emotions,
                                      Set<String> referenceNames) {
        var sb = new StringBuilder("""
                Listen to the attached audio and produce a complete diarized transcript. \
                One line per speaker turn, formatted exactly as "SpeakerName: text". \
                Preserve every interjection as its own turn (even single words), and \
                transcribe verbatim — keep stutters, fillers and code-switching as spoken. \
                Return ONLY the transcript lines, no preamble or commentary.""");
        if (!referenceNames.isEmpty()) {
            sb.append("\nVoice reference clips precede the recording, each announced with its "
                    + "person's name. Match the recording's voices against them ACOUSTICALLY and "
                    + "label matching turns with those names; label any voice that matches no "
                    + "reference Speaker 1, Speaker 2, ... consistently.");
        }
        if (speakerNames != null && !speakerNames.isBlank()) {
            sb.append("\nAdditional speaker context: ").append(speakerNames.strip())
                    .append(". Label turns with these names where it applies.");
        } else if (referenceNames.isEmpty()) {
            sb.append("\nLabel the voices Speaker 1, Speaker 2, ... consistently throughout.");
        }
        if (language != null && !language.isBlank()) {
            sb.append("\nThe recording is primarily in '").append(language.strip()).append("'.");
        }
        if (emotions) {
            sb.append("\nAfter each speaker name, add in parentheses a rich one-to-three-word "
                    + "description of HOW that turn is delivered — judge it from the voice's "
                    + "prosody (tone, pace, energy, attitude), not just the words. Be specific "
                    + "and varied, e.g. reflective, amused, emphatic, playful and dismissive, "
                    + "challenging, sarcastic, frustrated, interrupting and resolute, defiant "
                    + "and deeply passionate, calm. Avoid defaulting to 'neutral' — every turn "
                    + "is delivered SOME way. Format: \"SpeakerName (delivery): text\".");
        }
        return sb.toString();
    }

    /** One retry: routers occasionally land a request on an upstream host
     *  without input_audio support (S1141: lifted out of the caller's try). */
    private static String chatWithAudioRetrying(String baseUrl, String provider, String model,
                                                String prompt, String base64, String format,
                                                Map<String, String> references) throws IOException {
        try {
            return chatWithAudio(baseUrl, provider, model, prompt, base64, format, references);
        } catch (IOException first) {
            Logger.info("DiarizeAudioTool: retrying after %s", first.getMessage());
            return chatWithAudio(baseUrl, provider, model, prompt, base64, format, references);
        }
    }

    /** One OpenAI-compatible /chat/completions call with an input_audio
     *  content part — the same wire shape VisionAudioAssembler emits for
     *  audio-capable chat models (JCLAW-132). */
    private static String chatWithAudio(String baseUrl, String provider, String model,
                                        String prompt, String base64, String format,
                                        Map<String, String> references)
            throws IOException {
        var content = new java.util.ArrayList<Map<String, Object>>();
        for (var ref : references.entrySet()) {
            content.add(Map.of("type", "text", "text",
                    "Voice reference — this is %s speaking:".formatted(ref.getKey())));
            content.add(Map.of("type", INPUT_AUDIO,
                    INPUT_AUDIO, Map.of("data", ref.getValue(), "format", "mp3")));
        }
        content.add(Map.of("type", "text", "text", prompt));
        var inner = new LinkedHashMap<String, Object>();
        inner.put("data", base64);
        if (!format.isEmpty()) inner.put("format", format);
        content.add(Map.of("type", INPUT_AUDIO, INPUT_AUDIO, inner));
        var body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0);
        body.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
        var messages = new JsonArray();
        var user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", GSON.toJsonTree(content));
        messages.add(user);
        body.add("messages", messages);

        var apiKey = ConfigService.get(PROVIDER_PREFIX + provider + ".apiKey", "");
        var request = new Request.Builder()
                .url(baseUrl.replaceAll("/+$", "") + "/chat/completions")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        // Audio inference over a multi-minute recording routinely exceeds
        // the default single-shot timeout; give it room.
        var client = HttpFactories.llmSingleShot().newBuilder()
                .callTimeout(Duration.ofSeconds(AUDIO_CALL_TIMEOUT_SECONDS)).build();
        try (var response = client.newCall(request.build()).execute()) {
            // OkHttp 5 guarantees a non-null body on a synchronously executed
            // call (may be empty, never null) — same contract note as
            // OpenAiCompatibleTranscriptionClient.
            var text = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("model call failed (HTTP %d): %s".formatted(
                        response.code(), text.substring(0, Math.min(ERROR_BODY_SNIPPET_CHARS, text.length()))));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("model returned no choices");
            }
            var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            JsonElement contentEl = message == null ? null : message.get("content");
            var transcript = contentEl == null || contentEl.isJsonNull() ? "" : contentEl.getAsString();
            if (transcript.isBlank()) {
                throw new IOException("model returned an empty transcript (a reasoning-only model? "
                        + "pick a plain audio-capable instruct model in Settings)");
            }
            return transcript.strip();
        }
    }

    /** What the provider's configured model registry says about a model's
     *  ability to hear audio. UNKNOWN (unlisted/unparseable registry) gets
     *  the benefit of the doubt — the call decides. */
    public enum AudioCapability { AUDIO, NOT_AUDIO, UNKNOWN }

    public static AudioCapability audioCapability(String provider, String model) {
        try {
            var raw = ConfigService.get(PROVIDER_PREFIX + provider + ".models", "");
            if (raw.isBlank()) return AudioCapability.UNKNOWN;
            for (var el : JsonParser.parseString(raw).getAsJsonArray()) {
                var o = el.getAsJsonObject();
                if (o.has("id") && model.equals(o.get("id").getAsString())) {
                    boolean supports = o.has(SUPPORTS_AUDIO) && !o.get(SUPPORTS_AUDIO).isJsonNull()
                            && o.get(SUPPORTS_AUDIO).getAsBoolean();
                    return supports ? AudioCapability.AUDIO : AudioCapability.NOT_AUDIO;
                }
            }
            return AudioCapability.UNKNOWN;
        } catch (RuntimeException _) {
            return AudioCapability.UNKNOWN;
        }
    }

    /** Where the named reference clips live: data/voice-refs/<Name>.mp3. */
    static Path voiceRefsDir() {
        return Path.of("data", "voice-refs");
    }

    /** Save a short mono MP3 sample of the attachment as <name>'s voice. */
    private static String enrollVoice(Path audio, String name) {
        if (name == null || name.isBlank() || !NAME_PATTERN.matcher(name.strip()).matches()) {
            return "Error: enroll_voice needs a plain 'speaker_name' (letters, digits, spaces, "
                    + "dots, dashes; up to 60 characters).";
        }
        var clean = name.strip();
        try {
            Files.createDirectories(voiceRefsDir());
            var dest = voiceRefsDir().resolve(clean + ".mp3");
            var tmp = Files.createTempFile("jclaw-voice-ref-", ".mp3");
            try {
                var proc = new ProcessBuilder("ffmpeg", "-y", "-i", audio.toString(),
                        "-t", String.valueOf(REFERENCE_SECONDS), "-ac", "1", "-b:a", "96k",
                        tmp.toString()).redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() != 0) {
                    return "Error: could not extract a voice sample (%s).".formatted(
                            output.substring(Math.max(0, output.length() - FFMPEG_ERROR_TAIL_CHARS)));
                }
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return ("Enrolled %s's voice (first %d seconds of the recording). Future diarized "
                    + "transcripts will label this voice by name. Note: the sample is sent to the "
                    + "configured cloud model with each diarization.").formatted(clean, REFERENCE_SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while extracting the voice sample.";
        } catch (IOException e) {
            return "Error: could not save the voice reference: " + e.getMessage();
        }
    }

    /** Stored reference clips as name -> base64 MP3, oldest first, capped. */
    private static Map<String, String> loadReferences() {
        var out = new java.util.LinkedHashMap<String, String>();
        var dir = voiceRefsDir();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            var clips = files.filter(f -> f.getFileName().toString().endsWith(".mp3"))
                    .sorted().limit(MAX_REFERENCE_CLIPS).toList();
            for (var clip : clips) {
                var name = clip.getFileName().toString().replaceFirst("[.]mp3$", "");
                out.put(name, Base64.getEncoder().encodeToString(Files.readAllBytes(clip)));
            }
        } catch (IOException e) {
            Logger.warn("DiarizeAudioTool: voice references unavailable: %s", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ //

    private record Resolved(MessageAttachment value, String error) {
        static Resolved of(MessageAttachment a) { return new Resolved(a, null); }
        static Resolved fail(String message) { return new Resolved(null, message); }
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
                "message.conversation.id = ?1 and kind = ?2 and deleted = false "
                        + "and generated = false order by id desc",
                conversationId, MessageAttachment.KIND_AUDIO).first();
        if (att == null) {
            return Resolved.fail("Error: this conversation has no audio attachments to diarize. "
                    + "Ask the user to upload the recording first.");
        }
        return Resolved.of(att);
    }
}
