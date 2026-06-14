package services.caption;

import java.util.List;
import java.util.Optional;

/**
 * Local vision-language models jclaw can run in-JVM for offline image captioning (JCLAW-213).
 *
 * <p>Unlike a whisper GGML model (one {@code .bin}), a DJL/ONNX VLM is several files — a vision
 * encoder, a text decoder, and the tokenizer — so each variant carries a small manifest of
 * {@link FileSpec}s. Files are pulled on demand from Hugging Face and verified against a
 * <b>pinned SHA256</b> (the hashes below were computed from the exact bytes the resolve URLs serve);
 * a mismatch fails loudly rather than running a tampered/changed model.
 *
 * <p>v1 ships ViT-GPT2 (quantized, ~245 MB) — small, CPU-fast, generic-but-adequate captions for the
 * non-vision fallback. Richer small VLMs (SmolVLM-256M, Moondream-0.5B) can be added later behind the
 * same manifest shape (JCLAW-213 Phase 3).
 */
public enum VlmModel {

    VIT_GPT2("vit-gpt2", "ViT-GPT2 (image captioning)", 245, List.of(
            new FileSpec("encoder.onnx", "onnx/encoder_model_quantized.onnx",
                    "6f6e2e27c11303cf533682184543333e7ecb930a734197a0272a1e408aba2766"),
            new FileSpec("decoder.onnx", "onnx/decoder_model_quantized.onnx",
                    "4cd3a527b1acd9893dec2ef8f02b5e759c0365e365976a2f44313c6095a8a256"),
            new FileSpec("tokenizer.json", "tokenizer.json",
                    "6e785cd4f004c9291dadeba7d8d1c2871318d368a1cfcd416c0571dcd8f11413")));

    public static final VlmModel DEFAULT = VIT_GPT2;

    private static final String HF_BASE =
            "https://huggingface.co/Xenova/vit-gpt2-image-captioning/resolve/main/";

    /**
     * One file in a model's manifest.
     *
     * @param localName   filename on disk in the model's cache dir (what {@code LocalImageCaptioner}
     *                    expects: {@code encoder.onnx} / {@code decoder.onnx} / {@code tokenizer.json})
     * @param hfPath      path under the HF resolve base
     * @param sha256      pinned lowercase hex SHA256 of the file's bytes
     */
    public record FileSpec(String localName, String hfPath, String sha256) {
        public String url() {
            return HF_BASE + hfPath;
        }
    }

    private final String id;
    private final String displayName;
    private final int approxSizeMb;
    private final List<FileSpec> files;

    VlmModel(String id, String displayName, int approxSizeMb, List<FileSpec> files) {
        this.id = id;
        this.displayName = displayName;
        this.approxSizeMb = approxSizeMb;
        this.files = files;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    /** Approximate total on-disk size of all files, in MB (for the Settings UI before download). */
    public int approxSizeMb() { return approxSizeMb; }
    public List<FileSpec> files() { return files; }

    public static Optional<VlmModel> byId(String id) {
        if (id == null) return Optional.empty();
        for (var m : values()) {
            if (m.id.equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
