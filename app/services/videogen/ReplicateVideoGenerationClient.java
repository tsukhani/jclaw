package services.videogen;

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

/**
 * Replicate video-generation client (JCLAW-231). Replicate runs hosted video models (WAN 2.x, LTX)
 * behind the same async predictions API as {@code ReplicateImageGenerationClient}, but video takes
 * minutes, so this client deliberately does <b>not</b> send {@code Prefer: wait} (which only blocks
 * ~60s): {@link #submit} creates the prediction and returns its id immediately, and the
 * {@code VideoGenerationJobRunner} (JCLAW-230) drives {@link #poll} — a {@code GET /predictions/{id}}
 * — until the prediction reaches a terminal status. A succeeded prediction's {@code output} is an mp4
 * URL (or array; first taken) handed to the storage path (JCLAW-234) to fetch.
 *
 * <p>Config: {@code provider.replicate.baseUrl} (default {@code https://api.replicate.com/v1}),
 * {@code provider.replicate.apiKey}, and {@code videogen.cloud.model} (the {@code owner/model} slug,
 * e.g. {@code wan-video/wan-2.2-t2v-fast} or {@code lightricks/ltx-video}). Only {@code prompt} is sent
 * as input here — per-model param names (num_frames, aspect_ratio, …) vary across Replicate models, so
 * the tool layer (JCLAW-235) maps duration/aspect onto each model's schema; defaults apply otherwise.
 */
public class ReplicateVideoGenerationClient implements VideoGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String DEFAULT_BASE = "https://api.replicate.com/v1";
    private static final String DEFAULT_MODEL = "wan-video/wan-2.2-t2v-fast";
    private static final String STATUS = "status";
    private static final String OUTPUT = "output";
    private static final String ERROR = "error";

    private final OkHttpClient client;

    public ReplicateVideoGenerationClient() {
        this(HttpFactories.general());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public ReplicateVideoGenerationClient(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public String submit(VideoGenRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new VideoGenerationException("video generation: prompt is required");
        }
        var apiKey = requireConfig("provider.replicate.apiKey");
        var model = firstNonBlank(request.model(), ConfigService.get("videogen.cloud.model"), DEFAULT_MODEL);

        var input = new JsonObject();
        input.addProperty("prompt", request.prompt());
        var root = new JsonObject();
        root.add("input", input);
        var httpReq = new Request.Builder()
                .url(baseUrl() + "/models/" + model + "/predictions")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(RequestBody.create(root.toString(), JSON))
                .build();
        try (var resp = client.newCall(httpReq).execute()) {
            var body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new VideoGenerationException("replicate submit failed: HTTP %d%s".formatted(
                        resp.code(), body.isEmpty() ? "" : " — " + truncate(body, 500)));
            }
            var id = JsonParser.parseString(body).getAsJsonObject().get("id");
            if (id == null || id.isJsonNull() || id.getAsString().isBlank()) {
                throw new VideoGenerationException("replicate submit returned no prediction id");
            }
            return id.getAsString();
        } catch (IOException e) {
            throw new VideoGenerationException("replicate submit transport failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PollResult poll(String providerJobId) {
        var apiKey = requireConfig("provider.replicate.apiKey");
        var httpReq = new Request.Builder()
                .url(baseUrl() + "/predictions/" + providerJobId)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .get().build();
        try (var resp = client.newCall(httpReq).execute()) {
            var body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new VideoGenerationException("replicate poll failed: HTTP " + resp.code());
            }
            var pred = JsonParser.parseString(body).getAsJsonObject();
            var status = pred.has(STATUS) && !pred.get(STATUS).isJsonNull() ? pred.get(STATUS).getAsString() : "";
            return switch (status.toLowerCase()) {
                case "succeeded" -> {
                    var url = extractUrl(pred);
                    yield url == null
                            ? PollResult.failed("replicate succeeded but produced no output")
                            : PollResult.succeeded(url);
                }
                case "failed", "canceled" -> PollResult.failed(extractError(pred, status));
                // starting / processing — Replicate exposes no reliable percent for video.
                default -> PollResult.running(null);
            };
        } catch (IOException e) {
            throw new VideoGenerationException("replicate poll transport failed: " + e.getMessage(), e);
        }
    }

    /** {@code output} is an mp4 URL string or an array of URL strings — take the first. */
    private static String extractUrl(JsonObject pred) {
        if (!pred.has(OUTPUT) || pred.get(OUTPUT).isJsonNull()) return null;
        var output = pred.get(OUTPUT);
        if (output.isJsonArray()) {
            JsonArray arr = output.getAsJsonArray();
            return arr.isEmpty() ? null : arr.get(0).getAsString();
        }
        return output.getAsString();
    }

    private static String extractError(JsonObject pred, String status) {
        if (pred.has(ERROR) && !pred.get(ERROR).isJsonNull()) {
            return "replicate " + status + ": " + pred.get(ERROR).getAsString();
        }
        return "replicate prediction " + status;
    }

    private static String baseUrl() {
        return trimTrailingSlash(firstNonBlank(ConfigService.get("provider.replicate.baseUrl"), DEFAULT_BASE));
    }

    private static String requireConfig(String key) {
        var v = ConfigService.get(key);
        if (v == null || v.isBlank()) throw new VideoGenerationException(key + " is not configured");
        return v;
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
