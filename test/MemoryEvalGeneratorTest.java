import memory.MemoryCategory;
import memory.MemoryStoreFactory;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.evals.MemoryEvalGenerator;
import services.evals.MemoryEvalGenerator.Clustering;
import services.evals.MemoryEvalGenerator.QuestionWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-529's suite generator. Every case here runs offline: the model call is the
 * {@link QuestionWriter} seam, and lexical clustering is pure token math, so nothing
 * needs a provider or a vector backend.
 *
 * <p>Runs with the Lucene index closed — seeding memories fires {@code @PostPersist}
 * indexing, and the generator itself reads through JPQL, so the index is irrelevant to
 * what is under test and holding it open would only contend with the search lane.
 */
class MemoryEvalGeneratorTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        agent = new Agent();
        agent.name = "eval-gen-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private void seed(String text) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text, MemoryCategory.FACT.label, 0.6);
    }

    /** A writer that returns a fixed question and records what it was asked. */
    private static QuestionWriter writerReturning(String question, List<String> seen) {
        return msgs -> {
            seen.add(String.valueOf(msgs.getLast().content()));
            return question;
        };
    }

    // --- generate: per-fact questions ---

    @Test
    void generatesOneCasePerSampledMemoryWithThatMemoryAsGold() {
        seed("The user's home server is a Synology DS923+");
        seed("The user commutes by bicycle");
        var suite = MemoryEvalGenerator.generate(agent, "s1", 10, writerReturning("q?", new ArrayList<>()));

        assertEquals(2, suite.cases().size());
        for (var c : suite.cases()) {
            assertTrue(c.id().startsWith("mem-"), c.id());
            assertEquals("q?", c.query());
            assertEquals(1, c.goldGroups().size(), "an unrelated corpus gives one gold group per case");
            // The gold must be the memory the case was built FROM. Asserting only the group
            // count let a gold of "sourceId + 1" through.
            assertTrue(c.goldGroups().getFirst().contains(Long.valueOf(c.id().substring(4))),
                    "gold must contain the source memory: " + c.id() + " -> " + c.goldGroups());
        }
    }

    @Test
    void theSourceMemoryIsPassedToTheWriterSoTheQuestionCanBeAboutIt() {
        seed("The user's passport expires in March 2027");
        var seen = new ArrayList<String>();
        MemoryEvalGenerator.generate(agent, "s1", 10, writerReturning("when?", seen));

        assertEquals(1, seen.size());
        assertTrue(seen.getFirst().contains("passport"), seen.getFirst());
    }

    @Test
    void anEmptyCorpusYieldsAnEmptySuiteRatherThanThrowing() {
        var suite = MemoryEvalGenerator.generate(agent, "s1", 10, writerReturning("q?", new ArrayList<>()));

        assertTrue(suite.cases().isEmpty());
        assertTrue(suite.corpusFingerprint().startsWith("0:"), suite.corpusFingerprint());
    }

    @Test
    void sampleSizeCapsTheNumberOfCasesByStridingRatherThanTakingThePrefix() {
        // Deterministic stride, not a random draw and not the first N: a suite that sampled
        // different memories each build could not be compared with the run before it.
        for (int i = 0; i < 10; i++) seed("Distinct fact number %d about topic %d".formatted(i, i));
        var suite = MemoryEvalGenerator.generate(agent, "s1", 3, writerReturning("q?", new ArrayList<>()));

        assertTrue(suite.cases().size() <= 3, "asked for 3, got " + suite.cases().size());
        var ids = suite.cases().stream().map(c -> c.id()).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "no memory sampled twice");
    }

    @Test
    void sameCorpusGeneratesTheSameSuiteTwice() {
        for (int i = 0; i < 6; i++) seed("Distinct fact number %d about topic %d".formatted(i, i));
        var a = MemoryEvalGenerator.generate(agent, "s1", 3, writerReturning("q?", new ArrayList<>()));
        var b = MemoryEvalGenerator.generate(agent, "s1", 3, writerReturning("q?", new ArrayList<>()));

        assertEquals(a.cases().stream().map(c -> c.id()).toList(),
                b.cases().stream().map(c -> c.id()).toList(),
                "sampling must be deterministic or baselines cannot be compared");
        assertEquals(a.corpusFingerprint(), b.corpusFingerprint());
    }

    @Test
    void aBlankOrMultiLineQuestionIsDroppedRatherThanStored() {
        seed("The user's favorite tea is genmaicha");
        seed("The user keeps bees");
        assertTrue(MemoryEvalGenerator.generate(agent, "s1", 10,
                writerReturning("   ", new ArrayList<>())).cases().isEmpty(),
                "a blank question is not a case");
        assertTrue(MemoryEvalGenerator.generate(agent, "s1", 10,
                writerReturning("line one\nline two", new ArrayList<>())).cases().isEmpty(),
                "a multi-line answer means the model ignored the format instruction");
    }

    @Test
    void aWriterFailureSkipsThatCaseAndKeepsGenerating() {
        seed("Alpha fact about kayaking");
        seed("Beta fact about welding");
        var calls = new int[]{0};
        QuestionWriter flaky = msgs -> {
            if (calls[0]++ == 0) throw new IllegalStateException("provider down");
            return "q?";
        };
        var suite = MemoryEvalGenerator.generate(agent, "s1", 10, flaky);

        assertEquals(1, suite.cases().size(), "one call failed, the other must still produce a case");
    }

    @Test
    void aParaphraseOfTheSourceIsGoldToo() {
        // Otherwise a correct retrieval scores as a miss whenever the corpus holds the fact
        // twice, penalising exactly what dedup exists to allow.
        seed("The user keeps the NAS in the basement");
        seed("The user keeps the NAS in the basement.");
        var suite = MemoryEvalGenerator.generate(agent, "s1", 1, writerReturning("where?", new ArrayList<>()));

        assertEquals(1, suite.cases().size());
        assertEquals(2, suite.cases().getFirst().allGoldIds().size(),
                "the near-duplicate answers the question as well as the source does");
    }

    // --- fingerprint ---

    @Test
    void theFingerprintTracksTheCorpusAndChangesWhenItDoes() {
        seed("First fact about sailing");
        var before = MemoryEvalGenerator.generate(agent, "s1", 5,
                writerReturning("q?", new ArrayList<>())).corpusFingerprint();
        seed("Second fact about gardening");
        var after = MemoryEvalGenerator.generate(agent, "s1", 5,
                writerReturning("q?", new ArrayList<>())).corpusFingerprint();

        assertNotSame(before, after);
        assertNotEquals(before, after, "a grown corpus must not report the old fingerprint");
        assertTrue(before.startsWith("1:") && after.startsWith("2:"), before + " / " + after);
    }

    @Test
    void theFingerprintCarriesNoMemoryText() {
        seed("The user's canary phrase is zephyr-quartz-1917");
        var fp = MemoryEvalGenerator.generate(agent, "s1", 5,
                writerReturning("q?", new ArrayList<>())).corpusFingerprint();

        assertFalse(fp.contains("zephyr"), "personal data must not travel in the artifact: " + fp);
    }

    // --- Clustering ---

    @Test
    void clusteringNamesWhichSignalGroups() {
        assertTrue(new Clustering("semantic", 0.8, 3, 12).semantic());
        assertFalse(new Clustering("lexical", 0.3, 3, 12).semantic());
        assertFalse(new Clustering(null, 0.3, 3, 12).semantic());
    }

    // --- clusterSizes ---

    @Test
    void clusterSizesCountsDistinctFactsNotMemories() {
        // Three restatements of one fact plus one unrelated memory: the restatements
        // collapse to a single distinct fact, so a paraphrase-heavy corpus cannot inflate
        // what a coverage question is asked to cover.
        seed("The user's deploy process is a two-remote push");
        seed("The user's deploy process is a two-remote push.");
        seed("The user's deploy process is a two remote push");
        seed("Completely unrelated: the user's cat is called Marmalade");

        var sizes = MemoryEvalGenerator.clusterSizes(agent, new Clustering("lexical", 0.5, 1, 50));
        assertFalse(sizes.isEmpty());
        assertTrue(sizes.stream().allMatch(n -> n >= 1), sizes.toString());
        assertTrue(sizes.stream().mapToInt(Integer::intValue).sum() < 4,
                "restatements must collapse; got " + sizes);
    }

    @Test
    void anUnclusterableCorpusYieldsOneSingletonPerMemory() {
        seed("Alpha topic concerns kayaking rivers");
        seed("Beta topic concerns welding steel");
        seed("Gamma topic concerns baking sourdough");

        var sizes = MemoryEvalGenerator.clusterSizes(agent, new Clustering("lexical", 0.9, 1, 50));
        assertEquals(3, sizes.size());
        assertTrue(sizes.stream().allMatch(n -> n == 1), sizes.toString());
    }

    // --- generateCoverage ---

    @Test
    void aClusterBelowMinFactsIsNotACoverageQuestion() {
        // Nothing for the recall budget to have to choose between.
        seed("Alpha topic concerns kayaking rivers");
        seed("Beta topic concerns welding steel");

        var suite = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.9, 3, 12), writerReturning("broad?", new ArrayList<>()));
        assertTrue(suite.cases().isEmpty(), "singletons cannot form a coverage question");
    }

    @Test
    void aClusterAboveMaxFactsIsRejectedAsATopicRatherThanAQuestion() {
        // Each fact needs its own nouns. Facts that differ by one token are near-duplicates
        // under MemorySimilarity, collapse to ONE distinct fact, and are then rejected by
        // minFacts — so a "too many facts" test written that way passes for the wrong reason
        // and the maxFacts ceiling is never exercised at all.
        seed("Sailing concerns the mainsail rigging");
        seed("Sailing concerns the keel ballast");
        seed("Sailing concerns the rudder linkage");
        seed("Sailing concerns the winch servicing");
        seed("Sailing concerns the anchor windlass");

        var narrow = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 4), writerReturning("broad?", new ArrayList<>()));
        assertTrue(narrow.cases().isEmpty(),
                "a cluster past the ceiling is a topic, whose retrieval is too diffuse to compare rankers with");

        // Control: the SAME corpus with a ceiling above five must produce a case. Without
        // this, an unconditional `continue` would satisfy the assertion above.
        var wide = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("broad?", new ArrayList<>()));
        assertEquals(1, wide.cases().size(), "five distinct facts is a question, not a topic");
        assertEquals(5, wide.cases().getFirst().goldGroups().size());
    }

    @Test
    void aCoverageCaseCarriesEveryDistinctFactAsASeparateGoldGroup() {
        seed("Shared subject sailing concerns the mainsail rigging");
        seed("Shared subject sailing concerns the keel ballast");
        seed("Shared subject sailing concerns the rudder linkage");

        var suite = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("tell me about sailing", new ArrayList<>()));

        assertEquals(1, suite.cases().size());
        var c = suite.cases().getFirst();
        assertTrue(c.id().startsWith("cov-"), c.id());
        assertEquals(3, c.goldGroups().size(), "each distinct fact is its own group to cover");
    }

    @Test
    void aMemoryAlreadyUsedByOneClusterDoesNotSeedAnother() {
        seed("Shared subject sailing concerns the mainsail rigging");
        seed("Shared subject sailing concerns the keel ballast");
        seed("Shared subject sailing concerns the rudder linkage");

        var suite = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("tell me about sailing", new ArrayList<>()));

        assertEquals(1, suite.cases().size(), "the same three memories must not produce three overlapping cases");
    }

    @Test
    void coverageStopsAtMaxCases() {
        // Two clusters that do not reach each other lexically, each with three distinct
        // facts. Both halves matter: without two reachable clusters the cap is never the
        // reason the second case is absent, and `<= 1` would hold for an empty suite.
        seed("Sailing concerns the mainsail rigging");
        seed("Sailing concerns the keel ballast");
        seed("Sailing concerns the rudder linkage");
        // Four content tokens each, so the two shared ones give Jaccard 2/6 = 0.33 and clear
        // the 0.3 threshold. At five tokens it is 2/8 = 0.25 and the group does not cluster
        // at all — which is why the first attempt at this test saw one cluster, not two.
        seed("Baking needs precise temperature");
        seed("Baking needs accurate hydration");
        seed("Baking needs long fermentation");

        var uncapped = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("broad?", new ArrayList<>()));
        assertEquals(2, uncapped.cases().size(), "the corpus must offer two clusters for the cap to bite");

        var capped = MemoryEvalGenerator.generateCoverage(agent, "cov", 1,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("broad?", new ArrayList<>()));
        assertEquals(1, capped.cases().size(), "maxCases must stop the second cluster");
    }

    @Test
    void aCoverageWriterFailureSkipsThatClusterRatherThanAbortingTheSuite() {
        seed("Shared subject sailing concerns the mainsail rigging");
        seed("Shared subject sailing concerns the keel ballast");
        seed("Shared subject sailing concerns the rudder linkage");
        QuestionWriter failing = msgs -> {
            throw new IllegalStateException("provider down");
        };

        var suite = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), failing);
        assertTrue(suite.cases().isEmpty());
        assertNotNull(suite.corpusFingerprint(), "a suite with no cases still identifies its corpus");
    }

    @Test
    void aBlankCoverageQuestionIsDropped() {
        seed("Shared subject sailing concerns the mainsail rigging");
        seed("Shared subject sailing concerns the keel ballast");
        seed("Shared subject sailing concerns the rudder linkage");

        var suite = MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("  ", new ArrayList<>()));
        assertTrue(suite.cases().isEmpty());
    }

    @Test
    void theWholeClusterIsShownToTheWriterNotJustTheSeed() {
        seed("Shared subject sailing concerns the mainsail rigging");
        seed("Shared subject sailing concerns the keel ballast");
        seed("Shared subject sailing concerns the rudder linkage");
        var seen = new ArrayList<String>();

        MemoryEvalGenerator.generateCoverage(agent, "cov", 10,
                new Clustering("lexical", 0.3, 3, 12), writerReturning("broad?", seen));

        assertFalse(seen.isEmpty());
        var prompt = seen.getFirst();
        assertTrue(prompt.contains("mainsail") && prompt.contains("keel") && prompt.contains("rudder"),
                "a broad question needs every fact it is meant to cover: " + prompt);
    }

    // --- generateBridge: the query asks through a relation stored in another memory ---

    /** The shape the mode exists for: relation in one row, answer in another. */
    private void seedBridgePair() {
        seed("The user has a son named Zephyrin");
        seed("Zephyrin goes by Zeph");
    }

    @Test
    void pairsARelationMemoryWithTheEntityFactItBridgesTo() {
        seedBridgePair();
        var suite = MemoryEvalGenerator.generateBridge(agent, "b1", 10,
                writerReturning("what does my kid go by?", new ArrayList<>()));

        assertEquals(1, suite.cases().size(), "one bridgeable pair means one case");
        var c = suite.cases().getFirst();
        assertEquals("what does my kid go by?", c.query());
        // Gold is the TARGET — the row the question cannot reach — not the relation row,
        // which is already reachable by the words the question uses.
        var goldText = models.Memory.<models.Memory>findById(c.goldGroups().getFirst().getFirst()).text;
        assertTrue(goldText.contains("Zeph"), "gold must be the target fact, got: " + goldText);
    }

    @Test
    void passesBothFactsToTheWriterSoTheQuestionCanBridgeThem() {
        seedBridgePair();
        var seen = new ArrayList<String>();
        MemoryEvalGenerator.generateBridge(agent, "b1", 10, writerReturning("q?", seen));

        assertFalse(seen.isEmpty());
        var prompt = seen.getFirst();
        assertTrue(prompt.contains("son") && prompt.contains("Zeph"),
                "the writer needs the relation and the target to phrase a bridge: " + prompt);
    }

    @Test
    void skipsTargetsThatAlreadyCarryTheRelation() {
        // Both rows name the relation, so an ordinary question reaches the answer directly.
        // Scoring that as a bridge case would report ordinary recall under this mode's name.
        seed("The user has a son named Zephyrin");
        seed("The user's son Zephyrin goes by Zeph");
        var suite = MemoryEvalGenerator.generateBridge(agent, "b1", 10,
                writerReturning("q?", new ArrayList<>()));

        assertTrue(suite.cases().isEmpty(), "nothing to bridge: " + suite.cases());
    }

    @Test
    void requiresAnEntitySharedBetweenTheTwoMemories() {
        // A relation memory and an unrelated fact are not a pair, however well each reads.
        seed("The user has a son named Zephyrin");
        seed("Postgres is the production database");
        var suite = MemoryEvalGenerator.generateBridge(agent, "b1", 10,
                writerReturning("q?", new ArrayList<>()));

        assertTrue(suite.cases().isEmpty(), "no shared entity means no bridge: " + suite.cases());
    }

    @Test
    void dropsAQuestionThatNamesItsOwnGoldEntity() {
        // "Where does Zeph live?" reaches the gold by name, so it measures ordinary recall.
        // Observed 1 in 11 against a real corpus — the writer is told not to and mostly obeys.
        seedBridgePair();
        var suite = MemoryEvalGenerator.generateBridge(agent, "b1", 10,
                writerReturning("what has Zeph been up to?", new ArrayList<>()));

        assertTrue(suite.cases().isEmpty(), "a question naming its gold is not a bridge: " + suite.cases());
    }

    @Test
    void anEmptyCorpusYieldsAnEmptyBridgeSuiteRatherThanThrowing() {
        var suite = MemoryEvalGenerator.generateBridge(agent, "b1", 10,
                writerReturning("q?", new ArrayList<>()));

        assertTrue(suite.cases().isEmpty());
        assertTrue(suite.corpusFingerprint().startsWith("0:"), suite.corpusFingerprint());
    }

    // --- writerFor ---

    @Test
    void writerForReturnsNullWhenTheAgentsProviderIsNotRegistered() {
        agent.modelProvider = "no-such-provider";
        assertNull(MemoryEvalGenerator.writerFor(agent),
                "no provider means no generation, rather than a call that fails later");
    }
}
