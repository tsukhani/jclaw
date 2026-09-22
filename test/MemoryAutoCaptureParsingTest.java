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
 * {@code jaccard} primitives (reached by reflection —
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
    void parseCandidatesNeverYieldsACoreMemory() {
        // JCLAW-981: core is the always-loaded tier and is the operator's to grant, via an
        // explicit "remember that…". Dropping it from the extractor prompt is not enough —
        // the extractor already returns labels outside the set it is given (JCLAW-927), so
        // the parse has to be what refuses it.
        //
        // JCLAW-529 narrowed this to the model's *label* only: identity facts are promoted
        // on their content by MemoryIdentityClass. This text is deliberately outside that
        // class, so the original refusal is what is asserted here.
        var cands = MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"The user is a staff engineer\",\"category\":\"core\",\"importance\":0.95}]");

        assertEquals(1, cands.size(), "the memory is still captured — only its category changes");
        assertEquals(memory.MemoryCategory.FACT.label, cands.getFirst().category(),
                "a capture labelled core must be demoted, never stored as core");
    }

    @Test
    void parseCandidatesPromotesAnIdentityFactToCore() {
        // The failure this exists for: an identity fact left in the retrieval pool scored
        // below an unrelated memory on a real corpus. The always-loaded tier is where it belongs.
        var cands = MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"The user's son Arun goes by Bo\",\"category\":\"entity\",\"importance\":0.7}]");

        assertEquals(memory.MemoryCategory.CORE.label, cands.getFirst().category());
    }

    @Test
    void promotionLiftsImportanceAboveTheCoreLoadThreshold() {
        // findCore filters on memory.coreload.minImportance (0.8) and the extractor scores
        // facts of this shape at 0.7, so promoting without the lift admits a memory to a
        // tier that then never renders it — the defect would survive looking fixed.
        var cands = MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"The user's wife is named Renu\",\"category\":\"entity\",\"importance\":0.7}]");

        assertTrue(cands.getFirst().importance() >= 0.8,
                "promoted but unrenderable is not promoted: " + cands.getFirst().importance());
    }

    @Test
    void anOrdinaryFactIsNotPromoted() {
        var cands = MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"The deploy pipeline requires manual approval\",\"category\":\"fact\",\"importance\":0.5}]");

        assertEquals(memory.MemoryCategory.FACT.label, cands.getFirst().category());
        assertEquals(0.5, cands.getFirst().importance(), 1e-9, "no lift for a non-identity fact");
    }

    @Test
    void parseCandidatesKeepsTheQuestionsAsARetrievalKey() {
        var cands = MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"Arun goes by Bo\",\"category\":\"entity\",\"importance\":0.6,"
                + "\"questions\":[\"what do my kids go by?\",\"what is Arun's nickname?\"]}]");

        assertEquals("what do my kids go by?\nwhat is Arun's nickname?",
                cands.getFirst().retrievalKey());
    }

    @Test
    void omittedOrMalformedQuestionsLeaveNoKeyRatherThanFailing() {
        // Absent is the pre-JCLAW-529 behaviour: the row embeds its statement alone.
        assertNull(MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"t\",\"category\":\"fact\",\"importance\":0.5}]").getFirst().retrievalKey());
        assertNull(MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"t\",\"category\":\"fact\",\"importance\":0.5,\"questions\":\"oops\"}]")
                .getFirst().retrievalKey());
        assertNull(MemoryAutoCapture.parseCandidates(
                "[{\"text\":\"t\",\"category\":\"fact\",\"importance\":0.5,\"questions\":[\"  \"]}]")
                .getFirst().retrievalKey());
    }

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

    // ─── tokenize / jaccard ──────────────────────────────────────────────────

    @Test
    void tokenizeLowercasesSplitsAndDropsBlanks() throws Exception {
        assertTrue(tokenize(null).isEmpty(), "null → empty set");
        // JCLAW-1054 moved this onto the search analyzer, which keeps an underscored
        // identifier whole where the old split broke it apart. That is the improvement, not
        // a side effect: splitting made every *_id identifier share the token "id", so two
        // unrelated memories mentioning task_id and run_id overlapped on nothing real.
        assertEquals(Set.of("hello", "world", "foo_bar"),
                tokenize("Hello, WORLD!  foo_bar"),
                "lowercased, word-boundary tokenized, identifiers kept whole");
    }

    @Test
    void jaccardEmptyAndOverlapBranches() throws Exception {
        assertEquals(1.0, jaccard(Set.of(), Set.of()), 1e-9, "both empty → 1.0");
        assertEquals(0.0, jaccard(Set.of(), Set.of("a")), 1e-9, "one empty → 0.0");
        assertEquals(1.0 / 3.0, jaccard(Set.of("a", "b"), Set.of("b", "c")), 1e-9,
                "|inter|=1, |union|=3");
    }

    // --- JCLAW-927: the extractor ignoring its closed set of six ---

    @Test
    void anInventedCategoryIsCoercedRatherThanStoredVerbatim() {
        // Observed live: the prompt names six and says to pick exactly one, and the model
        // returned opinion / belief / instruction / project anyway.
        var got = MemoryAutoCapture.parseCandidates(
                "{\"memories\":[{\"text\":\"The user prefers dark mode\",\"category\":\"opinion\","
                        + "\"importance\":0.7}]}");

        assertEquals(1, got.size());
        assertEquals(MemoryCategory.FACT.label, got.getFirst().category(),
                "an unrecognised label must not reach the database");
        assertEquals(0.7, got.getFirst().importance(), 1e-9,
                "coercing the label must not disturb an explicit importance");
    }

    @Test
    void aCanonicalCategorySurvivesCoercionUnchanged() {
        // Guards against the coercion being unconditional, which would flatten the whole
        // taxonomy to fact and be invisible in a test that only checks invented labels.
        // CORE is excluded deliberately: capture may not assign it (JCLAW-981), and
        // parseCandidatesNeverYieldsACoreMemory above is what pins that.
        for (var c : MemoryCategory.values()) {
            if (c == MemoryCategory.CORE) continue;
            var got = MemoryAutoCapture.parseCandidates(
                    "{\"memories\":[{\"text\":\"t\",\"category\":\"" + c.label + "\"}]}");
            assertEquals(c.label, got.getFirst().category(), "must preserve " + c.label);
        }
    }

    @Test
    void anInventedCategoryTakesTheCoercedBucketsDefaultImportance() {
        // The latent half of this bug: with no explicit importance, an unrecognised label
        // fell through defaultImportanceFor to BASELINE rather than to a bucket default.
        var got = MemoryAutoCapture.parseCandidates(
                "{\"memories\":[{\"text\":\"The user ships on Fridays\",\"category\":\"project\"}]}");

        assertEquals(MemoryCategory.FACT.label, got.getFirst().category());
        assertEquals(MemoryCategory.FACT.defaultImportance, got.getFirst().importance(), 1e-9);
    }

    @Test
    void coercionIsAWritePathOnlyRuleAndReadsStillPassThrough() {
        // Pre-existing rows hold labels outside the six. normalize() is what renders them,
        // and tightening the write path must not make them disappear from the admin UI.
        assertEquals("opinion", MemoryCategory.normalize("  Opinion  "));
        assertEquals(MemoryCategory.FACT.label, MemoryCategory.coerceForStorage("opinion"));
    }
}
