package services.transcription;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import services.ConfigService;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the pyannote diarization sidecar (JCLAW-565). Sends the
 * audio file <b>by absolute path</b> — both processes share a filesystem, so
 * streaming megabytes through the localhost socket would be waste — and maps
 * the response into the same {@link SpeakerSegment} shape the
 * rest of the pipeline consumes, so {@link DiarizedTranscript},
 * {@link SpeakerNamer} and the emotion annotator are backend-agnostic.
 */
public class PyannoteDiarizationClient {

    /** Full /diarize output: segments plus the overlap regions the
     *  overlap-aware annotation detected (JCLAW-605 re-attribution gate). */
    public record DiarizationOutput(List<SpeakerSegment> segments,
                                    List<double[]> overlaps) {}

    private static final MediaType JSON = MediaType.get("application/json");

    /** JCLAW-620: the sidecar is one-inference-at-a-time by design (HTTP
     *  409 when busy). Serialize all sidecar calls JVM-wide with a FAIR
     *  lock so concurrent conversations queue instead of surfacing a
     *  retryable busy condition as a user-facing failure. */
    private static final java.util.concurrent.locks.ReentrantLock SIDECAR_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    private final String baseUrlOverride;
    private final OkHttpClient client;

    public PyannoteDiarizationClient() {
        // Derived from the shared general client (reuses its pool/dispatcher)
        // but with the per-read socket timeout lifted: the sidecar's first
        // /diarize lazily loads the pipeline (gated download + torch import)
        // and legitimately sends nothing for minutes. The JCLAW-565 UAT
        // caught general()'s 30s read timeout firing mid-load while the
        // sidecar went on to succeed. DELIBERATE TRADEOFF (JCLAW-626): with
        // readTimeout=0, a genuinely hung socket is bounded ONLY by the
        // 1800s per-call callTimeout set on every request below — do not
        // "fix" the zero without replacing that bound.
        this(null, HttpFactories.general().newBuilder()
                .readTimeout(java.time.Duration.ZERO)
                .build());
    }

    /** Test seam: fixed base URL (no sidecar spawn) + injected client. */
    public PyannoteDiarizationClient(String baseUrlOverride, OkHttpClient client) {
        this.baseUrlOverride = baseUrlOverride;
        this.client = client;
    }

    /**
     * Diarize {@code audioFile}. Blocking; ensures the sidecar is running
     * first (which may pay the one-time env/model download). Throws
     * {@link TranscriptionException} on any sidecar or protocol failure —
     * {@link DiarizationRouter} decides whether that falls back to sherpa.
     *
     * @param numSpeakers exact speaker count when known, or any value below 2
     *                    to let the pipeline find the count itself
     */
    public List<SpeakerSegment> diarize(Path audioFile, int numSpeakers) {
        return diarizeRich(audioFile, numSpeakers).segments();
    }

    /** As {@link #diarize} but keeping the overlap regions (JCLAW-605). */
    public DiarizationOutput diarizeRich(Path audioFile, int numSpeakers) {
        SIDECAR_LOCK.lock();
        try {
            return diarizeRichLocked(audioFile, numSpeakers);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private DiarizationOutput diarizeRichLocked(Path audioFile, int numSpeakers) {
        try {
            return diarizeRichOnce(audioFile, numSpeakers);
        } catch (TranscriptionException e) {
            // JCLAW-641: the idle self-evict can fire between the health
            // check and this POST — one respawn-and-retry absorbs it.
            if (baseUrlOverride == null && e.getCause() instanceof java.net.ConnectException) {
                Logger.info("PyannoteDiarizationClient: sidecar evicted between health check "
                        + "and request — respawning once");
                return diarizeRichOnce(audioFile, numSpeakers);
            }
            throw e;
        }
    }

    private DiarizationOutput diarizeRichOnce(Path audioFile, int numSpeakers) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : PyannoteSidecarManager.ensureRunning();

        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        if (numSpeakers >= 2) body.addProperty("num_speakers", numSpeakers);

        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/diarize")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        // Diarization is ~18x realtime on MPS but ~0.2x on CPU — the deadline
        // must cover an hour-long recording on a CPU-only host.
        call.timeout().timeout(ConfigService.getInt(
                PyannoteSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);

        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (resp.code() == 409) {
                // JCLAW-641: raw "already in progress" reads like a crash.
                throw new TranscriptionException(
                        "the diarization sidecar is still busy with an earlier recording "
                                + "(long inferences keep running even if their caller gave up) — "
                                + "try again in a few minutes");
            }
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar diarize failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            return new DiarizationOutput(parseSegments(text), parseOverlaps(text));
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        }
    }

    /** Parse the sidecar's {@code {segments:[{start,end,speaker}...]}} response.
     *  Public (like WhisperJniTranscriber.applyLanguage) so tests reach it from
     *  the default package. */
    public static List<SpeakerSegment> parseSegments(String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var device = root.has("device") && !root.get("device").isJsonNull()
                    ? root.get("device").getAsString() : "?";
            var arr = root.getAsJsonArray("segments");
            var segments = new ArrayList<SpeakerSegment>(arr.size());
            for (var el : arr) {
                var o = el.getAsJsonObject();
                segments.add(new SpeakerSegment(
                        o.get("start").getAsDouble(),
                        o.get("end").getAsDouble(),
                        o.get("speaker").getAsInt()));
            }
            Logger.info("PyannoteDiarizationClient: %d segments (device=%s)", segments.size(), device);
            return segments;
        } catch (RuntimeException e) {
            throw new TranscriptionException(
                    "pyannote sidecar returned an unparseable response: " + truncate(json), e);
        }
    }

    /** Parse {@code overlaps:[{start,end}...]}; tolerant of absence (older
     *  sidecar builds) so a version skew degrades to "no re-attribution". */
    public static List<double[]> parseOverlaps(String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("overlaps") || !root.get("overlaps").isJsonArray()) return List.of();
            var out = new ArrayList<double[]>();
            for (var el : root.getAsJsonArray("overlaps")) {
                var o = el.getAsJsonObject();
                out.add(new double[]{o.get("start").getAsDouble(), o.get("end").getAsDouble()});
            }
            return out;
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    /**
     * Separate prepared 16 kHz mono WAVs into two stems each via the
     * sidecar's MossFormer2 endpoint (JCLAW-605). Batched: the sidecar
     * shells one separator process for the whole list, so the model loads
     * once per diarization, not once per overlap region. Returns stem-path
     * pairs aligned with the inputs; the caller owns temp-dir cleanup.
     */
    public List<List<Path>> separate(List<Path> windowWavs) {
        SIDECAR_LOCK.lock();
        try {
            return separateLocked(windowWavs);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private List<List<Path>> separateLocked(List<Path> windowWavs) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : PyannoteSidecarManager.ensureRunning();
        var body = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        windowWavs.forEach(w -> arr.add(w.toAbsolutePath().toString()));
        body.add("audio_paths", arr);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/separate")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        // First call downloads + loads MossFormer2; generous deadline.
        call.timeout().timeout(ConfigService.getInt(
                PyannoteSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar separate failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var byInput = root.getAsJsonObject("stems");
            var out = new ArrayList<List<Path>>(windowWavs.size());
            for (var wav : windowWavs) {
                var key = wav.toAbsolutePath().toString();
                if (!byInput.has(key) || byInput.getAsJsonArray(key).size() != 2) {
                    throw new TranscriptionException(
                            "sidecar returned no stem pair for " + key);
                }
                var pair = new ArrayList<Path>(2);
                byInput.getAsJsonArray(key).forEach(el -> pair.add(Path.of(el.getAsString())));
                out.add(pair);
            }
            return out;
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e instanceof TranscriptionException te) throw te;
            throw new TranscriptionException(
                    "pyannote sidecar returned an unparseable separate response: " + e.getMessage(), e);
        }
    }

    /**
     * MSDD second opinion for contested turns (JCLAW-612): overlap-aware
     * segments from NeMo's profile-conditioned decoder, run by the sidecar
     * in its own script env. First call may build that env and download the
     * model — the generous shared deadline applies.
     */
    public List<SpeakerSegment> msdd(Path audioFile, int numSpeakers) {
        SIDECAR_LOCK.lock();
        try {
            return msddLocked(audioFile, numSpeakers);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private List<SpeakerSegment> msddLocked(Path audioFile, int numSpeakers) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : PyannoteSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        body.addProperty("num_speakers", numSpeakers);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/msdd")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                PyannoteSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar msdd failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            return parseSegments(text);
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * GPU ASR via the sidecar (JCLAW-627): mlx-whisper on Apple silicon,
     * faster-whisper elsewhere — same weights as whisper.cpp, 5-20x faster.
     * First call may build the script env and download weights.
     */
    public List<WhisperJniTranscriber.Segment> transcribe(Path audioFile, String model,
                                                          String language) {
        SIDECAR_LOCK.lock();
        try {
            return transcribeLocked(audioFile, model, language);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private List<WhisperJniTranscriber.Segment> transcribeLocked(Path audioFile, String model,
                                                                 String language) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : PyannoteSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        body.addProperty("model", model);
        if (language != null && !language.isBlank()) body.addProperty("language", language);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/transcribe")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                PyannoteSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar transcribe failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var segments = new ArrayList<WhisperJniTranscriber.Segment>();
            for (var el : root.getAsJsonArray("segments")) {
                var o = el.getAsJsonObject();
                segments.add(new WhisperJniTranscriber.Segment(
                        o.get("startMs").getAsLong(), o.get("endMs").getAsLong(),
                        o.get("text").getAsString(),
                        o.has("noSpeechProb") ? o.get("noSpeechProb").getAsDouble() : 0.0,
                        o.has("avgLogprob") ? o.get("avgLogprob").getAsDouble() : 0.0,
                        o.has("compressionRatio") ? o.get("compressionRatio").getAsDouble() : 1.0));
            }
            return segments;
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e instanceof TranscriptionException te) throw te;
            throw new TranscriptionException(
                    "pyannote sidecar returned an unparseable transcribe response: " + e.getMessage(), e);
        }
    }

    /**
     * Batched WeSpeaker embeddings via the sidecar (JCLAW-630) — same ONNX
     * and feature pipeline as the retired JNI stack, so results are
     * compatible with every calibrated threshold. One call per batch.
     */
    public List<float[]> embedBatch(List<Path> windowWavs, Path modelOnnx) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : PyannoteSidecarManager.ensureRunning();
        var body = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        windowWavs.forEach(w -> arr.add(w.toAbsolutePath().toString()));
        body.add("audio_paths", arr);
        body.addProperty("model", modelOnnx.toAbsolutePath().toString());
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/embed")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                PyannoteSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar embed failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var out = new ArrayList<float[]>();
            for (var el : root.getAsJsonArray("embeddings")) {
                var a = el.getAsJsonArray();
                var v = new float[a.size()];
                for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsFloat();
                out.add(v);
            }
            if (out.size() != windowWavs.size()) {
                throw new TranscriptionException("sidecar returned %d embeddings for %d windows"
                        .formatted(out.size(), windowWavs.size()));
            }
            return out;
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
