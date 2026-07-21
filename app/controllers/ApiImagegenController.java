package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.EventLogger;
import services.UvProbe;
import services.imagegen.ImageCapabilityProbe;
import services.imagegen.ImageModelManager;
import services.imagegen.LocalImageSidecarManager;
import services.imagegen.ReplicateImageModelCatalog;

import java.util.LinkedHashMap;

import static utils.GsonHolder.GSON;

/**
 * Local image-generation Settings UI backend (JCLAW-226), the producing analogue
 * of {@link ApiTranscriptionController}. Two endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/imagegen/local/state} — snapshot of the configured local
 *       model, {@code uv} availability (the sidecar prerequisite), and the model
 *       download status. The Settings page polls this while a pull is in flight,
 *       then stops once the model settles to AVAILABLE / ABSENT / ERROR.</li>
 *   <li>{@code POST /api/imagegen/local/pull} — kicks off a background weight
 *       download via {@link ImageModelManager#ensureAvailable} (which launches the
 *       sidecar on demand and drives its {@code /pull}). Returns immediately;
 *       progress is observed through the polling endpoint above.</li>
 * </ul>
 *
 * <p>Writes to {@code imagegen.provider} (selecting {@code flux-local}) and
 * {@code imagegen.local.model} go through the existing {@code POST /api/config}
 * endpoint — no new write path here.
 */
@With(AuthCheck.class)
public class ApiImagegenController extends Controller {

    private static final Gson gson = GSON;

    public record ImagegenLocalStateResponse(String provider, String model,
                                             boolean uvAvailable, String uvReason,
                                             String modelStatus, long bytesDownloaded,
                                             long totalBytes, String error) {}

    public record DownloadStartedResponse(String status, String model) {}

    /** GET /api/imagegen/local/state — snapshot for the Settings UI. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ImagegenLocalStateResponse.class)))
    @Operation(summary = "Snapshot local image-gen config, uv availability, and the local image model download status")
    public static void state() {
        var uv = UvProbe.lastResult();
        // Force one probe if the cache is still on the UNRUN sentinel — DefaultConfigJob
        // primes it on startup, but a hot-reloaded dev server may not have run that yet.
        if (!uv.available() && uv.reason() != null && uv.reason().startsWith("uv probe has not run")) {
            uv = UvProbe.probe();
        }

        var model = ImageModelManager.configuredModel();
        var status = ImageModelManager.status(model);

        var payload = new ImagegenLocalStateResponse(
                ConfigService.get("imagegen.provider"),
                model,
                uv.available(),
                uv.reason(),
                status.state().name(),
                status.bytesDownloaded(),
                status.totalBytes(),
                status.error());
        renderJSON(gson.toJson(payload));
    }

    /** POST /api/imagegen/local/pull — kick off a background download of the
     *  configured local model. Returns 202-style {@code {"status":"downloading"}}
     *  immediately; progress is observed via {@link #state}. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DownloadStartedResponse.class)))
    @ChatHidden("triggers a local image model download -- disk/network/GPU resource action")
    public static void pull() {
        var model = ImageModelManager.configuredModel();
        // ensureAvailable is single-flight; concurrent calls from the polling UI
        // all attach to the same in-flight pull, no harm done.
        ImageModelManager.ensureAvailable(model, null);
        EventLogger.info("imagegen", "image model download requested: %s".formatted(model));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", model)));
    }

    /** GET /api/imagegen/models — selectable Replicate models for the Settings dropdown: discovered
     *  text-to-image models plus the curated image-to-image (Kontext) set, each tagged with an
     *  {@code imageToImage} flag so the UI can group them. Empty when no Replicate API key is set or
     *  discovery fails; the UI degrades to "no models". */
    @Operation(summary = "List selectable Replicate image models (text-to-image + Kontext image-to-image)")
    public static void models() {
        renderJSON(gson.toJson(ReplicateImageModelCatalog.availableModels()));
    }

    /** GET /api/imagegen/progress — live step-progress of an in-flight LOCAL image generation, polled by
     *  the chat to drive a determinate bar. {@code percent} is null when the local sidecar is down or
     *  idle, or when the active provider is cloud (no per-step progress — like the video cloud path). */
    @Operation(summary = "Live step-progress of an in-flight local image generation (chat bar)")
    public static void progress() {
        var m = new LinkedHashMap<String, Object>();
        m.put("percent", LocalImageSidecarManager.currentProgressPercent());
        renderJSON(gson.toJson(m));
    }

    /** GET /api/imagegen/capability — adaptive host-capability snapshot for the Settings Self-Hosted gate
     *  (GPU + free VRAM + whether local Flux can run). The page polls this while PROBING, then disables
     *  the Self-Hosted radio when the host can't run it. */
    @Operation(summary = "Local image-gen host capability (GPU, free VRAM, whether Flux can run)")
    public static void capability() {
        renderJSON(gson.toJson(ImageCapabilityProbe.snapshot()));
    }

    /** POST /api/imagegen/capability/probe — kick off a background GPU/VRAM probe (one-shot
     *  {@code uv run serve.py --probe}). Returns immediately; progress is observed via {@link #capability}. */
    @Operation(summary = "Start a background local image-gen capability probe")
    @ChatHidden("runs a GPU capability subprocess -- resource action")
    public static void probeCapability() {
        ImageCapabilityProbe.probe();
        var m = new LinkedHashMap<String, Object>();
        m.put("status", "probing");
        renderJSON(gson.toJson(m));
    }
}
