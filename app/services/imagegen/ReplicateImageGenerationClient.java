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
import utils.Strings;

import java.io.IOException;
import java.util.Base64;

/**
 * Replicate image-generation client (JCLAW-225/229). Replicate runs hosted image models behind an
 * async predictions API: {@code POST /v1/models/{owner}/{model}/predictions} with a {@code Prefer:
 * wait} header runs the model inline (waiting up to ~60s); if it hasn't finished the prediction is
 * polled at its {@code urls.get} until {@code status:"succeeded"}, whose {@code output} is a URL (or
 * array of URLs) to the produced image, which is fetched for its bytes. Auth is
 * {@code Authorization: Bearer} (unlike BFL's {@code x-key}). Image-gen only — not a chat provider.
 *
 * <p>Configuration: {@code provider.replicate.baseUrl} (default {@code https://api.replicate.com/v1}),
 * {@code provider.replicate.apiKey}, {@code imagegen.replicate.model} (default
 * {@code black-forest-labs/flux-schnell}), {@code imagegen.timeoutSeconds} (poll deadline).
 */
public class ReplicateImageGenerationClient implements ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String DEFAULT_MODEL = "black-forest-labs/flux-schnell";
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final String STATUS = "status";
    private static final String OUTPUT = "output";

    private final OkHttpClient client;

    public ReplicateImageGenerationClient() {
        this(HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public ReplicateImageGenerationClient(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height) {
        return generate(prompt, model, width, height, null);
    }

    /**
     * JCLAW-696: image-to-image / style transfer. When {@code referenceImage} is present the
     * reference is sent as {@code input_image} (a base64 data URI) inside the prediction input.
     * This only produces a restyled image on an image-to-image-capable model — set
     * {@code imagegen.replicate.model} to a Kontext slug (e.g. black-forest-labs/flux-kontext-pro);
     * a text-to-image model ignores the field. Null reference = text-to-image as before.
     */
    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height,
                                   ReferenceImage referenceImage) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageGenerationException("image generation: prompt is required");
        }
        var baseUrl = ConfigService.get("provider.replicate.baseUrl");
        var apiKey = ConfigService.get("provider.replicate.apiKey");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ImageGenerationException("provider.replicate.baseUrl is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ImageGenerationException("provider.replicate.apiKey is not configured");
        }
        var effModel = Strings.firstNonBlank(model, ConfigService.get("imagegen.replicate.model"), DEFAULT_MODEL);

        var prediction = createPrediction(Strings.trimTrailingSlash(baseUrl), effModel, apiKey, prompt, width, height, referenceImage);
        var imageUrl = resolveOutputUrl(prediction, apiKey);
        return fetchImage(imageUrl, "replicate:" + effModel);
    }

    private JsonObject createPrediction(String baseUrl, String model, String apiKey, String prompt,
            Integer width, Integer height, ReferenceImage referenceImage) {
        var input = new JsonObject();
        input.addProperty("prompt", prompt);
        // Flux models on Replicate take an aspect_ratio string, not raw pixels (those need
        // aspect_ratio="custom"), so map the tool's width/height back to the landscape/portrait/square
        // label — the same width/height→label move OpenAiCompatibleImageGenerationClient.sizeFor makes.
        var aspect = aspectRatioFor(width, height);
        if (aspect != null) input.addProperty("aspect_ratio", aspect);
        if (referenceImage != null && referenceImage.bytes() != null && referenceImage.bytes().length > 0) {
            // Kontext image-to-image reference: a base64 data URI in input_image (Replicate also
            // accepts a hosted URL). Only meaningful on an image-to-image model.
            var mime = referenceImage.mimeType() != null ? referenceImage.mimeType() : "image/png";
            input.addProperty("input_image",
                    "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(referenceImage.bytes()));
        }
        var root = new JsonObject();
        root.add("input", input);
        var request = new Request.Builder()
                .url(baseUrl + "/models/" + model + "/predictions")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .header("Prefer", "wait") // run inline up to ~60s, then fall back to polling
                .post(RequestBody.create(root.toString(), JSON))
                .build();
        try (var response = client.newCall(request).execute()) {
            var body = response.body().string();
            if (!response.isSuccessful()) {
                throw new ImageGenerationException("replicate create failed: HTTP %d %s%s".formatted(
                        response.code(), response.message(), body.isEmpty() ? "" : (" — " + Strings.truncate(body, 500))));
            }
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (IOException e) {
            throw new ImageGenerationException("replicate create transport failed: " + e.getMessage(), e);
        }
    }

    /** Return the output image URL, polling the prediction's {@code urls.get} until it succeeds. */
    private String resolveOutputUrl(JsonObject prediction, String apiKey) {
        long timeoutMs = ConfigService.getInt("imagegen.timeoutSeconds", 60) * 1000L;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        var current = prediction;
        while (true) {
            var status = current.has(STATUS) && !current.get(STATUS).isJsonNull()
                    ? current.get(STATUS).getAsString() : "";
            if ("succeeded".equalsIgnoreCase(status)) {
                return extractUrl(current);
            }
            if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                throw new ImageGenerationException("replicate prediction " + status + ": "
                        + Strings.truncate(current.toString(), 500));
            }
            if (System.nanoTime() >= deadline) {
                throw new ImageGenerationException("replicate generation timed out after " + (timeoutMs / 1000) + "s");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImageGenerationException("replicate generation interrupted", e);
            }
            current = poll(pollUrl(current), apiKey);
        }
    }

    private String pollUrl(JsonObject prediction) {
        var urls = prediction.getAsJsonObject("urls");
        if (urls != null && urls.has("get") && !urls.get("get").isJsonNull()) {
            return urls.get("get").getAsString();
        }
        throw new ImageGenerationException("replicate prediction has no urls.get to poll");
    }

    private JsonObject poll(String getUrl, String apiKey) {
        var request = new Request.Builder()
                .url(getUrl).header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey).get().build();
        try (var response = client.newCall(request).execute()) {
            var body = response.body().string();
            if (!response.isSuccessful()) {
                throw new ImageGenerationException("replicate poll failed: HTTP " + response.code());
            }
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (IOException e) {
            throw new ImageGenerationException("replicate poll transport failed: " + e.getMessage(), e);
        }
    }

    /** {@code output} is a URL string or an array of URL strings — take the first. */
    private String extractUrl(JsonObject prediction) {
        if (!prediction.has(OUTPUT) || prediction.get(OUTPUT).isJsonNull()) {
            throw new ImageGenerationException("replicate succeeded but produced no output");
        }
        var output = prediction.get(OUTPUT);
        if (output.isJsonArray()) {
            JsonArray arr = output.getAsJsonArray();
            if (arr.isEmpty()) throw new ImageGenerationException("replicate output array is empty");
            return arr.get(0).getAsString();
        }
        return output.getAsString();
    }

    /** Fetch the produced image, reading its real content type (Replicate's Flux output is webp). */
    private GeneratedImage fetchImage(String imageUrl, String generatedBy) {
        var req = new Request.Builder().url(imageUrl).get().build();
        try (var resp = HttpFactories.general().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new ImageGenerationException("replicate image fetch failed: HTTP " + resp.code());
            }
            var ct = resp.body().contentType();
            var mime = ct != null ? (ct.type() + "/" + ct.subtype()) : mimeFromUrl(imageUrl);
            return new GeneratedImage(resp.body().bytes(), mime, generatedBy);
        } catch (IOException e) {
            throw new ImageGenerationException("replicate image fetch transport failed: " + e.getMessage(), e);
        }
    }

    private static String mimeFromUrl(String url) {
        var u = url.toLowerCase();
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".jpg") || u.contains(".jpeg")) return "image/jpeg";
        if (u.contains(".gif")) return "image/gif";
        return "image/png";
    }

    /** Map the tool's width/height back to a Flux {@code aspect_ratio} label (landscape/portrait/square);
     *  null when dims are unset, so the model keeps its own default. */
    private static String aspectRatioFor(Integer width, Integer height) {
        if (width == null || height == null) return null;
        if (width > height) return "16:9";
        if (height > width) return "9:16";
        return "1:1";
    }
}
