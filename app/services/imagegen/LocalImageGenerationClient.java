package services.imagegen;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.HttpFactories;
import utils.Strings;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Local image-generation client (JCLAW-226), talking to the Python
 * sidecar over {@code 127.0.0.1} (the shape chosen in the JCLAW-509 spike). Unlike
 * the cloud clients this is synchronous — {@code POST /generate} returns the raw
 * image bytes directly (no submit/poll), so the body + Content-Type map straight
 * onto {@link GeneratedImage}. The sidecar is launched on demand by
 * {@link LocalImageSidecarManager}; a {@code 409} means the weights aren't
 * downloaded yet.
 *
 * <p>Configuration: {@code imagegen.local.model} (the HF repo, set per launch),
 * {@code imagegen.local.generateTimeoutSeconds} (per-call deadline, default 300 —
 * generous because the first call also pays the lazy model load, and CPU mode is
 * slow).
 */
public class LocalImageGenerationClient implements ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client;
    /** Non-null only in tests — bypasses the sidecar manager and points at MockWebServer. */
    private final String baseUrlOverride;

    public LocalImageGenerationClient() {
        this(null, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a base URL (e.g. MockWebServer) and client, skipping the real sidecar. */
    public LocalImageGenerationClient(String baseUrl, OkHttpClient client) {
        this.baseUrlOverride = baseUrl;
        this.client = client;
    }

    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height) {
        return generate(prompt, model, width, height, null);
    }

    /**
     * JCLAW-699: image-to-image / style transfer. When {@code referenceImage} is present it's sent as
     * a base64 {@code image} field; the sidecar decodes it and passes it as {@code image=} to the
     * FLUX.2 Klein pipeline (which conditions on it natively — no pipeline swap). Null reference is
     * plain text-to-image.
     */
    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height,
                                   ReferenceImage referenceImage) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageGenerationException("image generation: prompt is required");
        }
        // In prod, ensure the daemon is up and get its base URL; in tests, use the override.
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : LocalImageSidecarManager.ensureRunning();

        var root = new JsonObject();
        root.addProperty("prompt", prompt);
        if (width != null) root.addProperty("width", width);
        if (height != null) root.addProperty("height", height);
        if (referenceImage != null && referenceImage.bytes() != null && referenceImage.bytes().length > 0) {
            root.addProperty("image", Base64.getEncoder().encodeToString(referenceImage.bytes()));
        }
        var generatedBy = "flux-local:" + shortName(ImageModelManager.configuredModel());

        int timeoutS = ConfigService.getInt("imagegen.local.generateTimeoutSeconds", 300);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/generate")
                .post(RequestBody.create(root.toString(), JSON))
                .build());
        call.timeout().timeout(timeoutS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (resp.code() == 409) {
                throw new ImageGenerationException(
                        "local image model not downloaded — download it in Settings → Image Generation");
            }
            if (!resp.isSuccessful()) {
                var body = resp.body().string();
                throw new ImageGenerationException("flux-local generate failed: HTTP %d%s".formatted(
                        resp.code(), body.isEmpty() ? "" : " — " + Strings.truncate(body, 500)));
            }
            var contentType = resp.header("Content-Type", "image/png");
            var mimeType = contentType.split(";")[0].trim();
            var bytes = resp.body().bytes();
            return new GeneratedImage(bytes, mimeType, generatedBy);
        } catch (IOException e) {
            throw new ImageGenerationException("flux-local generate transport failed: " + e.getMessage(), e);
        }
    }

    private static String shortName(String repo) {
        if (repo == null || repo.isBlank()) return "flux";
        int slash = repo.lastIndexOf('/');
        return slash >= 0 ? repo.substring(slash + 1) : repo;
    }
}
