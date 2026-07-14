package services.transcription;

import java.util.Optional;

/**
 * The self-hosted ASR models jclaw exposes in Settings. Each variant's stable
 * {@code id} is the value of the {@code transcription.localModel} config key;
 * the ASR sidecar (serve.py) maps it to the host engine's artifact and picks
 * the transcription path:
 *
 * <ul>
 *   <li><b>Whisper</b> ({@code base.en} … {@code large}) — an mlx-community
 *       snapshot on Apple silicon, Systran faster-whisper CT2 weights on
 *       CUDA/CPU hosts (see transcribe.py's SIZES/MLX_REPOS). Segment times
 *       are emitted natively.</li>
 *   <li><b>MERaLiON</b> ({@code meralion-3-3b}) — a Southeast-Asian-tuned
 *       speech LLM (meralion.py) that produces a plain transcript, paired with
 *       forced alignment (align.py) to recover segment times. Richer on SEA
 *       audio (Malay/Mandarin/Tamil code-switching); see serve.py's
 *       MERALION_HF routing.</li>
 * </ul>
 *
 * <p>{@code approxSizeMb} is the rough download size of the engine artifact
 * (fp16-class), used as the Settings progress denominator until the live
 * X-Linked-Size from HF replaces it once a download begins.
 *
 * <p>Whisper's large model is multilingual-only — there is no {@code large.en}.
 * Two large options are exposed: canonical {@code large} (v3) and
 * {@code large-turbo} (v3-turbo).
 */
public enum AsrModel {
    BASE_EN("base.en", "Base (English)", 300),
    SMALL_EN("small.en", "Small (English)", 950),
    MEDIUM_EN("medium.en", "Medium (English)", 1550),
    SMALL_MULTILINGUAL("small", "Small (Multilingual)", 950),
    MEDIUM_MULTILINGUAL("medium", "Medium (Multilingual)", 1550),
    LARGE_TURBO("large-turbo", "Large v3 Turbo (Multilingual)", 1650),
    LARGE("large", "Large v3 (Multilingual)", 3100),
    MERALION_3_3B("meralion-3-3b", "MERaLiON-3 3B (SE Asian)", 6600);

    /** Multilingual by default: JClaw is general-purpose and must not assume
     *  English audio. {@code small} is the same 950 MB as {@code small.en} but
     *  handles every language — the safe default; the operator can pick an
     *  English-only, larger, or SEA-tuned model in Settings. */
    public static final AsrModel DEFAULT = SMALL_MULTILINGUAL;

    private final String id;
    private final String displayName;
    private final int approxSizeMb;

    AsrModel(String id, String displayName, int approxSizeMb) {
        this.id = id;
        this.displayName = displayName;
        this.approxSizeMb = approxSizeMb;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    /** Approximate on-disk size in MB. Used by the Settings UI before the file
     *  is downloaded; the live X-Linked-Size from HF replaces this once a
     *  download begins. Q5_1 sizes don't drift between releases. */
    public int approxSizeMb() { return approxSizeMb; }

    public static Optional<AsrModel> byId(String id) {
        if (id == null) return Optional.empty();
        for (var m : values()) {
            if (m.id.equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
