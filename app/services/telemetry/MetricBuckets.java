package services.telemetry;

import java.util.List;

/** Bucket advice: the SDK's defaults are millisecond-scaled, so in seconds every value under 5 lands in one bucket. */
final class MetricBuckets {

    /** The GenAI semconv recommendation for {@code gen_ai.client.operation.duration}. */
    static final List<Double> GEN_AI_SECONDS = List.of(
            0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92);

    /** The GenAI semconv recommendation for {@code gen_ai.client.token.usage}. */
    static final List<Double> TOKENS = List.of(
            1d, 4d, 16d, 64d, 256d, 1024d, 4096d, 16384d, 65536d, 262144d, 1048576d, 4194304d, 16777216d, 67108864d);

    /** Turn segments run from a 1 ms persist to a minute-long stream body. */
    static final List<Double> SEGMENT_SECONDS = List.of(
            0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1d, 2.5, 5d, 10d, 30d, 60d);

    private MetricBuckets() {}
}
