import memory.MemoryAutoCapture;
import memory.MemoryStoreFactory;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import utils.CircuitBreaker;

import java.util.List;
import java.util.Map;

/**
 * JCLAW-525: memory consolidation — supersession and conflict resolution.
 * Exercises the capture pipeline's plan → judge → apply phases via canned
 * {@code Extractor}/{@code Consolidator} seams (no LLM), plus the recall
 * exclusion contract of {@code Memory.supersede}. Same harness stance as
 * {@code MemoryAutoCaptureTest}: index forced closed (LuceneTestSync), recall
 * assertions ride the deterministic agent-bounded LIKE fallback.
 */
class MemoryConsolidationTest extends UnitTest {

    private static final String BERLIN = "The user lives in Berlin";
    private static final String PORTO = "The user lives in Porto";
    /** Extractor output producing the Porto candidate. */
    private static final String PORTO_JSON =
            "{\"memories\":[{\"text\":\"" + PORTO + "\",\"category\":\"fact\",\"importance\":0.7}]}";
    /** Judge verdict: NEW 0 supersedes EXISTING 0. */
    private static final String SUPERSEDE_JSON = "{\"supersessions\":[{\"new\":0,\"old\":[0]}]}";

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    private CircuitBreaker freshBreaker() {
        return new CircuitBreaker(20, 0.5, 5, 30_000L);
    }

    private String agentId(String name) {
        var a = models.Agent.find("name = ?1", name).<models.Agent>first();
        if (a == null) {
            a = new models.Agent();
            a.name = name;
            a.modelProvider = "openrouter";
            a.modelId = "gpt-4.1";
            a.save();
        }
        return String.valueOf(a.id);
    }

    // ─── parseSupersessions ──────────────────────────────────────────────────

    @Test
    void parsesValidSupersessionsIncludingFenced() {
        assertEquals(Map.of(0, List.of(2)),
                MemoryAutoCapture.parseSupersessions("{\"supersessions\":[{\"new\":0,\"old\":[2]}]}", 1, 3));
        assertEquals(Map.of(1, List.of(0, 2)),
                MemoryAutoCapture.parseSupersessions(
                        "```json\n{\"supersessions\":[{\"new\":1,\"old\":[0,2]}]}\n```", 2, 3));
    }

    @Test
    void outOfRangeAndDuplicateIndicesAreDropped() {
        // new=5 out of range → entry ignored; old 7 out of range and the
        // repeated 1 are dropped from the surviving entry.
        var parsed = MemoryAutoCapture.parseSupersessions(
                "{\"supersessions\":[{\"new\":5,\"old\":[0]},{\"new\":0,\"old\":[7,1,1]}]}", 2, 3);
        assertEquals(Map.of(0, List.of(1)), parsed);
    }

    @Test
    void malformedOrWrongShapeYieldsNoSupersessions() {
        assertTrue(MemoryAutoCapture.parseSupersessions("not json", 1, 1).isEmpty());
        assertTrue(MemoryAutoCapture.parseSupersessions("{\"supersessions\":\"oops\"}", 1, 1).isEmpty());
        assertTrue(MemoryAutoCapture.parseSupersessions("[{\"new\":0,\"old\":[0]}]", 1, 1).isEmpty());
        assertTrue(MemoryAutoCapture.parseSupersessions("", 1, 1).isEmpty());
        assertTrue(MemoryAutoCapture.parseSupersessions("{\"supersessions\":[]}", 1, 1).isEmpty());
    }

    // ─── capture with consolidation: supersede path ──────────────────────────

    @Test
    void contradictingCaptureSupersedesTheOlderMemory() {
        var agent = agentId("consolidate");
        var store = MemoryStoreFactory.get();
        var berlinId = Long.valueOf(store.store(agent, BERLIN, "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> PORTO_JSON;
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        var result = MemoryAutoCapture.capture(agent, "consolidate",
                "Quick update: I moved from Berlin to Porto last month",
                "Noted — Porto it is.", extractor, consolidator, freshBreaker());

        assertEquals(1, result.captured());
        // The older memory is marked superseded — not hard-deleted (AC).
        Memory berlin = Memory.findById(berlinId);
        assertNotNull(berlin, "superseded memories are retained, never deleted");
        assertNotNull(berlin.supersededAt, "the older memory must be marked superseded");
        var active = Memory.findByAgent(agent);
        assertEquals(1, active.size(), "recall-facing listing shows only the active memory");
        assertEquals(PORTO, active.getFirst().text);
        // Deterministic provenance: superseded BY the newer row (serial order).
        assertEquals(active.getFirst().id, berlin.supersededById);
        assertTrue(berlin.id < berlin.supersededById,
                "the newer write always wins — recency by serial comparison");
    }

    @Test
    void supersededMemoriesAreExcludedFromRecallPaths() {
        var agent = agentId("excl");
        var store = MemoryStoreFactory.get();
        var oldCoreId = Long.valueOf(store.store(agent, "The user is named Martha", "core", 0.9));
        var newId = Long.valueOf(store.store(agent, "The user is named Martha Reyes", "core", 0.9));
        Memory.<Memory>findById(oldCoreId).supersede(newId);

        // Text recall (LIKE-fallback lane, deterministic in unit tests).
        var recalled = Memory.searchByText(agent, "martha", 10);
        assertEquals(1, recalled.size(), "superseded rows must not surface in recall");
        assertEquals(newId, recalled.getFirst().id);
        // Core auto-load (JCLAW-40 path).
        var core = Memory.findCore(agent, 0.5, 10);
        assertEquals(1, core.size(), "superseded core memories must not auto-load");
        assertEquals(newId, core.getFirst().id);
        // Store list API.
        assertEquals(1, store.list(agent).size());
    }

    @Test
    void supersededMemoryDoesNotDedupNoopARepeatedFact() {
        // "Lives in Porto" superseded "lives in Berlin"; the user later moves
        // back. The Berlin candidate is a near-duplicate of the SUPERSEDED row
        // only — the dedup scan must ignore that row or the move-back is lost.
        var agent = agentId("moveback");
        var store = MemoryStoreFactory.get();
        var berlinId = Long.valueOf(store.store(agent, BERLIN, "fact", 0.7));
        var portoId = Long.valueOf(store.store(agent, PORTO, "fact", 0.7));
        Memory.<Memory>findById(berlinId).supersede(portoId);

        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"" + BERLIN + "\",\"category\":\"fact\",\"importance\":0.7}]}";
        // Judge pairs the re-captured Berlin against the active Porto row.
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        var result = MemoryAutoCapture.capture(agent, "moveback",
                "Actually I have moved back to Berlin now",
                "Welcome back to Berlin.", extractor, consolidator, freshBreaker());

        assertEquals(1, result.captured(),
                "a fact matching only a superseded row must store, not NOOP");
        var active = Memory.findByAgent(agent);
        assertEquals(1, active.size());
        assertEquals(BERLIN, active.getFirst().text, "Porto is now superseded in turn");
    }

    // ─── fail-open: judge problems never block the write ─────────────────────

    @Test
    void judgeFailureStoresAppendOnlyWithoutSupersession() {
        var agent = agentId("judge-fail");
        var store = MemoryStoreFactory.get();
        var berlinId = Long.valueOf(store.store(agent, BERLIN, "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> PORTO_JSON;
        MemoryAutoCapture.Consolidator consolidator = msgs -> {
            throw new RuntimeException("judge model unreachable");
        };
        var result = MemoryAutoCapture.capture(agent, "judge-fail",
                "Quick update: I moved from Berlin to Porto last month",
                "Noted.", extractor, consolidator, freshBreaker());

        assertEquals(1, result.captured(), "a judge failure must never block the write");
        assertNull(Memory.<Memory>findById(berlinId).supersededAt,
                "nothing is superseded on a failed judgement");
        assertEquals(2, Memory.findByAgent(agent).size(), "append-only fallback");
    }

    @Test
    void malformedJudgeOutputStoresAppendOnly() {
        var agent = agentId("judge-garble");
        var store = MemoryStoreFactory.get();
        var berlinId = Long.valueOf(store.store(agent, BERLIN, "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> PORTO_JSON;
        MemoryAutoCapture.Consolidator consolidator = msgs -> "I think the first one supersedes!";
        MemoryAutoCapture.capture(agent, "judge-garble",
                "Quick update: I moved from Berlin to Porto last month",
                "Noted.", extractor, consolidator, freshBreaker());

        assertNull(Memory.<Memory>findById(berlinId).supersededAt);
        assertEquals(2, Memory.findByAgent(agent).size());
    }

    @Test
    void nullConsolidatorKeepsPre525AppendOnlyBehavior() {
        var agent = agentId("no-judge");
        var store = MemoryStoreFactory.get();
        store.store(agent, BERLIN, "fact", 0.7);

        MemoryAutoCapture.Extractor extractor = msgs -> PORTO_JSON;
        var result = MemoryAutoCapture.capture(agent, "no-judge",
                "Quick update: I moved from Berlin to Porto last month",
                "Noted.", extractor, freshBreaker());   // six-arg overload

        assertEquals(1, result.captured());
        assertEquals(2, Memory.findByAgent(agent).size(), "no consolidator → append-only");
    }
}
