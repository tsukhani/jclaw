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

    @Test
    void twoRestatementsInOneTurnCorroborateTheStoredRowOnce() {
        var agent = agentId("dedup-once-per-turn");
        var stored = "The user has a NAS at home with IP address 192.168.0.50 with various SMB shares.";
        MemoryStoreFactory.get().store(agent, stored, "fact", 0.7);
        MemoryAutoCapture.Extractor extractor = msgs -> ("{\"memories\":["
                + "{\"text\":\"%s\",\"category\":\"fact\",\"importance\":0.7},"
                + "{\"text\":\"The user's NAS/SMB server is at IP address 192.168.0.50.\",\"category\":\"fact\",\"importance\":0.6}]}")
                .formatted(stored);
        var result = MemoryAutoCapture.capture(agent, "dedup-agent",
                "Here is something durable worth remembering about my setup.",
                "Understood, noted.", extractor, freshBreaker());

        assertEquals(0, result.captured());
        var rows = Memory.findByAgent(agent);
        assertEquals(1, rows.size());
        assertEquals(1, corroborations(rows.getFirst().id), "repetition inside one turn counts once");
    }

    // ─── JCLAW-1353: a guest's restatement is a duplicate but not a corroboration ──

    private int captureAs(String agentId, String candidateText, memory.MemoryProvenance provenance) {
        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"%s\",\"category\":\"fact\",\"importance\":0.7}]}"
                        .formatted(candidateText);
        return MemoryAutoCapture.capture(agentId, "dedup-agent",
                "Here is something durable worth remembering about my setup.",
                "Understood, noted.", extractor, null, freshBreaker(), provenance).captured();
    }

    @Test
    void aGuestLexicalRestatementIsDroppedWithoutCorroborating() {
        var agent = agentId("dedup-guest-lexical");
        var stored = "The user drives a Tesla Model Y in Malaysia.";
        MemoryStoreFactory.get().store(agent, stored, "fact", 0.7);

        assertEquals(0, captureAs(agent, stored,
                memory.MemoryProvenance.extractor("m1", models.MemoryAuthorType.GUEST_TURN)));
        var rows = Memory.findByAgent(agent);
        assertEquals(1, rows.size(), "still a duplicate, so no second row");
        assertEquals(0, corroborations(rows.getFirst().id));
    }

    @Test
    void anOperatorLexicalRestatementCorroborates() {
        var agent = agentId("dedup-operator-lexical");
        var stored = "The user drives a Tesla Model Y in Malaysia.";
        MemoryStoreFactory.get().store(agent, stored, "fact", 0.7);

        assertEquals(0, captureAs(agent, stored,
                memory.MemoryProvenance.extractor("m1", models.MemoryAuthorType.HUMAN_TURN)));
        assertEquals(1, corroborations(Memory.findByAgent(agent).getFirst().id));
    }

    // ─── JF-147: a guest's write and the operator's never retire each other ──

    private static memory.MemoryProvenance guestIn(models.Conversation chat) {
        return new memory.MemoryProvenance(chat.id, null, memory.MemoryProvenance.guest(chat.channelType),
                models.MemoryAuthorType.GUEST_TURN, java.util.List.of());
    }

    private static models.Conversation chat(String agentId, String channel, String peer) {
        return services.ConversationService.create(models.Agent.findById(Long.valueOf(agentId)), channel, peer);
    }

    @Test
    void anOperatorRestatingAGuestsFactInTheSameGroupStoresTheirOwnRow() {
        var agent = agentId("dedup-owner-over-guest");
        var group = chat(agent, "telegram", "-100555");
        var stated = "The user drives a Tesla Model Y in Malaysia.";
        var guestRow = Long.valueOf(MemoryStoreFactory.get().storeDeferred(agent, stated, "fact", 0.7, null,
                guestIn(group)));

        assertEquals(1, captureAs(agent, stated,
                memory.MemoryProvenance.extractor("m1", models.MemoryAuthorType.HUMAN_TURN).withSource(group.id, null)));

        var rows = Memory.findByAgent(agent);
        assertEquals(2, rows.size(), "the operator's statement is stored as the operator's own row");
        assertEquals(java.util.Set.of(models.MemoryAuthorType.GUEST_TURN, models.MemoryAuthorType.HUMAN_TURN),
                rows.stream().map(m -> m.authorType).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, corroborations(guestRow), "the guest's row is untouched");
    }

    @Test
    void aGuestContradictingTheOperatorsFactNeverSupersedesIt() {
        var agent = agentId("dedup-guest-contradicts");
        var operatorsRow = Long.valueOf(MemoryStoreFactory.get().store(agent, "The user drives an Xpeng G6", "fact", 0.7));
        var judged = new java.util.concurrent.atomic.AtomicBoolean();
        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"The user drives a Volvo EX30 since March\",\"category\":\"fact\",\"importance\":0.7}]}";
        MemoryAutoCapture.Consolidator consolidator = msgs -> {
            judged.set(true);
            return "{\"supersessions\":[{\"new\":0,\"old\":[0]}]}";
        };

        var result = MemoryAutoCapture.capture(agent, "dedup-agent", "I swapped the Xpeng for a Volvo EX30 in March.",
                "Noted.", extractor, consolidator, freshBreaker(), guestIn(chat(agent, "whatsapp", "60111")));

        assertEquals(1, result.captured());
        assertTrue(judged.get(), "precondition: the judge paired the two, so the guard is what kept the row");
        Memory kept = Memory.findById(operatorsRow);
        assertNull(kept.supersededAt);
        assertEquals(2, Memory.findByAgent(agent).size());
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
