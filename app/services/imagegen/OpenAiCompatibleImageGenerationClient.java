package services.imagegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;
import java.util.Base64;

/**
 * Cloud image-generation backend (JCLAW-225) that speaks OpenAI's {@code POST /images/generations}
 * shape. For the GPT image models ({@code gpt-image-1} and family) the response carries the image as
 * base64 by default, so no {@code response_format} is sent; a {@code url}-shaped response (dall-e, or
 * an OpenAI-compatible proxy) is also handled by fetching the bytes. Subclasses bind name + default
 * model only, mirroring {@code services.caption.OpenAiCompatibleImageCaptionClient}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — {@code /images/generations} is appended.</li>
 *   <li>{@code provider.{name}.apiKey} — sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code imagegen.cloud.model} — overrides the subclass default model.</li>
 *   <li>{@code imagegen.imageSize} — default size string when the caller passes no dimensions.</li>
 * </ul>
 */
public class OpenAiCompatibleImageGenerationClient implements ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");

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
        var effModel = firstNonBlank(model, ConfigService.get("imagegen.cloud.model"), defaultModel);
        if (effModel == null || effModel.isBlank()) {
            throw new ImageGenerationException(providerName + " image generation: no model configured");
        }

        var url = trimTrailingSlash(baseUrl) + "/images/generations";
        var request = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(RequestBody.create(buildRequestJson(effModel, prompt, sizeFor(width, height)), JSON))
                .build();

        try (var response = client.newCall(request).execute()) {
            var body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new ImageGenerationException("%s image generation failed: HTTP %d %s%s".formatted(
                        providerName, response.code(), response.message(),
                        body.isEmpty() ? "" : (" — " + truncate(body, 500))));
            }
            return parseImage(body, effModel);
        } catch (IOException e) {
            throw new ImageGenerationException(providerName + " image generation transport failed: " + e.getMessage(), e);
        }
    }

    /** Build the OpenAI {@code /images/generations} body. b64 is the default for GPT image models. */
    private String buildRequestJson(String model, String prompt, String size) {
        var root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("prompt", prompt);
        root.addProperty("size", size);
        root.addProperty("n", 1);
        return root.toString();
    }

    private GeneratedImage parseImage(String responseBody, String model) {
        var json = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ImageGenerationException(providerName + " image generation: no image data in response");
        }
        var first = data.get(0).getAsJsonObject();
        if (first.has("b64_json") && !first.get("b64_json").isJsonNull()) {
            byte[] bytes = Base64.getDecoder().decode(first.get("b64_json").getAsString());
            return new GeneratedImage(bytes, "image/png", providerName + ":" + model);
        }
        if (first.has("url") && !first.get("url").isJsonNull()) {
            byte[] bytes = fetchBytes(first.get("url").getAsString());
            return new GeneratedImage(bytes, "image/png", providerName + ":" + model);
        }
        throw new ImageGenerationException(providerName + " image generation: response had neither b64_json nor url");
    }

    /** Fetch image bytes from a returned URL (dall-e / proxy shape). Uses the general HTTP tier. */
    private byte[] fetchBytes(String imageUrl) {
        var req = new Request.Builder().url(imageUrl).get().build();
        try (var resp = HttpFactories.general().newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
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

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
