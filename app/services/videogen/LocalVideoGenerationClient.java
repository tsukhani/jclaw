package services.videogen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import utils.HttpFactories;

import java.io.IOException;

/**
 * Local video-generation client (JCLAW-232 WAN / JCLAW-233 LTX) — drives the Python sidecar's async
 * protocol (SV-3 / JCLAW-512) behind the SAME {@link VideoGenerationService} contract as the Replicate
 * cloud client (JCLAW-231). That symmetry is the whole point: the runner (JCLAW-230) and storage path
 * (JCLAW-234) treat local and cloud identically — {@link #submit} returns a job id, {@link #poll}
 * returns state+percent, and on success {@code resultUrl} is the sidecar's {@code /jobs/<id>/result},
 * which the runner fetches and fills into the placeholder verbatim, exactly as it does for a cloud URL.
 *
 * <p>The only behavioural difference from cloud is upside: {@link #poll} carries a real {@code percent}
 * (from the sidecar's diffusion step callback), whereas cloud reports {@code null}.
 *
 * <p>Parameterised by the engine model id ({@code ltx} / {@code wan-5b} / {@code wan-14b}); each
 * {@link #submit} first {@link LocalVideoSidecarManager#ensureRunning(String) ensures} the sidecar is up
 * on that engine, restarting it if a different engine was previously active.
 */
public class LocalVideoGenerationClient implements VideoGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int DEFAULT_FPS = 24; // sidecar default when the request doesn't specify fps

    // Sidecar poll-response JSON keys.
    private static final String KEY_STATE = "state";
    private static final String KEY_PERCENT = "percent";
    private static final String KEY_ERROR = "error";

    private final String model;
    private final OkHttpClient client;
    private final String baseUrlOverride; // tests inject a mock server; null in prod -> resolve dynamically

    public LocalVideoGenerationClient(String model) {
        this(model, HttpFactories.general(), null);
    }

    /** Test seam: {@code baseUrlOverride} points at a mock server so no real {@code uv} sidecar spawns.
     *  Public only because jclaw's tests live in the default package and can't reach package-private ctors. */
    public LocalVideoGenerationClient(String model, OkHttpClient client, String baseUrlOverride) {
        this.model = model;
        this.client = client;
        this.baseUrlOverride = baseUrlOverride;
    }

    /** Base URL for {@link #submit}: ensures the sidecar is up on {@code model} (prod) or the injected
     *  mock server (tests). */
    private String submitBase() {
        return baseUrlOverride != null ? baseUrlOverride : LocalVideoSidecarManager.ensureRunning(model);
    }

    /** Base URL for {@link #poll}: the already-running sidecar (prod) or the injected mock server (tests). */
    private String pollBase() {
        return baseUrlOverride != null ? baseUrlOverride : LocalVideoSidecarManager.baseUrl();
    }

    @Override
    public String submit(VideoGenRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new VideoGenerationException("video generation: prompt is required");
        }
        var base = submitBase();
        var payload = new JsonObject();
        payload.addProperty("prompt", request.prompt());
        // Duration + fps are independent knobs: the clip is exported at fps, and num_frames = duration*fps.
        int fps = (request.fps() != null && request.fps() > 0) ? request.fps() : DEFAULT_FPS;
        payload.addProperty("fps", fps);
        if (request.durationSeconds() != null && request.durationSeconds() > 0) {
            payload.addProperty("num_frames", request.durationSeconds() * fps);
        }
        // Aspect ratio -> base width x height (landscape / portrait / square). The sidecar snaps to each
        // model's constraints (e.g. multiples of 64 for LTX-2), so the final ratio is approximate. When
        // unset, the sidecar uses its own default (landscape).
        var dims = dimsForAspect(request.aspectRatio());
        if (dims != null) {
            payload.addProperty("width", dims[0]);
            payload.addProperty("height", dims[1]);
        }
        var httpReq = new Request.Builder()
                .url(base + "/jobs")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (var resp = client.newCall(httpReq).execute()) {
            var body = resp.body().string();
            if (resp.code() == 409) {
                throw new VideoGenerationException("local video sidecar is busy (one job at a time) — retry shortly");
            }
            if (resp.code() == 400) {
                // The free-VRAM gate (SV-2): the runner turns this into a FAILED job, which the
                // generate_video tool can surface so the user can switch to the cloud provider.
                throw new VideoGenerationException("local video generation rejected (likely insufficient free VRAM): "
                        + truncate(body, 300));
            }
            if (!resp.isSuccessful()) {
                throw new VideoGenerationException("local video submit failed: HTTP " + resp.code() + " " + truncate(body, 200));
            }
            var id = JsonParser.parseString(body).getAsJsonObject().get("job_id");
            if (id == null || id.isJsonNull()) {
                throw new VideoGenerationException("local video submit returned no job_id: " + truncate(body, 200));
            }
            return id.getAsString();
        } catch (IOException e) {
            throw new VideoGenerationException("local video submit transport failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PollResult poll(String providerJobId) {
        // Poll hits the live sidecar directly (no ensureRunning): the idle watcher never evicts while a
        // job is active, and on success the runner fetches the result within the same tick — well inside
        // the idle window — so the process is still up to serve /jobs/<id>/result.
        var base = pollBase();
        var httpReq = new Request.Builder().url(base + "/jobs/" + providerJobId).get().build();
        try (var resp = client.newCall(httpReq).execute()) {
            var body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new VideoGenerationException("local video poll failed: HTTP " + resp.code() + " " + truncate(body, 200));
            }
            var j = JsonParser.parseString(body).getAsJsonObject();
            var state = j.has(KEY_STATE) && !j.get(KEY_STATE).isJsonNull() ? j.get(KEY_STATE).getAsString() : "";
            Integer percent = j.has(KEY_PERCENT) && !j.get(KEY_PERCENT).isJsonNull() ? j.get(KEY_PERCENT).getAsInt() : null;
            return switch (state) {
                case "succeeded" -> PollResult.succeeded(base + "/jobs/" + providerJobId + "/result");
                case "failed" -> PollResult.failed(
                        j.has(KEY_ERROR) && !j.get(KEY_ERROR).isJsonNull() ? j.get(KEY_ERROR).getAsString()
                                : "local video generation failed");
                default -> PollResult.running(percent); // running / queued
            };
        } catch (IOException e) {
            throw new VideoGenerationException("local video poll transport failed: " + e.getMessage(), e);
        }
    }

    /** Map the tool's aspect_ratio enum to a base width x height (~480p short side); null = let the
     *  sidecar use its default landscape resolution. */
    private static int[] dimsForAspect(String aspect) {
        if (aspect == null) return null;
        return switch (aspect) {
            case "16:9" -> new int[]{832, 480}; // landscape
            case "9:16" -> new int[]{480, 832}; // portrait
            case "1:1" -> new int[]{512, 512};  // square
            default -> null;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
