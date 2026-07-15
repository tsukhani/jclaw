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
import services.transcription.AsrModel;
import services.transcription.AsrModelStore;
import services.transcription.DiarizeModelStore;
import services.transcription.FfmpegProbe;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Transcription Settings UI backend (JCLAW-164). Endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/transcription/state} — snapshot of the configured
 *       backend, the local model selection, ffmpeg availability, and the
 *       per-model download/verify status. The Settings page polls this
 *       every couple of seconds while a download is in flight, then stops
 *       once every model has settled to AVAILABLE / ABSENT / ERROR.</li>
 *   <li>{@code POST /api/transcription/models/{id}/download} — kicks off a
 *       background download via {@link WhisperModelManager#ensureAvailable}.
 *       Returns immediately with {@code {status: "downloading"}}; progress
 *       is observed through the polling endpoint above.</li>
 * </ul>
 *
 * <p>Writes to {@code transcription.provider} and {@code transcription.localModel}
 * use the existing {@code POST /api/config} endpoint — no new write path
 * here. The Settings page's existing config-write helper covers it.
 */
@With(AuthCheck.class)
public class ApiTranscriptionController extends Controller {

    private static final Gson gson = INSTANCE;

    public record AsrModelEntry(String id, String displayName, int approxSizeMb, String status, long bytesDownloaded, long totalBytes, String engine, String error) {}

    public record TranscriptionStateResponse(String provider, String localModel,
                                             boolean ffmpegAvailable, String ffmpegReason,
                                             List<AsrModelEntry> models) {}

    public record DownloadStartedResponse(String status, String modelId) {}

    /** One on-device diarization weight for the Settings UI. {@code role} is
     *  {@code "diarizer"} (pyannote) or {@code "emotion"} (SER), so the frontend
     *  renders each next to its control. No {@code totalBytes} — repo sizes are
     *  unknown/free-text, so the UI shows a downloaded-MB counter. */
    public record DiarizeModelEntry(String repo, String displayName, String role,
                                    String status, long bytesDownloaded, String engine, String error) {}

    public record DiarizationModelsResponse(List<DiarizeModelEntry> models,
                                            List<DiarizeModelStore.SerOption> serOptions) {}

    /** GET /api/transcription/state — snapshot for the Settings UI. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TranscriptionStateResponse.class)))
    @Operation(summary = "Snapshot transcription config, ffmpeg availability, and per-Whisper-model download status")
    public static void state() {
        var ffmpeg = FfmpegProbe.lastResult();
        // Force one probe if the cache is still on the UNRUN sentinel —
        // DefaultConfigJob primes it on startup, but a hot-reloaded dev
        // server may not have run that yet.
        if (!ffmpeg.available() && ffmpeg.reason() != null
                && ffmpeg.reason().startsWith("ffmpeg probe has not run")) {
            ffmpeg = FfmpegProbe.probe();
        }

        // JCLAW-650: status comes from the sidecar's HOST engine (mlx on
        // Apple silicon, faster-whisper CT2 elsewhere) — the artifact the
        // Download button provisions is the one that actually runs.
        var statuses = AsrModelStore.statusAll();
        var models = new ArrayList<AsrModelEntry>();
        for (var m : AsrModel.values()) {
            var status = statuses.get(m.id());
            models.add(new AsrModelEntry(
                    m.id(),
                    m.displayName(),
                    m.approxSizeMb(),
                    status.state().wireName(),
                    status.bytesDownloaded(),
                    status.totalBytes(),
                    status.engine(),
                    status.error()));
        }

        var payload = new TranscriptionStateResponse(
                ConfigService.get("transcription.provider"),
                ConfigService.get("transcription.localModel"),
                ffmpeg.available(),
                ffmpeg.reason(),
                models);
        renderJSON(gson.toJson(payload));
    }

    /** POST /api/transcription/models/{id}/download — kick off a background
     *  download for the model with the given id. Returns 202-style
     *  {@code {"status":"downloading"}} immediately on success, 400 on
     *  unknown id. */
    // S3655 (Optional.get without isPresent): error(400) above halts via a
    // Result that Sonar can't see across the framework boundary, so the
    // analyzer thinks model.get() can be reached when model.isEmpty(). It
    // can't. Same Play-1.x-vs-Sonar gap that S2259 papers over.
    @SuppressWarnings({"java:S2259", "java:S3655"})
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DownloadStartedResponse.class)))
    @ChatHidden("triggers a Whisper model download -- disk/network resource action")
    public static void download(String id) {
        var model = AsrModel.byId(id);
        if (model.isEmpty()) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Unknown whisper model id: " + id);
        // Single-flight per model id; concurrent calls from the polling UI
        // attach to the same in-flight prefetch, no harm done (JCLAW-650:
        // downloads the HOST engine's weights via the sidecar).
        AsrModelStore.prefetch(model.get());
        EventLogger.info("transcription",
                "ASR model download requested: %s".formatted(id));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", id)));
    }

    /** GET /api/transcription/diarization/models — download status for the
     *  on-device diarization weights (the pyannote diarizer + the configured
     *  SER emotion model). Polled by the Settings page like the ASR models. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DiarizationModelsResponse.class)))
    public static void diarizationModels() {
        var serRepo = DiarizeModelStore.serRepo();
        var statuses = DiarizeModelStore.statusAll(serRepo);
        var models = new ArrayList<DiarizeModelEntry>();
        models.add(entryFor(statuses, DiarizeModelStore.PYANNOTE_REPO, "Speaker diarizer", "diarizer"));
        models.add(entryFor(statuses, serRepo, "Emotion model", "emotion"));
        renderJSON(gson.toJson(new DiarizationModelsResponse(models, DiarizeModelStore.SER_MODELS)));
    }

    private static DiarizeModelEntry entryFor(java.util.Map<String, DiarizeModelStore.Status> statuses,
                                              String repo, String displayName, String role) {
        var s = statuses.get(repo);
        if (s == null) return new DiarizeModelEntry(repo, displayName, role, "UNAVAILABLE", 0, null, null);
        return new DiarizeModelEntry(repo, displayName, role, s.state().wireName(),
                s.bytesDownloaded(), s.engine(), s.error());
    }

    /** POST /api/transcription/diarization/download?repo=&lt;hf-repo&gt; — kick a
     *  background download of a diarization weight (pyannote or the SER model).
     *  Returns immediately; progress is observed through diarizationModels. */
    @SuppressWarnings("java:S2259")  // ApiResponses.error(400) halts; repo is non-null past the guard
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DownloadStartedResponse.class)))
    @ChatHidden("triggers an on-device diarization weight download -- disk/network resource action")
    public static void diarizationDownload(String repo) {
        if (repo == null
                || (!repo.equals(DiarizeModelStore.PYANNOTE_REPO) && !DiarizeModelStore.isAllowedSer(repo))) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "repo must be the pyannote diarizer or one of the selectable SER models: " + repo);
        }
        DiarizeModelStore.prefetch(repo);
        EventLogger.info("transcription", "diarization weight download requested: %s".formatted(repo));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", repo)));
    }
}
