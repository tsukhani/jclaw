package services.compression;

/**
 * Outcome of compressing one message body. Carries the (possibly transformed)
 * content, the algorithm that produced it, whether anything actually changed,
 * and whether a lossier fallback produced it after the structural path failed.
 *
 * <p>Token accounting is deliberately NOT on this record: token counts are
 * model/encoding-dependent, so the pipeline measures them via
 * {@code TokenUsageEstimator} where the model is known. That keeps the
 * compressors pure string→string transforms, unit-testable without a tokenizer.
 */
public record CompressionResult(String content, String algorithm, boolean changed, boolean degraded) {

    /** The input was left as-is (unparseable, already minimal, or would inflate). */
    public static CompressionResult unchanged(String original, String algorithm) {
        return new CompressionResult(original, algorithm, false, false);
    }

    /** The content was rewritten to a shorter form. */
    public static CompressionResult compressed(String content, String algorithm) {
        return new CompressionResult(content, algorithm, true, false);
    }

    /** Shortened, but by a lossier fallback the compressor reached for after its primary path failed. */
    public static CompressionResult degraded(String content, String algorithm) {
        return new CompressionResult(content, algorithm, true, true);
    }
}
