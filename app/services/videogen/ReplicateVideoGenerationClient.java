package services.videogen;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import services.ConfigService;
import services.ReplicatePredictions;
import utils.HttpFactories;
import utils.Strings;

/**
 * Replicate video-generation client (JCLAW-231). Replicate runs hosted video models (WAN 2.x, LTX)
 * behind the same async predictions API as {@code ReplicateImageGenerationClient}, but video takes
 * minutes, so this client deliberately does <b>not</b> send {@code Prefer: wait} (which only blocks
 * ~60s): {@link #submit} creates the prediction and returns its id immediately, and the
 * {@code VideoGenerationJobRunner} (JCLAW-230) drives {@link #poll} — a {@code GET /predictions/{id}}
 * — until the prediction reaches a terminal status. A succeeded prediction's {@code output} is an mp4
 * URL (or array; first taken) handed to the storage path (JCLAW-234) to fetch.
 *
 * <p>The create/poll transport is shared with the image client via {@link ReplicatePredictions}
 * (JCLAW-708); this client owns the video-specific input-building and status→{@link PollResult}
 * mapping.
 *
 * <p>Config: {@code provider.replicate.baseUrl} (default {@code https://api.replicate.com/v1}),
 * {@code provider.replicate.apiKey}, and {@code videogen.cloud.model} (the {@code owner/model} slug,
 * e.g. {@code wan-video/wan-2.2-t2v-fast} or {@code lightricks/ltx-video}). Only {@code prompt} is sent
 * as input here — per-model param names (num_frames, aspect_ratio, …) vary across Replicate models, so
 * the tool layer (JCLAW-235) maps duration/aspect onto each model's schema; defaults apply otherwise.
 */
public class ReplicateVideoGenerationClient implements VideoGenerationService {

    private static final String DEFAULT_BASE = "https://api.replicate.com/v1";
    private static final String DEFAULT_MODEL = "wan-video/wan-2.2-t2v-fast";
    private static final String STATUS = "status";

    private final ReplicatePredictions predictions;

    public ReplicateVideoGenerationClient() {
        this(HttpFactories.general());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public ReplicateVideoGenerationClient(OkHttpClient client) {
        this.predictions = new ReplicatePredictions(client);
    }

    @Override
    public String submit(VideoGenRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new VideoGenerationException("video generation: prompt is required");
        }
        var apiKey = requireConfig("provider.replicate.apiKey");
        var model = Strings.firstNonBlank(request.model(), ConfigService.get("videogen.cloud.model"), DEFAULT_MODEL);

        var input = new JsonObject();
        input.addProperty("prompt", request.prompt());
        JsonObject prediction;
        try {
            prediction = predictions.create(baseUrl(), model, apiKey, input, false); // async — no Prefer: wait
        } catch (ReplicatePredictions.ReplicateException e) {
            if (e.isTransport()) {
                throw new VideoGenerationException("replicate submit transport failed: " + e.getCause().getMessage(), e.getCause());
            }
            var body = e.body();
            throw new VideoGenerationException("replicate submit failed: HTTP %d%s".formatted(
                    e.code(), body.isEmpty() ? "" : " — " + Strings.truncate(body, Strings.ERROR_SNIPPET_MAX_CHARS)));
        }
        var id = prediction.get("id");
        if (id == null || id.isJsonNull() || id.getAsString().isBlank()) {
            throw new VideoGenerationException("replicate submit returned no prediction id");
        }
        return id.getAsString();
    }

    @Override
    public PollResult poll(String providerJobId) {
        var apiKey = requireConfig("provider.replicate.apiKey");
        JsonObject pred;
        try {
            pred = predictions.get(baseUrl() + "/predictions/" + providerJobId, apiKey);
        } catch (ReplicatePredictions.ReplicateException e) {
            if (e.isTransport()) {
                throw new VideoGenerationException("replicate poll transport failed: " + e.getCause().getMessage(), e.getCause());
            }
            throw new VideoGenerationException("replicate poll failed: HTTP " + e.code());
        }
        var status = pred.has(STATUS) && !pred.get(STATUS).isJsonNull() ? pred.get(STATUS).getAsString() : "";
        return switch (status.toLowerCase()) {
            case "succeeded" -> {
                var url = ReplicatePredictions.firstOutputUrl(pred);
                yield url == null
                        ? PollResult.failed("replicate succeeded but produced no output")
                        : PollResult.succeeded(url);
            }
            case "failed", "canceled" -> PollResult.failed(ReplicatePredictions.extractError(pred, status));
            // starting / processing — Replicate exposes no reliable percent for video.
            default -> PollResult.running(null);
        };
    }

    private static String baseUrl() {
        return Strings.trimTrailingSlash(Strings.firstNonBlank(ConfigService.get("provider.replicate.baseUrl"), DEFAULT_BASE));
    }

    private static String requireConfig(String key) {
        var v = ConfigService.get(key);
        if (v == null || v.isBlank()) throw new VideoGenerationException(key + " is not configured");
        return v;
    }

}
