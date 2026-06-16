import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.JsonCompressor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-461: JSON SmartCrusher. Verifies schema/key preservation, array
 * elision with anomaly survival, value truncation, and the inflation guard.
 */
class JsonCompressorTest extends UnitTest {

    private final JsonCompressor compressor = new JsonCompressor();

    @Test
    void crushesLargeArrayWithSavings() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i)
                    .append(",\"title\":\"Result number ").append(i)
                    .append(" with a fairly long descriptive title that repeats\"}");
        }
        sb.append(']');
        var json = sb.toString();

        var result = compressor.compress(json);
        assertTrue(result.changed());
        // ≥60% char savings on a 500-item array (in practice ~99%).
        assertTrue(result.content().length() < json.length() * 0.4,
                "expected >=60% savings, got " + result.content().length() + " of " + json.length());
        assertTrue(result.content().contains("\"id\":0"), "first item kept in full");
        assertTrue(result.content().contains("items elided"), "elision marker present");
    }

    @Test
    void preservesAllObjectKeys() {
        var json = "{\"alpha\":\"" + "x".repeat(100) + "\",\"beta\":1,\"gamma\":true,\"delta\":null}";
        var out = compressor.compress(json).content();
        assertTrue(out.contains("\"alpha\""));
        assertTrue(out.contains("\"beta\""));
        assertTrue(out.contains("\"gamma\""));
        assertTrue(out.contains("\"delta\""));
    }

    @Test
    void preservesErrorItemsBeyondTheKeepWindow() {
        // 20 items; the error sits at index 10, past the first-3 keep window.
        var sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            if (i == 10) sb.append("{\"status\":\"ERROR\",\"msg\":\"disk full\"}");
            else sb.append("{\"status\":\"ok\",\"n\":").append(i).append('}');
        }
        sb.append(']');

        var out = compressor.compress(sb.toString()).content();
        assertTrue(out.toLowerCase().contains("disk full"),
                "anomaly should survive elision: " + out);
    }

    @Test
    void truncatesLongStringsButKeepsShortAndHighEntropy() {
        var uuid = "550e8400-e29b-41d4-a716-446655440000";
        var json = "{\"short\":\"hello\",\"id\":\"" + uuid + "\",\"blurb\":\""
                + "lorem ipsum dolor sit amet ".repeat(20) + "\"}";
        var out = compressor.compress(json).content();
        assertTrue(out.contains("\"hello\""), "short value kept");
        assertTrue(out.contains(uuid), "high-entropy id kept verbatim");
        assertTrue(out.contains("chars)"), "long value truncated with marker");
    }

    @Test
    void inflationGuardReturnsOriginalForTinyJson() {
        var json = "{\"a\":1}";
        var result = compressor.compress(json);
        assertFalse(result.changed());
        assertEquals(json, result.content());
    }

    @Test
    void leavesInvalidJsonUnchanged() {
        var bad = "{not valid";
        var result = compressor.compress(bad);
        assertFalse(result.changed());
        assertEquals(bad, result.content());
    }

    @Test
    void leavesNonStructuredJsonUnchanged() {
        var result = compressor.compress("\"just a string\"");
        assertFalse(result.changed());
    }
}
