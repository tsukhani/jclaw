import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.*;
import services.TaskSteps;

import java.util.List;

/**
 * JCLAW-260: unit tests for {@link TaskSteps#parse} and
 * {@link TaskSteps#flattenForPrompt}. Pure functions — no DB needed.
 */
class TaskStepsTest extends UnitTest {

    // --- parse ---

    @Test
    void plainTextParsesToSingleStep() {
        assertEquals(List.of("Water the plants"), TaskSteps.parse("Water the plants"));
    }

    @Test
    void jsonArrayParsesToOrderedSteps() {
        var steps = TaskSteps.parse("[\"Fetch orders\",\"Summarise\",\"Post to Slack\"]");
        assertEquals(List.of("Fetch orders", "Summarise", "Post to Slack"), steps);
    }

    @Test
    void multiStepJsonRoundTrips() {
        // The LLM-supplied order is preserved exactly.
        assertEquals(List.of("a", "b", "c"), TaskSteps.parse("[\"a\",\"b\",\"c\"]"));
    }

    @Test
    void singleElementArrayParsesToOneStep() {
        assertEquals(List.of("only step"), TaskSteps.parse("[\"only step\"]"));
    }

    @Test
    void nullAndBlankParseToEmptyList() {
        assertTrue(TaskSteps.parse(null).isEmpty());
        assertTrue(TaskSteps.parse("").isEmpty());
        assertTrue(TaskSteps.parse("   ").isEmpty());
    }

    /**
     * Every input that isn't a JSON array of strings falls back to a
     * single-element list holding the raw text verbatim: malformed JSON,
     * an array with non-string elements, an empty array, and a JSON object.
     */
    @ParameterizedTest(name = "fallsBackToSingleStep[{0}]")
    @ValueSource(strings = {
            "[not valid json",
            "[\"ok\", 42, true]",
            "[]",
            "{\"foo\":\"bar\"}"
    })
    void nonStringArrayInputFallsBackToSingleStep(String raw) {
        assertEquals(List.of(raw), TaskSteps.parse(raw));
    }

    // --- flattenForPrompt ---

    @Test
    void flattenPlainTextIsVerbatim() {
        assertEquals("Water the plants", TaskSteps.flattenForPrompt("Water the plants"));
    }

    @Test
    void flattenMultiStepIsNumberedList() {
        var flat = TaskSteps.flattenForPrompt("[\"Fetch orders\",\"Summarise\",\"Post to Slack\"]");
        assertEquals("1. Fetch orders\n2. Summarise\n3. Post to Slack", flat);
    }

    @Test
    void flattenSingleElementArrayEqualsPlainString() {
        // No-regression invariant: a one-element array and the equivalent
        // plain string flatten to byte-identical prompts (no numbering).
        assertEquals("do X", TaskSteps.flattenForPrompt("[\"do X\"]"));
        assertEquals(TaskSteps.flattenForPrompt("do X"),
                TaskSteps.flattenForPrompt("[\"do X\"]"));
    }

    @Test
    void flattenNullAndBlankYieldEmpty() {
        assertEquals("", TaskSteps.flattenForPrompt(null));
        assertEquals("", TaskSteps.flattenForPrompt(""));
    }
}
