import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Adjudications;
import services.grapheval.Adjudications.Verdict;
import services.grapheval.GraphEvalHarness;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.GateRecord;
import services.grapheval.GraphEvalScorer.GateTally;
import services.grapheval.RecordBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JCLAW-1368: the v2 adjudication format, the hash-drawn agreed sample, unjudged records and model-verdict checks. */
class AdjudicationsTest extends UnitTest {

    private static final String GUIDE = "guide@0f9e8d7c6b5a";
    private static final long SEED = 1356;

    private static Verdict verdict(GateRecord r, String verdict, String adjudicator, String check) {
        return new Verdict(r.caseId(), r.record(), r.agreed() ? Adjudications.AGREED : Adjudications.UNMATCHED, verdict,
                GUIDE, adjudicator, r.agreed() ? 0.2 : null, check, "");
    }

    private static List<GateRecord> agreed(int n, String gate) {
        var out = new ArrayList<GateRecord>();
        for (int i = 0; i < n; i++) out.add(new GateRecord("c" + i, "rel:A" + i + ":" + gate + ":B" + i, gate, true));
        return out;
    }

    @Test
    void theSpecFormatParses() {
        var parsed = Adjudications.parse("""
                [{"caseId": "c042", "record": "rel:Avery Lin:uses:Kestrel CI", "side": "unmatched", "verdict": "wrong",
                  "guide": "guide@0f9e8d7c6b5a", "adjudicator": "operator", "note": "the memory does not relate the pair"},
                 {"caseId": "c018", "record": "term:Fenwick:System", "side": "agreed", "inclusion": 0.2, "verdict": "right",
                  "guide": "guide@0f9e8d7c6b5a", "adjudicator": "model:<name>", "check": "agree"}]""");
        assertEquals(List.of(
                new Verdict("c042", "rel:Avery Lin:uses:Kestrel CI", "unmatched", "wrong", GUIDE, "operator", null, null,
                        "the memory does not relate the pair"),
                new Verdict("c018", "term:Fenwick:System", "agreed", "right", GUIDE, "model:<name>", 0.2, "agree", "")),
                parsed);
        assertTrue(parsed.get(1).byModel());
        assertFalse(parsed.get(0).byModel());
    }

    @Test
    void anyOtherValueOrKeyIsRefused() {
        var base = "\"caseId\": \"c1\", \"record\": \"term:X:System\", \"guide\": \"" + GUIDE + "\"";
        for (var bad : Map.of(
                "\"side\": \"unmatched\", \"verdict\": \"right\", \"adjudicator\": \"operator\"", "unmatched verdict",
                "\"side\": \"agreed\", \"verdict\": \"label-error\", \"adjudicator\": \"operator\"", "agreed verdict",
                "\"side\": \"both\", \"verdict\": \"wrong\", \"adjudicator\": \"operator\"", "side must be",
                "\"side\": \"agreed\", \"verdict\": \"wrong\", \"adjudicator\": \"bot\"", "adjudicator must be",
                "\"side\": \"agreed\", \"verdict\": \"wrong\", \"adjudicator\": \"model:m\", \"check\": \"maybe\"",
                "check must be",
                "\"side\": \"agreed\", \"verdict\": \"wrong\", \"adjudicator\": \"operator\", \"check\": \"agree\"",
                "only a model's verdict",
                "\"side\": \"agreed\", \"verdict\": \"wrong\", \"adjudicator\": \"operator\", \"weight\": 1",
                "unknown key 'weight'").entrySet()) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> Adjudications.parse("[{" + base + ", " + bad.getKey() + "}]"));
            assertTrue(e.getMessage().contains(bad.getValue()), e.getMessage());
        }
        assertEquals(1, Adjudications.parse("[{" + base
                + ", \"side\": \"unmatched\", \"verdict\": \"label-error\", \"adjudicator\": \"operator\"}]").size());
    }

    @Test
    void theDrawIsSeededAndASmallerShareDrawsASubset() {
        var records = agreed(400, "uses");
        int atTwenty = 0;
        for (var r : records) {
            double f = Adjudications.fraction(SEED, r.caseId(), r.record());
            assertEquals(f, Adjudications.fraction(SEED, r.caseId(), r.record()));
            assertTrue(f >= 0 && f < 1);
            if (Adjudications.sampled(SEED, r.caseId(), r.record(), 0.2)) {
                atTwenty++;
                assertTrue(Adjudications.sampled(SEED, r.caseId(), r.record(), 0.5));
            }
        }
        assertTrue(atTwenty > 50 && atTwenty < 110, "about a fifth of 400: " + atTwenty);
        assertNotEquals(Adjudications.fraction(SEED, "c1", "k"), Adjudications.fraction(SEED + 1, "c1", "k"));
    }

    /** One memory per record, each writing one record of {@code gate}, scored for the evaluator. */
    private static GraphEvalScorer.Configured configured(List<GateRecord> records) {
        var memories = new ArrayList<GraphEvalScorer.MemoryScore>();
        for (var r : records) {
            memories.add(new GraphEvalScorer.MemoryScore(r.caseId(), List.of("plain"),
                    Map.of(r.gate(), new GateTally(1, r.agreed() ? 0 : 1, 1, r.agreed() ? 1 : 0)), Map.of(), 1,
                    r.agreed() ? 0 : 1, 0, 0, 0, 0, 5, !r.agreed()));
        }
        return new GraphEvalScorer.Configured(memories, records);
    }

    @Test
    void oneSampledAgreedRecordJudgedWrongAtAFifthAddsFiveToItsGate() {
        var records = agreed(100, "uses");
        var book0 = new Adjudications.Book(List.of(), GUIDE, SEED, 0.2, 0.2);
        var sampled = records.stream().filter(book0::sampled).toList();
        var unsampled = records.stream().filter(r -> !book0.sampled(r)).toList();
        assertFalse(sampled.isEmpty());
        var wrong = verdict(sampled.getFirst(), Adjudications.WRONG, Adjudications.OPERATOR, null);
        var book = new Adjudications.Book(List.of(wrong), GUIDE, SEED, 0.2, 0.2);
        var gate = GraphEvalHarness.evaluation(configured(records), book).gates().get("uses");
        assertEquals(100, RecordBounds.n(gate.writing()));
        assertEquals(5, RecordBounds.k(gate.writing()));
        var pooled = GraphEvalHarness.evaluation(configured(records), book).written();
        assertEquals(100, RecordBounds.n(pooled));
        assertEquals(5, RecordBounds.k(pooled), "G_written carries the agreed sample's weighted wrong too");

        var outside = verdict(unsampled.getFirst(), Adjudications.WRONG, Adjudications.OPERATOR, null);
        var ignored = new Adjudications.Book(List.of(outside), GUIDE, SEED, 0.2, 0.2);
        assertEquals(0, RecordBounds.k(GraphEvalHarness.evaluation(configured(records), ignored).gates().get("uses")
                .writing()), "an unsampled agreed record adds nothing");
        var right = verdict(sampled.getFirst(), Adjudications.RIGHT, Adjudications.OPERATOR, null);
        assertEquals(0, RecordBounds.k(GraphEvalHarness.evaluation(configured(records),
                new Adjudications.Book(List.of(right), GUIDE, SEED, 0.2, 0.2)).gates().get("uses").writing()));
    }

    @Test
    void unmatchedAndSampledAgreedRecordsWithoutAVerdictAreUnjudgedAndUnsampledOnesNever() {
        var records = new ArrayList<>(agreed(100, "uses"));
        var unmatched = new GateRecord("c900", "term:Kestrel:System", GraphEvalScorer.TERMS, false);
        records.add(unmatched);
        var empty = new Adjudications.Book(List.of(), GUIDE, SEED, 0.2, 0.2);
        int sampled = (int) records.stream().filter(empty::sampled).count();
        var j = empty.judge(records);
        assertEquals(sampled + 1, j.unjudgedCount());
        assertEquals(Map.of("uses", sampled, GraphEvalScorer.TERMS, 1), j.unjudgedByGate());
        assertEquals(sampled, j.sampled().size());

        var verdicts = new ArrayList<Verdict>();
        records.stream().filter(empty::sampled).forEach(r -> verdicts.add(verdict(r, Adjudications.RIGHT,
                Adjudications.OPERATOR, null)));
        verdicts.add(verdict(unmatched, Adjudications.WRONG, Adjudications.OPERATOR, null));
        var judged = new Adjudications.Book(verdicts, GUIDE, SEED, 0.2, 0.2).judge(records);
        assertEquals(0, judged.unjudgedCount());
        assertFalse(judged.labelError());

        var stale = new Adjudications.Book(verdicts, "guide@111111111111", SEED, 0.2, 0.2).judge(records);
        assertEquals(sampled + 1, stale.unjudgedCount(), "a verdict under another guide version does not count");

        verdicts.set(verdicts.size() - 1, verdict(unmatched, Adjudications.LABEL_ERROR, Adjudications.OPERATOR, null));
        assertTrue(new Adjudications.Book(verdicts, GUIDE, SEED, 0.2, 0.2).judge(records).labelError());
    }

    @Test
    void theCheckIsDrawnApartFromTheAgreedSample() {
        var records = agreed(3000, "uses");
        var draw = new Adjudications.Book(List.of(), GUIDE, SEED, 0.2, 0.2);
        var sampled = records.stream().filter(draw::sampled).toList();
        var verdicts = sampled.stream().map(r -> verdict(r, Adjudications.RIGHT, "model:clef", null)).toList();
        var j = new Adjudications.Book(verdicts, GUIDE, SEED, 0.2, 0.2).judge(records);
        assertEquals(sampled.size(), j.sampled().size());
        double share = (double) j.marked() / sampled.size();
        assertTrue(share > 0.12 && share < 0.28, "about a fifth of the sampled verdicts, not all: " + share);
        var wide = new Adjudications.Book(verdicts, GUIDE, SEED, 0.2, 0.5).judge(records);
        double wideShare = (double) wide.marked() / sampled.size();
        assertTrue(wideShare > 0.4 && wideShare < 0.6, "a check share above the agreed share still marks about it: "
                + wideShare);
    }

    @Test
    void aSeededShareOfModelVerdictsIsMarkedAndAnUncheckedOneKeepsItPending() {
        var records = agreed(400, "uses");
        var book = new Adjudications.Book(List.of(), GUIDE, SEED, 1.0, 0.2);
        var unchecked = records.stream().map(r -> verdict(r, Adjudications.RIGHT, "model:clef", null)).toList();
        var j = new Adjudications.Book(unchecked, GUIDE, SEED, 1.0, 0.2).judge(records);
        assertEquals(0, j.unjudgedCount());
        assertTrue(j.marked() > 40 && j.marked() < 130, "about a fifth: " + j.marked());
        assertEquals(j.marked(), j.uncheckedMarked());
        assertNull(j.disagreementRate());

        var checked = new ArrayList<Verdict>();
        int[] n = {0};
        for (var r : records) {
            boolean marked = book.checkMarked(r);
            String check = marked ? (n[0]++ % 4 == 0 ? Adjudications.DISAGREE : Adjudications.AGREE) : null;
            checked.add(verdict(r, Adjudications.RIGHT, "model:clef", check));
        }
        var k = new Adjudications.Book(checked, GUIDE, SEED, 1.0, 0.2).judge(records);
        assertEquals(0, k.uncheckedMarked());
        assertEquals(j.marked(), k.checked());
        assertEquals((double) k.disagreed() / k.checked(), k.disagreementRate());
        assertTrue(k.disagreed() > 0);
        assertEquals(0.2, book.checkShare());
    }
}
