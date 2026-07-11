package services.imagegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

import java.io.IOException;
import java.util.Base64;

/**
 * Cloud image-generation backend (JCLAW-225) that speaks OpenAI's {@code POST /images/generations}
 * shape for text-to-image, and {@code POST /images/edits} (multipart) for image-to-image when a
 * reference image is supplied (JCLAW-697). For the GPT image models ({@code gpt-image-1} and family)
 * the response carries the image as base64 by default, so no {@code response_format} is sent; a
 * {@code url}-shaped response (dall-e, or an OpenAI-compatible proxy) is also handled by fetching the
 * bytes. Subclasses bind name + default model only, mirroring
 * {@code services.caption.OpenAiCompatibleImageCaptionClient}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — {@code /images/generations} or {@code /images/edits} is appended.</li>
 *   <li>{@code provider.{name}.apiKey} — sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code imagegen.{name}.model} — overrides the subclass default model (provider-scoped,
 *       so switching providers can't leak one provider's model id into another).</li>
 *   <li>{@code imagegen.imageSize} — default size string when the caller passes no dimensions.</li>
 * </ul>
 */
public class OpenAiCompatibleImageGenerationClient implements ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String B64_JSON = "b64_json";
    private static final String MIME_PNG = "image/png";

    private final String providerName;
    private final String defaultModel;
    private final OkHttpClient client;

    public OpenAiCompatibleImageGenerationClient(String providerName, String defaultModel) {
        this(providerName, defaultModel, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenAiCompatibleImageGenerationClient(String providerName, String defaultModel, OkHttpClient client) {
        this.providerName = providerName;
        this.defaultModel = defaultModel;
        this.client = client;
    }

    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height) {
        return generate(prompt, model, width, height, null);
    }

    /**
     * JCLAW-697: image-to-image / style transfer. With a {@code referenceImage}, the request switches
     * from the JSON {@code /images/generations} create endpoint to the multipart {@code /images/edits}
     * endpoint, sending the reference as the {@code image} part. gpt-image models additionally get
     * {@code input_fidelity=high} so the subject stays recognizable (style transfer + consistency).
     * A null reference is the original text-to-image path.
     */
    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height,
                                   ReferenceImage referenceImage) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageGenerationException("image generation: prompt is required");
        }
        var baseUrl = ConfigService.get("provider." + providerName + ".baseUrl");
        var apiKey = ConfigService.get("provider." + providerName + ".apiKey");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ImageGenerationException("provider." + providerName + ".baseUrl is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ImageGenerationException("provider." + providerName + ".apiKey is not configured");
        }
        var effModel = Strings.firstNonBlank(model, ConfigService.get("imagegen." + providerName + ".model"), defaultModel);
        if (effModel == null || effModel.isBlank()) {
            throw new ImageGenerationException(providerName + " image generation: no model configured");
        }

        var base = Strings.trimTrailingSlash(baseUrl);
        var size = sizeFor(width, height);
        boolean hasReference = referenceImage != null
                && referenceImage.bytes() != null && referenceImage.bytes().length > 0;
        var request = hasReference
                ? buildEditsRequest(base, apiKey, effModel, prompt, size, referenceImage)
                : buildGenerationsRequest(base, apiKey, effModel, prompt, size);

        try (var response = client.newCall(request).execute()) {
            var body = response.body().string();
            if (!response.isSuccessful()) {
                throw new ImageGenerationException("%s image generation failed: HTTP %d %s%s".formatted(
                        providerName, response.code(), response.message(),
                        body.isEmpty() ? "" : (" — " + Strings.truncate(body, 500))));
            }
            return parseImage(body, effModel);
        } catch (IOException e) {
            throw new ImageGenerationException(providerName + " image generation transport failed: " + e.getMessage(), e);
        }
    }

    /** JSON {@code /images/generations} (text-to-image). b64 is the default for GPT image models. */
    private Request buildGenerationsRequest(String base, String apiKey, String model, String prompt, String size) {
        var root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("prompt", prompt);
        root.addProperty("size", size);
        root.addProperty("n", 1);
        return new Request.Builder()
                .url(base + "/images/generations")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(RequestBody.create(root.toString(), JSON))
                .build();
    }

    /** Multipart {@code /images/edits} (image-to-image): the reference rides as the {@code image} part. */
    private Request buildEditsRequest(String base, String apiKey, String model, String prompt, String size,
                                      ReferenceImage referenceImage) {
        var mime = (referenceImage.mimeType() != null && !referenceImage.mimeType().isBlank())
                ? referenceImage.mimeType() : MIME_PNG;
        var builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("size", size)
                .addFormDataPart("n", "1")
                .addFormDataPart("image", "reference." + extForMime(mime),
                        RequestBody.create(referenceImage.bytes(), MediaType.parse(mime)));
        // gpt-image models expose input_fidelity (low|high) to tune how strongly the output preserves
        // the reference; "high" favors character/style consistency. Skip it for non-gpt-image models
        // (e.g. dall-e edits) that would 400 on the unknown field.
        if (model.startsWith("gpt-image")) {
            builder.addFormDataPart("input_fidelity", "high");
        }
        return new Request.Builder()
                .url(base + "/images/edits")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(builder.build())
                .build();
    }

    private static String extForMime(String mime) {
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    private GeneratedImage parseImage(String responseBody, String model) {
        var json = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ImageGenerationException(providerName + " image generation: no image data in response");
        }
        var first = data.get(0).getAsJsonObject();
        if (first.has(B64_JSON) && !first.get(B64_JSON).isJsonNull()) {
            byte[] bytes = Base64.getDecoder().decode(first.get(B64_JSON).getAsString());
            return new GeneratedImage(bytes, MIME_PNG, providerName + ":" + model);
        }
        if (first.has("url") && !first.get("url").isJsonNull()) {
            byte[] bytes = fetchBytes(first.get("url").getAsString());
            return new GeneratedImage(bytes, MIME_PNG, providerName + ":" + model);
        }
        throw new ImageGenerationException(providerName + " image generation: response had neither b64_json nor url");
    }

    /** Fetch image bytes from a returned URL (dall-e / proxy shape). Uses the general HTTP tier. */
    private byte[] fetchBytes(String imageUrl) {
        var req = new Request.Builder().url(imageUrl).get().build();
        try (var resp = HttpFactories.general().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new ImageGenerationException(providerName + " image fetch failed: HTTP " + resp.code());
            }
            return resp.body().bytes();
        } catch (IOException e) {
            throw new ImageGenerationException(providerName + " image fetch transport failed: " + e.getMessage(), e);
        }
    }

    /** Map requested pixel dims to a size the GPT image models accept (1024x1024, 1536x1024, 1024x1536). */
    private static String sizeFor(Integer width, Integer height) {
        if (width == null || height == null) {
            var cfg = ConfigService.get("imagegen.imageSize");
            return (cfg != null && !cfg.isBlank()) ? cfg : "1024x1024";
        }
        if (width > height) return "1536x1024";
        if (height > width) return "1024x1536";
        return "1024x1024";
    }

}
