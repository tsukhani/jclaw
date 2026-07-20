package services.tts;

import java.util.Optional;

/**
 * The two TTS backends the operator chooses between in Settings &gt; Speech
 * (JCLAW-789/793). The selection is the {@code tts.engine} config key and can
 * be switched at will — {@link TtsRouter} reads it per request, so a change
 * takes effect on the next read-aloud with no restart.
 *
 * <ul>
 *   <li><b>Sidecar</b> — the Python sidecar (mlx-audio on Apple silicon,
 *       vLLM/transformers on NVIDIA): Qwen3-TTS + Kokoro. Quality-first, does
 *       voice cloning, GPU-capable — but needs {@code uv} and a spawned
 *       process.</li>
 *   <li><b>JVM-native</b> — in-process sherpa-onnx {@code OfflineTts}
 *       (JCLAW-793): Piper / Kokoro as ONNX inside the JVM. Fast CPU synthesis,
 *       no Python, no sidecar — the zero-dependency fallback tier.</li>
 * </ul>
 */
public enum TtsEngine {
    SIDECAR("sidecar", "Sidecar (Qwen3-TTS / Kokoro)"),
    JVM("jvm", "JVM-native (sherpa-onnx)");

    /** Quality-first default; the operator can switch to JVM-native in Settings. */
    public static final TtsEngine DEFAULT = SIDECAR;

    private final String id;
    private final String displayName;

    TtsEngine(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }

    public static Optional<TtsEngine> byId(String id) {
        if (id == null) return Optional.empty();
        for (var e : values()) {
            if (e.id.equals(id)) return Optional.of(e);
        }
        return Optional.empty();
    }

    /** Resolve a config value to an engine, falling back to {@link #DEFAULT}
     *  for null/blank/unknown so a stale or empty key never breaks read-aloud. */
    public static TtsEngine fromConfigOrDefault(String id) {
        return byId(id).orElse(DEFAULT);
    }
}
