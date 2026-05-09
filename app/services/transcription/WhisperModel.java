package services.transcription;

import java.util.Optional;

/**
 * Whisper.cpp GGML model variants jclaw supports for offline transcription.
 *
 * <p>Each variant is identified by a stable {@code id} used as the value of
 * the {@code transcription.localModel} config key, mapped to the GGML
 * filename ggerganov publishes on Hugging Face. Quantizations are picked
 * per-model based on what ggerganov actually ships: base / small variants
 * use Q5_1 (the newer block-quantization format), medium uses Q5_0 (no Q5_1
 * was ever published for medium), and large variants use Q5_0 (the only
 * Q5 quantization shipped for large).
 *
 * <p>Files are downloaded on demand from
 * {@code https://huggingface.co/ggerganov/whisper.cpp/resolve/main/{filename}};
 * SHA256 verification reads the {@code X-Linked-Etag} header HF returns on
 * the resolve endpoint, so we don't carry a hand-maintained manifest.
 *
 * <p>Whisper's large model is multilingual-only — there is no {@code large.en}
 * variant to ship. Two large options are exposed: canonical {@code large} (v3)
 * and {@code large-turbo} (v3-turbo), the speed-optimized variant ggerganov
 * ships for whisper.cpp users.
 */
public enum WhisperModel {
    BASE_EN("base.en", "ggml-base.en-q5_1.bin", "Base (English)", 57),
    SMALL_EN("small.en", "ggml-small.en-q5_1.bin", "Small (English)", 190),
    MEDIUM_EN("medium.en", "ggml-medium.en-q5_0.bin", "Medium (English)", 514),
    SMALL_MULTILINGUAL("small", "ggml-small-q5_1.bin", "Small (Multilingual)", 190),
    MEDIUM_MULTILINGUAL("medium", "ggml-medium-q5_0.bin", "Medium (Multilingual)", 514),
    LARGE_TURBO("large-turbo", "ggml-large-v3-turbo-q5_0.bin", "Large v3 Turbo (Multilingual)", 547),
    LARGE("large", "ggml-large-v3-q5_0.bin", "Large v3 (Multilingual)", 1031);

    public static final WhisperModel DEFAULT = SMALL_EN;
    private static final String HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String id;
    private final String filename;
    private final String displayName;
    private final int approxSizeMb;

    WhisperModel(String id, String filename, String displayName, int approxSizeMb) {
        this.id = id;
        this.filename = filename;
        this.displayName = displayName;
        this.approxSizeMb = approxSizeMb;
    }

    public String id() { return id; }
    public String filename() { return filename; }
    public String displayName() { return displayName; }
    /** Approximate on-disk size in MB. Used by the Settings UI before the file
     *  is downloaded; the live X-Linked-Size from HF replaces this once a
     *  download begins. Q5_1 sizes don't drift between releases. */
    public int approxSizeMb() { return approxSizeMb; }
    public String downloadUrl() { return HF_BASE + filename; }

    public static Optional<WhisperModel> byId(String id) {
        if (id == null) return Optional.empty();
        for (var m : values()) {
            if (m.id.equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
