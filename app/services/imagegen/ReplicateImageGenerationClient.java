package services.imagegen;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import services.ConfigService;
import services.ReplicatePredictions;
import utils.HttpFactories;
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
 * <p>The create/poll transport is shared with the video client via {@link ReplicatePredictions}
 * (JCLAW-708); this client owns the image-specific input-building (aspect ratio, reference image) and
 * output-mapping (fetching the produced image bytes).
 *
 * <p>Configuration: {@code provider.replicate.baseUrl} (default {@code https://api.replicate.com/v1}),
 * {@code provider.replicate.apiKey}, {@code imagegen.replicate.model} (default
 * {@code black-forest-labs/flux-schnell}), {@code imagegen.timeoutSeconds} (poll deadline).
 */
public class ReplicateImageGenerationClient implements ImageGenerationService {

    private static final String DEFAULT_MODEL = "black-forest-labs/flux-schnell";
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final String STATUS = "status";

    private final ReplicatePredictions predictions;

    public ReplicateImageGenerationClient() {
        this(HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public ReplicateImageGenerationClient(OkHttpClient client) {
        this.predictions = new ReplicatePredictions(client);
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
        try {
            return predictions.create(baseUrl, model, apiKey, input, true); // Prefer: wait — run inline ~60s
        } catch (ReplicatePredictions.ReplicateException e) {
            if (e.isTransport()) {
                throw new ImageGenerationException("replicate create transport failed: " + e.getCause().getMessage(), e.getCause());
            }
            var body = e.body();
            throw new ImageGenerationException("replicate create failed: HTTP %d %s%s".formatted(
                    e.code(), e.statusMessage(), body.isEmpty() ? "" : (" — " + Strings.truncate(body, Strings.ERROR_SNIPPET_MAX_CHARS))));
        }
    }

    /** Return the output image URL, polling the prediction's {@code urls.get} until it succeeds. */
    private String resolveOutputUrl(JsonObject prediction, String apiKey) {
        long timeoutMs = ConfigService.getInt("imagegen.timeoutSeconds", 60) * 1000L;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        var current = prediction;
        while (true) {
            var status = statusOf(current);
            if ("succeeded".equalsIgnoreCase(status)) {
                var url = ReplicatePredictions.firstOutputUrl(current);
                if (url == null) {
                    throw new ImageGenerationException("replicate succeeded but produced no output");
                }
                return url;
            }
            if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                throw new ImageGenerationException("replicate prediction " + status + ": "
                        + Strings.truncate(current.toString(), Strings.ERROR_SNIPPET_MAX_CHARS));
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

    /** The prediction's {@code status} string, or {@code ""} when absent/JSON-null. */
    private static String statusOf(JsonObject pred) {
        return pred.has(STATUS) && !pred.get(STATUS).isJsonNull() ? pred.get(STATUS).getAsString() : "";
    }

    private String pollUrl(JsonObject prediction) {
        var urls = prediction.getAsJsonObject("urls");
        if (urls != null && urls.has("get") && !urls.get("get").isJsonNull()) {
            return urls.get("get").getAsString();
        }
        throw new ImageGenerationException("replicate prediction has no urls.get to poll");
    }

    private JsonObject poll(String getUrl, String apiKey) {
        try {
            return predictions.get(getUrl, apiKey);
        } catch (ReplicatePredictions.ReplicateException e) {
            if (e.isTransport()) {
                throw new ImageGenerationException("replicate poll transport failed: " + e.getCause().getMessage(), e.getCause());
            }
            throw new ImageGenerationException("replicate poll failed: HTTP " + e.code());
        }
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
