package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.EventLogger;
import services.transcription.FfmpegProbe;
import services.transcription.WhisperModel;
import services.transcription.WhisperModelManager;

import java.util.ArrayList;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Transcription Settings UI backend (JCLAW-164). Two endpoints:
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

    public record WhisperModelEntry(String id, String displayName, int approxSizeMb,
                                    String status, long bytesDownloaded, Long totalBytes,
                                    String error) {}

    public record TranscriptionStateResponse(String provider, String localModel,
                                             boolean ffmpegAvailable, String ffmpegReason,
                                             List<WhisperModelEntry> models) {}

    public record DownloadStartedResponse(String status, String modelId) {}

    /** GET /api/transcription/state — snapshot for the Settings UI. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TranscriptionStateResponse.class)))
    public static void state() {
        var ffmpeg = FfmpegProbe.lastResult();
        // Force one probe if the cache is still on the UNRUN sentinel —
        // DefaultConfigJob primes it on startup, but a hot-reloaded dev
        // server may not have run that yet.
        if (!ffmpeg.available() && ffmpeg.reason() != null
                && ffmpeg.reason().startsWith("ffmpeg probe has not run")) {
            ffmpeg = FfmpegProbe.probe();
        }

        var models = new ArrayList<WhisperModelEntry>();
        for (var m : WhisperModel.values()) {
            var status = WhisperModelManager.status(m);
            models.add(new WhisperModelEntry(
                    m.id(),
                    m.displayName(),
                    m.approxSizeMb(),
                    status.state().name(),
                    status.bytesDownloaded(),
                    status.totalBytes(),
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
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DownloadStartedResponse.class)))
    public static void download(String id) {
        var model = WhisperModel.byId(id);
        if (model.isEmpty()) {
            // Play's error() throws a Result internally, but the static
            // analyzer can't see that — explicit return makes the flow
            // legible and lets the model.get() below be unambiguously safe.
            error(400, "Unknown whisper model id: " + id);
            return;
        }
        // ensureAvailable is single-flight; concurrent calls from the
        // polling UI all attach to the same in-flight future, no harm done.
        WhisperModelManager.ensureAvailable(model.get(), null);
        EventLogger.info("transcription",
                "Whisper model download requested: %s".formatted(id));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", id)));
    }
}
