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
import services.imagegen.FluxModelManager;
import services.imagegen.FluxSidecarProbe;

import static utils.GsonHolder.INSTANCE;

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
 *       download via {@link FluxModelManager#ensureAvailable} (which launches the
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

    private static final Gson gson = INSTANCE;

    public record ImagegenLocalStateResponse(String provider, String model,
                                             boolean uvAvailable, String uvReason,
                                             String modelStatus, long bytesDownloaded,
                                             long totalBytes, String error) {}

    public record DownloadStartedResponse(String status, String model) {}

    /** GET /api/imagegen/local/state — snapshot for the Settings UI. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ImagegenLocalStateResponse.class)))
    @Operation(summary = "Snapshot local image-gen config, uv availability, and the Flux model download status")
    public static void state() {
        var uv = FluxSidecarProbe.lastResult();
        // Force one probe if the cache is still on the UNRUN sentinel — DefaultConfigJob
        // primes it on startup, but a hot-reloaded dev server may not have run that yet.
        if (!uv.available() && uv.reason() != null && uv.reason().startsWith("uv probe has not run")) {
            uv = FluxSidecarProbe.probe();
        }

        var model = FluxModelManager.configuredModel();
        var status = FluxModelManager.status(model);

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
    @ChatHidden("triggers a Flux model download -- disk/network/GPU resource action")
    public static void pull() {
        var model = FluxModelManager.configuredModel();
        // ensureAvailable is single-flight; concurrent calls from the polling UI
        // all attach to the same in-flight pull, no harm done.
        FluxModelManager.ensureAvailable(model, null);
        EventLogger.info("imagegen", "Flux model download requested: %s".formatted(model));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", model)));
    }
}
