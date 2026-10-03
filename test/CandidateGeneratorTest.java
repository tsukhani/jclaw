import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.graphspike.CandidateGenerator;
import services.graphspike.CandidateGenerator.Candidate;
import services.graphspike.GraphCases;

import java.util.List;

/** JCLAW-1356: the fixed candidate rules, with no model in the loop. */
class CandidateGeneratorTest extends UnitTest {

    private static List<String> spans(String text) {
        return CandidateGenerator.generate(text).stream().map(Candidate::span).toList();
    }

    @Test
    void theUserIsTheOperatorAndIsNeverTyped() {
        var candidates = CandidateGenerator.generate("Wren Castillo met the user in Port Calloway.");
        assertEquals(new Candidate("the user", true, false), candidates.getFirst());
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
}
