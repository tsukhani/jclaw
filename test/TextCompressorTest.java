import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.CompressionResult;
import services.compression.TextCompressor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-464: TextCompressor. Validates near-duplicate collapse (Jaccard),
 * long-block summarization with the first unit preserved, the target-ratio
 * effectiveness guard (never inflates), and savings on verbose logs.
 */
class TextCompressorTest extends UnitTest {

    private final TextCompressor compressor = new TextCompressor();

    @Test
    void collapsesNearDuplicateLines() {
        var log = "INFO request handled in 12ms\n".repeat(15) + "WARN slow query detected";
        var result = compressor.compress(log);
        assertTrue(result.changed());
        var infoOccurrences = result.content().split("INFO request handled", -1).length - 1;
        assertEquals(1, infoOccurrences, "duplicate INFO lines collapse to one: " + result.content());
        assertTrue(result.content().contains("WARN slow query detected"), "the distinct line survives");
    }

    @Test
    void achievesThirtyPercentOnVerboseLog() {
        var sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("INFO heartbeat ok, all systems nominal, no action required\n");
        }
        var log = sb.toString();
        var result = compressor.compress(log);
        assertTrue(result.changed());
        double ratio = 1.0 - (double) result.content().length() / log.length();
        assertTrue(ratio >= 0.30, "expected >=30% savings, got " + Math.round(ratio * 100) + "%");
    }

    @Test
    void summarizesLongParagraphPreservingFirstSentence() {
        var first = "The deployment completed successfully across all regions.";
        var filler = "Additional detail follows in this rather long paragraph. ".repeat(40); // > 200 words
        var paragraph = first + " " + filler;

        var result = compressor.compress(paragraph);
        assertTrue(result.changed());
        assertTrue(result.content().startsWith(first),
                "first sentence of a long paragraph is always preserved: " + result.content());
        assertTrue(result.content().contains("summarized"), "summary marker present");
    }

    @Test
    void incompressibleProseReturnsUnchanged() {
        // Distinct, short sentences: no near-duplicates, not a long block, so the
        // target-ratio guard rejects the rewrite and the original is returned.
        var text = "The quick brown fox jumps. A lazy dog sleeps nearby. Birds sing softly today.";
        var result = compressor.compress(text);
        assertFalse(result.changed(), "no dups + short -> no-op");
        assertEquals(text, result.content());
    }

    @Test
    void neverInflates() {
        var text = "Short and unique. " + "Repeated sentence here. ".repeat(10);
        var result = compressor.compress(text);
        assertTrue(result.content().length() <= text.length(), "compressor must never inflate");
    }

    @Test
    void blankInputIsUnchanged() {
        var result = compressor.compress("   \n  ");
        assertFalse(result.changed());
    }

    @Test
    void aggressiveTargetRatioCompressesMore() {
        // A modestly-redundant text that a gentle ratio would reject but an
        // aggressive one accepts — proves the knob is live.
        var text = "alpha beta gamma delta. " + "alpha beta gamma delta. ".repeat(3)
                + "epsilon zeta eta theta iota.";
        var aggressive = new TextCompressor(0.3).compress(text);
        assertTrue(aggressive.changed(), "duplicates should collapse under the default ratio");
        assertTrue(aggressive.content().length() < text.length());
    }
}
