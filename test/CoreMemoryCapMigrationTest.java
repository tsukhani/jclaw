import com.google.gson.JsonParseException;
import memory.CoreMemoryCapMigration;
import memory.MemoryCategory;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-981: the operator-triggered pass that brings an over-cap corpus back in line.
 *
 * <p>Operator-triggered because the tool-side rule can refuse a core write past the cap but
 * cannot make the agent ask before filing something elsewhere — that half is instruction to
 * a model, so the enforceable path is a button rather than a prompt.
 *
 * <p>Classification is stubbed through {@code setClassifierForTest}: what matters here is
 * which memories are selected, that the survivors are the ones the prompt was already
 * loading, and that an unclassified memory is left alone rather than guessed at.
 */
class CoreMemoryCapMigrationTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        MemoryStoreFactory.reset();
        agent = new Agent();
        agent.name = "core-cap-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @AfterEach
    void release() {
        CoreMemoryCapMigration.setClassifierForTest(null);
        LuceneTestSync.release();
    }

    /** A second agent, so the per-agent cap can be told apart from a global sum. */
    private Agent otherAgent() {
        var a = new Agent();
        a.name = "core-cap-other-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private void storeCoreFor(Agent a, String text, double importance) {
        MemoryStoreFactory.get().store(String.valueOf(a.id), text,
                MemoryCategory.CORE.label, importance);
    }

    private String agentId() {
        return String.valueOf(agent.id);
    }

    private void storeCore(String text, double importance) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text,
                MemoryCategory.CORE.label, importance);
    }

    private long liveCore() {
        return Memory.countLiveCore(String.valueOf(agent.id));
    }

    private static int cap() {
        return ConfigService.getInt("memory.coreload.maxCount", 20);
    }

    /** Answers with the same category for everything, which is enough to pin selection. */
    private static void classifyAllAs(String category) {
        CoreMemoryCapMigration.setClassifierForTest((a, texts) -> {
            var out = new ArrayList<String>();
            for (int i = 0; i < texts.size(); i++) out.add(category);
            return out;
        });
    }

    @Test
    void theModelsAnswersAreReadAfterLeakedReasoningOrInsideAFence() {
        assertEquals(List.of("preference", "fact"), CoreMemoryCapMigration.parseAnswers(
                "Memory 0 is a {like}.</think>[\"preference\", \"fact\"]", 2));
        assertEquals(List.of("decision"), CoreMemoryCapMigration.parseAnswers("```json\n[\"decision\"]\n```", 1));
        assertEquals(List.of("fact"), CoreMemoryCapMigration.parseAnswers(
                "[\"fact\"] — memory {0} only", 1), "a trailing object must not displace the answers");
        assertEquals(List.of(), CoreMemoryCapMigration.parseAnswers(null, 3));
        assertThrows(JsonParseException.class, () -> CoreMemoryCapMigration.parseAnswers("I cannot classify these.", 1));
    }

    @Test
    void anAnswerPerMemoryOrNoAnswersAtAll() {
        // Answers land on memories by position, so a short array would recategorise the wrong rows.
        assertThrows(JsonParseException.class, () -> CoreMemoryCapMigration.parseAnswers("[\"fact\"]", 3));
        assertThrows(JsonParseException.class,
                () -> CoreMemoryCapMigration.parseAnswers("[\"fact\",\"lesson\",\"decision\"]", 2));
    }

    @Test
    void statusReportsTheCorpusAgainstTheCap() {
        storeCore("The only core fact", 0.9);
        var s = CoreMemoryCapMigration.status(agentId());
        assertEquals(1L, s.liveCore());
        assertEquals(cap(), s.cap());
        assertFalse(s.overCap(), "one memory is not over a cap of %d".formatted(cap()));
        assertFalse(s.running());
    }

    @Test
    void refusesToStartWhenNothingIsOverTheCap() {
        storeCore("The only core fact", 0.9);
        var refusal = CoreMemoryCapMigration.start(agentId());
        assertNotNull(refusal, "starting a pointless migration must be refused, not silently run");
        assertTrue(refusal.contains("nothing to migrate"), refusal);
    }

    @Test
    void keepsTheCapAndRecategorisesTheRest() {
        for (int i = 0; i < cap() + 5; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        assertEquals(cap() + 5L, liveCore(), "seed must actually exceed the cap");
        classifyAllAs(MemoryCategory.ENTITY.label);

        CoreMemoryCapMigration.runForTest(agentId());

        assertEquals(cap(), liveCore(), "exactly the cap survives as core");
        assertEquals(5, MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                        .filter(m -> MemoryCategory.ENTITY.label.equals(m.category())).count(),
                "the overflow is recategorised, not deleted");
    }

    @Test
    void keepsTheHighestRankedMemoriesAsCore() {
        // findCore orders by importance then recency, and the migration must keep that
        // order — any other choice silently swaps which memories load every turn.
        for (int i = 0; i < cap(); i++) storeCore("Important core fact %02d".formatted(i), 0.95);
        storeCore("MARKER_LOW an afterthought", 0.30);
        classifyAllAs(MemoryCategory.FACT.label);

        CoreMemoryCapMigration.runForTest(agentId());

        var demoted = MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                .filter(m -> m.text().contains("MARKER_LOW")).findFirst().orElseThrow();
        assertEquals(MemoryCategory.FACT.label, demoted.category(),
                "the lowest-importance memory is the one that loses core");
    }

    @Test
    void anUnclassifiedMemoryStaysCoreRatherThanBeingGuessedAt() {
        // Fail-safe, not fail-open: a wrong bucket is a silent permanent mislabel of
        // something the operator marked important, where staying over the cap is visible
        // and the button can simply be pressed again.
        for (int i = 0; i < cap() + 1; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        CoreMemoryCapMigration.setClassifierForTest((a, texts) -> {
            var out = new ArrayList<String>();
            for (int i = 0; i < texts.size(); i++) out.add(null);
            return out;
        });

        CoreMemoryCapMigration.runForTest(agentId());

        assertEquals(cap() + 1L, liveCore(), "nothing may be recategorised on a failed classification");
    }

    @Test
    void aModelAnsweringCoreCannotPutAMemoryBack() {
        // The classifier is a model, and models return values outside the set they are
        // given (JCLAW-927). "core" is the one answer that would defeat the migration.
        for (int i = 0; i < cap() + 1; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        classifyAllAs(MemoryCategory.CORE.label);

        CoreMemoryCapMigration.runForTest(agentId());

        assertEquals(cap(), liveCore(), "an answer of 'core' must be coerced away, not honoured");
    }

    @Test
    void leavesACorpusUnderTheCapAlone() {
        storeCore("The only core fact", 0.9);
        classifyAllAs(MemoryCategory.FACT.label);
        CoreMemoryCapMigration.runForTest(agentId());
        assertEquals(1, liveCore());
    }

    @Test
    void isSafeToRunAgainOnceTheCorpusIsInLine() {
        for (int i = 0; i < cap() + 3; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        classifyAllAs(MemoryCategory.FACT.label);

        CoreMemoryCapMigration.runForTest(agentId());
        CoreMemoryCapMigration.runForTest(agentId());

        assertEquals(cap(), liveCore(), "a second pass has nothing left to do");
        assertNull(CoreMemoryCapMigration.status(agentId()).error());
    }

    @Test
    void listsTheOverflowInPromptOrderSoSurvivorsAreThoseAlreadyLoaded() {
        // Guards the selection itself rather than its side effect: the migration must ask
        // the model about the overflow, never about a memory that stays core.
        for (int i = 0; i < cap(); i++) storeCore("KEEP core fact %02d".formatted(i), 0.95);
        storeCore("MOVE lower ranked", 0.20);
        var seen = new ArrayList<String>();
        CoreMemoryCapMigration.setClassifierForTest((a, texts) -> {
            seen.addAll(texts);
            return List.of(MemoryCategory.FACT.label);
        });

        CoreMemoryCapMigration.runForTest(agentId());

        assertEquals(1, seen.size(), "only the overflow is classified");
        assertTrue(seen.getFirst().contains("MOVE"), "and it is the lowest-ranked memory: " + seen);
    }

    // --- the cap is per agent, not a total across the instance ---

    @Test
    void twoAgentsEachWithinTheCapAreNotOverIt() {
        // Reported from the running instance: 20 core on one agent plus 1 on another read
        // as "21 stored, 20 allowed" and lit the migrate button, but migrate() skips any
        // agent at or under the cap, so every pass moved nothing and the panel stayed over
        // the limit. status() was summing across agents while migrate() enforced per agent.
        for (int i = 0; i < cap(); i++) storeCore("main core fact " + i, 0.9);
        storeCoreFor(otherAgent(), "the other agent's single core fact", 0.9);

        var s = CoreMemoryCapMigration.status(agentId());
        assertEquals(cap(), s.liveCore(),
                "liveCore must describe the agent the cap governs, not the instance total");
        assertFalse(s.overCap(),
                "no agent exceeds the cap, so nothing is over it");
    }

    @Test
    void aCorpusNoMigrationCanFixIsNotOfferedOne() {
        // The user-visible half of the same bug: the button was enabled for a state the
        // pass could never change.
        for (int i = 0; i < cap(); i++) storeCore("main core fact " + i, 0.9);
        storeCoreFor(otherAgent(), "the other agent's single core fact", 0.9);

        var refusal = CoreMemoryCapMigration.start(agentId());
        assertNotNull(refusal, "a migration that cannot move anything must be refused");
        assertTrue(refusal.contains("nothing to migrate"), refusal);
    }

    // --- scoping: one agent's card must not answer for another ---

    @Test
    void statusAnswersForTheAgentAskedAboutOnly() {
        // The panel moved onto the agent editor, so every reading is addressed to one
        // agent. A status that leaked another agent's count would put the migrate button
        // on the wrong card — the same class of mistake as the instance-wide total.
        for (int i = 0; i < cap() + 3; i++) storeCore("busy core fact " + i, 0.9);
        var quiet = otherAgent();
        storeCoreFor(quiet, "the quiet agent's only core fact", 0.9);

        var busy = CoreMemoryCapMigration.status(agentId());
        assertEquals(cap() + 3L, busy.liveCore());
        assertTrue(busy.overCap(), "the agent over the cap must say so");

        var calm = CoreMemoryCapMigration.status(String.valueOf(quiet.id));
        assertEquals(1L, calm.liveCore(), "an agent's card must report only its own memories");
        assertFalse(calm.overCap());
    }

    @Test
    void migratingOneAgentLeavesAnotherAgentsCoreMemoriesAlone() {
        for (int i = 0; i < cap() + 2; i++) storeCore("busy core fact " + i, 0.9);
        var quiet = otherAgent();
        for (int i = 0; i < 3; i++) storeCoreFor(quiet, "quiet core fact " + i, 0.9);
        classifyAllAs(MemoryCategory.FACT.label);

        CoreMemoryCapMigration.runForTest(agentId());

        assertEquals(cap(), liveCore(), "the migrated agent comes down to the cap");
        assertEquals(3L, Memory.countLiveCore(String.valueOf(quiet.id)),
                "an untouched agent keeps every core memory it had");
    }

    @Test
    void anAgentWithinTheCapIsRefusedEvenWhileAnotherIsOverIt() {
        for (int i = 0; i < cap() + 5; i++) storeCore("busy core fact " + i, 0.9);
        var quiet = otherAgent();
        storeCoreFor(quiet, "the quiet agent's only core fact", 0.9);

        var refusal = CoreMemoryCapMigration.start(String.valueOf(quiet.id));
        assertNotNull(refusal, "a migration this agent cannot benefit from must be refused");
        assertTrue(refusal.contains("not over the core-memory cap"), refusal);
    }
}
