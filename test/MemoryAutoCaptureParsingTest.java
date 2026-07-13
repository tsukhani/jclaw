import memory.MemoryAutoCapture;
import memory.MemoryCategory;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.Set;

/**
 * Branch coverage for {@link MemoryAutoCapture}'s pure parsing / eligibility /
 * similarity helpers (JCLAW-707). Complements the existing
 * {@code MemoryAutoCaptureTest} (which drives {@code capture()} end-to-end via
 * the store) by targeting the branchy, DB-free logic directly:
 * {@code parseSupersessions} (the JCLAW-525 judge parser with its many
 * validity guards), the additional {@code parseCandidates} edge arms not
 * already exercised, {@code captureEligible}, and the {@code tokenize} /
 * {@code jaccard} / {@code stripFences} primitives (reached by reflection —
 * they are package-private statics).
 *
 * <p>None of these touch the Lucene index or the database, so no
 * {@code LuceneTestSync} / {@code Fixtures} plumbing is needed.
 */
class MemoryAutoCaptureParsingTest extends UnitTest {

    @SuppressWarnings("unchecked")
    private static Set<String> tokenize(String s) throws Exception {
        var m = MemoryAutoCapture.class.getDeclaredMethod("tokenize", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, s);
    }

    private static double jaccard(Set<String> a, Set<String> b) throws Exception {
        var m = MemoryAutoCapture.class.getDeclaredMethod("jaccard", Set.class, Set.class);
        m.setAccessible(true);
        return (double) m.invoke(null, a, b);
    }

    private static String stripFences(String s) throws Exception {
        var m = MemoryAutoCapture.class.getDeclaredMethod("stripFences", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    // ─── parseSupersessions ──────────────────────────────────────────────────

    @Test
    void parseSupersessionsNullBlankAndNonObjectYieldEmpty() {
        assertTrue(MemoryAutoCapture.parseSupersessions(null, 1, 1).isEmpty());
        assertTrue(MemoryAutoCapture.parseSupersessions("   ", 1, 1).isEmpty());
        assertTrue(MemoryAutoCapture.parseSupersessions("[]", 1, 1).isEmpty(), "array root → empty");
        assertTrue(MemoryAutoCapture.parseSupersessions("{}", 1, 1).isEmpty(), "no supersessions key");
        assertTrue(MemoryAutoCapture.parseSupersessions("{\"supersessions\":\"x\"}", 1, 1).isEmpty(),
                "supersessions not an array");
    }

    @Test
    void parseSupersessionsRecordsValidEntry() {
        var map = MemoryAutoCapture.parseSupersessions(
                "{\"supersessions\":[{\"new\":0,\"old\":[1,2]}]}", 1, 3);
        assertEquals(1, map.size());
        assertEquals(java.util.List.of(1, 2), map.get(0));
    }

    @Test
    void parseSupersessionsDropsOutOfRangeNewIndex() {
        assertTrue(MemoryAutoCapture.parseSupersessions(
                "{\"supersessions\":[{\"new\":5,\"old\":[0]}]}", 1, 3).isEmpty(),
                "new index >= newCount is dropped");
    }

    @Test
    void parseSupersessionsFiltersOldIndicesAndDedups() {
        var map = MemoryAutoCapture.parseSupersessions(
                "{\"supersessions\":[{\"new\":0,\"old\":[1,1,9,-1]}]}", 1, 3);
        // 9 and -1 out of range dropped; the duplicate 1 collapses.
        assertEquals(java.util.List.of(1), map.get(0));
    }

    @Test
    void parseSupersessionsSkipsInvalidEntriesButKeepsValidOnes() {
        var map = MemoryAutoCapture.parseSupersessions(
                "{\"supersessions\":["
                        + "{\"new\":0},"                       // missing old
                        + "{\"new\":0,\"old\":[]},"            // empty olds → not added
                        + "{\"new\":0,\"old\":\"x\"},"         // old not an array
                        + "{\"new\":1,\"old\":[2]}"            // valid
                        + "]}", 2, 3);
        assertEquals(1, map.size(), "only the valid entry survives");
        assertEquals(java.util.List.of(2), map.get(1));
    }

    @Test
    void parseSupersessionsStripsCodeFencesAndFailsOpenOnGarbage() {
        var fenced = MemoryAutoCapture.parseSupersessions(
                "```json\n{\"supersessions\":[{\"new\":0,\"old\":[0]}]}\n```", 1, 1);
        assertEquals(java.util.List.of(0), fenced.get(0), "fences stripped before parse");
        assertTrue(MemoryAutoCapture.parseSupersessions("{not valid json", 1, 1).isEmpty(),
                "malformed JSON → fail open (empty)");
    }

    // ─── parseCandidates: additional edge arms ───────────────────────────────

    @Test
    void parseCandidatesRootPrimitiveYieldsEmpty() {
        assertTrue(MemoryAutoCapture.parseCandidates("42").isEmpty(),
                "a bare JSON primitive is neither object nor array → empty");
    }

    @Test
    void parseCandidatesSkipsNonObjectAndTextlessEntries() {
        var cands = MemoryAutoCapture.parseCandidates(
                "[1, {\"category\":\"fact\"}, {\"text\":null}, {\"text\":\"   \"}, {\"text\":\"real fact\"}]");
        assertEquals(1, cands.size(), "only the entry with non-blank text survives");
        assertEquals("real fact", cands.getFirst().text());
    }

    @Test
    void parseCandidatesImportanceStringNumberIsParsed() {
        var c = MemoryAutoCapture.parseCandidates(
                "{\"memories\":[{\"text\":\"x\",\"category\":\"fact\",\"importance\":\"0.3\"}]}").getFirst();
        assertEquals(0.3, c.importance(), 1e-9);
    }

    @Test
    void parseCandidatesNonNumericImportanceFallsBackToBaseline() {
        var c = MemoryAutoCapture.parseCandidates(
                "{\"memories\":[{\"text\":\"x\",\"category\":\"fact\",\"importance\":\"abc\"}]}").getFirst();
        assertEquals(MemoryCategory.BASELINE_IMPORTANCE, c.importance(), 1e-9);
    }

    // ─── captureEligible ─────────────────────────────────────────────────────

    @Test
    void captureEligibleRules() {
        assertFalse(MemoryAutoCapture.captureEligible(null), "null agent → not eligible");

        var root = new Agent();
        assertTrue(MemoryAutoCapture.captureEligible(root), "root agent, capture on by default → eligible");

        var subagent = new Agent();
        subagent.parentAgent = new Agent();
        assertFalse(MemoryAutoCapture.captureEligible(subagent), "subagents are excluded");

        var disabled = new Agent();
        disabled.memoryAutocaptureEnabled = false;
        assertFalse(MemoryAutoCapture.captureEligible(disabled), "capture disabled → not eligible");
    }

    // ─── tokenize / jaccard / stripFences ────────────────────────────────────

    @Test
    void tokenizeLowercasesSplitsAndDropsBlanks() throws Exception {
        assertTrue(tokenize(null).isEmpty(), "null → empty set");
        assertEquals(Set.of("hello", "world", "foo", "bar"),
                tokenize("Hello, WORLD!  foo_bar"),
                "lowercased, split on non-alphanumerics (underscore included), blanks dropped");
    }

    @Test
    void jaccardEmptyAndOverlapBranches() throws Exception {
        assertEquals(1.0, jaccard(Set.of(), Set.of()), 1e-9, "both empty → 1.0");
        assertEquals(0.0, jaccard(Set.of(), Set.of("a")), 1e-9, "one empty → 0.0");
        assertEquals(1.0 / 3.0, jaccard(Set.of("a", "b"), Set.of("b", "c")), 1e-9,
                "|inter|=1, |union|=3");
    }

    @Test
    void stripFencesVariants() throws Exception {
        assertEquals("{}", stripFences("```json\n{}\n```"), "fenced with language + closing");
        assertEquals("no fence", stripFences("no fence"), "unfenced unchanged");
        assertEquals("abc", stripFences("```\nabc"), "opening fence only, no closing");
    }
}
