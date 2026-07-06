package services.transcription;

import java.util.Optional;

/**
 * The Whisper model variants jclaw exposes in Settings. Each variant's
 * stable {@code id} is the value of the {@code transcription.localModel}
 * config key; the ASR sidecar maps it to the HOST engine's artifact
 * (JCLAW-650: an mlx-community snapshot on Apple silicon, Systran
 * faster-whisper CT2 weights on CUDA/CPU hosts — see transcribe.py's
 * SIZES/MLX_REPOS tables). {@code approxSizeMb} is the rough weight size
 * of those engine artifacts (fp16-class), used as the Settings progress
 * denominator.
 *
 * <p>Whisper's large model is multilingual-only — there is no
 * {@code large.en}. Two large options are exposed: canonical {@code large}
 * (v3) and {@code large-turbo} (v3-turbo).
 */
public enum WhisperModel {
    BASE_EN("base.en", "Base (English)", 300),
    SMALL_EN("small.en", "Small (English)", 950),
    MEDIUM_EN("medium.en", "Medium (English)", 1550),
    SMALL_MULTILINGUAL("small", "Small (Multilingual)", 950),
    MEDIUM_MULTILINGUAL("medium", "Medium (Multilingual)", 1550),
    LARGE_TURBO("large-turbo", "Large v3 Turbo (Multilingual)", 1650),
    LARGE("large", "Large v3 (Multilingual)", 3100);

    public static final WhisperModel DEFAULT = SMALL_EN;

    private final String id;
    private final String displayName;
    private final int approxSizeMb;

    WhisperModel(String id, String displayName, int approxSizeMb) {
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

    public static Optional<WhisperModel> byId(String id) {
        if (id == null) return Optional.empty();
        for (var m : values()) {
            if (m.id.equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
