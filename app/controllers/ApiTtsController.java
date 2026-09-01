package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.data.Upload;
import play.mvc.Controller;
import play.mvc.SseStream;
import play.mvc.With;
import services.ConfigService;
import services.EventLogger;
import services.UvProbe;
import services.tts.TtsEngine;
import services.tts.TtsException;
import services.tts.TtsJvmEngine;
import services.tts.TtsModel;
import services.tts.TtsReferenceVoice;
import services.tts.TtsRouter;
import services.tts.TtsSentenceChunker;
import services.tts.TtsSidecarManager;
import services.tts.TtsText;
import services.tts.TtsVoiceCatalog;
import utils.ApiResponses;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static utils.GsonHolder.GSON;

/**
 * Text-to-speech / read-aloud Settings + playback backend (JCLAW-789/793).
 *
 * <ul>
 *   <li>{@code GET /api/tts/state} — snapshot of both engines (Sidecar vs
 *       JVM-native) for the Settings &gt; Speech panel: which is selected, each
 *       engine's availability + status, its selected model, and its model list
 *       with on-disk readiness (JVM) — mirrors {@code /api/transcription/state}.</li>
 *   <li>{@code POST /api/tts/synthesize} — read-aloud: text in, {@code
 *       audio/wav} bytes out, produced by whichever engine the operator
 *       selected.</li>
 *   <li>{@code POST /api/tts/models/{id}/download} — background-provision a
 *       JVM-native model's weights (sidecar weights are pulled by the sidecar on
 *       first use).</li>
 * </ul>
 *
 * <p>Writes to {@code tts.engine} / {@code tts.<engine>.model} go through the
 * existing {@code POST /api/config} endpoint — no new write path here.
 */
@With(AuthCheck.class)
public class ApiTtsController extends Controller {

    private static final Gson gson = GSON;

    /**
     * @param supportsCloning true for models that pick their speaker from a
     *                        reference clip rather than a named preset (JCLAW-865).
     *                        These are exactly the models with an empty
     *                        {@code voices} list, so the UI shows a clip upload
     *                        where it would otherwise show nothing at all.
     */
    public record TtsModelEntry(String id, String displayName, int approxSizeMb,
                                boolean present, boolean downloading,
                                List<TtsVoiceCatalog.Voice> voices,
                                boolean supportsCloning) {}

    public record TtsEngineEntry(String id, String displayName, boolean available,
                                 String status, String model, List<TtsModelEntry> models) {}

    /**
     * @param referenceVoice filename of the active cloning clip, or null. Only the
     *                       basename is exposed — the absolute path is a local
     *                       filesystem detail the browser has no use for.
     */
    public record TtsStateResponse(String engine, List<TtsEngineEntry> engines, String referenceVoice) {}

    public record ReferenceVoiceResponse(String status, String filename) {}

    public record DownloadStartedResponse(String status, String modelId) {}

    /** GET /api/tts/state — snapshot for the Settings &gt; Speech panel. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TtsStateResponse.class)))
    @Operation(summary = "Snapshot both TTS engines, the selected one, and per-model readiness")
    public static void state() {
        var engines = new ArrayList<TtsEngineEntry>();
        engines.add(sidecarEntry());
        engines.add(jvmEntry());
        var refPath = TtsReferenceVoice.activePath(TtsEngine.SIDECAR);
        var refName = refPath == null ? null : Path.of(refPath).getFileName().toString();
        renderJSON(gson.toJson(new TtsStateResponse(TtsRouter.currentEngine().id(), engines, refName)));
    }

    private static TtsEngineEntry sidecarEntry() {
        boolean uv = UvProbe.isAvailable();
        boolean running = TtsSidecarManager.isRunning();
        String status;
        if (running) {
            status = "running";
        } else if (uv) {
            status = "ready — starts on first use";
        } else {
            status = "needs 'uv' on PATH: " + UvProbe.lastResult().reason();
        }
        var models = new ArrayList<TtsModelEntry>();
        for (var m : TtsModel.forEngine(TtsEngine.SIDECAR)) {
            // Sidecar weights live in the sidecar's HF cache, pulled on first use —
            // not disk-tracked here, so "present" tracks whether the sidecar is up.
            models.add(new TtsModelEntry(m.id(), m.displayName(), m.approxSizeMb(), running, false,
                    TtsVoiceCatalog.voicesFor(m.id()), m.supportsCloning()));
        }
        return new TtsEngineEntry(TtsEngine.SIDECAR.id(), TtsEngine.SIDECAR.displayName(),
                uv, status, TtsRouter.modelFor(TtsEngine.SIDECAR), models);
    }

    private static TtsEngineEntry jvmEntry() {
        String selected = TtsRouter.modelFor(TtsEngine.JVM);
        boolean ready = TtsJvmEngine.isModelPresent(selected);
        String status;
        if (ready) {
            status = "ready";
        } else if (TtsJvmEngine.isDownloading(selected)) {
            status = "downloading model";
        } else {
            status = "model downloads on first use";
        }
        var models = new ArrayList<TtsModelEntry>();
        for (var m : TtsModel.forEngine(TtsEngine.JVM)) {
            // JVM models never clone — sherpa-onnx selects a speaker by index, and
            // the two shipped voices are single-speaker or named.
            models.add(new TtsModelEntry(m.id(), m.displayName(), m.approxSizeMb(),
                    TtsJvmEngine.isModelPresent(m.id()), TtsJvmEngine.isDownloading(m.id()),
                    TtsVoiceCatalog.voicesFor(m.id()), m.supportsCloning()));
        }
        // The JVM engine is always "available" — its native lib is bundled by the
        // build; only the weights need provisioning (present/downloading above).
        return new TtsEngineEntry(TtsEngine.JVM.id(), TtsEngine.JVM.displayName(),
                true, status, selected, models);
    }

    /** POST /api/tts/synthesize {text} — read-aloud: WAV bytes from the selected engine. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = byte[].class)))
    @Operation(summary = "Synthesize text to speech (WAV) with the operator-selected engine")
    @ChatHidden("synthesizes speech audio -- compute/disk resource action")
    public static void synthesize() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();
        var text = JsonBodyReader.requiredOr400(body, "text");
        var speakable = TtsText.toSpeakable(text);
        if (speakable.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "no speakable text after stripping markup");
        }
        // The cap belongs on THIS endpoint and not on stream() (JCLAW-880): one
        // synthesize call for the whole text holds the JVM-wide sidecar lock for its
        // full duration, with nothing to interleave against and no way to cancel it
        // mid-flight. Measured against the speakable text, not the raw markdown —
        // code fences and URLs are stripped before speaking, so counting them
        // rejected messages for length they would never have spoken.
        int maxChars = ConfigService.getInt("tts.maxChars", 5000);
        if (speakable.length() > maxChars) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "text too long for one-shot synthesis (%d > %d chars) — use the streaming endpoint"
                            .formatted(speakable.length(), maxChars));
        }
        byte[] audio;
        try {
            audio = TtsRouter.synthesize(speakable);
        } catch (TtsException e) {
            EventLogger.warn("tts", "read-aloud failed: " + e.getMessage());
            ApiResponses.error(503, ApiResponses.TTS_UNAVAILABLE, e.getMessage());
            return; // unreachable (error() halts) — documents intent for javac
        }
        response.setHeader("Content-Type", "audio/wav");
        response.setHeader("Cache-Control", "no-store");
        renderBinary(new ByteArrayInputStream(audio));
    }

    /** POST /api/tts/stream {text} — streaming read-aloud (JCLAW-790). The text
     *  is sentence-chunked and each chunk's WAV is SSE-streamed as it
     *  synthesizes, so the client starts playback on the first sentence instead
     *  of after the whole message. Frames: {type:"audio",index,audio:&lt;base64
     *  wav&gt;} per chunk, then {type:"complete",count} — or {type:"error",message}.
     *  Uses the operator-selected engine. */
    @ChatHidden("streams synthesized speech audio -- compute/disk resource action")
    public static void stream() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();
        var text = JsonBodyReader.requiredOr400(body, "text");
        // Deliberately uncapped (JCLAW-880). This endpoint already handles arbitrary
        // length by construction: the text is sentence-chunked below, each chunk is
        // synthesized separately, and the per-chunk sidecar lock is fair, so a long
        // read-aloud interleaves with voice mode rather than starving it. Cancellation
        // is honored between chunks, so an operator who changes their mind stops the
        // work within one sentence. A length cap here refused input the loop underneath
        // it was built to stream — a 5.7k-character reply is a long answer, not abuse.
        var chunks = TtsSentenceChunker.chunk(TtsText.toSpeakable(text));
        if (chunks.isEmpty()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "no speakable text after stripping markup");
        }
        SseStream sse = openSSE().heartbeat(Duration.ofSeconds(15)).timeout(Duration.ofMinutes(10));
        var cancelled = new AtomicBoolean(false);
        sse.onClose(() -> cancelled.set(true));
        // Synthesize + stream on a virtual thread; the Play worker returns to the
        // pool and await(completion()) suspends this invocation until the VT closes
        // the stream (mirrors ApiChatController's streaming turn).
        Thread.ofVirtual().name("tts-stream").start(() -> {
            var enc = Base64.getEncoder();
            try {
                int i = 0;
                for (var chunk : chunks) {
                    if (cancelled.get()) return;
                    var wav = TtsRouter.synthesize(chunk);
                    if (cancelled.get()) return;
                    sse.send(Map.of("type", "audio", "index", i++, "audio", enc.encodeToString(wav)));
                }
                if (!cancelled.get()) sse.send(Map.of("type", "complete", "count", chunks.size()));
            } catch (RuntimeException e) {  // TtsException + anything unexpected — report to the client
                EventLogger.warn("tts", "streaming read-aloud failed: " + e.getMessage());
                if (!cancelled.get()) sse.send(Map.of("type", "error", "message", String.valueOf(e.getMessage())));
            } finally {
                sse.close();
            }
        });
        await(sse.completion());
    }

    /** POST /api/tts/models/{id}/download — provision a JVM-native model's
     *  weights in the background; sidecar models auto-provision on first use. */
    // S2259/S3655: ApiResponses.error(400) halts via a Play Result the analyzer
    // can't see across the framework boundary, so model.get() past the guard is safe.
    @SuppressWarnings({"java:S2259", "java:S3655"})
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DownloadStartedResponse.class)))
    @ChatHidden("triggers a TTS model download -- disk/network resource action")
    public static void download(String id) {
        var model = TtsModel.byId(id);
        if (model.isEmpty()) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Unknown TTS model id: " + id);
        if (model.get().engine() != TtsEngine.JVM) {
            renderJSON(gson.toJson(new DownloadStartedResponse("managed", id)));
            return;
        }
        // Single-flight background prefetch; the polling state endpoint shows progress.
        Thread.ofVirtual().name("tts-prefetch-" + id).start(() -> {
            try {
                TtsJvmEngine.prefetch(id);
            } catch (RuntimeException e) {
                EventLogger.warn("tts", "TTS model prefetch failed for %s: %s".formatted(id, e.getMessage()));
            }
        });
        EventLogger.info("tts", "TTS model download requested: %s".formatted(id));
        renderJSON(gson.toJson(new DownloadStartedResponse("downloading", id)));
    }

    /**
     * POST /api/tts/reference-voice — set the clip a cloning model copies its
     * speaker from (JCLAW-865).
     *
     * <p>Validated here rather than at synthesis time. A clip the engine cannot
     * read fails deep inside Python, on a later read-aloud, with no obvious link
     * back to the upload — whereas at this moment the operator is holding the file
     * and can pick another.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReferenceVoiceResponse.class)))
    public static void uploadReferenceVoice(Upload file) {
        if (file == null || file.asFile() == null || !file.asFile().exists()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "No reference clip supplied");
            return;
        }
        var f = file.asFile();
        var name = file.getFileName() != null ? file.getFileName() : f.getName();
        var rejection = TtsReferenceVoice.validate(name, f.length());
        if (rejection != null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, rejection);
            return;
        }
        try {
            TtsReferenceVoice.store(f, name, TtsEngine.SIDECAR);
        } catch (IOException e) {
            ApiResponses.error(500, ApiResponses.INTERNAL_ERROR, "Could not store reference clip: " + e.getMessage());
            return;
        }
        EventLogger.info("tts", "Reference voice set from '%s'".formatted(name));
        // Load it now rather than on the operator's first read-aloud: cloning adds
        // state on top of the model, and they are not waiting on a turn right now.
        TtsSidecarManager.prewarmModelAsync();
        renderJSON(gson.toJson(new ReferenceVoiceResponse("ok", name)));
    }

    /** DELETE /api/tts/reference-voice — drop the clip and return to the model's
     *  default speaker. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReferenceVoiceResponse.class)))
    public static void clearReferenceVoice() {
        try {
            TtsReferenceVoice.clear(TtsEngine.SIDECAR);
        } catch (IOException e) {
            ApiResponses.error(500, ApiResponses.INTERNAL_ERROR, "Could not clear reference clip: " + e.getMessage());
            return;
        }
        EventLogger.info("tts", "Reference voice cleared");
        renderJSON(gson.toJson(new ReferenceVoiceResponse("cleared", null)));
    }
}
