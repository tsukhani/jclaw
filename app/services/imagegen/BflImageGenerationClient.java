package services.imagegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import services.BflModelCatalog;
import services.BflModelCatalog.RequestShape;
import services.BflTasks;
import services.ConfigService;
import utils.HttpFactories;
import utils.Strings;
import utils.TikaHolder;

import java.io.IOException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Black Forest Labs (Flux) image-generation client (JCLAW-225). BFL's API is asynchronous even on the
 * simplest endpoints: a POST submits the job and returns a polling URL, which is polled until the
 * result is ready, then the produced image is fetched from a short-lived signed URL. Auth is the
 * {@code x-key} header (not Bearer), and the result arrives as a URL rather than inline base64 — so
 * this does NOT extend {@link OpenAiCompatibleImageGenerationClient}.
 *
 * <p>Configuration: {@code provider.bfl.baseUrl} (default {@code https://api.bfl.ai/v1}),
 * {@code provider.bfl.apiKey}, {@code imagegen.bfl.model} (default {@code flux-2-pro}),
 * {@code imagegen.timeoutSeconds} (poll deadline, default 60). The submit/poll transport is
 * {@link BflTasks}, shared with the BFL video client. Each request is built from the fields BFL
 * publishes for the chosen model ({@link BflModelCatalog#requestShape}), since models differ in how
 * they take a size and a reference image, and the FLUX 3 ones refuse any field they do not list.
 */
public class BflImageGenerationClient implements ImageGenerationService {

    private static final String DEFAULT_MODEL = "flux-2-pro";
    private static final long POLL_INTERVAL_MS = 1000L;
    /** The FLUX 3 {@code aspect_ratio} enum, also used for the models that take a free-form ratio in 21:9..9:21. */
    private static final List<String> ASPECT_RATIOS = List.of(
            "21:9", "2:1", "16:9", "3:2", "7:5", "4:3", "5:4", "1:1", "4:5", "3:4", "5:7", "2:3", "9:16", "1:2", "9:21");
    // When the published spec is out of reach: FLUX 3 sizes by ratio and takes an images array,
    // FLUX.1/.2 take pixels and an input_image.
    private static final RequestShape FLUX3_FALLBACK =
            new RequestShape(Set.of("prompt", "aspect_ratio", "images"), ASPECT_RATIOS);
    private static final RequestShape PIXELS_FALLBACK =
            new RequestShape(Set.of("prompt", "width", "height", "input_image"), List.of());

    private final BflTasks tasks;
    private final Function<String, Optional<RequestShape>> shapes;

    public BflImageGenerationClient() {
        this(HttpFactories.llmSingleShotGuarded(), BflModelCatalog::requestShape);
    }

    /** Test seam — inject a MockWebServer-backed client; requests take the fallback shapes. */
    public BflImageGenerationClient(OkHttpClient client) {
        this(client, _ -> Optional.empty());
    }

    /** Test seam — inject the transport and the published request shapes. */
    public BflImageGenerationClient(OkHttpClient client, Function<String, Optional<RequestShape>> shapes) {
        this.tasks = new BflTasks(client);
        this.shapes = shapes;
    }

    @Override
    public GeneratedImage generate(String prompt, @Nullable String model, @Nullable Integer width,
                                   @Nullable Integer height) {
        return generate(prompt, model, width, height, null);
    }

    /**
     * JCLAW-695: FLUX.2 does image-to-image on the same endpoint via an {@code input_image}
     * field (base64 or URL) — no separate editing endpoint. When {@code referenceImage} is
     * present, the configured model (default flux-2-pro; flux-kontext-pro/max for stronger
     * style transfer) restyles/uses it for consistency; when null this is plain text-to-image.
     */
    @Override
    public GeneratedImage generate(String prompt, @Nullable String model, @Nullable Integer width,
                                   @Nullable Integer height,
                                   @Nullable ReferenceImage referenceImage) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageGenerationException("image generation: prompt is required");
        }
        var baseUrl = ConfigService.get("provider.bfl.baseUrl");
        var apiKey = ConfigService.get("provider.bfl.apiKey");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ImageGenerationException("provider.bfl.baseUrl is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ImageGenerationException("provider.bfl.apiKey is not configured");
        }
        var effModel = Strings.firstNonBlankOr(DEFAULT_MODEL, model, ConfigService.get("imagegen.bfl.model"));

        String pollingUrl;
        try {
            pollingUrl = tasks.submit(Strings.trimTrailingSlash(baseUrl), effModel, apiKey,
                    requestBody(shapeFor(effModel), prompt, width, height, referenceImage));
        } catch (BflTasks.BflException e) {
            throw new ImageGenerationException(e.getMessage(), e);
        }
        var sampleUrl = pollUntilReady(pollingUrl, apiKey);
        var bytes = fetchBytes(sampleUrl);
        return new GeneratedImage(bytes, imageMime(bytes), "bfl:" + effModel);
    }

    /** BFL returns JPEG or PNG depending on the model, so the label comes from the bytes. */
    private static String imageMime(byte[] bytes) {
        var detected = TikaHolder.TIKA.detect(bytes);
        return detected.startsWith("image/") ? detected : "image/png";
    }

    private RequestShape shapeFor(String model) {
        return shapes.apply(model).orElse(model.startsWith("flux-3-") ? FLUX3_FALLBACK : PIXELS_FALLBACK);
    }

    /** Sends only fields the model lists, so a field one model needs never reaches one that rejects it. */
    private static JsonObject requestBody(RequestShape shape, String prompt, @Nullable Integer width,
                                          @Nullable Integer height, @Nullable ReferenceImage referenceImage) {
        var fields = shape.fields();
        var root = new JsonObject();
        root.addProperty("prompt", prompt);
        boolean pixels = fields.contains("width") && fields.contains("height");
        boolean ratio = fields.contains("aspect_ratio");
        // A model that can choose its own ratio is left to it unless a size was asked for.
        if (pixels && (!ratio || width != null || height != null)) {
            root.addProperty("width", width != null ? width : 1024);
            root.addProperty("height", height != null ? height : 1024);
        } else if (ratio) {
            var aspect = nearestAspectRatio(shape.aspectRatios().isEmpty() ? ASPECT_RATIOS : shape.aspectRatios(),
                    width, height);
            if (aspect != null) root.addProperty("aspect_ratio", aspect);
        }
        if (referenceImage != null && referenceImage.bytes() != null && referenceImage.bytes().length > 0) {
            // Turns the request into image-to-image / style transfer against the same model + poll path.
            var encoded = Base64.getEncoder().encodeToString(referenceImage.bytes());
            if (fields.contains("input_image")) {
                root.addProperty("input_image", encoded);
            } else if (fields.contains("images")) {
                var images = new JsonArray();
                images.add(encoded);
                root.add("images", images);
            } else if (fields.contains("image_prompt")) {
                root.addProperty("image_prompt", encoded);
            }
        }
        return root;
    }

    /** The ratio in {@code ratios} closest to {@code width:height}; null when either is unset. */
    private static @Nullable String nearestAspectRatio(List<String> ratios, @Nullable Integer width,
                                                       @Nullable Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) return null;
        double target = Math.log((double) width / height);
        return ratios.stream()
                .filter(r -> r.matches("\\d+:\\d+"))
                .min(Comparator.comparingDouble(r -> Math.abs(Math.log(ratio(r)) - target)))
                .orElse(null);
    }

    private static double ratio(String label) {
        var parts = label.split(":");
        return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
    }

    private String pollUntilReady(String pollingUrl, String apiKey) {
        long timeoutMs = ConfigService.getInt("imagegen.timeoutSeconds", 60) * 1000L;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            var sampleUrl = pollOnce(pollingUrl, apiKey);
            if (sampleUrl != null) {
                return sampleUrl;
            }
            if (System.nanoTime() >= deadline) {
                throw new ImageGenerationException("bfl generation timed out after " + (timeoutMs / 1000) + "s");
            }
            sleepBetweenPolls();
        }
    }

    /** One poll: the sample URL once the job is Ready, or {@code null} while it is still pending. Throws on a failure. */
    private @Nullable String pollOnce(String pollingUrl, String apiKey) {
        BflTasks.Poll poll;
        try {
            poll = tasks.poll(pollingUrl, apiKey);
        } catch (BflTasks.BflException e) {
            throw new ImageGenerationException(e.getMessage(), e);
        }
        return switch (poll.state()) {
            case READY -> {
                var sample = poll.sampleUrl();
                if (sample == null) throw new ImageGenerationException("bfl result Ready but no sample URL");
                yield sample;
            }
            case FAILED -> throw new ImageGenerationException(String.valueOf(poll.error()));
            case PENDING -> null;
        };
    }

    private static void sleepBetweenPolls() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("bfl generation interrupted", e);
        }
    }

    private byte[] fetchBytes(String imageUrl) {
        var req = new Request.Builder().url(imageUrl).get().build();
        try (var resp = HttpFactories.general().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new ImageGenerationException("bfl image fetch failed: HTTP " + resp.code());
            }
            return resp.body().bytes();
        } catch (IOException e) {
            throw new ImageGenerationException("bfl image fetch transport failed: " + e.getMessage(), e);
        }
    }

}
