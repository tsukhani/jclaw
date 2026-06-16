package services.compression;

/**
 * A content-type-specific compressor (JCLAW-459 epic). Implementations are
 * pure string→string transforms with no tokenizer or model dependency — the
 * pipeline owns token measurement, the token-level inflation guard, and
 * routing by {@link ContentType}.
 */
public interface ContentCompressor {

    /** Stable identifier recorded in metrics, e.g. {@code "json-smartcrush"}. */
    String algorithm();

    /**
     * Compress {@code content}. Implementations MUST NOT inflate: return
     * {@link CompressionResult#unchanged} when the input can't be parsed or the
     * transform wouldn't shrink it. Lossy elisions are acceptable because the
     * pipeline retains the original (CCR) and exposes it for retrieval.
     */
    CompressionResult compress(String content);
}
