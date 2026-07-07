package tools;

import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.MessageAttachment;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import services.AgentService;
import services.ConfigService;
import services.MimeExtensions;
import services.Tx;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code diarize_audio} (JCLAW-654): speaker-attributed transcription via an
 * audio-capable CLOUD chat model. Local diarization was removed after the
 * measured tier comparison — an audio-native LLM through a single
 * {@code input_audio} chat call beat every local configuration on turn
 * attribution (18/18 vs 13/17 on the human-arbitrated benchmark) at a
 * fraction of the wall-clock. This tool is the thin wrapper: it sends the
 * recording plus a verbatim-diarization prompt to the model the operator
 * picked in Settings → Transcription → Diarization
 * ({@code transcription.diarization.provider} / {@code .model}, filtered to
 * audio-capable models) and returns the model's transcript.
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

    private static final String ARG_ACTION = "action";
    private static final String ARG_UUID = "attachment_uuid";
    private static final String ARG_SPEAKER_NAMES = "speaker_names";
    private static final String ARG_LANGUAGE = "language";
    private static final String ARG_EMOTIONS = "emotions";
    private static final String ARG_SPEAKER_NAME = "speaker_name";

    private static final String ACTION_DIARIZE = "diarize";
    private static final String ACTION_ENROLL = "enroll_voice";

    /** Speaker names become file names — path traversal is rejected here. */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[\\p{L}\\p{N} ._-]{1,60}$");

    /** Reference clips capped to keep the multi-audio request bounded. */
    private static final int MAX_REFERENCE_CLIPS = 8;
    private static final int REFERENCE_SECONDS = 8;

    private static final Gson GSON = new Gson();

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
                (ISO 639-1) hints the recording's language when known. Known voices are matched \
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
        } catch (RuntimeException _) {
            return "Error: invalid arguments for " + TOOL_NAME + ".";
        }

        var action = optString(args, ARG_ACTION);
        if (action != null && !action.isBlank()
                && !ACTION_DIARIZE.equals(action) && !ACTION_ENROLL.equals(action)) {
            return "Error: unknown action '%s' (expected %s or %s).".formatted(
                    action, ACTION_DIARIZE, ACTION_ENROLL);
        }

        var provider = ConfigService.get(PROVIDER_KEY, "");
        var model = ConfigService.get(MODEL_KEY, "");
        if (provider.isBlank() || model.isBlank()) {
            return "Error: no diarization model is configured. The operator must pick an "
                    + "audio-capable model under Settings → Transcription → Diarization "
                    + "before recordings can be speaker-labeled.";
        }
        var baseUrl = ConfigService.get("provider." + provider + ".baseUrl", "");
        if (baseUrl.isBlank()) {
            return "Error: provider '%s' has no base URL configured.".formatted(provider);
        }
        // Guard: a non-audio model fails upstream with a cryptic 400
        // ("content blocks must be text or image_url"). When the provider's
        // model registry knows the model and does NOT tag it audio-capable,
        // say so actionably instead of making the call.
        var capability = audioCapability(provider, model);
        if (Boolean.FALSE.equals(capability)) {
            return ("Error: the configured diarization model '%s' (%s) is not audio-capable — "
                    + "it cannot listen to recordings. The operator must pick an audio-capable "
                    + "model under Settings → Transcription → Diarization.").formatted(model, provider);
        }

        var attachment = Tx.run(() -> resolveAttachment(optString(args, ARG_UUID)));
        if (attachment.error() != null) return attachment.error();
        var att = attachment.value();
        var path = AgentService.workspaceRoot().resolve(att.storagePath);
        if (!Files.isRegularFile(path)) {
            return "Error: the audio file for attachment %s is missing from storage.".formatted(att.uuid);
        }

        if (ACTION_ENROLL.equals(action)) {
            return enrollVoice(path, optString(args, ARG_SPEAKER_NAME));
        }

        try {
            var audio = services.transcription.LlmAudio.prepare(path, att.mimeType);
            boolean emotions = args.has(ARG_EMOTIONS) && !args.get(ARG_EMOTIONS).isJsonNull()
                    && args.get(ARG_EMOTIONS).getAsBoolean();
            var references = loadReferences();
            var prompt = buildPrompt(optString(args, ARG_SPEAKER_NAMES),
                    optString(args, ARG_LANGUAGE), emotions, references.keySet());
            String transcript;
            try {
                transcript = chatWithAudio(baseUrl, provider, model, prompt,
                        audio.base64(), audio.format(), references);
            } catch (IOException first) {
                // One retry: routers occasionally land a request on an
                // upstream host without input_audio support.
                play.Logger.info("DiarizeAudioTool: retrying after %s", first.getMessage());
                transcript = chatWithAudio(baseUrl, provider, model, prompt,
                        audio.base64(), audio.format(), references);
            }
            return "Diarized transcript of %s (via %s %s):\n\n%s"
                    .formatted(att.originalFilename, provider, model, transcript);
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizeAudioTool: %s", e.getMessage());
            return "Speaker diarization failed: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ //

    private static String buildPrompt(String speakerNames, String language, boolean emotions,
                                      java.util.Set<String> referenceNames) {
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
            content.add(Map.of("type", "input_audio",
                    "input_audio", Map.of("data", ref.getValue(), "format", "mp3")));
        }
        content.add(Map.of("type", "text", "text", prompt));
        var inner = new LinkedHashMap<String, Object>();
        inner.put("data", base64);
        if (!format.isEmpty()) inner.put("format", format);
        content.add(Map.of("type", "input_audio", "input_audio", inner));
        var body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0);
        body.addProperty("max_tokens", 8192);
        var messages = new JsonArray();
        var user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", GSON.toJsonTree(content));
        messages.add(user);
        body.add("messages", messages);

        var apiKey = ConfigService.get("provider." + provider + ".apiKey", "");
        var request = new Request.Builder()
                .url(baseUrl.replaceAll("/+$", "") + "/chat/completions")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        // Audio inference over a multi-minute recording routinely exceeds
        // the default single-shot timeout; give it room.
        var client = HttpFactories.llmSingleShot().newBuilder()
                .callTimeout(Duration.ofSeconds(600)).build();
        try (var response = client.newCall(request.build()).execute()) {
            var text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("model call failed (HTTP %d): %s".formatted(
                        response.code(), text.substring(0, Math.min(300, text.length()))));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("model returned no choices");
            }
            var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            com.google.gson.JsonElement contentEl = message == null ? null : message.get("content");
            var transcript = contentEl == null || contentEl.isJsonNull() ? "" : contentEl.getAsString();
            if (transcript.isBlank()) {
                throw new IOException("model returned an empty transcript (a reasoning-only model? "
                        + "pick a plain audio-capable instruct model in Settings)");
            }
            return transcript.strip();
        }
    }

    /** TRUE/FALSE when the provider's configured model list knows the
     *  model's audio capability; null when the model isn't listed (an
     *  unlisted model gets the benefit of the doubt — the call decides). */
    static Boolean audioCapability(String provider, String model) {
        try {
            var raw = ConfigService.get("provider." + provider + ".models", "");
            if (raw.isBlank()) return null;
            for (var el : JsonParser.parseString(raw).getAsJsonArray()) {
                var o = el.getAsJsonObject();
                if (o.has("id") && model.equals(o.get("id").getAsString())) {
                    return o.has("supportsAudio") && !o.get("supportsAudio").isJsonNull()
                            && o.get("supportsAudio").getAsBoolean();
                }
            }
            return null;
        } catch (RuntimeException _) {
            return null;
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
                            output.substring(Math.max(0, output.length() - 200)));
                }
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return ("Enrolled %s's voice (first %d seconds of the recording). Future diarized "
                    + "transcripts will label this voice by name. Note: the sample is sent to the "
                    + "configured cloud model with each diarization.").formatted(clean, REFERENCE_SECONDS);
        } catch (InterruptedException e) {
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
                out.put(name, java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(clip)));
            }
        } catch (IOException e) {
            play.Logger.warn("DiarizeAudioTool: voice references unavailable: %s", e.getMessage());
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

    private static String optString(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }
}
