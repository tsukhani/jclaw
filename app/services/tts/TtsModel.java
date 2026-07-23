package services.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The self-hosted TTS voices/models jclaw exposes in Settings &gt; Speech, each
 * tagged with the {@link TtsEngine} that serves it. The stable {@code id} is
 * what the router hands the engine: the sidecar's {@code /synthesize} {@code
 * model} field (mapped to an mlx-audio repo in synth.py), or the JVM engine's
 * model map (mapped to a sherpa-onnx model archive in {@code TtsJvmEngine}).
 *
 * <p>{@code approxSizeMb} is the rough download size of the weights, used as the
 * Settings progress denominator until a live size replaces it — mirrors
 * {@link services.transcription.AsrModel}.
 */
public enum TtsModel {
    // Sidecar (Python) — Qwen3-TTS is quality-first + zero-shot cloning; Kokoro is light.
    QWEN3_06B(TtsEngine.SIDECAR, "qwen3-0.6b", "Qwen3-TTS 0.6B", 2500),
    QWEN3_06B_4BIT(TtsEngine.SIDECAR, "qwen3-0.6b-4bit", "Qwen3-TTS 0.6B (4-bit)", 400),
    KOKORO_MLX(TtsEngine.SIDECAR, "kokoro", "Kokoro-82M", 330),
    // Chatterbox is a cross-platform PyTorch model (Apple MPS + NVIDIA CUDA), not
    // mlx-audio, so the sidecar runs it via a torch loader that bypasses the
    // Apple-only gate (JCLAW-814). Listed last so the SIDECAR default stays Qwen3-TTS.
    CHATTERBOX(TtsEngine.SIDECAR, "chatterbox", "Chatterbox (PyTorch, MPS/CUDA)", 1000),
    // JVM (in-process sherpa-onnx OfflineTts) — Piper is tiny/fast, Kokoro adds languages.
    PIPER_AMY(TtsEngine.JVM, "piper-en_US-amy-low", "Piper Amy (English, fast)", 65),
    KOKORO_SHERPA(TtsEngine.JVM, "kokoro-multi-lang-v1_0", "Kokoro-82M (multilingual)", 720);

    private final TtsEngine engine;
    private final String id;
    private final String displayName;
    private final int approxSizeMb;

    TtsModel(TtsEngine engine, String id, String displayName, int approxSizeMb) {
        this.engine = engine;
        this.id = id;
        this.displayName = displayName;
        this.approxSizeMb = approxSizeMb;
    }

    public TtsEngine engine() { return engine; }
    public String id() { return id; }
    public String displayName() { return displayName; }
    public int approxSizeMb() { return approxSizeMb; }

    public static Optional<TtsModel> byId(String id) {
        if (id == null) return Optional.empty();
        for (var m : values()) {
            if (m.id.equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /** Models served by {@code engine}, in declaration order (the Settings
     *  dropdown order). The first entry is the engine's safe default. */
    public static List<TtsModel> forEngine(TtsEngine engine) {
        var out = new ArrayList<TtsModel>();
        for (var m : values()) {
            if (m.engine == engine) out.add(m);
        }
        return out;
    }

    /** The default model for an engine when {@code tts.<engine>.model} is unset. */
    public static TtsModel defaultFor(TtsEngine engine) {
        return forEngine(engine).get(0);
    }
}
