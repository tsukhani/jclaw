package services.grapheval;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The memory-clustered {@link GateBounds} (JCLAW-1369, option A): the sampled unit is the writing memory, so n is the
 * memories that wrote at least one of the gate's records and k those counted wrong, and the bound is the exact
 * one-sided Clopper-Pearson bound over them. Recall's lower bound is a seeded cluster bootstrap over memories with gold.
 * Pure.
 */
public final class MemoryBounds implements GateBounds {

    public static final MemoryBounds INSTANCE = new MemoryBounds();
    public static final long BOOTSTRAP_SEED = 1369L;
    public static final int RESAMPLES = 10_000;
    /** Past this many memories {@link #recordsNeeded} gives up: no gate here is that large. */
    private static final int MAX_MEMORIES = 1_000_000;
    /** Absorbs the rounding in a sum of 1 / inclusion, so five weights of 1 / 0.2 make 5, not 6. */
    private static final double WEIGHT_SLACK = 1e-9;
    private static final Comparator<MemoryCounts> BY_MEMORY = Comparator.comparing(MemoryCounts::memoryId)
            .thenComparingInt(MemoryCounts::gold).thenComparingInt(MemoryCounts::right);

    private MemoryBounds() {}

    /** The writing memories: those with at least one written record. */
    public static int n(List<MemoryCounts> writing) {
        return (int) writing.stream().filter(m -> m.written() > 0).count();
    }

    /**
     * Memories with an unmatched record judged wrong, plus the rounded-up sum of {@code agreedWrongWeight} over the
     * writing memories not already counted, capped at {@link #n}.
     */
    public static int k(List<MemoryCounts> writing) {
        int wrong = 0;
        double weight = 0;
        for (var m : writing) {
            if (m.written() <= 0) continue;
            if (m.wrong() > 0) wrong++;
            else weight += m.agreedWrongWeight();
        }
        return Math.min(n(writing), wrong + (int) Math.ceil(Math.max(0, weight - WEIGHT_SLACK)));
    }

    @Override
    public boolean passes(List<MemoryCounts> writing, double limit, double tail) {
        return Certifier.boundPasses(k(writing), n(writing), limit, tail);
    }

    @Override
    public double upper(List<MemoryCounts> writing, double tail) {
        return Certifier.upperBound(k(writing), n(writing), tail);
    }

    /**
     * The cluster bootstrap: memories with gold, sorted by id, resampled {@link #RESAMPLES} times with replacement from
     * {@link #BOOTSTRAP_SEED}; the ascending resample ratio at index floor(tail × resamples), never above the point
     * estimate. 0 when nothing is labelled.
     */
    @Override
    public double recallLower(List<MemoryCounts> labelled, double tail) {
        var memories = labelled.stream().filter(m -> m.gold() > 0).sorted(BY_MEMORY).toArray(MemoryCounts[]::new);
        if (memories.length == 0) return 0.0;
        long right = 0;
        long gold = 0;
        for (var m : memories) {
            right += m.right();
            gold += m.gold();
        }
        double point = (double) right / gold;
        var random = new SplittableRandom(BOOTSTRAP_SEED);
        var ratios = new double[RESAMPLES];
        for (int r = 0; r < RESAMPLES; r++) {
            long sumRight = 0;
            long sumGold = 0;
            for (int i = 0; i < memories.length; i++) {
                var m = memories[random.nextInt(memories.length)];
                sumRight += m.right();
                sumGold += m.gold();
            }
            ratios[r] = (double) sumRight / sumGold;
        }
        Arrays.sort(ratios);
        int at = Math.min(RESAMPLES - 1, (int) Math.floor(tail * RESAMPLES));
        return Math.min(ratios[at], point);
    }

    /** P(at most the largest passing k wrong of n), with n this gate's writing memories, each wrong at the share. */
    @Override
    public double power(List<MemoryCounts> writing, double trueWrongShare, double limit, double tail) {
        int n = n(writing);
        int kmax = -1;
        while (kmax + 1 < n && Certifier.boundPasses(kmax + 1, n, limit, tail)) kmax++;
        return kmax < 0 ? 0.0 : Math.exp(Certifier.logCdf(kmax, n, trueWrongShare));
    }

    /** The fewest writing memories that pass with {@code wrong} of them wrong. */
    @Override
    public int recordsNeeded(int wrong, double limit, double tail) {
        for (int n = wrong + 1; n <= MAX_MEMORIES; n++) {
            if (Certifier.boundPasses(wrong, n, limit, tail)) return n;
        }
        throw new IllegalArgumentException("no memory count up to " + MAX_MEMORIES + " passes with " + wrong + " wrong");
    }
}
