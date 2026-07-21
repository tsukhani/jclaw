package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import services.tts.TtsRouter;
import services.tts.TtsSentenceChunker;
import services.tts.TtsSidecarManager;
import services.tts.TtsText;
import utils.ApiResponses;

import java.io.ByteArrayInputStream;
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

    public record TtsModelEntry(String id, String displayName, int approxSizeMb,
                                boolean present, boolean downloading) {}

    public record TtsEngineEntry(String id, String displayName, boolean available,
                                 String status, String model, List<TtsModelEntry> models) {}

    public record TtsStateResponse(String engine, List<TtsEngineEntry> engines) {}

    public record DownloadStartedResponse(String status, String modelId) {}

    /** GET /api/tts/state — snapshot for the Settings &gt; Speech panel. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TtsStateResponse.class)))
    @Operation(summary = "Snapshot both TTS engines, the selected one, and per-model readiness")
    public static void state() {
        var engines = new ArrayList<TtsEngineEntry>();
        engines.add(sidecarEntry());
        engines.add(jvmEntry());
        renderJSON(gson.toJson(new TtsStateResponse(TtsRouter.currentEngine().id(), engines)));
    }

    private static TtsEngineEntry sidecarEntry() {
        boolean uv = UvProbe.isAvailable();
        boolean running = TtsSidecarManager.isRunning();
        String status = running ? "running"
                : uv ? "ready — starts on first use"
                : "needs 'uv' on PATH: " + UvProbe.lastResult().reason();
        var models = new ArrayList<TtsModelEntry>();
        for (var m : TtsModel.forEngine(TtsEngine.SIDECAR)) {
            // Sidecar weights live in the sidecar's HF cache, pulled on first use —
            // not disk-tracked here, so "present" tracks whether the sidecar is up.
            models.add(new TtsModelEntry(m.id(), m.displayName(), m.approxSizeMb(), running, false));
        }
        return new TtsEngineEntry(TtsEngine.SIDECAR.id(), TtsEngine.SIDECAR.displayName(),
                uv, status, TtsRouter.modelFor(TtsEngine.SIDECAR), models);
    }

    private static TtsEngineEntry jvmEntry() {
        String selected = TtsRouter.modelFor(TtsEngine.JVM);
        boolean ready = TtsJvmEngine.isModelPresent(selected);
        String status = ready ? "ready"
                : TtsJvmEngine.isDownloading(selected) ? "downloading model"
                : "model downloads on first use";
        var models = new ArrayList<TtsModelEntry>();
        for (var m : TtsModel.forEngine(TtsEngine.JVM)) {
            models.add(new TtsModelEntry(m.id(), m.displayName(), m.approxSizeMb(),
                    TtsJvmEngine.isModelPresent(m.id()), TtsJvmEngine.isDownloading(m.id())));
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
        int maxChars = ConfigService.getInt("tts.maxChars", 5000);
        if (text.length() > maxChars) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "text too long for read-aloud (%d > %d chars)".formatted(text.length(), maxChars));
        }
        var speakable = TtsText.toSpeakable(text);
        if (speakable.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "no speakable text after stripping markup");
        }
        byte[] audio;
        try {
            audio = TtsRouter.synthesize(speakable);
        } catch (TtsException e) {
            EventLogger.warn("tts", "read-aloud failed: " + e.getMessage());
            ApiResponses.error(503, "tts_unavailable", e.getMessage());
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
        int maxChars = ConfigService.getInt("tts.maxChars", 5000);
        if (text.length() > maxChars) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "text too long for read-aloud (%d > %d chars)".formatted(text.length(), maxChars));
        }
        var chunks = TtsSentenceChunker.chunk(TtsText.toSpeakable(text));
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
}
