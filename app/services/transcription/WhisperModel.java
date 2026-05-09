package services.transcription;

import java.util.Optional;

/**
 * Whisper.cpp GGML model variants jclaw supports for offline transcription.
 *
 * <p>Each variant is identified by a stable {@code id} used as the value of
 * the {@code transcription.localModel} config key, mapped to the GGML
 * filename ggerganov publishes on Hugging Face. All five variants are pinned
 * to Q5_1 quantization — a good size/quality knee for laptop-class CPUs.
 *
 * <p>Files are downloaded on demand from
 * {@code https://huggingface.co/ggerganov/whisper.cpp/resolve/main/{filename}};
 * SHA256 verification reads the {@code X-Linked-Etag} header HF returns on
 * the resolve endpoint, so we don't carry a hand-maintained manifest.
 */
public enum WhisperModel {
    BASE_EN("base.en", "ggml-base.en-q5_1.bin"),
    SMALL_EN("small.en", "ggml-small.en-q5_1.bin"),
    MEDIUM_EN("medium.en", "ggml-medium.en-q5_1.bin"),
    SMALL_MULTILINGUAL("small", "ggml-small-q5_1.bin"),
    MEDIUM_MULTILINGUAL("medium", "ggml-medium-q5_1.bin");

    public static final WhisperModel DEFAULT = SMALL_EN;
    private static final String HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String id;
    private final String filename;

    WhisperModel(String id, String filename) {
        this.id = id;
        this.filename = filename;
    }

    public String id() { return id; }
    public String filename() { return filename; }
    public String downloadUrl() { return HF_BASE + filename; }

    public static Optional<WhisperModel> byId(String id) {
        if (id == null) return Optional.empty();
        for (var m : values()) {
            if (m.id.equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
