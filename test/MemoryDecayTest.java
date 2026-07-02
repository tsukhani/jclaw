import agents.SystemPromptAssembler;
import memory.MemoryDecay;
import memory.MemoryStore.MemoryEntry;
import models.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * JCLAW-526: retrieval time decay. The factor is a pure function (clock and
 * parameters passed in), so the maths is pinned deterministically; the blend
 * integration goes through the decay-aware {@code rankRecall} overload with an
 * explicit decay function — no config flips (the play1 engine runs test lanes
 * concurrently). {@code touchAccessed} is verified against real rows: it must
 * move the decay anchor without bumping {@code updatedAt} (content-change
 * semantics) — the "recently-accessed memories decay more slowly" AC.
 */
class MemoryDecayTest extends UnitTest {

    private static final Instant NOW = Instant.parse("2026-07-03T00:00:00Z");
    private static final double HALF_LIFE = 30.0;
    private static final double BOOST = 2.0;
    private static final double FLOOR = 0.25;

    private static Instant daysAgo(double days) {
        return NOW.minus(Math.round(days * 86_400), ChronoUnit.SECONDS);
    }

    // ─── the pure factor ─────────────────────────────────────────────────────

    @Test
    void freshMemoryHasNoDecay() {
        assertEquals(1.0, MemoryDecay.factor(0.5, NOW, NOW, HALF_LIFE, BOOST, FLOOR), 1e-9);
        assertEquals(1.0, MemoryDecay.factor(0.5, daysAgo(-5), NOW, HALF_LIFE, BOOST, FLOOR), 1e-9,
                "a future/clock-skewed anchor must not decay");
        assertEquals(1.0, MemoryDecay.factor(0.5, null, NOW, HALF_LIFE, BOOST, FLOOR), 1e-9,
                "no age evidence → no penalty");
    }

    @Test
    void oneEffectiveHalfLifeHalvesTheDecayingShare() {
        // importance 0 → effective half-life = base (30d). At exactly one
        // half-life the decaying share above the floor is halved.
        double expected = FLOOR + (1 - FLOOR) * 0.5;
        assertEquals(expected, MemoryDecay.factor(0.0, daysAgo(30), NOW, HALF_LIFE, BOOST, FLOOR), 1e-9);
    }

    @Test
    void highImportanceDecaysMoreSlowly() {
        double low = MemoryDecay.factor(0.2, daysAgo(60), NOW, HALF_LIFE, BOOST, FLOOR);
        double high = MemoryDecay.factor(0.9, daysAgo(60), NOW, HALF_LIFE, BOOST, FLOOR);
        assertTrue(high > low, "importance stretches the half-life (AC: high-importance decays slower)");
    }

    @Test
    void decayOnlyLowersAndNeverBelowTheFloor() {
        double ancient = MemoryDecay.factor(0.0, daysAgo(10_000), NOW, HALF_LIFE, BOOST, FLOOR);
        assertTrue(ancient >= FLOOR, "the floor keeps every memory retrievable");
        assertTrue(ancient <= 1.0, "decay is only ever a reduction");
        assertEquals(FLOOR, ancient, 1e-6, "very old converges to the floor, not zero");
    }

    @Test
    void nonPositiveHalfLifeDisablesDecay() {
        assertEquals(1.0, MemoryDecay.factor(0.5, daysAgo(365), NOW, 0, BOOST, FLOOR), 1e-9);
    }

    // ─── blend integration (decay-aware rankRecall overload) ─────────────────

    private static MemoryEntry entry(String id, double relevance, double importance, Instant recencyAt) {
        return new MemoryEntry(id, "1", "text-" + id, "fact", importance,
                daysAgo(365), relevance, recencyAt);
    }

    @Test
    void staleMemoryLosesToFreshOneAtEqualRelevanceAndImportance() {
        var fresh = entry("fresh", 0.8, 0.5, NOW);
        var stale = entry("stale", 0.8, 0.5, daysAgo(120));

        var ranked = SystemPromptAssembler.rankRecall(List.of(stale, fresh), Set.of(),
                0.7, 0.3, 10, e -> MemoryDecay.factor(e.importance(), e.recencyAt(), NOW,
                        HALF_LIFE, BOOST, FLOOR));

        assertEquals(2, ranked.size(), "decay must never drop a memory, only re-rank it");
        assertEquals("fresh", ranked.getFirst().id());
        assertEquals("stale", ranked.get(1).id());
    }

    @Test
    void decayFreeOverloadIsUnchangedRanking() {
        var fresh = entry("fresh", 0.5, 0.5, NOW);
        var stale = entry("stale", 0.9, 0.5, daysAgo(365));

        // Without decay, the stale-but-more-relevant memory must still win —
        // the identity overload keeps the pre-526 contract.
        var ranked = SystemPromptAssembler.rankRecall(List.of(fresh, stale), Set.of(), 0.7, 0.3, 10);
        assertEquals("stale", ranked.getFirst().id());
    }

    // ─── touchAccessed: the recency anchor refresh ───────────────────────────

    @BeforeEach
    void cleanDb() {
        Fixtures.deleteDatabase();
    }

    @Test
    void touchAccessedMovesTheAnchorWithoutBumpingUpdatedAt() {
        var a = new models.Agent();
        a.name = "decay-touch";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        var m = new Memory();
        m.agent = a;
        m.text = "The user prefers dark mode";
        m.category = "preference";
        m.save();
        m.refresh();   // normalize timestamps to DB precision before comparing
        var updatedBefore = m.updatedAt;
        assertNull(m.lastAccessedAt);
        assertEquals(updatedBefore, m.recencyAnchor(), "anchor starts at updatedAt");

        Memory.touchAccessed(List.of(m.id));
        m.refresh();

        assertNotNull(m.lastAccessedAt, "recall access must stamp lastAccessedAt");
        assertEquals(updatedBefore, m.updatedAt,
                "a recall touch is not a content change — updatedAt must not move");
        assertEquals(m.lastAccessedAt, m.recencyAnchor(),
                "the anchor follows the newest of access/update");
    }

    @Test
    void touchAccessedWithNoIdsIsANoOp() {
        Memory.touchAccessed(List.of());
        Memory.touchAccessed(null);
        // reaching here without an exception is the assertion
        assertTrue(true);
    }
}
