import memory.TemporalExpressions;
import memory.graph.GraphStore;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator;
import services.grapheval.CandidateGenerator.Candidate;
import services.grapheval.CandidateGenerator.Source;
import services.grapheval.GraphCases;
import services.grapheval.KnownNames;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
        assertEquals(new Candidate("the user", true, false, 18, 26, null, Set.of(Source.OPERATOR), null, -1, -1),
                candidates.getFirst());
        assertEquals(List.of("the user", "Wren Castillo", "Port Calloway"), spans("Wren Castillo met the user in Port Calloway."));
    }

    @Test
    void aSubjectlessMemoryGetsAnImplicitOperatorAndDropsItsOpeningVerb() {
        var candidates = CandidateGenerator.generate("Prefers Neovim over VS Code when editing on Kestrel.");
        assertEquals(new Candidate(GraphCases.IMPLICIT_OPERATOR_SPAN, true, true, -1, -1, null, Set.of(Source.OPERATOR),
                null, -1, -1), candidates.getFirst());
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

    private static CandidateGenerator.PreferenceFrame frameOf(String text, String span) {
        return CandidateGenerator.generate(text).stream().filter(c -> c.span().equals(span)).findFirst()
                .orElseThrow(() -> new AssertionError(span + " not in " + CandidateGenerator.generate(text)))
                .frame();
    }

    private static void assertFrame(String text, String object, OntologyRecord.Valence valence, String subject) {
        var frame = frameOf(text, object);
        assertNotNull(frame, text);
        assertEquals(valence, frame.valence(), text);
        assertEquals(subject, frame.subject(), text);
    }

    @Test
    void aFavorableVerbFramesItsObjectWithTheSubjectBeforeIt() {
        assertFrame("Dana Reyes enjoys Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.FAVORABLE, "Dana Reyes");
        assertNull(frameOf("Dana Reyes enjoys Corvid Notes.", "Dana Reyes"), "the subject itself carries no frame");
    }

    @Test
    void everyUnfavorablePhraseFramesItsObject() {
        for (var verb : List.of("hates", "dislikes", "can't stand", "cannot stand", "doesn't like", "does not like",
                "never liked")) {
            assertFrame("Dana Reyes " + verb + " Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.UNFAVORABLE,
                    "Dana Reyes");
        }
    }

    @Test
    void bothEndingFramesAreFavorable() {
        assertFrame("Dana Reyes no longer likes Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.FAVORABLE,
                "Dana Reyes");
        assertFrame("Dana Reyes doesn't like Corvid Notes anymore.", "Corvid Notes", OntologyRecord.Valence.FAVORABLE,
                "Dana Reyes");
        assertFalse(spans("Dana Reyes doesn't like Corvid Notes anymore.").stream().anyMatch(s -> s.contains("anymore")));
    }

    @Test
    void frameAdverbsMayStandBetweenSubjectAndVerb() {
        assertFrame("Dana Reyes really loves Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.FAVORABLE,
                "Dana Reyes");
        assertFrame("Dana Reyes still hates Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.UNFAVORABLE,
                "Dana Reyes");
        assertFrame("Dana Reyes said Mateo loves Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.FAVORABLE,
                "Mateo");
        assertNull(frameOf("Dana Reyes said it loves Corvid Notes.", "Corvid Notes").subject(),
                "no subject across a word that is not a frame word");
    }

    @Test
    void aSubjectlessMemoryFramesTheOperator() {
        assertFrame("Loves Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.FAVORABLE,
                GraphCases.IMPLICIT_OPERATOR_SPAN);
        assertFrame("The user hates Corvid Notes.", "Corvid Notes", OntologyRecord.Valence.UNFAVORABLE, "The user");
    }

    @Test
    void aViewTakesNoFrame() {
        assertNull(frameOf("The user thinks that static typing prevents bugs.", "static typing prevents bugs"));
    }

    @Test
    void aSharedObjectKeepsTheFirstVerbsFrame() {
        var text = "Dana Reyes hates Corvid Notes, but Avery Lin loves Corvid Notes.";
        assertFrame(text, "Corvid Notes", OntologyRecord.Valence.UNFAVORABLE, "Dana Reyes");
    }

    @Test
    void aSharedObjectKeepsTheFirstVerbsFrameWhenTheFavorableVerbComesFirst() {
        var text = "Avery Lin loves Corvid Notes, but Dana Reyes hates Corvid Notes.";
        assertFrame(text, "Corvid Notes", OntologyRecord.Valence.FAVORABLE, "Avery Lin");
    }

    @Test
    void aClaimedSeasonIsNeverACandidate() {
        var text = "Spring 2027 brings the Atlas Migration.";
        var claimed = TemporalExpressions.claimedSpans(text);
        assertTrue(claimed.stream().anyMatch(r -> r.start() == 0 && r.end() >= "Spring".length()), claimed.toString());
        var spans = spans(text);
        assertTrue(spans.stream().noneMatch(s -> s.contains("Spring")), spans.toString());
        assertTrue(spans.contains("the Atlas Migration") || spans.contains("Atlas Migration"), spans.toString());
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

    private static final String OWNER = "Avery Lin";

    private static List<Candidate> withSource(String text, Source source) {
        return CandidateGenerator.generate(text, List.of(), OWNER).stream()
                .filter(c -> c.sources().contains(source)).toList();
    }

    private static void assertOnly(String text, Source source, String span) {
        var found = withSource(text, source);
        assertEquals(List.of(span), found.stream().map(Candidate::span).toList(), text);
    }

    @Test
    void everyMustMatchContactIsExactlyOneCandidateWithItsSource() {
        assertOnly("Write to avery.lin@example.com about it.", Source.EMAIL, "avery.lin@example.com");
        assertOnly("Alerts go to ops+alerts@harborlight.example.com.", Source.EMAIL, "ops+alerts@harborlight.example.com");
        assertOnly("She posts as @averylin on most sites.", Source.HANDLE, "@averylin");
        assertOnly("The studio is @vela_design, run by Wren.", Source.HANDLE, "@vela_design");
        assertOnly("Call +1 202 555 0142 after six.", Source.PHONE, "+1 202 555 0142");
        assertOnly("The office number is (415) 555-0137.", Source.PHONE, "(415) 555-0137");
        assertOnly("The London desk is +44 20 7946 0958, ext 2.", Source.PHONE, "+44 20 7946 0958");
        assertOnly("Her landline is 020 7946 0123 now.", Source.PHONE, "020 7946 0123");
    }

    @Test
    void anEmailEndsBeforeTrailingPunctuationAndHidesNoHandle() {
        var text = "Mail avery.lin@example.com.";
        assertOnly(text, Source.EMAIL, "avery.lin@example.com");
        assertEquals(List.of(), withSource(text, Source.HANDLE));
    }

    @Test
    void noMustNotStringIsAContactCandidate() {
        for (var s : List.of("2019-2022", "2026-12-12", "12/06/2027", "Q3 2027", "v2.4.1", "192.168.10.12", "OPS-4417",
                "JCLAW-1346", "port 8080", "14:30", "1,250,000", "https://wiki.example.com/@team", "notes-2024-03.md")) {
            var text = "The user noted " + s + " in the log.";
            for (var source : List.of(Source.EMAIL, Source.HANDLE, Source.PHONE)) {
                assertEquals(List.of(), withSource(text, source), source + " in " + text);
            }
        }
        assertOnly("The user noted https://wiki.example.com/@team in the log.", Source.URL,
                "https://wiki.example.com/@team");
        assertOnly("The user noted OPS-4417 in the log.", Source.TICKET, "OPS-4417");
        assertOnly("The user noted notes-2024-03.md in the log.", Source.FILE, "notes-2024-03.md");
    }

    @Test
    void handlesNeedTwoCharactersAndAFreeStart() {
        assertOnly("Ping @ab today.", Source.HANDLE, "@ab");
        assertEquals(List.of(), withSource("Ping @a today.", Source.HANDLE));
        assertEquals(List.of(), withSource("Ping x@ab today.", Source.HANDLE));
    }

    @Test
    void aPhoneHasSevenToFifteenDigits() {
        assertOnly("Dial 555-0142 now.", Source.PHONE, "555-0142");
        assertOnly("Dial 555 0142 0199 0123 now.", Source.PHONE, "555 0142 0199 0123");
        assertEquals(List.of(), withSource("Dial 555-014 now.", Source.PHONE));
        assertEquals(List.of(), withSource("Dial 5550 0142 0199 0123 now.", Source.PHONE));
        assertEquals(List.of(), withSource("Dial 555 014 now.", Source.PHONE));
    }

    private static void assertKin(String text, String span, String kin) {
        var found = withSource(text, Source.KIN);
        assertEquals(1, found.size(), text + " gave " + found);
        assertEquals(span, found.getFirst().span(), text);
        assertEquals(kin, found.getFirst().kin(), text);
        var c = found.getFirst();
        assertTrue(c.possessorStart() == c.start() && c.possessorEnd() > c.start() && c.possessorEnd() < c.end(),
                () -> text + " possessor " + c);
    }

    @Test
    void anOwnerPossessiveKinPhraseIsOneKinCandidate() {
        assertKin("Avery Lin's son starts school in Port Calloway.", "Avery Lin's son", "son");
        assertKin("The user's younger sister lives nearby.", "The user's younger sister", "sister");
        assertKin("Avery Lin\u2019s son plays chess.", "Avery Lin\u2019s son", "son");
        assertKin("avery lin's sister moved.", "avery lin's sister", "sister");
        assertKin("They met Avery Lin's twin brother.", "Avery Lin's twin brother", "brother");
        assertKin("Avery Lin's sister-in-law visited.", "Avery Lin's sister-in-law", "sister-in-law");
        var c = withSource("Avery Lin's son plays chess.", Source.KIN).getFirst();
        assertEquals(0, c.possessorStart());
        assertEquals(9, c.possessorEnd());
    }

    @Test
    void noKinForAPronounAPluralOrANameInApposition() {
        for (var text : List.of("The user said their sister moved.", "Avery Lin's parents moved.",
                "Avery Lin's sister Wren Castillo moved.", "Avery Lin's spouse, Priya Nandakumar, moved.",
                "Wren Castillo, Avery Lin's sister, moved.")) {
            assertEquals(List.of(), withSource(text, Source.KIN), text);
        }
        for (var row : List.of(List.of("Avery Lin's sister Wren Castillo", "Wren Castillo"),
                List.of("Avery Lin's spouse, Priya Nandakumar", "Priya Nandakumar"),
                List.of("Wren Castillo, Avery Lin's sister", "Wren Castillo"),
                List.of("Avery Lin's spouse, Priya Nandakumar, moved.", "Priya Nandakumar"),
                List.of("Wren Castillo, Avery Lin's sister, moved.", "Wren Castillo"))) {
            var text = row.getFirst();
            assertEquals(List.of(), withSource(text, Source.KIN), text);
            assertTrue(withSource(text, Source.CAPITALIZED).stream().anyMatch(c -> c.span().equals(row.get(1))), text);
        }
    }

    @Test
    void aClauseBesideTheKinPhraseIsNotApposition() {
        assertKin("In Port Calloway, Avery Lin's son plays chess.", "Avery Lin's son", "son");
        assertKin("Sadly, the user's son broke a leg.", "the user's son", "son");
        assertKin("Avery Lin's son, Harborlight Academy hired him.", "Avery Lin's son", "son");
    }

    @Test
    void phoneAndHandleShapesNeedGroupsAndALetter() {
        for (var text : List.of("The build was 20261012 then.", "It cost 1250000 then.", "Windows 10.0.19041 ran.")) {
            assertEquals(List.of(), withSource(text, Source.PHONE), text);
        }
        assertOnly("Called 555-0142 3 times.", Source.PHONE, "555-0142");
        assertOnly("Call (555)123-4567 today.", Source.PHONE, "(555)123-4567");
        assertEquals(List.of(), withSource("Meet @12:30 by the door.", Source.HANDLE));
        assertEquals(List.of(), withSource("Meet @10am by the door.", Source.HANDLE));
    }

    @Test
    void aPhoneInsideParenthesesIsStillAPhone() {
        assertOnly("Her cell (+1 202 555 0142) is new.", Source.PHONE, "+1 202 555 0142");
        assertOnly("The desk (555-0142) answers.", Source.PHONE, "555-0142");
        assertOnly("The office ((415) 555-0137) answers.", Source.PHONE, "(415) 555-0137");
        for (var s : List.of("(2019-2022)", "(2026-12-12)", "(12/06/2027)", "(v2.4.1)", "(192.168.10.12)",
                "(1,250,000)", "(14:30)")) {
            var text = "The user noted " + s + " in the log.";
            assertEquals(List.of(), withSource(text, Source.PHONE), text);
        }
    }

    @Test
    void aRecurringOwnerKeepsTheOccurrenceThatReallyOverlaps() {
        var candidates = CandidateGenerator.generate("Avery Lin's son met staff at Avery Lin Studio.", List.of(), OWNER);
        var owner = candidates.stream().filter(c -> c.span().equals(OWNER)).findFirst().orElseThrow();
        var studio = candidates.stream().filter(c -> c.span().equals("Avery Lin Studio")).findFirst().orElseThrow();
        assertTrue(owner.overlaps(studio), candidates::toString);
        assertEquals(1, candidates.stream().filter(c -> "son".equals(c.kin())).count(), candidates::toString);
    }

    private static List<String> spansOf(String text) {
        return CandidateGenerator.generate(text, List.of(), OWNER).stream().map(Candidate::span).toList();
    }

    @Test
    void aLongDottedRunAfterAnAddressDoesNotOverflowTheStack() throws Exception {
        var text = "Write to x@" + "a.".repeat(20_000) + "a today.";
        var spans = new AtomicReference<List<String>>();
        var failure = new AtomicReference<Throwable>();
        // On its own thread for the JVM's default stack, which the greedy loop exhausted at about 4,000 labels.
        var thread = new Thread(() -> spans.set(spansOf(text)));
        thread.setUncaughtExceptionHandler((_, e) -> failure.set(e));
        thread.start();
        thread.join();

        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertTrue(spans.get().stream().anyMatch(s -> s.startsWith("x@a.a.")), "the address is still a candidate");
    }

    @Test
    void aKinCandidateDoesNotOverlapItsOwnPossessor() {
        var candidates = CandidateGenerator.generate("Avery Lin's son starts at Harborlight Academy.", List.of(), OWNER);
        var owner = candidates.stream().filter(c -> c.span().equals(OWNER)).findFirst().orElseThrow();
        var kin = candidates.stream().filter(c -> "son".equals(c.kin())).findFirst().orElseThrow();
        assertFalse(owner.overlaps(kin));
        assertFalse(kin.overlaps(owner));
        assertTrue(candidates.indexOf(owner) < candidates.indexOf(kin));
    }

    @Test
    void anAliasIsAKnownCandidate() {
        var kestrel = CandidateGenerator.generate("The user uses kestrel daily.", List.of("Kestrel CI", "Kestrel"));
        assertTrue(kestrel.stream().anyMatch(c -> c.span().equals("kestrel") && c.sources().equals(Set.of(Source.KNOWN))),
                kestrel.toString());
    }

    @Test
    void aSpanProposedTwiceKeepsBothSources() {
        var c = CandidateGenerator.generate("The user met Wren Castillo in town.", List.of("Wren Castillo")).stream()
                .filter(k -> k.span().equals("Wren Castillo")).findFirst().orElseThrow();
        assertEquals(List.of(Source.KNOWN, Source.CAPITALIZED), List.copyOf(c.sources()));
    }

    @Test
    void theNewSourcesAreDeterministic() {
        var text = "Avery Lin's son emails avery.lin@example.com, posts as @averylin and calls (415) 555-0137.";
        assertEquals(CandidateGenerator.generate(text, List.of("Kestrel"), OWNER),
                CandidateGenerator.generate(text, List.of("Kestrel"), OWNER));
    }

    @TempDir
    Path tmp;

    @Test
    void knownNamesReadsEveryNameAndAliasDedupedWithoutBlanks() throws Exception {
        var store = new GraphStore(tmp.resolve("memory-graph"));
        long agent = 11L;
        store.write(agent, List.of(
                new Evidence(Meta.fresh("e1", agent, Tier.TENTATIVE), "memory:1", null),
                new Term(Meta.fresh("t1", agent, Tier.TENTATIVE), "Organization", "Kestrel CI", List.of("m1"), List.of("e1"),
                        List.of("Kestrel", " ", "kestrel ci"), null),
                new Term(Meta.fresh("t2", agent, Tier.TENTATIVE), "Person", "Wren Castillo", List.of("m2"),
                        List.of("e1"), List.of(" Kestrel ", "Wren"), null),
                new Mapping(Meta.fresh("m1", agent, Tier.TENTATIVE), "t1", "memory:1", List.of("e1")),
                new Mapping(Meta.fresh("m2", agent, Tier.TENTATIVE), "t2", "memory:1", List.of("e1"))));
        var names = KnownNames.of(store, agent);
        assertEquals(List.of("Kestrel CI", "Kestrel", "kestrel ci", "Wren Castillo", "Wren"), names);
        var candidates = CandidateGenerator.generate("The user uses kestrel daily.", names);
        assertTrue(candidates.stream().anyMatch(c -> c.span().equals("kestrel") && c.sources().contains(Source.KNOWN)),
                candidates.toString());
    }
}
