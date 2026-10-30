import llm.LlmTypes.ChatMessage;
import memory.MemoryAutoCapture;
import memory.MemoryProvenance;
import memory.MemorySimilarity;
import memory.MemoryStoreFactory;
import models.Memory;
import models.MemoryAuthorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;
import services.Tx;
import utils.AppClock;
import utils.CircuitBreaker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
    void aTrailingFootnoteDoesNotDisplaceTheSupersessionsObject() {
        assertEquals(Map.of(0, List.of(1)), MemoryAutoCapture.parseSupersessions(
                "{\"supersessions\":[{\"new\":0,\"old\":[1]}]}\nNEW [0] retires EXISTING [1].", 1, 2));
    }

    @Test
    void parsesSupersessionsAfterLeakedReasoning() {
        assertEquals(Map.of(0, List.of(1)), MemoryAutoCapture.parseSupersessions(
                "NEW 0 retires EXISTING {1}.</think>{\"supersessions\":[{\"new\":0,\"old\":[1]}]}", 1, 2));
    }

    /**
     * JCLAW-942: the judge prompt now asks for the question both texts answer, so live
     * output carries a field the shipped parser never saw. Pinned because the parser is
     * all-or-nothing — a rejected object is a silently skipped supersession, not an error.
     */
    @Test
    void aSupersessionCarryingTheJudgesReasonStillParses() {
        var withReason = "{\"supersessions\":[{\"new\":0,\"old\":[1],"
                + "\"question\":\"where does the user live\"}]}";

        var parsed = MemoryAutoCapture.parseSupersessions(withReason, 1, 2);

        assertEquals(1, parsed.size(), "the extra field must not reject the object");
        assertEquals(List.of(1), parsed.get(0));
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

    // ─── JCLAW-1050: a supersession must not shrink the fact ─────────────────

    /** Extractor output for one candidate, with the JSON quoting the harness needs. */
    private static String extractorJson(String text) {
        return "{\"memories\":[{\"text\":\"" + text + "\",\"category\":\"fact\",\"importance\":0.7}]}";
    }

    @Test
    void aNarrowerRestatementDoesNotSupersedeTheFullerMemory() {
        // Found in UAT, not by inspection: capture re-extracted a thinner version of a fact
        // already stored, the judge paired them as the same subject, and the serial guard let
        // the thinner row win — taking the clinic address with it. The agent then said, of its
        // own accord, "I don't have a location stored for her clinic".
        var full = "The user's osteopath is called Ines and her clinic is on Rua do Almada";
        var thin = "The user's osteopath is named Ines";
        var agent = agentId("narrower");
        var fullId = Long.valueOf(MemoryStoreFactory.get().store(agent, full, "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> extractorJson(thin);
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        MemoryAutoCapture.capture(agent, "narrower",
                "Reminder that my osteopath is Ines.", "Noted.",
                extractor, consolidator, freshBreaker());

        Memory kept = Memory.findById(fullId);
        assertNull(kept.supersededAt,
                "the fuller memory was superseded by a narrower restatement, losing its content");
        var active = Memory.findByAgent(agent);
        assertEquals(2, active.size(), "both rows must survive: " + active);
        assertTrue(active.stream().anyMatch(m -> m.text.contains("Rua do Almada")),
                "the clinic address is unreachable: " + active);
    }

    @Test
    void aRicherCorrectionStillSupersedes() {
        // The guard must not neuter consolidation. A replacement that carries at least as
        // much content is exactly what supersession is for, and still wins on recency.
        var older = "The user drives an Xpeng G6";
        var richer = "The user drives a Volvo EX30 since March";
        var agent = agentId("richer");
        var olderId = Long.valueOf(MemoryStoreFactory.get().store(agent, older, "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> extractorJson(richer);
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        MemoryAutoCapture.capture(agent, "richer",
                "I swapped the Xpeng for a Volvo EX30 in March.", "Noted.",
                extractor, consolidator, freshBreaker());

        Memory replaced = Memory.findById(olderId);
        assertNotNull(replaced.supersededAt, "a richer correction must still supersede");
        var active = Memory.findByAgent(agent);
        assertEquals(1, active.size(), "only the newer fact should remain active: " + active);
        assertEquals(richer, active.getFirst().text);
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

    // ─── JCLAW-942: a supersession must be about the same thing ──────────────

    /**
     * Both texts verbatim from a 302-turn LongMemEval ingest, where the judge paired them
     * and a correct memory was retired by an unrelated one. They share only "7"/"pm", and
     * {@link MemoryAutoCapture} previously checked content <em>volume</em> only, so the
     * replacement cleared the bar on length while concerning a different subject entirely.
     */
    @Test
    void anUnrelatedMemoryCannotSupersedeOneThatMerelySharesATime() {
        var agent = agentId("no-shared-subject");
        var store = MemoryStoreFactory.get();
        var gymId = Long.valueOf(store.store(agent,
                "The user has gym sessions at 7:00 pm on Mondays, Wednesdays, and Fridays",
                "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> extractorJson(
                "The user stops work emails and messages by 7 pm to separate work and personal life");
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        MemoryAutoCapture.capture(agent, "no-shared-subject",
                "I stop checking work email by 7pm so I can switch off.",
                "That's a healthy boundary.", extractor, consolidator, freshBreaker());

        Memory gym = Memory.findById(gymId);
        assertNull(gym.supersededAt,
                "a memory about gym times was retired by one about work email - the two share "
                        + "only a clock time, which is not a shared subject");
        assertEquals(2, Memory.findByAgent(agent).size(),
                "both facts must remain recallable");
    }

    /**
     * JCLAW-942: the pair clears both earlier guards — it is long enough for
     * {@code atLeastAsInformative} and plainly about the same routine for
     * {@code sharesSubject} — and still destroys the only thing that made the old row
     * answer "how long have I kept it up". Measured over a 302-turn LongMemEval ingest:
     * of 104 supersessions, 16 retired a memory holding a value and 15 dropped it.
     */
    @Test
    void aReplacementThatPinsNoDurationCannotSupersedeOneThatDid() {
        var agent = agentId("value-destroyed");
        var store = MemoryStoreFactory.get();
        var routineId = Long.valueOf(store.store(agent,
                "The user has maintained a daily tidying routine for 3 weeks and feels proud "
                        + "of this accomplishment.", "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> extractorJson(
                "The user's daily tidying routine has made a significant positive difference "
                        + "in their apartment.");
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        MemoryAutoCapture.capture(agent, "value-destroyed",
                "My daily tidying routine has really changed how the apartment feels.",
                "That's great to hear.", extractor, consolidator, freshBreaker());

        assertNull(Memory.<Memory>findById(routineId).supersededAt,
                "the replacement pins no duration, so '3 weeks' would be lost with nothing "
                        + "to recover it from");
        assertEquals(2, Memory.findByAgent(agent).size(), "both must remain recallable");
    }

    /**
     * The complement, and the reason the guard compares kinds rather than values: an update
     * refills the slot it empties, so a changed clock time must still supersede.
     */
    @Test
    void aCorrectionThatRefillsTheValueStillSupersedes() {
        var agent = agentId("value-refilled");
        var store = MemoryStoreFactory.get();
        var flightId = Long.valueOf(store.store(agent,
                "The user's flight to Lisbon is at 08:00", "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> extractorJson(
                "The user's flight to Lisbon is at 11:30 from Gate 4");
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        MemoryAutoCapture.capture(agent, "value-refilled",
                "My Lisbon flight got moved to 11:30, leaving from Gate 4.", "Noted.",
                extractor, consolidator, freshBreaker());

        assertNotNull(Memory.<Memory>findById(flightId).supersededAt,
                "a corrected time is still a clock time — the value guard must not block an update");
    }

    @Test
    void aCorrectionStillSupersedesWhenTheSubjectSurvives() {
        // The guard must not block updates, which are the reason supersession exists: a
        // correction keeps its subject and changes its value, so one shared noun is enough.
        var agent = agentId("subject-survives");
        var store = MemoryStoreFactory.get();
        var berlinId = Long.valueOf(store.store(agent, BERLIN, "fact", 0.7));

        MemoryAutoCapture.Extractor extractor = msgs -> PORTO_JSON;
        MemoryAutoCapture.Consolidator consolidator = msgs -> SUPERSEDE_JSON;
        MemoryAutoCapture.capture(agent, "subject-survives",
                "I moved from Berlin to Porto last month", "Noted.", extractor, consolidator,
                freshBreaker());

        assertNotNull(Memory.<Memory>findById(berlinId).supersededAt,
                "the shared-subject guard must not block a genuine correction");
    }

    // ─── GH-15: the judge links an addendum to the routine it extends ────────

    private static final String ROUTINE =
            "The user's morning routine is to make coffee, read the news and review the calendar";
    private static final String WEATHER = "The user wants checking the weather added to their morning routine";
    private static final String WEATHER_TURN = "Please add checking the weather to my morning routine.";

    @Test
    void parsesExtensionsKeepingTheFirstForEachNewMemory() {
        assertEquals(Map.of(0, 2, 1, 0), MemoryAutoCapture.parseExtensions(
                "{\"supersessions\":[],\"extensions\":[{\"new\":0,\"extends\":2},{\"new\":1,\"extends\":0},"
                        + "{\"new\":0,\"extends\":1}]}", 2, 3));
    }

    @Test
    void extensionIndicesAtTheEdgesOfTheirRanges() {
        assertEquals(Map.of(1, 2), MemoryAutoCapture.parseExtensions(
                "{\"extensions\":[{\"new\":1,\"extends\":2}]}", 2, 3));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions(
                "{\"extensions\":[{\"new\":2,\"extends\":0}]}", 2, 3));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions(
                "{\"extensions\":[{\"new\":0,\"extends\":3}]}", 2, 3));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions(
                "{\"extensions\":[{\"new\":-1,\"extends\":0},{\"new\":0,\"extends\":-1}]}", 2, 3));
    }

    @Test
    void malformedExtensionsYieldNoLinks() {
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions(
                "{\"supersessions\":[{\"new\":0,\"old\":[0]}]}", 1, 1));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions("{\"extensions\":\"oops\"}", 1, 1));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions(
                "{\"extensions\":[{\"new\":0,\"extends\":\"0\"},{\"new\":0,\"extends\":0.5},"
                        + "{\"new\":0,\"extends\":null},{\"new\":0},[0,0]]}", 1, 1));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions("not json", 1, 1));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions("", 1, 1));
        assertEquals(Map.of(), MemoryAutoCapture.parseExtensions(null, 1, 1));
    }

    /** The EXISTING index the judge was shown {@code text} at, or -1. */
    private static int existingIndex(List<ChatMessage> msgs, String text) {
        var rendered = String.valueOf(msgs.get(1).content());
        for (var line : rendered.substring(rendered.indexOf("EXISTING:")).split("\n")) {
            int colon = line.indexOf(": ");
            if (colon > 0 && line.substring(colon + 2).equals(text)) return Integer.parseInt(line.substring(0, colon));
        }
        return -1;
    }

    private static String extendsJson(int existing) {
        return "{\"supersessions\":[],\"extensions\":[{\"new\":0,\"extends\":" + existing + "}]}";
    }

    private static String snippet(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    private static List<String> linkEvents(List<EventLogger.Captured> events) {
        return events.stream().map(EventLogger.Captured::message)
                .filter(m -> m.contains("linked as an extension") || m.contains("unlinked from")).toList();
    }

    private Memory onlyNewRow(String agent, Long... existing) {
        var fresh = new ArrayList<Memory>(Memory.<Memory>find("agent.id = ?1 ORDER BY id", Long.valueOf(agent)).fetch());
        fresh.removeIf(m -> List.of(existing).contains(m.id));
        assertEquals(1, fresh.size(), "exactly one row captured: " + fresh);
        return fresh.getFirst();
    }

    private List<EventLogger.Captured> captureWeather(String agent, MemoryAutoCapture.Consolidator judge,
                                                      MemoryProvenance provenance) {
        return EventLogger.captureForTest(() -> MemoryAutoCapture.capture(agent, "routine", WEATHER_TURN, "Done.",
                msgs -> extractorJson(WEATHER), judge, freshBreaker(), provenance));
    }

    private List<EventLogger.Captured> captureWeather(String agent, MemoryAutoCapture.Consolidator judge) {
        return captureWeather(agent, judge, MemoryProvenance.extractor("m"));
    }

    @Test
    void anAddendumIsLinkedToTheRoutineItExtends() {
        var agent = agentId("ext-link");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7));

        var events = captureWeather(agent, msgs -> extendsJson(existingIndex(msgs, ROUTINE)));

        var added = onlyNewRow(agent, routineId);
        assertEquals(WEATHER, added.text);
        assertEquals(routineId, added.extendsId);
        assertNull(Memory.<Memory>findById(routineId).supersededAt, "an extension never supersedes");
        assertEquals(List.of("Memory %d linked as an extension of %d: \"%s\" extends \"%s\""
                .formatted(added.id, routineId, snippet(WEATHER), snippet(ROUTINE))), linkEvents(events));
    }

    @Test
    void aLinkToARoutineSupersededMeanwhileLandsOnItsReplacement() {
        var agent = agentId("ext-head");
        var store = MemoryStoreFactory.get();
        var routineId = Long.valueOf(store.store(agent, ROUTINE, "fact", 0.7));
        var replacementId = new AtomicReference<Long>();

        var events = captureWeather(agent, msgs -> {
            int idx = existingIndex(msgs, ROUTINE);
            Tx.run(() -> {
                replacementId.set(Long.valueOf(store.store(agent,
                        "The user's morning routine is to make tea, read the news and review the calendar", "fact", 0.7)));
                Memory.<Memory>findById(routineId).supersede(replacementId.get());
            });
            return extendsJson(idx);
        });

        var added = onlyNewRow(agent, routineId, replacementId.get());
        assertEquals(replacementId.get(), added.extendsId);
        assertEquals(1, linkEvents(events).size(), linkEvents(events).toString());
        assertTrue(linkEvents(events).getFirst().startsWith(
                "Memory %d linked as an extension of %d: ".formatted(added.id, replacementId.get())));
    }

    @Test
    void aRoutineWithNoCurrentVersionIsNotLinked() {
        var agent = agentId("ext-no-head");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7));

        var events = captureWeather(agent, msgs -> {
            int idx = existingIndex(msgs, ROUTINE);
            Tx.run(() -> {
                Memory r = Memory.findById(routineId);
                r.supersededAt = AppClock.now();
                r.save();
            });
            return extendsJson(idx);
        });

        var added = onlyNewRow(agent, routineId);
        assertNull(added.extendsId);
        assertEquals(List.of("Kept memory %d unlinked from %d — that memory has no current version"
                .formatted(added.id, routineId)), linkEvents(events));
    }

    /** A routine replaced {@code hops} times while the judge ran, so its live head is that many hops away. */
    private Long[] supersedeChain(String agent, Long routineId, int hops) {
        var ids = new Long[hops + 1];
        ids[0] = routineId;
        Tx.run(() -> {
            for (int k = 1; k <= hops; k++) {
                ids[k] = Long.valueOf(MemoryStoreFactory.get().store(agent,
                        "The user's morning routine, version " + k, "fact", 0.7));
                Memory.<Memory>findById(ids[k - 1]).supersede(ids[k]);
            }
        });
        return ids;
    }

    @Test
    void theLiveHeadIsFollowedForTwentyHops() {
        var agent = agentId("ext-20-hops");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7));
        var chain = new AtomicReference<Long[]>();

        captureWeather(agent, msgs -> {
            int idx = existingIndex(msgs, ROUTINE);
            chain.set(supersedeChain(agent, routineId, 20));
            return extendsJson(idx);
        });

        assertEquals(chain.get()[20], onlyNewRow(agent, chain.get()).extendsId);
    }

    @Test
    void aHeadTwentyOneHopsAwayIsNotLinked() {
        var agent = agentId("ext-21-hops");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7));
        var chain = new AtomicReference<Long[]>();

        var events = captureWeather(agent, msgs -> {
            int idx = existingIndex(msgs, ROUTINE);
            chain.set(supersedeChain(agent, routineId, 21));
            return extendsJson(idx);
        });

        var added = onlyNewRow(agent, chain.get());
        assertNull(added.extendsId);
        assertEquals(List.of("Kept memory %d unlinked from %d — that memory has no current version"
                .formatted(added.id, routineId)), linkEvents(events));
    }

    @Test
    void aGuestsAddendumIsNotLinkedToTheOperatorsRoutine() {
        var agent = agentId("ext-guest");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7));

        var events = captureWeather(agent, msgs -> extendsJson(existingIndex(msgs, ROUTINE)),
                MemoryProvenance.extractor("m", MemoryAuthorType.GUEST_TURN));

        var added = onlyNewRow(agent, routineId);
        assertNull(added.extendsId);
        assertEquals(List.of(("Kept memory %d unlinked from %d — a guest's turn never extends a memory that is "
                + "not a guest's").formatted(added.id, routineId)), linkEvents(events));
    }

    @Test
    void malformedExtensionsLeaveTheSupersessionsStanding() {
        var agent = agentId("ext-garbled");
        var berlinId = Long.valueOf(MemoryStoreFactory.get().store(agent, BERLIN, "fact", 0.7));

        MemoryAutoCapture.capture(agent, "ext-garbled", "Quick update: I moved from Berlin to Porto last month",
                "Noted.", msgs -> PORTO_JSON,
                msgs -> "{\"supersessions\":[{\"new\":0,\"old\":[0]}],\"extensions\":\"oops\"}", freshBreaker());

        var porto = onlyNewRow(agent, berlinId);
        assertEquals(porto.id, Memory.<Memory>findById(berlinId).supersededById);
        assertNull(porto.extendsId);
    }

    @Test
    void aPairJudgedBothSupersededAndExtendedIsOnlySuperseded() {
        var agent = agentId("ext-both");
        var berlinId = Long.valueOf(MemoryStoreFactory.get().store(agent, BERLIN, "fact", 0.7));

        var events = EventLogger.captureForTest(() -> MemoryAutoCapture.capture(agent, "ext-both",
                "Quick update: I moved from Berlin to Porto last month", "Noted.", msgs -> PORTO_JSON,
                msgs -> "{\"supersessions\":[{\"new\":0,\"old\":[0]}],\"extensions\":[{\"new\":0,\"extends\":0}]}",
                freshBreaker()));

        var porto = onlyNewRow(agent, berlinId);
        assertEquals(porto.id, Memory.<Memory>findById(berlinId).supersededById);
        assertNull(porto.extendsId);
        assertEquals(List.of(), linkEvents(events));
    }

    private static final String LONG_ROUTINE = "The user's morning routine is to make coffee, read the news, review "
            + "the calendar, water the plants, feed the cat and check the stock prices";

    @Test
    void aPairWhoseSupersessionAGuardRefusesIsStillLinked() {
        var agent = agentId("ext-refused");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, LONG_ROUTINE, "fact", 0.7));

        var events = captureWeather(agent, msgs -> {
            int idx = existingIndex(msgs, LONG_ROUTINE);
            return "{\"supersessions\":[{\"new\":0,\"old\":[" + idx + "]}],"
                    + "\"extensions\":[{\"new\":0,\"extends\":" + idx + "}]}";
        });

        var added = onlyNewRow(agent, routineId);
        assertEquals(routineId, added.extendsId);
        assertNull(Memory.<Memory>findById(routineId).supersededAt);
        assertEquals(List.of(
                "Kept memory %d instead of superseding it with %d — the newer text drops content: \"%s\" vs \"%s\""
                        .formatted(routineId, added.id, snippet(WEATHER), snippet(LONG_ROUTINE)),
                "Memory %d linked as an extension of %d: \"%s\" extends \"%s\""
                        .formatted(added.id, routineId, snippet(WEATHER), snippet(LONG_ROUTINE))),
                events.stream().map(EventLogger.Captured::message)
                        .filter(m -> m.startsWith("Kept memory") || m.contains("linked as an extension")).toList());
    }

    @Test
    void anExtensionOfARoutineReplacedInTheSameTurnByALaterSurvivorIsRefused() {
        var agent = agentId("ext-same-turn");
        var routineId = Long.valueOf(MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7));
        var corrected = "The user's morning routine is now to make green tea, skim the headlines and review the calendar";

        var events = EventLogger.captureForTest(() -> MemoryAutoCapture.capture(agent, "ext-same-turn",
                WEATHER_TURN + " Also, I switched to green tea and skim the headlines now.", "Done.",
                msgs -> "{\"memories\":[{\"text\":\"" + WEATHER + "\",\"category\":\"fact\",\"importance\":0.7},"
                        + "{\"text\":\"" + corrected + "\",\"category\":\"fact\",\"importance\":0.7}]}",
                msgs -> {
                    int idx = existingIndex(msgs, ROUTINE);
                    return "{\"supersessions\":[{\"new\":1,\"old\":[" + idx + "]}],"
                            + "\"extensions\":[{\"new\":0,\"extends\":" + idx + "}]}";
                }, freshBreaker()));

        Memory weather = Memory.find("agent.id = ?1 AND text = ?2", Long.valueOf(agent), WEATHER).first();
        Memory replacement = Memory.find("agent.id = ?1 AND text = ?2", Long.valueOf(agent), corrected).first();
        assertEquals(replacement.id, Memory.<Memory>findById(routineId).supersededById);
        assertNull(weather.extendsId);
        assertEquals(List.of("Kept memory %d unlinked from %d — a memory only extends an older one"
                .formatted(weather.id, replacement.id)), linkEvents(events));
    }

    // ─── GH-15: the shortlist admits a row holding much of the candidate ─────

    private static final String SHORTLIST_ROW =
            "kalvar bemtor zorbik trimbol vasgut nerpok yeldam cuspat orvik lantop mibsut regdol ufkat";

    /** The share of {@code candidate}'s content tokens that {@code row} also holds. */
    private static double candidateShare(String candidate, String row) {
        var c = MemorySimilarity.Tokens.of(candidate).content();
        var shared = new HashSet<>(c);
        shared.retainAll(MemorySimilarity.Tokens.of(row).content());
        return (double) shared.size() / c.size();
    }

    /** What the judge saw as EXISTING for one capture of {@code candidate}, or null when it was not called. */
    private String judgedAgainst(String agentName, String candidate) {
        return judgedAgainst(agentName, SHORTLIST_ROW, candidate);
    }

    private String judgedAgainst(String agentName, String stored, String candidate) {
        var agent = agentId(agentName);
        MemoryStoreFactory.get().store(agent, stored, "fact", 0.7);
        var seen = new AtomicReference<String>();
        MemoryAutoCapture.capture(agent, agentName, candidate, "Noted.", msgs -> extractorJson(candidate), msgs -> {
            var rendered = String.valueOf(msgs.get(1).content());
            seen.set(rendered.substring(rendered.indexOf("EXISTING:")));
            return "{\"supersessions\":[],\"extensions\":[]}";
        }, freshBreaker());
        return seen.get();
    }

    @Test
    void aRowCoveringThreeTenthsOfTheCandidateIsShortlisted() {
        var candidate = "kalvar bemtor zorbik plemvat quorbat drensik falmok gristop wendup hoskat";
        assertEquals(0.3, candidateShare(candidate, SHORTLIST_ROW), 1e-9);
        var c = MemorySimilarity.Tokens.of(candidate);
        var row = MemorySimilarity.Tokens.of(SHORTLIST_ROW);
        assertTrue(MemorySimilarity.jaccard(c.raw(), row.raw()) < 0.2, "only containment can admit it");

        assertEquals("EXISTING:\n0: " + SHORTLIST_ROW + "\n", judgedAgainst("shortlist-at", candidate));
    }

    @Test
    void aRowCoveringLessThanThreeTenthsIsNotShortlisted() {
        var candidate = "kalvar bemtor zorbik plemvat quorbat drensik falmok gristop wendup hoskat jopsik";
        assertEquals(3.0 / 11, candidateShare(candidate, SHORTLIST_ROW), 1e-9);

        assertNull(judgedAgainst("shortlist-below", candidate), "an empty shortlist never reaches the judge");
    }

    @Test
    void aShortRowSharingOneWordWithALongerCandidateIsNotShortlisted() {
        var stored = "kalvar bemtor";
        var candidate = "kalvar plemvat quorbat drensik falmok gristop";
        assertEquals(2, MemorySimilarity.Tokens.of(stored).content().size());
        assertEquals(6, MemorySimilarity.Tokens.of(candidate).content().size());
        assertEquals(0.5, MemorySimilarity.containment(MemorySimilarity.Tokens.of(candidate).content(),
                MemorySimilarity.Tokens.of(stored).content()), 1e-9, "the overlap coefficient would admit it");

        assertNull(judgedAgainst("shortlist-short-row", stored, candidate), "an empty shortlist never reaches the judge");
    }

    // ─── GH-15: the shipped prompts ──────────────────────────────────────────

    @Test
    void theShippedPromptsCarryTheRoutineGuidance() {
        var extraction = new AtomicReference<String>();
        var consolidation = new AtomicReference<String>();
        var agent = agentId("prompts");
        MemoryStoreFactory.get().store(agent, ROUTINE, "fact", 0.7);
        MemoryAutoCapture.capture(agent, "prompts", WEATHER_TURN, "Done.", msgs -> {
            extraction.set(String.valueOf(msgs.getFirst().content()));
            return extractorJson(WEATHER);
        }, msgs -> {
            consolidation.set(String.valueOf(msgs.getFirst().content()));
            return extendsJson(0);
        }, freshBreaker());

        assertTrue(extraction.get().contains("""
                Extract the durable part rather than discarding the sentence.

                A routine or procedure the user wants carried out on request — steps they will later ask you to run — is the exception to one fact per memory: keep every step, in order, in a single memory, and say what it is for: "The user's daily morning routine, run when they ask for it: check email, review the calendar, then post the day's plan to Slack". A later turn that adds, removes or changes one step is its own memory that names the routine it belongs to: "The user wants checking the weather added to their daily morning routine, after reviewing the calendar".
                """), extraction.get());
        assertTrue(extraction.get().contains("""
                These are used to find the memory later and are never shown to anyone.

                For a routine or procedure, at least one of the "questions" is the short, casual way the user will start it — "do my morning stuff", "morning routine" — because the request that triggers it arrives as a brief command that shares few words with the steps.
                """), extraction.get());
        assertTrue(consolidation.get().contains("""
                A NEW memory can instead EXTEND an existing one: it adds a step, item or detail to the routine, procedure or list that the existing memory describes, so that the two together are the current whole and both stay true. "The user wants checking the weather added to their morning routine" extends "The user's morning routine is to make coffee, read the news and review the calendar". An extension never supersedes: list it under "extensions", not "supersessions". Give each NEW memory at most one extension, naming the EXISTING memory that describes the routine itself.

                Never judge which side is more recent"""), consolidation.get());
        assertTrue(consolidation.get().endsWith("""
                Output ONLY a JSON object — no prose, no code fences — with exactly this shape, and an empty array for either key when nothing qualifies:
                {"supersessions":[{"new":0,"old":[2],"question":"where does the user live"}],"extensions":[{"new":1,"extends":3}]}
                """), consolidation.get());
    }
}
