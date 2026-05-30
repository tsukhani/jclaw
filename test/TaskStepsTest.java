import org.junit.jupiter.api.*;
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

    @Test
    void malformedJsonArrayFallsBackToSingleStep() {
        var raw = "[not valid json";
        assertEquals(List.of(raw), TaskSteps.parse(raw));
    }

    @Test
    void nonStringArrayElementsFallBackToSingleStep() {
        var raw = "[\"ok\", 42, true]";
        assertEquals(List.of(raw), TaskSteps.parse(raw));
    }

    @Test
    void emptyJsonArrayFallsBackToSingleStep() {
        assertEquals(List.of("[]"), TaskSteps.parse("[]"));
    }

    @Test
    void jsonObjectIsTreatedAsPlainText() {
        var raw = "{\"foo\":\"bar\"}";
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
