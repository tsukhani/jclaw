package services.voice;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import okhttp3.Request;
import play.Logger;
import play.Play;
import services.ConfigService;
import utils.HttpFactories;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * JVM-native voice-activity detection (JCLAW-797) via the sherpa-onnx Silero
 * VAD — the same in-process ONNX stack already on the classpath for the
 * JCLAW-793 TTS engine, so this adds no dependency and no sidecar hop. Feeds
 * {@link #WINDOW}-sample, 16&nbsp;kHz mono frames and reports whether the model
 * currently hears speech; {@link TurnEndpointer} layers the adaptive-silence
 * turn logic on top.
 *
 * <p>The Silero weights ({@code silero_vad.onnx}, ~2&nbsp;MB) are provisioned
 * lazily to {@code data/voice-models/} on first construction, mirroring
 * {@link services.tts.TtsJvmEngine}'s download-on-demand convention. Not
 * thread-safe: sherpa's {@code Vad} holds streaming state, so hold one instance
 * per voice session (each turn resets it via {@link #reset()}).
 *
 * <p>This is the speech-signal source for the JCLAW-795 Tier-1 cascade; it is
 * driven by a live continuous audio stream in JCLAW-799.
 */
public final class VoiceVad implements AutoCloseable {

    /** Silero operates on fixed 512-sample windows at 16 kHz (~32 ms). */
    public static final int WINDOW = 512;
    /** Required input sample rate. Callers must resample the mic to this. */
    public static final int SAMPLE_RATE = 16_000;
    /** Per-window duration in ms — the timestamp step for {@link TurnEndpointer}. */
    public static final long WINDOW_MS = 1000L * WINDOW / SAMPLE_RATE;

    private static final String MODEL_FILE = "silero_vad.onnx";
    private static final String DEFAULT_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private final Vad vad;

    public VoiceVad() {
        var model = ensureModel();
        var threshold = configFloat("voice.vad.threshold", 0.5f);
        var silero = SileroVadModelConfig.builder()
                .setModel(model.toString())
                .setThreshold(threshold)
                // Keep sherpa's own hysteresis minimal — TurnEndpointer owns the
                // adaptive silence, so the VAD should report near-raw per-window
                // speech state rather than smoothing the tail itself.
                .setMinSilenceDuration(0.05f)
                .setMinSpeechDuration(0.05f)
                .setWindowSize(WINDOW)
                .setMaxSpeechDuration(20f)
                .build();
        var config = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(1)
                .setProvider("cpu")
                .setDebug(false)
                .build();
        this.vad = new Vad(config); // triggers sherpa's native-lib load
    }

    /**
     * Feed one {@link #WINDOW}-sample frame; returns whether speech is currently
     * detected. Samples are mono float PCM in [-1, 1] at {@link #SAMPLE_RATE}.
     */
    public boolean isSpeech(float[] window) {
        vad.acceptWaveform(window);
        return vad.isSpeechDetected();
    }

    /** Clear the VAD's streaming state at a turn/session boundary. */
    public void reset() {
        vad.reset();
    }

    @Override
    public void close() {
        vad.release();
    }

    /** Absolute path of the Silero weights, downloading them on first use. */
    static Path ensureModel() {
        var dir = new File(Play.applicationPath, "data/voice-models").toPath();
        var model = dir.resolve(MODEL_FILE);
        if (Files.isRegularFile(model)) return model;
        var url = ConfigService.get("voice.vad.modelUrl");
        if (url == null || url.isBlank()) url = DEFAULT_MODEL_URL;
        try {
            Files.createDirectories(dir);
            Logger.info("VoiceVad: provisioning Silero VAD — downloading %s", url);
            downloadTo(url, model);
            return model;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to provision Silero VAD model: " + e.getMessage(), e);
        }
    }

    private static void downloadTo(String url, Path dest) throws IOException {
        var client = HttpFactories.general().newBuilder().readTimeout(Duration.ZERO).build();
        var call = client.newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(ConfigService.getInt("voice.vad.downloadTimeoutSeconds", 600), TimeUnit.SECONDS);
        var tmp = Files.createTempFile(dest.getParent(), "silero-", ".part");
        try (var resp = call.execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " fetching " + url);
            }
            try (var in = resp.body().byteStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); // atomic publish
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static float configFloat(String key, float dflt) {
        var v = ConfigService.get(key);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException _) {
            return dflt;
        }
    }
}
