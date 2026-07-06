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
import services.transcription.DiarizedTranscript;
import services.transcription.AsrModelStore;
import services.transcription.DiarizationPipeline;
import services.transcription.FfmpegProbe;
import services.transcription.TranscriptionException;
import services.transcription.WhisperModel;

import java.io.File;
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

    public record WhisperModelEntry(String id, String displayName, int approxSizeMb, String status, long bytesDownloaded, long totalBytes, String engine, String error) {}

    public record TranscriptionStateResponse(String provider, String localModel,
                                             boolean ffmpegAvailable, String ffmpegReason,
                                             List<WhisperModelEntry> models) {}

    public record DownloadStartedResponse(String status, String modelId) {}

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
        var models = new ArrayList<WhisperModelEntry>();
        for (var m : WhisperModel.values()) {
            var status = statuses.get(m.id());
            models.add(new WhisperModelEntry(
                    m.id(),
                    m.displayName(),
                    m.approxSizeMb(),
                    status.state().name(),
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
        var model = WhisperModel.byId(id);
        if (model.isEmpty()) error(400, "Unknown whisper model id: " + id);
        // Single-flight per model id; concurrent calls from the polling UI
        // attach to the same in-flight prefetch, no harm done (JCLAW-650:
        // downloads the HOST engine's weights via the sidecar).
        AsrModelStore.prefetch(model.get());
        EventLogger.info("transcription",
                "ASR model download requested: %s".formatted(id));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", id)));
    }

    /**
     * POST /api/transcription/diarize — speaker-diarized transcription of an
     * uploaded audio file (JCLAW-556). Runs whisper (segment-level) and the
     * pyannote sidecar diarizer over the same audio, merges by temporal overlap,
     * annotates each turn with an acoustic emotion label (JCLAW-563, unless
     * {@code transcription.emotion.enabled} is off), and renders in the
     * requested format.
     *
     * <p>Synchronous by design — the ticket's "single command" contract; a
     * 10-minute file takes on the order of a minute (whisper dominates).
     * Callers script it via curl with the internal API token.
     *
     * @param audio       multipart file upload (any container/codec ffmpeg reads)
     * @param format      json (default) | txt | srt | vtt
     * @param numSpeakers exact speaker count when known; omit to cluster by
     * @param language    ISO 639-1 override for this recording; omit to follow
     *                    the model (auto-detect on multilingual, English on .en)
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DiarizedTranscript.Entry.class)))
    @Operation(summary = "Speaker-diarized transcription of an uploaded audio file (JSON/TXT/SRT/VTT)")
    @ChatHidden("long-running local whisper + diarization inference -- CPU resource action")
    public static void diarize(File audio, String format, Integer numSpeakers, String language) {
        if (audio == null) error(400, "multipart file parameter 'audio' is required");
        var fmt = format == null || format.isBlank() ? "json" : format;
        // Reject a bad format name before paying for a minute of inference.
        if (DiarizedTranscript.format(List.of(), fmt).isEmpty()) {
            error(400, "Unknown format: %s (expected json, txt, srt or vtt)".formatted(fmt));
        }

        var lang = language != null && !language.isBlank() ? language : null;

        List<DiarizedTranscript.Entry> entries;
        long startedAt = System.currentTimeMillis();
        try {
            // JCLAW-628: the stage sequence lives in DiarizationPipeline.
            // The stateless API cannot converse, so identification-first is
            // off and the transcript always renders (anonymous labels when
            // voices are unknown). cacheable=false (JCLAW-636): the multipart
            // temp upload gets a fresh path per request, so a sibling cache
            // file could never hit and would outlive Play's temp cleanup.
            var outcome = DiarizationPipeline.run(audio.toPath(),
                    new DiarizationPipeline.Options(numSpeakers, lang, false, true, false));
            entries = ((DiarizationPipeline.Transcript) outcome).entries();
        } catch (TranscriptionException e) {
            // Operator-fixable preconditions (model not downloaded, ffmpeg
            // absent) and backend failures both surface here; 409 matches the
            // imagegen "weights absent" convention.
            error(409, e.getMessage());
            return; // unreachable — error() throws; keeps the definite-assignment checker happy
        }
        var rendered = DiarizedTranscript.format(entries, fmt)
                .orElseThrow(); // format validated above
        EventLogger.info("transcription",
                "Diarized %s: %d segments in %d ms (format=%s)".formatted(
                        audio.getName(), entries.size(), System.currentTimeMillis() - startedAt, fmt));

        if ("json".equalsIgnoreCase(fmt)) {
            renderJSON(rendered);
        } else {
            response.contentType = "vtt".equalsIgnoreCase(fmt)
                    ? "text/vtt; charset=utf-8"
                    : "text/plain; charset=utf-8";
            renderText(rendered);
        }
    }
}
