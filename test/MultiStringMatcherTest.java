import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.MultiStringMatcher;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JF-361: the one-pass matcher the credential mask replaces values with. */
class MultiStringMatcherTest extends UnitTest {

    private static String masked(Map<String, String> strings, String text) {
        return new MultiStringMatcher<>(strings).replaceAll(text, label -> "[" + label + "]");
    }

    @Test
    void aStringInsideALongerLineIsReplacedExactly() {
        assertEquals("token=[KEY]; next", masked(Map.of("s3cr3t-value", "KEY"), "token=s3cr3t-value; next"));
        assertEquals("[KEY][KEY]", masked(Map.of("s3cr3t-value", "KEY"), "s3cr3t-values3cr3t-value"));
    }

    @Test
    void ofOverlappingStringsTheLeftmostAndThenTheLongestWins() {
        var strings = new LinkedHashMap<String, String>();
        strings.put("abcdefgh12", "A");
        strings.put("defgh12345", "B");
        assertEquals("xx[A]345yy", masked(strings, "xxabcdefgh12345yy"), "the one that starts first wins");
        strings.clear();
        strings.put("abcdefgh", "SHORT");
        strings.put("abcdefghij", "LONG");
        assertEquals("[LONG]k", masked(strings, "abcdefghijk"), "of two that start together, the longer");
        assertEquals("[SHORT]X", masked(strings, "abcdefghX"));
    }

    @Test
    void aStringFoundAfterAFalseStartIsStillFound() {
        assertEquals("aab[K]", masked(Map.of("abcabd", "K"), "aababcabd"));
    }

    @Test
    void noStringsOrNoTextChangesNothing() {
        assertEquals("plain text", masked(Map.of(), "plain text"));
        assertEquals("", masked(Map.of("abcdefgh", "K"), ""));
        assertEquals("nothing here", masked(Map.of("abcdefgh", "K"), "nothing here"));
    }

    /** One pass over the text whatever the number of strings: 2,000 strings over a 1 MB text. */
    @Test
    void manyStringsOverALargeTextStayFast() {
        var strings = new LinkedHashMap<String, String>();
        for (int i = 0; i < 2_000; i++) strings.put("credential-%05d-%08x".formatted(i, i * 2654435761L), "K" + i);
        var text = new StringBuilder();
        int planted = 0;
        while (text.length() < 1_000_000) {
            text.append("ordinary line of tool output with nothing secret in it ");
            if (text.length() % 20_000 < 60) {
                text.append("credential-01234-").append("%08x".formatted(1234 * 2654435761L)).append(' ');
                planted++;
            }
        }
        var matcher = new MultiStringMatcher<>(strings);
        long started = System.nanoTime();
        var out = matcher.replaceAll(text.toString(), label -> "[masked: " + label + "]");
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(planted, out.split("\\[masked: K1234]", -1).length - 1);
        assertTrue(millis < 3_000, "one pass over 1 MB took " + millis + " ms");
    }
}
