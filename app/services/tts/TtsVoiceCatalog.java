package services.tts;

import java.util.List;
import java.util.Map;

/**
 * Curated speaker-voice catalog per TTS model (JCLAW-846), surfaced in
 * Settings &gt; Speech so operators can pick a voice instead of accepting the
 * model default. The {@code id} is the value written to {@code tts.<engine>.voice}
 * and passed through {@link TtsRouter#voiceFor} to the engine's {@code voice}
 * field: a named voice for MLX Kokoro, or a numeric RNG-seed speaker for
 * Qwen3-TTS-Base (see {@code sidecar/tts/synth.py}).
 *
 * <p>Only models with a known-good, validated voice set have entries — a bogus
 * Kokoro voice name errors the synth (no fallback), so this list must stay
 * accurate. Models without a curated set (Piper is single-voice; Chatterbox
 * clones from a reference clip rather than presets; sherpa speaker indices are
 * unmapped) return an empty list, and the UI then shows no voice picker.
 */
public final class TtsVoiceCatalog {

    /** A selectable speaker voice: {@code id} is the engine {@code voice} value, {@code label} is the UI text. */
    public record Voice(String id, String label) {}

    private TtsVoiceCatalog() {}

    // MLX Kokoro-82M named voices (a/b = American/British, f/m = female/male).
    // All eight validated against the live sidecar (JCLAW-846).
    private static final List<Voice> KOKORO_VOICES = List.of(
            new Voice("af_bella", "Bella (American, female)"),
            new Voice("af_sarah", "Sarah (American, female)"),
            new Voice("am_adam", "Adam (American, male)"),
            new Voice("am_michael", "Michael (American, male)"),
            new Voice("bf_emma", "Emma (British, female)"),
            new Voice("bf_isabella", "Isabella (British, female)"),
            new Voice("bm_george", "George (British, male)"),
            new Voice("bm_lewis", "Lewis (British, male)"));

    // Qwen3-TTS-Base has no named voices; a numeric `voice` deterministically
    // seeds a stable (but unlabelled) speaker via synth.py's _voice_seed.
    private static final List<Voice> QWEN3_VOICES = List.of(
            new Voice("1", "Voice 1"),
            new Voice("2", "Voice 2"),
            new Voice("3", "Voice 3"),
            new Voice("4", "Voice 4"));

    private static final Map<String, List<Voice>> BY_MODEL = Map.of(
            "kokoro", KOKORO_VOICES,
            "qwen3-0.6b", QWEN3_VOICES,
            "qwen3-0.6b-4bit", QWEN3_VOICES);

    /** Curated selectable voices for {@code modelId}, or an empty list when the
     *  model has no preset picker (single-voice or reference-clip models). */
    public static List<Voice> voicesFor(String modelId) {
        if (modelId == null) return List.of(); // Map.of() rejects a null-key lookup
        return BY_MODEL.getOrDefault(modelId, List.of());
    }
}
