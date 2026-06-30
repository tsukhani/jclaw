import org.junit.jupiter.api.*;
import play.test.*;
import memory.*;
import utils.CircuitBreaker;

class MemoryAutoCaptureTest extends UnitTest {

    @BeforeEach
    void setup() {
        // capture() persists via the store (→ Memory @PostPersist Lucene upsert)
        // and dedups via Memory.findByAgent; force the index closed and serialize
        // against the other Lucene tests, mirroring MemoryStoreTest.
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

    // ─── parseCandidates ─────────────────────────────────────────────────────

    @Test
    void parsesMemoriesObject() {
        var json = "{\"memories\":[{\"text\":\"The user prefers dark mode\",\"category\":\"preference\",\"importance\":0.8}]}";
        var cands = MemoryAutoCapture.parseCandidates(json);
        assertEquals(1, cands.size());
        assertEquals("The user prefers dark mode", cands.getFirst().text());
        assertEquals("preference", cands.getFirst().category());
        assertEquals(0.8, cands.getFirst().importance(), 1e-9);
    }

    @Test
    void parsesFencedBareArrayAndDefaultsImportance() {
        var json = "```json\n[{\"text\":\"Prod DB is Postgres\",\"category\":\"fact\"}]\n```";
        var cands = MemoryAutoCapture.parseCandidates(json);
        assertEquals(1, cands.size());
        assertEquals("fact", cands.getFirst().category());
        assertEquals(MemoryCategory.FACT.defaultImportance, cands.getFirst().importance(), 1e-9);
    }

    @Test
    void malformedOrEmptyJsonYieldsEmpty() {
        assertTrue(MemoryAutoCapture.parseCandidates("not json at all").isEmpty());
        assertTrue(MemoryAutoCapture.parseCandidates("{\"memories\": \"oops\"}").isEmpty());
        assertTrue(MemoryAutoCapture.parseCandidates("").isEmpty());
    }

    @Test
    void clampsImportanceAndNormalizesCategory() {
        var c = MemoryAutoCapture.parseCandidates(
                "{\"memories\":[{\"text\":\"x\",\"category\":\"CORE\",\"importance\":5}]}").getFirst();
        assertEquals("core", c.category());
        assertEquals(1.0, c.importance(), 1e-9);
    }

    // ─── capture() end-to-end via injected seam ──────────────────────────────

    @Test
    void captureStoresExtractedMemories() {
        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"The user works at Acme\",\"category\":\"fact\",\"importance\":0.6}]}";
        var result = MemoryAutoCapture.capture("agent-cap", "agent-cap", "I work at Acme Corp on widgets",
                "Noted — Acme Corp.", extractor, freshBreaker());

        assertEquals(1, result.captured());
        var stored = MemoryStoreFactory.get().list("agent-cap");
        assertEquals(1, stored.size());
        assertEquals("fact", stored.getFirst().category());
    }

    @Test
    void captureSkipsTrivialTurnViaGateWithoutCallingExtractor() {
        MemoryAutoCapture.Extractor extractor = msgs -> {
            throw new AssertionError("extractor must not run for a gated turn");
        };
        var result = MemoryAutoCapture.capture("agent-triv", "agent-triv", "thanks!", "You're welcome.",
                extractor, freshBreaker());
        assertEquals("trivial", result.skipReason());
        assertEquals(0, result.captured());
        assertEquals(0, MemoryStoreFactory.get().list("agent-triv").size());
    }

    @Test
    void captureDeduplicatesAgainstExisting() {
        var store = MemoryStoreFactory.get();
        store.store("agent-dup", "The user prefers dark mode interfaces", "preference", 0.7);

        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"The user prefers dark mode interfaces\",\"category\":\"preference\",\"importance\":0.7}]}";
        var result = MemoryAutoCapture.capture("agent-dup", "agent-dup", "I really like dark mode in all my apps",
                "Got it.", extractor, freshBreaker());

        assertEquals(0, result.captured());                 // NOOP — duplicate
        assertEquals(1, store.list("agent-dup").size());    // store unchanged
    }

    @Test
    void captureRecordsFailureOnExtractorError() {
        var breaker = freshBreaker();
        MemoryAutoCapture.Extractor extractor = msgs -> {
            throw new RuntimeException("boom");
        };
        var result = MemoryAutoCapture.capture("agent-err", "agent-err", "I live in Berlin and work on ML",
                "Noted.", extractor, breaker);
        assertEquals("extraction_error", result.skipReason());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state()); // one failure, below minVolume
    }

    @Test
    void captureSkipsWhenBreakerOpen() {
        var breaker = new CircuitBreaker(1, 1.0, 1, 60_000L);
        breaker.recordFailure();  // 1/1 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        MemoryAutoCapture.Extractor extractor = msgs -> {
            throw new AssertionError("extractor must not run when breaker is open");
        };
        var result = MemoryAutoCapture.capture("agent-open", "agent-open", "I prefer tabs over spaces",
                "Understood.", extractor, breaker);
        assertEquals("breaker_open", result.skipReason());
    }

    @Test
    void captureRespectsMaxPerTurn() {
        var sb = new StringBuilder("{\"memories\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"text\":\"Distinct durable fact number %d about project alpha%d\",\"category\":\"fact\",\"importance\":0.5}"
                    .formatted(i, i));
        }
        sb.append("]}");
        final var json = sb.toString();
        MemoryAutoCapture.Extractor extractor = msgs -> json;

        var result = MemoryAutoCapture.capture("agent-cap5", "agent-cap5",
                "Here are several facts about project alpha for you to remember going forward",
                "Recorded.", extractor, freshBreaker());
        assertEquals(5, result.captured());  // default maxPerTurn=5
        assertEquals(5, MemoryStoreFactory.get().list("agent-cap5").size());
    }
}
