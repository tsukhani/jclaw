package services.telemetry;

import io.opentelemetry.api.common.AttributeKey;

import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.doubleKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The GenAI semantic-convention attribute keys jclaw records on model-call and turn spans.
 *
 * <p>opentelemetry-semconv-incubating deprecated its copies when the GenAI conventions moved to
 * their own repository, which publishes no Java artifact, so jclaw owns them. The wire names are
 * the contract — collectors, dashboards and queries key on them — and must stay identical to the
 * semconv ones they replace. Constant names match semconv's so call sites read unchanged.
 */
public final class GenAiAttributes {

    private GenAiAttributes() {}

    public static final AttributeKey<String> GEN_AI_CONVERSATION_ID = stringKey("gen_ai.conversation.id");
    public static final AttributeKey<String> GEN_AI_OPERATION_NAME = stringKey("gen_ai.operation.name");
    public static final AttributeKey<String> GEN_AI_PROVIDER_NAME = stringKey("gen_ai.provider.name");
    public static final AttributeKey<Long> GEN_AI_REQUEST_MAX_TOKENS = longKey("gen_ai.request.max_tokens");
    public static final AttributeKey<String> GEN_AI_REQUEST_MODEL = stringKey("gen_ai.request.model");
    public static final AttributeKey<Boolean> GEN_AI_REQUEST_STREAM = booleanKey("gen_ai.request.stream");
    public static final AttributeKey<List<String>> GEN_AI_RESPONSE_FINISH_REASONS =
            stringArrayKey("gen_ai.response.finish_reasons");
    public static final AttributeKey<String> GEN_AI_RESPONSE_ID = stringKey("gen_ai.response.id");
    public static final AttributeKey<String> GEN_AI_RESPONSE_MODEL = stringKey("gen_ai.response.model");
    public static final AttributeKey<Double> GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK =
            doubleKey("gen_ai.response.time_to_first_chunk");
    public static final AttributeKey<String> GEN_AI_TOKEN_TYPE = stringKey("gen_ai.token.type");
    public static final AttributeKey<Long> GEN_AI_USAGE_CACHE_CREATION_INPUT_TOKENS =
            longKey("gen_ai.usage.cache_creation.input_tokens");
    public static final AttributeKey<Long> GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS =
            longKey("gen_ai.usage.cache_read.input_tokens");
    public static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = longKey("gen_ai.usage.input_tokens");
    public static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = longKey("gen_ai.usage.output_tokens");
    public static final AttributeKey<Long> GEN_AI_USAGE_REASONING_OUTPUT_TOKENS =
            longKey("gen_ai.usage.reasoning.output_tokens");

    /** Values for {@link #GEN_AI_TOKEN_TYPE}. */
    public static final class TokenTypeValues {
        public static final String INPUT = "input";
        public static final String OUTPUT = "output";

        private TokenTypeValues() {}
    }
}
