package services.transcription;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.HttpFactories;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP client for the local diarization sidecar (JCLAW-565 revival): pyannote
 * speaker turns, no transcription. Requests carry the audio by absolute path —
 * both processes share the host. A JVM-wide fair lock serializes calls so
 * concurrent conversations queue instead of thrashing the model.
 */
public class DiarizeSidecarClient {

    /** One speaker turn: {@code [startMs, endMs)} attributed to {@code speaker},
     *  optionally with a per-turn emotion (null unless emotions were requested
     *  and the turn was long enough to score). */
    public record Turn(long startMs, long endMs, String speaker, Emotion emotion) {
        public Turn(long startMs, long endMs, String speaker) {
            this(startMs, endMs, speaker, null);
        }
    }

    /** MERaLiON-SER per-turn emotion: a categorical {@code label} plus optional
     *  valence/arousal/dominance in [0,1] (null when the model emits no VAD). */
    public record Emotion(String label, double confidence,
                          Double valence, Double arousal, Double dominance) {}

    private static final MediaType JSON = MediaType.get("application/json");

    /** The sidecar is one-inference-at-a-time by design (HTTP 409 when busy).
     *  Serialize all sidecar calls JVM-wide with a FAIR lock so concurrent
     *  conversations queue instead of surfacing a retryable busy condition as
     *  a user-facing failure. */
    private static final ReentrantLock SIDECAR_LOCK =
            new ReentrantLock(true);

    private final String baseUrlOverride;
    private final OkHttpClient client;

    public DiarizeSidecarClient() {
        // Derived from the shared general client (reuses its pool/dispatcher)
        // but with the per-read socket timeout lifted: the first /diarize
        // lazily loads the pyannote pipeline (gated download + torch import +
        // ~20s model load) and legitimately sends nothing for minutes. With
        // readTimeout=0 a genuinely hung socket is bounded ONLY by the per-call
        // callTimeout set on every request below — do not "fix" the zero
        // without replacing that bound.
        this(null, HttpFactories.general().newBuilder()
                .readTimeout(Duration.ZERO)
                .build());
    }

    /** Test seam: fixed base URL (no sidecar spawn) + injected client. */
    public DiarizeSidecarClient(String baseUrlOverride, OkHttpClient client) {
        this.baseUrlOverride = baseUrlOverride;
        this.client = client;
    }

    /**
     * Speaker turns via the sidecar (pyannote community-1 on GPU/CPU). First
     * call may build the script env, download the gated weights, and load the
     * pipeline. {@code numSpeakers} is an optional hint ({@code null} = auto).
     * {@code emotions} runs a per-turn SER pass; {@code emotionModel} picks the
     * SER model (null/blank = the sidecar default, MERaLiON-SER-v1).
     */
    public List<Turn> diarize(Path audioFile, Integer numSpeakers, boolean emotions, String emotionModel) {
        SIDECAR_LOCK.lock();
        try {
            return diarizeLocked(audioFile, numSpeakers, emotions, emotionModel);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private List<Turn> diarizeLocked(Path audioFile, Integer numSpeakers, boolean emotions,
                                     String emotionModel) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : DiarizeSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        if (numSpeakers != null && numSpeakers > 0) body.addProperty("num_speakers", numSpeakers);
        if (emotions) {
            body.addProperty("emotions", true);
            if (emotionModel != null && !emotionModel.isBlank()) {
                body.addProperty("emotion_model", emotionModel);
            }
        }
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/diarize")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                DiarizeSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "diarize sidecar failed: HTTP %d — %s".formatted(resp.code(), truncate(text)));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var turns = new ArrayList<Turn>();
            for (var el : root.getAsJsonArray("turns")) {
                var o = el.getAsJsonObject();
                turns.add(new Turn(o.get("startMs").getAsLong(), o.get("endMs").getAsLong(),
                        o.get("speaker").getAsString(), parseEmotion(o)));
            }
            return turns;
        } catch (IOException e) {
            throw new TranscriptionException("diarize sidecar unreachable: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e instanceof TranscriptionException te) throw te;
            throw new TranscriptionException(
                    "diarize sidecar returned an unparseable response: " + e.getMessage(), e);
        }
    }

    /** Download status for the given HF repos (raw JSON body) — the on-device
     *  diarization weights on the Settings page. Not under SIDECAR_LOCK, so it
     *  never queues behind an in-flight diarization. */
    public String models(String commaSeparatedRepos) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : DiarizeSidecarManager.ensureRunning();
        var encoded = URLEncoder.encode(commaSeparatedRepos, StandardCharsets.UTF_8);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/diarize/models?ids=" + encoded).get().build());
        call.timeout().timeout(60, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException("diarize model status failed: HTTP %d — %s"
                        .formatted(resp.code(), truncate(text)));
            }
            return text;
        } catch (IOException e) {
            throw new TranscriptionException("diarize sidecar unreachable: " + e.getMessage(), e);
        }
    }

    /** Kick a DETACHED download of an HF repo (pyannote or SER model); returns
     *  immediately with a downloading ack. The sidecar polls its own progress. */
    public String prefetch(String repo) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : DiarizeSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("model", repo);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/diarize/prefetch")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(60, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException("diarize prefetch failed: HTTP %d — %s"
                        .formatted(resp.code(), truncate(text)));
            }
            return text;
        } catch (IOException e) {
            throw new TranscriptionException("diarize sidecar unreachable: " + e.getMessage(), e);
        }
    }

    /** The optional {@code emotion} object on a turn, or null if absent. */
    private static Emotion parseEmotion(JsonObject turn) {
        final var field = "emotion";
        if (!turn.has(field) || turn.get(field).isJsonNull()) return null;
        var e = turn.getAsJsonObject(field);
        return new Emotion(
                e.get("label").getAsString(),
                e.has("confidence") ? e.get("confidence").getAsDouble() : 0.0,
                e.has("valence") ? e.get("valence").getAsDouble() : null,
                e.has("arousal") ? e.get("arousal").getAsDouble() : null,
                e.has("dominance") ? e.get("dominance").getAsDouble() : null);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
