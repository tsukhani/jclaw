package memory;

import memory.MemoryStore.MemoryEntry;
import services.ConfigService;

import java.time.Duration;
import java.time.Instant;

/**
 * JCLAW-526: time decay for memory retrieval. Produces a multiplier in
 * {@code [floor, 1]} applied to the recall blend so older, unreferenced
 * memories fade in ranking — never disappear: the floor keeps every memory
 * retrievable when nothing fresher competes, and nothing here deletes rows
 * (supersession, JCLAW-525, is the invalidation mechanism; decay is only a
 * ranking force).
 *
 * <p>Exponential half-life on the entry's recency anchor (last content change
 * or last recall access, whichever is newer — so recently-accessed memories
 * decay more slowly), with the effective half-life stretched by importance:
 * {@code halfLife × (1 + boost × importance)}. At the defaults (30-day
 * half-life, boost 2.0) an importance-0.9 memory halves in ~84 days while an
 * importance-0.2 one halves in ~42.
 *
 * <p>Operator knobs (DB-backed ConfigService, code defaults):
 * {@code memory.decay.enabled} (true), {@code memory.decay.halfLifeDays} (30),
 * {@code memory.decay.importanceBoost} (2.0), {@code memory.decay.floor}
 * (0.25).
 */
public final class MemoryDecay {

    private MemoryDecay() {}

    /**
     * Pure decay factor. {@code recencyAt == null} (or a future timestamp)
     * means "no age evidence" and decays nothing.
     */
    public static double factor(double importance, Instant recencyAt, Instant now,
                                double halfLifeDays, double importanceBoost, double floor) {
        if (recencyAt == null || halfLifeDays <= 0) return 1.0;
        double ageDays = Duration.between(recencyAt, now).toMillis() / 86_400_000.0;
        if (ageDays <= 0) return 1.0;
        double clampedFloor = Math.clamp(floor, 0.0, 1.0);
        double effectiveHalfLife = halfLifeDays * (1 + Math.max(0, importanceBoost) * Math.clamp(importance, 0.0, 1.0));
        return clampedFloor + (1 - clampedFloor) * Math.pow(0.5, ageDays / effectiveHalfLife);
    }

    /**
     * Config-driven factor for one recall candidate, as applied by the
     * {@code SystemPromptAssembler} blend. Reads the operator knobs once per
     * call; disabled ⇒ 1.0 (identity — ranking reverts to relevance +
     * importance only).
     */
    public static double factorFor(MemoryEntry entry, Instant now) {
        if (!ConfigService.getBoolean("memory.decay.enabled", true)) return 1.0;
        return factor(entry.importance(), entry.recencyAt(), now,
                ConfigService.getDouble("memory.decay.halfLifeDays", 30.0),
                ConfigService.getDouble("memory.decay.importanceBoost", 2.0),
                ConfigService.getDouble("memory.decay.floor", 0.25));
    }
}
