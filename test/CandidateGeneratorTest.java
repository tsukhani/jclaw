import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator;
import services.grapheval.CandidateGenerator.Candidate;
import services.grapheval.GraphCases;

import java.util.ArrayList;
import java.util.List;

/** JCLAW-1356, JCLAW-1357: the fixed candidate rules, with no model in the loop. */
class CandidateGeneratorTest extends UnitTest {

    /** Topic mentions found on {@code evals/graph/cases.json}: 18 of 19 when the frames landed (c078 missed). */
    private static final int TOPIC_RECALL_FLOOR = 18;

    private static List<String> spans(String text) {
        return CandidateGenerator.generate(text).stream().map(Candidate::span).toList();
    }

    @Test
    void theUserIsTheOperatorAndIsNeverTyped() {
        var candidates = CandidateGenerator.generate("Wren Castillo met the user in Port Calloway.");
        assertEquals(new Candidate("the user", true, false, 18, 26), candidates.getFirst());
        assertEquals(List.of("the user", "Wren Castillo", "Port Calloway"), spans("Wren Castillo met the user in Port Calloway."));
    }

    @Test
    void aSubjectlessMemoryGetsAnImplicitOperatorAndDropsItsOpeningVerb() {
        var candidates = CandidateGenerator.generate("Prefers Neovim over VS Code when editing on Kestrel.");
        assertEquals(new Candidate(GraphCases.IMPLICIT_OPERATOR_SPAN, true, true), candidates.getFirst());
        assertEquals(List.of("the user", "Neovim", "VS Code", "Kestrel"),
                spans("Prefers Neovim over VS Code when editing on Kestrel."));
    }

    @Test
    void subjectlessMeansAListedVerbFirst() {
        assertTrue(CandidateGenerator.subjectless("Uses Kestrel CI for releases."));
        assertTrue(CandidateGenerator.subjectless("Is allergic to peanuts."));
        assertFalse(CandidateGenerator.subjectless("The user uses Kestrel CI."));
        assertFalse(CandidateGenerator.subjectless("Usually walks to work."));
        assertFalse(CandidateGenerator.subjectless(""));
    }

    @Test
    void capitalizedRunsKeepConnectorsAndDropASentenceInitialDeterminer() {
        assertEquals(List.of("The user", "Bank of Montreal & Partners", "Larchmere-2 Labs"),
                spans("The user works at Bank of Montreal & Partners, then joined Larchmere-2 Labs in 2021."));
        assertEquals(List.of("Meridian Program"), spans("The Meridian Program ships in spring."));
    }

    @Test
    void aPossessiveEndsARun() {
        assertEquals(List.of("The user", "Wren Castillo", "Port Calloway"),
                spans("The user's sister Wren Castillo's dog lives in Port Calloway."));
    }

    @Test
    void urlsPathsFilesAndTicketKeysAreCandidates() {
        assertEquals(List.of("The user", "OPS-142", "~/notes/plan.md", "https://wiki.example.com/runbook", "report.pdf"),
                spans("The user filed OPS-142 about ~/notes/plan.md and https://wiki.example.com/runbook, linked from report.pdf."));
    }

    @Test
    void aPreferenceObjectRunsToPunctuationOrAStopWord() {
        assertEquals(List.of("The user", "remote work is overrated"),
                spans("The user thinks that remote work is overrated because commutes waste time."));
        assertEquals(List.of("The user", "dark roast coffee"), spans("The user likes dark roast coffee, black."));
    }

    @Test
    void aDotInsideATokenDoesNotEndAPreference() {
        var spans = spans("The user prefers Node.js, always.");
        assertTrue(spans.contains("Node.js"), spans.toString());
    }

    @Test
    void aTicketKeyInsideAUrlIsNotASecondCandidate() {
        var spans = spans("The user tracks https://jira.example.com/browse/OPS-7 daily.");
        assertTrue(spans.contains("https://jira.example.com/browse/OPS-7"), spans.toString());
        assertFalse(spans.contains("OPS-7"), spans.toString());
    }

    @Test
    void aPreferenceOverlappingAUrlIsNotACandidate() {
        var spans = spans("The user prefers https://docs.example.com/guide for reference.");
        assertTrue(spans.contains("https://docs.example.com/guide"), spans.toString());
        assertFalse(spans.contains("https"), spans.toString());
    }

    @Test
    void theOutputIsDeterministic() {
        var text = "The user owns Larchmere House in Ashgrove and drives a Volvo XC40 to Harborlight Analytics.";
        assertEquals(CandidateGenerator.generate(text), CandidateGenerator.generate(text));
    }

    @Test
    void timeWordsAreNeverCandidates() {
        assertEquals(List.of("The user", "Ana"), spans("The user meets Ana on Friday in March."));
        assertEquals(List.of("The user", "Ana"), spans("The user meets Ana every Friday Morning."));
        assertEquals(List.of("The user", "Ana"), spans("The user meets Ana on Tue."));
        assertEquals(List.of("The user"), spans("The user prefers Friday mornings."));
    }

    @Test
    void anImpossibleDateLeavesTheCandidates() {
        assertEquals(List.of("The user", "Ana"), spans("The user met Ana on June 31."));
    }

    @Test
    void aTimeWordOpeningANameIsKept() {
        assertTrue(spans("The user works at Fridays Ltd.").contains("Fridays Ltd"));
        assertTrue(spans("The user met May Chen in Lisbon.").contains("May Chen"));
        assertEquals(List.of("The user", "Ana Ruiz"), spans("The user meets Ana Ruiz Monday."));
    }

    @Test
    void knownNamesMatchWholeWordsCaseInsensitively() {
        var candidates = CandidateGenerator.generate("The user moved the kestrel ci jobs to kestrelci.", List.of("Kestrel CI"));
        assertTrue(candidates.stream().anyMatch(c -> c.span().equals("kestrel ci")), candidates.toString());
        assertTrue(candidates.stream().noneMatch(c -> c.span().equals("kestrelci")), candidates.toString());
        assertEquals(List.of("The user"), spans("The user moved the kestrel ci jobs."), "unknown without the name");
    }

    @Test
    void aKnownNameIsKeptEvenWhenItIsATimeWord() {
        var candidates = CandidateGenerator.generate("The user met May at the fair.", List.of("May"));
        assertTrue(candidates.stream().anyMatch(c -> c.span().equals("May")), candidates.toString());
        assertFalse(spans("The user met May at the fair.").contains("May"), "a bare time word without the name");
    }

    @Test
    void aSpanInsideAFoundDateIsNeverACandidate() {
        assertEquals(List.of(), spans("The launch slipped to Q3 2027."));
    }

    @Test
    void aSpanHoldingOrOverlappingAFoundDateIsKept() {
        assertEquals(List.of("Avery Lin", "Ashgrove", "Larchmere House", "Juniper Clinic"),
                spans("Avery Lin says Ashgrove includes Larchmere House, their home since 2019, and the Juniper Clinic"
                        + " branch nearby."));
        assertTrue(spans("Avery Lin prefers the June 2026 Lisbon Summit.").contains("the June 2026 Lisbon Summit"));
    }

    @Test
    void aKnownNameIsKeptEvenInsideAFoundDate() {
        var candidates = CandidateGenerator.generate("The launch slipped to Q3 2027.", List.of("Q3"));
        assertTrue(candidates.stream().anyMatch(c -> c.span().equals("Q3")), candidates.toString());
    }

    @Test
    void aRecurringSpanKeepsTheOccurrenceThatOverlaps() {
        var text = "Meridian ships soon. The Meridian kickoff is Friday.";
        var candidates = CandidateGenerator.generate(text, List.of("Meridian kickoff"));
        var meridian = candidates.stream().filter(c -> c.span().equals("Meridian")).toList();
        assertEquals(1, meridian.size(), candidates.toString());
        assertEquals(25, meridian.getFirst().start(), "the occurrence inside Meridian kickoff");
        assertTrue(candidates.stream().anyMatch(c -> c.span().equals("Meridian kickoff")), candidates.toString());
    }

    @Test
    void everyCandidateCarriesItsOffsets() {
        var text = "The user met Wren Castillo at https://example.com/a.";
        for (var c : CandidateGenerator.generate(text)) {
            assertEquals(c.span(), text.substring(c.start(), c.end()), c.toString());
        }
        var implicit = CandidateGenerator.generate("Works at Harborlight.").getFirst();
        assertTrue(implicit.implicit() && implicit.start() < 0, "the implicit operator is no span of the text");
    }

    @Test
    void topicFramesYieldTheViewAndTheKind() {
        assertTrue(spans("The user believes that static typing prevents bugs.").contains("static typing prevents bugs"));
        var kind = spans("The user considers kombucha a kind of fermented tea.");
        assertTrue(kind.contains("kombucha") && kind.contains("fermented tea"), kind.toString());
        var relative = spans("The user loves mochi, which is a type of rice cake.");
        assertTrue(relative.contains("rice cake"), relative.toString());
        assertFalse(relative.contains("which"), relative.toString());
        assertTrue(spans("Ana bought a kind of tea.").stream().noneMatch(sp -> sp.contains("bought")),
                "no copula and no considers verb means no X");
        assertTrue(spans("The user says the soup has a kind of smoky taste.").stream().noneMatch(sp -> sp.contains("has")));
        var split = spans("The user considers Bo smart. Tea is a kind of drink.");
        assertTrue(split.contains("Tea") && split.contains("drink"), split.toString());
        assertTrue(split.stream().noneMatch(sp -> sp.contains("smart")), "considers does not reach past its sentence");
    }

    @Test
    void topicCandidateRecallOnTheCommittedCasesHoldsItsMeasuredFloor() throws Exception {
        var cases = GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH), OntologySchema.seed());
        int hit = 0;
        int total = 0;
        var missed = new ArrayList<String>();
        for (var c : cases) {
            var found = CandidateGenerator.generate(c.text()).stream().map(Candidate::span).toList();
            for (var e : c.entities()) {
                if (!e.type().equals("Topic") || e.implicit()) continue;
                total++;
                if (found.stream().anyMatch(e::answersTo)) hit++;
                else missed.add(c.id() + ":" + e.span());
            }
        }
        assertTrue(total > 0);
        assertTrue(hit >= TOPIC_RECALL_FLOOR, "Topic recall " + hit + "/" + total + ", missed " + missed);
    }
}
