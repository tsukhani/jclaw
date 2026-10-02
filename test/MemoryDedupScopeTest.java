import memory.MemoryAutoCapture;
import memory.MemoryStoreFactory;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import utils.CircuitBreaker;


/**
 * JCLAW-920 + JCLAW-922: the capture-time dedup pool. Runs with the Lucene index
 * OPEN because the pool's retrieval leg is the FTS search — the closed-index DB
 * LIKE fallback only matches whole substrings, which would not exercise the
 * paraphrase path at all.
 */
class MemoryDedupScopeTest extends UnitTest {

    /** Above the default memory.autocapture.dedup.scanLimit, so filler evicts the target. */
    private static final int FILLER_ROWS = 105;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void release() {
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

    /** Drive capture with one canned candidate; returns how many rows it stored. */
    private int captureOne(String agentId, String candidateText) {
        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"%s\",\"category\":\"fact\",\"importance\":0.7}]}"
                        .formatted(candidateText.replace("\"", "\\\""));
        var result = MemoryAutoCapture.capture(agentId, "dedup-agent",
                "Here is something durable worth remembering about my setup.",
                "Understood, noted.", extractor, freshBreaker());
        return result.captured();
    }

    /** Read past the persistence context: corroboration is a bulk update. */
    private static int corroborations(Long id) {
        return play.db.jpa.JPA.em()
                .createQuery("SELECT m.corroborationCount FROM Memory m WHERE m.id = :id", Integer.class)
                .setParameter("id", id).getSingleResult();
    }

    @Test
    void aWithinTurnDuplicateStoresOneRowAndCorroboratesNothing() {
        var agent = agentId("dedup-within-turn");
        var item = "{\"text\":\"The user drives a Tesla Model Y in Malaysia.\",\"category\":\"fact\",\"importance\":0.7}";
        MemoryAutoCapture.Extractor extractor = msgs -> "{\"memories\":[" + item + "," + item + "]}";
        var result = MemoryAutoCapture.capture(agent, "dedup-agent",
                "Here is something durable worth remembering about my setup.",
                "Understood, noted.", extractor, freshBreaker());

        assertEquals(1, result.captured());
        var rows = Memory.findByAgent(agent);
        assertEquals(1, rows.size());
        assertEquals(0, corroborations(rows.getFirst().id), "a survivor match is not a corroboration");
    }

    // ─── control: the harness can still store something ──────────────────────

    @Test
    void unrelatedCandidateIsStored() {
        var agent = agentId("dedup-control");
        MemoryStoreFactory.get().store(agent, "The user's NAS is at 192.168.0.50.", "fact", 0.7);

        assertEquals(1, captureOne(agent, "The user drives a Tesla Model Y in Malaysia."),
                "an unrelated fact must still be captured — otherwise the dedup assertions below prove nothing");
    }

    // ─── JCLAW-922: semantic restatement, Jaccard below threshold ────────────

    @Test
    void paraphraseOfExistingMemoryIsRejected() {
        var agent = agentId("dedup-paraphrase");
        MemoryStoreFactory.get().store(agent,
                "The user has a NAS at home with IP address 192.168.0.50 with various SMB shares.",
                "fact", 0.7);

        // Token Jaccard 0.55 — under the 0.85 NOOP, so pre-JCLAW-922 this appended.
        assertEquals(0, captureOne(agent, "The user's NAS/SMB server is at IP address 192.168.0.50."),
                "a paraphrase of a stored memory must NOOP");
        assertEquals(1, Memory.findByAgent(agent).size(), "no second row may be written");
        assertEquals(1, corroborations(Memory.findByAgent(agent).getFirst().id),
                "the dropped restatement corroborates the stored row (JCLAW-1318)");
    }

    @Test
    void subsetRestatementOfExistingMemoryIsRejected() {
        var agent = agentId("dedup-subset");
        MemoryStoreFactory.get().store(agent,
                "The user enjoys action, sci-fi, and thriller movies, but not horror or romance as primary genres.",
                "preference", 0.7);

        // Jaccard 0.53 — the shorter restatement is a strict subset.
        assertEquals(0, captureOne(agent, "The user enjoys Action, Sci-Fi, and Thriller genres."));
        assertEquals(1, Memory.findByAgent(agent).size());
    }

    // ─── JCLAW-920: duplicate older than the recency window ─────────────────

    @Test
    void duplicateOlderThanScanLimitIsRejected() {
        var agent = agentId("dedup-window");
        var store = MemoryStoreFactory.get();
        var target = "The user's mother is named Martha Kowalczyk.";
        store.store(agent, target, "core", 0.9);

        // Push the target out of the recency slice the pre-JCLAW-920 dedup scanned.
        for (int i = 0; i < FILLER_ROWS; i++) {
            store.store(agent, "Unrelated filler observation number %d about widgets.".formatted(i),
                    "fact", 0.4);
        }
        assertTrue(Memory.findByAgent(agent, FILLER_ROWS + 10).size() > 100,
                "filler must exceed the default scan limit for this test to mean anything");

        assertEquals(0, captureOne(agent, target),
                "an exact duplicate outside the recency window must still NOOP");
    }
}
