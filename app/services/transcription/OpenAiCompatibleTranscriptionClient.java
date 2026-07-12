package services.transcription;

import com.google.gson.JsonParser;
import models.MessageAttachment;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import services.AgentService;
import services.ConfigService;
import services.openaicompat.OpenAiCompatibleClientBase;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Shared implementation for transcription backends that speak OpenAI's
 * {@code POST /audio/transcriptions} multipart shape — both OpenAI itself
 * and OpenRouter (which proxies the same wire format on its base URL).
 * Subclasses are name-only; the wire protocol is identical.
 *
 * <p>Credential resolution and HTTP-error → typed-exception mapping live in
 * {@link OpenAiCompatibleClientBase} (JCLAW-721); this class owns only the
 * {@code /audio/transcriptions} endpoint and the multipart request / JSON
 * response shaping.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — must end without {@code /} so
 *       {@code /audio/transcriptions} appends cleanly.</li>
 *   <li>{@code provider.{name}.apiKey} — sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code transcription.model} — model id; defaults to {@code whisper-1}.</li>
 * </ul>
 *
 * <p>File bytes stream from disk into the multipart body via OkHttp's
 * {@link RequestBody#create(java.io.File, MediaType)} (Okio-backed source),
 * so the JVM never holds the full attachment in heap.
 */
public class OpenAiCompatibleTranscriptionClient extends OpenAiCompatibleClientBase
        implements TranscriptionService {

    /** Default model id when {@code transcription.model} isn't set. OpenAI
     *  ships {@code whisper-1} as their canonical transcription model and
     *  OpenRouter routes the same id through to OpenAI under the hood. */
    public static final String DEFAULT_MODEL = "whisper-1";

    public OpenAiCompatibleTranscriptionClient(String providerName) {
        this(providerName, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a custom client (e.g. backed by MockWebServer). */
    public OpenAiCompatibleTranscriptionClient(String providerName, OkHttpClient client) {
        super(providerName, client);
    }

    @Override
    protected String operationLabel() {
        return "transcription";
    }

    @Override
    protected RuntimeException newException(String message) {
        return new TranscriptionException(message);
    }

    @Override
    protected RuntimeException newException(String message, Throwable cause) {
        return new TranscriptionException(message, cause);
    }

    @Override
    public String transcribe(MessageAttachment attachment) {
        if (attachment == null) {
            throw new TranscriptionException("attachment is null");
        }

        var creds = resolveCredentials();
        var model = ConfigService.get("transcription.model");
        if (model == null || model.isBlank()) model = DEFAULT_MODEL;

        var path = AgentService.workspaceRoot().resolve(attachment.storagePath);
        if (!Files.isRegularFile(path)) {
            throw new TranscriptionException(
                    "attachment file not found on disk: " + path);
        }

        var mediaType = MediaType.parse(
                attachment.mimeType != null ? attachment.mimeType : "application/octet-stream");
        var fileBody = RequestBody.create(path.toFile(), mediaType);

        var multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(
                        Headers.of("Content-Disposition",
                                "form-data; name=\"file\"; filename=\"" + safeFilename(attachment) + "\""),
                        fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .build();

        var url = creds.baseUrl() + "/audio/transcriptions";
        var request = bearer(new Request.Builder().url(url), creds.apiKey())
                .post(multipart)
                .build();

        try (var response = client.newCall(request).execute()) {
            // OkHttp 5 guarantees response.body() is non-null on a
            // synchronously-executed call, so no defensive null guards
            // are needed below — the body may be empty, but never null.
            if (!response.isSuccessful()) {
                throw httpError(response, response.body().string());
            }
            var json = JsonParser.parseString(response.body().string()).getAsJsonObject();
            // OpenAI/OpenRouter return {"text":"..."} for response_format=json.
            // Some Whisper variants emit "text" at top level; others wrap in
            // a verbose envelope. Be lenient: prefer top-level text, fall
            // back to "transcription" or empty.
            if (json.has("text")) return json.get("text").getAsString();
            if (json.has("transcription")) return json.get("transcription").getAsString();
            Logger.warn("OpenAiCompatibleTranscriptionClient: %s response had no text field: %s",
                    providerName, json);
            return "";
        } catch (IOException e) {
            throw transportError(e);
        }
    }

    private static String safeFilename(MessageAttachment attachment) {
        var name = attachment.originalFilename;
        if (name == null || name.isBlank()) name = attachment.uuid;
        // Strip quotes / newlines that would break the Content-Disposition header.
        return name.replaceAll("[\"\\r\\n]", "_");
    }
}
