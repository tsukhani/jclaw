package services.telemetry;

/**
 * Names, descriptions and units of the GenAI client histograms jclaw records.
 *
 * <p>The metric-side sibling of {@link GenAiAttributes}, owned by jclaw for the same reason:
 * opentelemetry-semconv-incubating deprecated these when the GenAI conventions moved to a
 * repository with no Java artifact. Names and units are the contract — a collector aggregates
 * by both — and track {@code model/gen-ai/metrics.yaml} at the same upstream commit as
 * {@link GenAiAttributes}, checked the same way.
 */
public final class GenAiMetrics {

    private GenAiMetrics() {}

    public static final String GEN_AI_CLIENT_OPERATION_DURATION_NAME = "gen_ai.client.operation.duration";
    public static final String GEN_AI_CLIENT_OPERATION_DURATION_DESCRIPTION = "GenAI operation duration.";
    public static final String GEN_AI_CLIENT_OPERATION_DURATION_UNIT = "s";

    public static final String GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_NAME =
            "gen_ai.client.operation.time_to_first_chunk";
    public static final String GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_DESCRIPTION =
            "Time to receive the first chunk, measured from when the client issues the generation"
                    + " request to when the first chunk is received in the response stream.";
    public static final String GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_UNIT = "s";

    public static final String GEN_AI_CLIENT_TOKEN_USAGE_NAME = "gen_ai.client.token.usage";
    public static final String GEN_AI_CLIENT_TOKEN_USAGE_DESCRIPTION = "Number of input and output tokens used.";
    public static final String GEN_AI_CLIENT_TOKEN_USAGE_UNIT = "{token}";
}
