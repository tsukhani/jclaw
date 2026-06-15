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
import services.caption.VlmModel;
import services.caption.VlmModelManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Image Captioning Settings UI backend (JCLAW-214) — the vision counterpart of
 * {@link ApiTranscriptionController}. Drives the two-tier fallback the operator configures for
 * non-vision chat models: a <b>cloud</b> option (preferred) and a <b>local</b> ViT-GPT2 option.
 *
 * <ul>
 *   <li>{@code GET /api/caption/state} — snapshot of the cloud provider/model selection plus the
 *       per-local-model download/verify status. Polled every couple of seconds while a download is
 *       in flight, then stops once every model settles to AVAILABLE / ABSENT / ERROR.</li>
 *   <li>{@code POST /api/caption/models/{id}/download} — kicks off a background download via
 *       {@link VlmModelManager#ensureAvailable}; returns {@code {status:"downloading"}} immediately.</li>
 *   <li>{@code DELETE /api/caption/models/{id}} — removes a downloaded local model (reclaim disk /
 *       disable the local fallback) via {@link VlmModelManager#delete}.</li>
 * </ul>
 *
 * <p>Writes to {@code caption.provider} and {@code caption.model} go through the existing
 * {@code POST /api/config} endpoint — no new write path here. The cloud provider's API key / base URL
 * are the shared {@code provider.{name}.*} keys from the LLM Providers section.
 */
@With(AuthCheck.class)
public class ApiCaptionController extends Controller {

    private static final Gson gson = INSTANCE;

    public record VlmModelEntry(String id, String displayName, int approxSizeMb,
                                String status, long bytesDownloaded, Long totalBytes,
                                String error) {}

    public record CaptionStateResponse(String provider, String model,
                                       List<VlmModelEntry> models) {}

    public record DownloadStartedResponse(String status, String modelId) {}

    public record RemovedResponse(String status, String modelId, boolean removed) {}

    /** GET /api/caption/state — snapshot for the Settings UI. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CaptionStateResponse.class)))
    @Operation(summary = "Snapshot caption provider/model config and per-local-model download status")
    public static void state() {
        var models = new ArrayList<VlmModelEntry>();
        for (var m : VlmModel.values()) {
            var status = VlmModelManager.status(m);
            models.add(new VlmModelEntry(
                    m.id(),
                    m.displayName(),
                    m.approxSizeMb(),
                    status.state().name(),
                    status.bytesDownloaded(),
                    status.totalBytes(),
                    status.error()));
        }

        var payload = new CaptionStateResponse(
                ConfigService.get("caption.provider"),
                ConfigService.get("caption.model"),
                models);
        renderJSON(gson.toJson(payload));
    }

    /** POST /api/caption/models/{id}/download — kick off a background download for the model with
     *  the given id. Returns {@code {"status":"downloading"}} immediately on success, 400 on
     *  unknown id. */
    // S3655: error(400) halts via a Result Sonar can't see across the framework boundary, so the
    // analyzer thinks model.get() is reachable when model.isEmpty(). It isn't (same as the
    // transcription controller).
    @SuppressWarnings({"java:S2259", "java:S3655"})
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DownloadStartedResponse.class)))
    @ChatHidden("triggers a VLM model download -- disk/network resource action")
    public static void download(String id) {
        var model = VlmModel.byId(id);
        if (model.isEmpty()) error(400, "Unknown caption model id: " + id);
        // ensureAvailable is single-flight; concurrent calls from the polling UI all attach to the
        // same in-flight future, no harm done.
        VlmModelManager.ensureAvailable(model.get(), null);
        EventLogger.info("caption", "VLM model download requested: %s".formatted(id));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", id)));
    }

    /** DELETE /api/caption/models/{id} — remove a downloaded local model. */
    @SuppressWarnings({"java:S2259", "java:S3655"})
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RemovedResponse.class)))
    @ChatHidden("deletes a VLM model from disk -- destructive resource action")
    public static void remove(String id) {
        var model = VlmModel.byId(id);
        if (model.isEmpty()) error(400, "Unknown caption model id: " + id);
        boolean removed;
        try {
            removed = VlmModelManager.delete(model.get());
        } catch (IOException e) {
            error(500, "Failed to remove caption model %s: %s".formatted(id, e.getMessage()));
            throw new AssertionError("unreachable: error() throws");
        }
        EventLogger.info("caption", "VLM model removal requested: %s (removed=%b)".formatted(id, removed));
        renderJSON(gson.toJson(new RemovedResponse("removed", id, removed)));
    }
}
