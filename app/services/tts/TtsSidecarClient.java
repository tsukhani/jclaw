package services.tts;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.LocalSidecarDaemon;
import services.sidecar.SidecarHttpClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
public class TtsSidecarClient extends SidecarHttpClient {

    /** The sidecar is one-synthesis-at-a-time by design (HTTP 409 when busy).
     *  Serialize all sidecar calls JVM-wide with a FAIR lock so concurrent
     *  read-aloud requests queue instead of failing on a retryable busy state. */
    private static final ReentrantLock SIDECAR_LOCK = new ReentrantLock(true);

    public TtsSidecarClient() {
        this(null, defaultClient());
    }

    /** Test seam: fixed base URL (no sidecar spawn) + injected client. */
    public TtsSidecarClient(@Nullable String baseUrlOverride, OkHttpClient client) {
        super(baseUrlOverride, client);
    }

    @Override
    protected ReentrantLock sidecarLock() {
        return SIDECAR_LOCK;
    }

    /**
     * Synthesize {@code text} to audio bytes (WAV) via the sidecar. First call
     * may build the script env and download weights. {@code model} selects the
     * engine (Qwen3-TTS / Kokoro); {@code voice} and {@code format} are
     * optional (null/blank omitted, letting the sidecar default).
     */
    public byte[] synthesize(String text, String model, @Nullable String voice, String format) {
        return synthesize(text, model, voice, format, null);
    }

    /**
     * Cloning-aware overload (JCLAW-865). {@code refAudio} is a filesystem path to
     * a short reference clip the engine clones its speaker from — Chatterbox takes
     * it as {@code audio_prompt_path}, Qwen3-TTS as {@code ref_audio}. It is the
     * only way to choose a voice on those models, which have no named presets.
     *
     * <p>Omitted from the request when null or blank, leaving the model's default
     * speaker in place.
     *
     * <p>The path is read by the sidecar process, not this one, so it must be
     * absolute — the sidecar runs with its own working directory.
     *
     * <p>No transcript is sent alongside it (JCLAW-867). Qwen3-TTS clones two
     * ways: a speaker embedding derived from the clip alone, and ICL, which
     * prefills the clip's audio codes plus its transcript ahead of every
     * utterance. The embedding is what the Base checkpoints ship a speaker
     * encoder for, and it is amortized — ICL re-pays that prefill on each turn
     * and needs its repetition penalty forced up to stop long prefills
     * degenerating. Sending a transcript is the only thing that selects ICL, so
     * not having one to send is what keeps the cheap path.
     */
    public byte[] synthesize(String text, String model, @Nullable String voice, String format,
                             @Nullable String refAudio) {
        return withSidecarLock(() -> synthesizeLocked(text, model, voice, format, refAudio));
    }

    private byte[] synthesizeLocked(String text, String model, @Nullable String voice, String format,
                                    @Nullable String refAudio) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : TtsSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("text", text);
        if (model != null && !model.isBlank()) body.addProperty("model", model);
        if (voice != null && !voice.isBlank()) body.addProperty("voice", voice);
        if (format != null && !format.isBlank()) body.addProperty("format", format);
        if (refAudio != null && !refAudio.isBlank()) body.addProperty("ref_audio", refAudio);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/synthesize")
                .header(LocalSidecarDaemon.AUTH_HEADER, TtsSidecarManager.authToken())
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
}
