package services.tts;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP client for the local TTS sidecar (JCLAW-789). Inverts the ASR data
 * direction: it sends TEXT and receives AUDIO BYTES (a WAV). The engine +
 * weights are chosen by the {@code model} field (Qwen3-TTS / Kokoro), mirroring
 * how {@link services.transcription.AsrSidecarClient} passes the ASR model per
 * request. A JVM-wide fair lock serializes calls so concurrent read-aloud
 * requests queue instead of surfacing the sidecar's one-at-a-time HTTP 409.
 */
public class TtsSidecarClient {

    private static final MediaType JSON = MediaType.get("application/json");

    /** The sidecar is one-synthesis-at-a-time by design (HTTP 409 when busy).
     *  Serialize all sidecar calls JVM-wide with a FAIR lock so concurrent
     *  read-aloud requests queue instead of failing on a retryable busy state. */
    private static final ReentrantLock SIDECAR_LOCK = new ReentrantLock(true);

    private final String baseUrlOverride;
    private final OkHttpClient client;

    public TtsSidecarClient() {
        // Derived from the shared general client (reuses its pool/dispatcher)
        // but with the per-read socket timeout lifted: the first /synthesize
        // lazily builds the script env and downloads weights and legitimately
        // sends nothing for minutes. DELIBERATE TRADEOFF (mirrors AsrSidecarClient,
        // JCLAW-626): with readTimeout=0 a genuinely hung socket is bounded ONLY
        // by the per-call callTimeout set on every request below.
        this(null, HttpFactories.general().newBuilder()
                .readTimeout(Duration.ZERO)
                .build());
    }

    /** Test seam: fixed base URL (no sidecar spawn) + injected client. */
    public TtsSidecarClient(String baseUrlOverride, OkHttpClient client) {
        this.baseUrlOverride = baseUrlOverride;
        this.client = client;
    }

    /**
     * Synthesize {@code text} to audio bytes (WAV) via the sidecar. First call
     * may build the script env and download weights. {@code model} selects the
     * engine (Qwen3-TTS / Kokoro); {@code voice} and {@code format} are
     * optional (null/blank omitted, letting the sidecar default).
     */
    public byte[] synthesize(String text, String model, String voice, String format) {
        SIDECAR_LOCK.lock();
        try {
            return synthesizeLocked(text, model, voice, format);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private byte[] synthesizeLocked(String text, String model, String voice, String format) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : TtsSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("text", text);
        if (model != null && !model.isBlank()) body.addProperty("model", model);
        if (voice != null && !voice.isBlank()) body.addProperty("voice", voice);
        if (format != null && !format.isBlank()) body.addProperty("format", format);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/synthesize")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                TtsSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            // Success => raw audio bytes; failure => a small JSON error body.
            var bytes = resp.body().bytes();
            if (!resp.isSuccessful()) {
                throw new TtsException("TTS sidecar synthesize failed: HTTP %d — %s".formatted(
                        resp.code(), truncate(new String(bytes, StandardCharsets.UTF_8))));
            }
            return bytes;
        } catch (IOException e) {
            throw new TtsException("TTS sidecar unreachable: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
