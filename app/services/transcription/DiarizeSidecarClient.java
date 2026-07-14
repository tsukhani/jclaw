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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the local diarization sidecar (JCLAW-565 revival): pyannote
 * speaker turns, no transcription. Requests carry the audio by absolute path —
 * both processes share the host. A JVM-wide fair lock serializes calls so
 * concurrent conversations queue instead of thrashing the model.
 */
public class DiarizeSidecarClient {

    /** One speaker turn: {@code [startMs, endMs)} attributed to {@code speaker}. */
    public record Turn(long startMs, long endMs, String speaker) {}

    private static final MediaType JSON = MediaType.get("application/json");

    /** The sidecar is one-inference-at-a-time by design (HTTP 409 when busy).
     *  Serialize all sidecar calls JVM-wide with a FAIR lock so concurrent
     *  conversations queue instead of surfacing a retryable busy condition as
     *  a user-facing failure. */
    private static final java.util.concurrent.locks.ReentrantLock SIDECAR_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

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
     */
    public List<Turn> diarize(Path audioFile, Integer numSpeakers) {
        SIDECAR_LOCK.lock();
        try {
            return diarizeLocked(audioFile, numSpeakers);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private List<Turn> diarizeLocked(Path audioFile, Integer numSpeakers) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : DiarizeSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        if (numSpeakers != null && numSpeakers > 0) body.addProperty("num_speakers", numSpeakers);
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
                turns.add(new Turn(o.get("startMs").getAsLong(),
                        o.get("endMs").getAsLong(), o.get("speaker").getAsString()));
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

    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
