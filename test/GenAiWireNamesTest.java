import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.telemetry.GenAiAttributes;
import services.telemetry.GenAiMetrics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pins the GenAI wire names jclaw emits. They track the upstream GenAI semantic-conventions
 * registry rather than a Java artifact, so nothing else notices a drift — and GenAiSpansTest
 * asserts through these same constants, so a typo in the constant and the test would still pass.
 * A change here should be deliberate and made alongside the upstream check in /renovate.
 */
class GenAiWireNamesTest extends UnitTest {

    private static final Map<String, AttributeType> ATTRIBUTES = Map.ofEntries(
            Map.entry("gen_ai.conversation.id", AttributeType.STRING),
            Map.entry("gen_ai.operation.name", AttributeType.STRING),
            Map.entry("gen_ai.provider.name", AttributeType.STRING),
            Map.entry("gen_ai.request.max_tokens", AttributeType.LONG),
            Map.entry("gen_ai.request.model", AttributeType.STRING),
            Map.entry("gen_ai.request.stream", AttributeType.BOOLEAN),
            Map.entry("gen_ai.response.finish_reasons", AttributeType.STRING_ARRAY),
            Map.entry("gen_ai.response.id", AttributeType.STRING),
            Map.entry("gen_ai.response.model", AttributeType.STRING),
            Map.entry("gen_ai.response.time_to_first_chunk", AttributeType.DOUBLE),
            Map.entry("gen_ai.token.type", AttributeType.STRING),
            Map.entry("gen_ai.usage.cache_read.input_tokens", AttributeType.LONG),
            Map.entry("gen_ai.usage.cache_write.input_tokens", AttributeType.LONG),
            Map.entry("gen_ai.usage.input_tokens", AttributeType.LONG),
            Map.entry("gen_ai.usage.output_tokens", AttributeType.LONG),
            Map.entry("gen_ai.usage.reasoning.output_tokens", AttributeType.LONG));

    @Test
    void everyAttributeKeyMatchesItsPinnedWireNameAndType() throws IllegalAccessException {
        // Collected by reflection, so a key added without being pinned fails as surely as a renamed one.
        var actual = new TreeMap<String, AttributeType>();
        for (Field f : GenAiAttributes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != AttributeKey.class) continue;
            var key = (AttributeKey<?>) f.get(null);
            actual.put(key.getKey(), key.getType());
        }
        assertEquals(new TreeMap<>(ATTRIBUTES), actual);
    }

    @Test
    void tokenTypeValuesMatchTheUpstreamEnum() {
        assertEquals("input", GenAiAttributes.TokenTypeValues.INPUT);
        assertEquals("output", GenAiAttributes.TokenTypeValues.OUTPUT);
    }

    @Test
    void metricNamesAndUnitsArePinned() {
        // Units matter as much as names: a collector aggregates by both.
        assertEquals("gen_ai.client.operation.duration", GenAiMetrics.GEN_AI_CLIENT_OPERATION_DURATION_NAME);
        assertEquals("s", GenAiMetrics.GEN_AI_CLIENT_OPERATION_DURATION_UNIT);
        assertEquals("gen_ai.client.operation.time_to_first_chunk",
                GenAiMetrics.GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_NAME);
        assertEquals("s", GenAiMetrics.GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_UNIT);
        assertEquals("gen_ai.client.token.usage", GenAiMetrics.GEN_AI_CLIENT_TOKEN_USAGE_NAME);
        assertEquals("{token}", GenAiMetrics.GEN_AI_CLIENT_TOKEN_USAGE_UNIT);
    }
}
