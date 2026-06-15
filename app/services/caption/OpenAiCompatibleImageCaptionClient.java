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
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;

/**
 * Cloud image-captioning backend (JCLAW-212) that speaks OpenAI's {@code POST /chat/completions}
 * multimodal shape with an {@code image_url} content part. Both OpenAI and OpenRouter proxy the
 * identical wire format — only the base URL + API key differ — so subclasses bind name + default
 * model only, mirroring {@code OpenAiCompatibleTranscriptionClient}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — {@code /chat/completions} is appended.</li>
 *   <li>{@code provider.{name}.apiKey} — sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code caption.model} — captioning model id; defaults to the subclass's cheap vision
 *       model. Should name a <b>vision-capable</b> model on the chosen provider (JCLAW-214).</li>
 * </ul>
 */
public class OpenAiCompatibleImageCaptionClient implements ImageCaptionService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String INSTRUCTION =
            "Describe this image objectively in one concise sentence for a reader who cannot see it.";
    /** OpenAI chat-completions message field name (request part + response parse). */
    private static final String CONTENT = "content";

    private final String providerName;
    private final String defaultModel;
    private final OkHttpClient client;

    public OpenAiCompatibleImageCaptionClient(String providerName, String defaultModel) {
        this(providerName, defaultModel, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenAiCompatibleImageCaptionClient(String providerName, String defaultModel, OkHttpClient client) {
        this.providerName = providerName;
        this.defaultModel = defaultModel;
        this.client = client;
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
        var baseUrl = ConfigService.get("provider." + providerName + ".baseUrl");
        var apiKey = ConfigService.get("provider." + providerName + ".apiKey");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new CaptionException("provider." + providerName + ".baseUrl is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new CaptionException("provider." + providerName + ".apiKey is not configured");
        }
        var model = ConfigService.get("caption.model");
        if (model == null || model.isBlank()) model = defaultModel;

        var url = trimTrailingSlash(baseUrl) + "/chat/completions";
        var request = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(RequestBody.create(buildRequestJson(model, imageDataUrl), JSON))
                .build();

        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var snippet = truncate(response.body().string(), 500);
                throw new CaptionException("%s captioning failed: HTTP %d %s%s".formatted(
                        providerName, response.code(), response.message(),
                        snippet.isEmpty() ? "" : (" — " + snippet)));
            }
            return parseCaption(response.body().string());
        } catch (IOException e) {
            throw new CaptionException(providerName + " captioning transport failed: " + e.getMessage(), e);
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

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
