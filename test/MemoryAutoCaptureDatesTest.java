import memory.MemoryAutoCapture;
import memory.MemoryForgetLog;
import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import services.TimezoneResolver;
import utils.AppClock;
import utils.CircuitBreaker;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/** JCLAW-1383: capture stores relative dates as absolute ones, anchored on the source turn. */
class MemoryAutoCaptureDatesTest extends UnitTest {

    private static final String SUPERSEDE_JSON = "{\"supersessions\":[{\"new\":0,\"old\":[0]}]}";
    private static final LocalDate D = LocalDate.of(2026, 10, 3);

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
    }

    @AfterEach
    void luceneRelease() {
        MemoryForgetLog.clearForTest();
        LuceneTestSync.release();
    }

    private CircuitBreaker freshBreaker() {
        return new CircuitBreaker(20, 0.5, 5, 30_000L);
    }

    private models.Agent agent(String name) {
        var a = models.Agent.find("name = ?1", name).<models.Agent>first();
        if (a == null) {
            a = new models.Agent();
            a.name = name;
            a.modelProvider = "openrouter";
            a.modelId = "gpt-4.1";
            a.save();
        }
        return a;
    }

    private String agentId(String name) {
        return String.valueOf(agent(name).id);
    }

    private static Instant appZoneInstant(LocalDate day, LocalTime time) {
        return ZonedDateTime.of(day, time, TimezoneResolver.appZone()).toInstant();
    }

    private static <T> T at(Instant instant, Supplier<T> block) {
        return AppClock.callWith(Clock.fixed(instant, ZoneOffset.UTC), block::get);
    }

    /** Noon in the app zone on {@code day}. */
    private static <T> T on(LocalDate day, Supplier<T> block) {
        return at(appZoneInstant(day, LocalTime.NOON), block);
    }

    private static String extractorJson(String text) {
        return "{\"memories\":[{\"text\":\"" + text + "\",\"category\":\"fact\",\"importance\":0.7}]}";
    }

    private MemoryAutoCapture.CaptureResult capture(String name, String text,
            MemoryAutoCapture.Consolidator consolidator) {
        return MemoryAutoCapture.capture(agentId(name), name, "Worth remembering: " + text, "Noted.",
                msgs -> extractorJson(text), consolidator, freshBreaker());
    }

    private Memory only(String name) {
        var active = Memory.findByAgent(agentId(name));
        assertEquals(1, active.size(), () -> "active rows: " + active);
        return active.getFirst();
    }

    // ─── (a) anchor ──────────────────────────────────────────────────────────

    @Test
    void theAnchorIsTheSourceMessagesDayNotTheClocks() {
        var a = agent("anchor-source");
        var conv = ConversationService.create(a, "web", "u-dates");
        var user = at(appZoneInstant(D, LocalTime.of(23, 59)),
                () -> ConversationService.appendUserMessage(conv, "I moved to Porto yesterday."));

        var result = at(appZoneInstant(D.plusDays(1), LocalTime.of(0, 30)), () -> MemoryAutoCapture.capture(
                agentId("anchor-source"), a.name, "I moved to Porto yesterday, worth remembering.", "Noted.",
                msgs -> extractorJson("The user moved to Porto yesterday."), null, freshBreaker(),
                MemoryProvenance.extractor("m1").withSource(conv.id, user.id)));

        assertEquals(1, result.captured());
        assertEquals("The user moved to Porto on 2 October 2026.", only("anchor-source").text);
    }

    @Test
    void withNoSourceMessageTheAnchorIsTheClocksDay() {
        var result = at(appZoneInstant(D.plusDays(1), LocalTime.of(0, 30)),
                () -> capture("anchor-clock", "The user moved to Porto yesterday.", null));

        assertEquals(1, result.captured());
        assertEquals("The user moved to Porto on 3 October 2026.", only("anchor-clock").text);
    }

    @Test
    void theTableFormsAreStoredWithEveryOtherCharacterUnchanged() {
        on(D, () -> capture("table", "Last year the user joined Vela; the retreat is this June.", null));
        assertEquals("In 2025 the user joined Vela; the retreat is in June 2026.", only("table").text);
    }

    // ─── (b) safety filters judge the extractor's own words ─────────────────

    @Test
    void aCandidateSourcedFromTheAssistantIsStillDropped() {
        var result = on(D, () -> MemoryAutoCapture.capture(agentId("filtered"), "filtered",
                "Who is my accountant again?", "Your optometrist Dr Sena moved her clinic yesterday.",
                msgs -> extractorJson("Optometrist Dr Sena moved her clinic yesterday."), null, freshBreaker()));

        assertEquals("all_filtered", result.skipReason());
        assertTrue(Memory.findByAgent(agentId("filtered")).isEmpty());
    }

    @Test
    void theFiltersJudgeTheRawTextNotTheRewrittenOne() {
        // The user turn shares only "yesterday" with the candidate: the raw text passes the assistant-content
        // filter, while the rewritten text would touch nothing in the user turn and be dropped.
        var result = on(D, () -> MemoryAutoCapture.capture(agentId("raw-verdict"), "raw-verdict",
                "Busy day yesterday.", "Your optometrist Dr Sena moved her clinic yesterday.",
                msgs -> extractorJson("Optometrist Dr Sena moved her clinic yesterday."), null, freshBreaker()));

        assertEquals(1, result.captured(), () -> "skip: " + result.skipReason());
        assertEquals("Optometrist Dr Sena moved her clinic on 2 October 2026.", only("raw-verdict").text);
    }

    @Test
    void aFactForgottenInItsAbsoluteFormIsNotRecapturedFromItsRelativeForm() {
        var name = "forgotten";
        MemoryForgetLog.noteForgotten(agentId(name), "The user moved to Porto on 2 October 2026.");

        var result = on(D, () -> capture(name, "The user moved to Porto yesterday.", null));

        assertEquals("all_filtered", result.skipReason());
        assertTrue(Memory.findByAgent(agentId(name)).isEmpty());
    }

    // ─── (c) retrievalKey, category, importance ─────────────────────────────

    @Test
    void theRetrievalKeyCategoryAndImportanceSurvive() {
        var json = "{\"memories\":[{\"text\":\"The user moved to Porto yesterday.\",\"category\":\"decision\","
                + "\"importance\":0.83,\"questions\":[\"Did the user move yesterday?\",\"Where did the user move last"
                + " year?\"]}]}";
        on(D, () -> MemoryAutoCapture.capture(agentId("key"), "key", "Worth remembering: I moved to Porto yesterday.",
                "Noted.", msgs -> json, null, freshBreaker()));

        var m = only("key");
        assertEquals("The user moved to Porto on 2 October 2026.", m.text);
        assertEquals("Did the user move yesterday?\nWhere did the user move last year?", m.retrievalKey);
        assertEquals("decision", m.category);
        assertEquals(0.83, m.importance);
    }

    // ─── (d) guard effects and dedup ─────────────────────────────────────────

    @Test
    void aDatelessReplacementNoLongerSupersedesARewrittenYear() {
        on(D, () -> capture("values-dateless", "The user joined the Vela studio last year.", null));
        var older = only("values-dateless");
        assertEquals("The user joined the Vela studio in 2025.", older.text);

        on(D, () -> capture("values-dateless", "The user left the Vela studio.", msgs -> SUPERSEDE_JSON));

        assertNull(Memory.<Memory>findById(older.id).supersededAt, "the year the older row pinned would be lost");
    }

    @Test
    void aReplacementRewrittenToTheSameYearSupersedes() {
        on(D, () -> capture("values-dated", "The user joined the Vela studio last year.", null));
        var older = only("values-dated");

        on(D, () -> capture("values-dated", "The user left the Vela studio last year.", msgs -> SUPERSEDE_JSON));

        assertNotNull(Memory.<Memory>findById(older.id).supersededAt);
        assertEquals("The user left the Vela studio in 2025.", only("values-dated").text);
    }

    @Test
    void unrelatedMemoriesRewrittenIntoTheSameMonthShareASubject() {
        on(D, () -> capture("subject-month", "The user adopted a cat last month.", null));
        var older = only("subject-month");
        assertEquals("The user adopted a cat in September 2026.", older.text);

        on(D, () -> capture("subject-month", "The user sold the car last month.", msgs -> SUPERSEDE_JSON));

        assertNotNull(Memory.<Memory>findById(older.id).supersededAt, "september is now a shared content token");
    }

    @Test
    void memoriesThatSharedOnlyYesterdayNoLongerShareASubject() {
        on(LocalDate.of(2026, 9, 15), () -> capture("subject-day", "The user adopted a cat yesterday.", null));
        var older = only("subject-day");
        assertEquals("The user adopted a cat on 14 September 2026.", older.text);

        on(D, () -> capture("subject-day", "The user sold the car yesterday.", msgs -> SUPERSEDE_JSON));

        assertNull(Memory.<Memory>findById(older.id).supersededAt, "the pair shares only digits now");
        assertEquals(2, Memory.findByAgent(agentId("subject-day")).size());
    }

    @Test
    void theRewritesExtraTokensClearTheContentRatio() {
        var name = "informative";
        var olderId = Long.valueOf(MemoryStoreFactory.get().store(agentId(name),
                "The user moved to Porto with Ana and Rui and Bea.", "fact", 0.7));

        on(D, () -> capture(name, "The user moved to Lisbon yesterday.", msgs -> SUPERSEDE_JSON));

        assertNotNull(Memory.<Memory>findById(olderId).supersededAt,
                "five content tokens against five; the raw text's three fell below 0.75");
        assertEquals("The user moved to Lisbon on 2 October 2026.", only(name).text);
    }

    @Test
    void anOlderRawRelativeRowNoLongerDeduplicatesTheRewrittenCandidate() {
        var name = "dedup-raw";
        MemoryStoreFactory.get().store(agentId(name), "The user moved to Porto last year.", "fact", 0.7);

        var result = on(D, () -> capture(name, "The user moved to Porto last year.", null));

        assertEquals(1, result.captured());
        assertEquals(2, Memory.findByAgent(agentId(name)).size());
    }

    @Test
    void anOlderAbsoluteRowDeduplicatesTheRewrittenCandidate() {
        var name = "dedup-absolute";
        MemoryStoreFactory.get().store(agentId(name), "The user moved to Porto in 2025.", "fact", 0.7);

        var result = on(D, () -> capture(name, "The user moved to Porto last year.", null));

        assertEquals(0, result.captured());
        assertEquals("The user moved to Porto in 2025.", only(name).text);
    }
}
