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

    private static final String ARG_UUID = "attachment_uuid";
    private static final String ARG_SPEAKER_NAMES = "speaker_names";
    private static final String ARG_LANGUAGE = "language";

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
                Speaker 1/Speaker 2. 'language' (ISO 639-1) hints the recording's language when \
                known. Requires a configured diarization model; if none is set the tool says so — \
                relay that to the user rather than retrying.""";
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
                                        + "recording's language is known.")
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

        var attachment = Tx.run(() -> resolveAttachment(optString(args, ARG_UUID)));
        if (attachment.error() != null) return attachment.error();
        var att = attachment.value();
        var path = AgentService.workspaceRoot().resolve(att.storagePath);
        if (!Files.isRegularFile(path)) {
            return "Error: the audio file for attachment %s is missing from storage.".formatted(att.uuid);
        }

        try {
            var audio = services.transcription.LlmAudio.prepare(path, att.mimeType);
            var prompt = buildPrompt(optString(args, ARG_SPEAKER_NAMES), optString(args, ARG_LANGUAGE));
            String transcript;
            try {
                transcript = chatWithAudio(baseUrl, provider, model, prompt, audio.base64(), audio.format());
            } catch (IOException first) {
                // Routers load-balance a model id across upstream hosts and
                // not all of them accept input_audio — a 4xx here is often a
                // per-request routing blip (measured: 4/4 immediate retries
                // succeeded after one such 400). One retry before giving up.
                play.Logger.info("DiarizeAudioTool: retrying after %s", first.getMessage());
                transcript = chatWithAudio(baseUrl, provider, model, prompt, audio.base64(), audio.format());
            }
            return "Diarized transcript of %s (via %s %s):\n\n%s"
                    .formatted(att.originalFilename, provider, model, transcript);
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizeAudioTool: %s", e.getMessage());
            return "Speaker diarization failed: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ //

    private static String buildPrompt(String speakerNames, String language) {
        var sb = new StringBuilder("""
                Listen to the attached audio and produce a complete diarized transcript. \
                One line per speaker turn, formatted exactly as "SpeakerName: text". \
                Preserve every interjection as its own turn (even single words), and \
                transcribe verbatim — keep stutters, fillers and code-switching as spoken. \
                Return ONLY the transcript lines, no preamble or commentary.""");
        if (speakerNames != null && !speakerNames.isBlank()) {
            sb.append("\nThe speakers are known: ").append(speakerNames.strip())
                    .append(". Label every turn with these names.");
        } else {
            sb.append("\nLabel the voices Speaker 1, Speaker 2, ... consistently throughout.");
        }
        if (language != null && !language.isBlank()) {
            sb.append("\nThe recording is primarily in '").append(language.strip()).append("'.");
        }
        return sb.toString();
    }

    /** One OpenAI-compatible /chat/completions call with an input_audio
     *  content part — the same wire shape VisionAudioAssembler emits for
     *  audio-capable chat models (JCLAW-132). */
    private static String chatWithAudio(String baseUrl, String provider, String model,
                                        String prompt, String base64, String format)
            throws IOException {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("data", base64);
        if (!format.isEmpty()) inner.put("format", format);
        var content = List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "input_audio", "input_audio", inner));
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
