package services.caption;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.MessageAttachment;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import services.AttachmentService;
import services.ConfigService;
import services.openaicompat.OpenAiCompatibleClientBase;
import utils.HttpFactories;

import java.io.IOException;

/**
 * Cloud image-captioning backend (JCLAW-212) that speaks OpenAI's {@code POST /chat/completions}
 * multimodal shape with an {@code image_url} content part. Both OpenAI and OpenRouter proxy the
 * identical wire format — only the base URL + API key differ — so subclasses bind name + default
 * model only, mirroring {@code OpenAiCompatibleTranscriptionClient}.
 *
 * <p>Credential resolution and HTTP-error → typed-exception mapping live in
 * {@link OpenAiCompatibleClientBase} (JCLAW-721); this class owns only the {@code /chat/completions}
 * endpoint and the request/response body shaping.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — {@code /chat/completions} is appended.</li>
 *   <li>{@code provider.{name}.apiKey} — sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code caption.model} — captioning model id; defaults to the subclass's cheap vision
 *       model. Should name a <b>vision-capable</b> model on the chosen provider (JCLAW-214).</li>
 * </ul>
 */
public class OpenAiCompatibleImageCaptionClient extends OpenAiCompatibleClientBase
        implements ImageCaptionService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String INSTRUCTION =
            "Describe this image objectively in one concise sentence for a reader who cannot see it.";
    /** OpenAI chat-completions message field name (request part + response parse). */
    private static final String CONTENT = "content";

    private final String defaultModel;

    public OpenAiCompatibleImageCaptionClient(String providerName, String defaultModel) {
        this(providerName, defaultModel, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenAiCompatibleImageCaptionClient(String providerName, String defaultModel, OkHttpClient client) {
        super(providerName, client);
        this.defaultModel = defaultModel;
    }

    @Override
    protected String operationLabel() {
        return "captioning";
    }

    @Override
    protected RuntimeException newException(String message) {
        return new CaptionException(message);
    }

    @Override
    protected RuntimeException newException(String message, Throwable cause) {
        return new CaptionException(message, cause);
    }

    @Override
    public String caption(MessageAttachment attachment) {
        if (attachment == null) return "";
        try {
            return captionDataUrl(AttachmentService.readAsDataUrl(attachment));
        } catch (RuntimeException e) {
            Logger.warn("%s image captioning failed: %s", providerName, e.getMessage());
            return "";
        }
    }

    /**
     * HTTP core — caption an image given its {@code data:} URL. Throws {@link CaptionException} on
     * failure (the {@link #caption} wrapper catches it and returns ""). The unit-test seam: no JPA
     * or on-disk attachment needed.
     */
    public String captionDataUrl(String imageDataUrl) {
        var creds = resolveCredentials();
        var model = ConfigService.get("caption.model");
        if (model == null || model.isBlank()) model = defaultModel;
        if (model == null || model.isBlank()) {
            // No caption.model and no provider default (ollama-local has none — there's no
            // universally-pulled Ollama vision model). Fail fast rather than POST a blank model.
            throw newException("%s captioning: no model configured — set caption.model".formatted(providerName));
        }

        // Transcode WebP/etc. → PNG so a caption model that can't decode the source format (notably
        // local Ollama, which rejects WebP) gets an image it can load.
        imageDataUrl = CaptionImageNormalizer.toModelSafeDataUrl(imageDataUrl);

        var url = creds.baseUrl() + "/chat/completions";
        var request = bearer(new Request.Builder().url(url), creds.apiKey())
                .post(RequestBody.create(buildRequestJson(model, imageDataUrl), JSON))
                .build();

        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw httpError(response, response.body().string());
            }
            return parseCaption(response.body().string());
        } catch (IOException e) {
            throw transportError(e);
        }
    }

    /** Build the OpenAI multimodal chat body: one user message with a text instruction + image_url. */
    private String buildRequestJson(String model, String imageDataUrl) {
        var textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", INSTRUCTION);

        var urlObj = new JsonObject();
        urlObj.addProperty("url", imageDataUrl);
        var imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", urlObj);

        var content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);
        var message = new JsonObject();
        message.addProperty("role", "user");
        message.add(CONTENT, content);
        var messages = new JsonArray();
        messages.add(message);

        var root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.addProperty("max_tokens", 100);
        return root.toString();
    }

    private String parseCaption(String responseBody) {
        var json = JsonParser.parseString(responseBody).getAsJsonObject();
        var choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            Logger.warn("%s captioning: no choices in response: %s", providerName, json);
            return "";
        }
        var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has(CONTENT) || message.get(CONTENT).isJsonNull()) {
            return "";
        }
        return message.get(CONTENT).getAsString().trim();
    }
}
