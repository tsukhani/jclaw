package services.compression;

/**
 * Outcome of compressing one message body. Carries the (possibly transformed)
 * content, the algorithm that produced it, and whether anything actually
 * changed.
 *
 * <p>Token accounting is deliberately NOT on this record: token counts are
 * model/encoding-dependent, so the pipeline measures them via
 * {@code TokenUsageEstimator} where the model is known. That keeps the
 * compressors pure string→string transforms, unit-testable without a tokenizer.
 */
public record CompressionResult(String content, String algorithm, boolean changed) {

    /** The input was left as-is (unparseable, already minimal, or would inflate). */
    public static CompressionResult unchanged(String original, String algorithm) {
        return new CompressionResult(original, algorithm, false);
    }

    /** The content was rewritten to a shorter form. */
    public static CompressionResult compressed(String content, String algorithm) {
        return new CompressionResult(content, algorithm, true);
    }
}
