package services.grapheval;

import java.util.List;

/**
 * The record-level {@link GateBounds} (JCLAW-1368): every memory's records pooled into one binomial count, n the
 * written records and k the wrong ones plus the agreed sample's weighted wrong, rounded up. It serves the rules decided
 * in values rather than memories (JCLAW-1369): the qualifier classes, {@code G_trap} and lineage. Pure.
 */
public final class RecordBounds implements GateBounds {

    public static final RecordBounds INSTANCE = new RecordBounds();
    /** Past this many records {@link #recordsNeeded} gives up: no gate here is that large. */
    private static final int MAX_RECORDS = 1_000_000;
    /** Absorbs the rounding in a sum of 1 / inclusion, so five weights of 1 / 0.2 make 5, not 6. */
    private static final double WEIGHT_SLACK = 1e-9;

    private RecordBounds() {}

    public static int n(List<MemoryCounts> writing) {
        return writing.stream().mapToInt(MemoryCounts::written).sum();
    }

    public static int k(List<MemoryCounts> writing) {
        int wrong = writing.stream().mapToInt(MemoryCounts::wrong).sum();
        double weight = writing.stream().mapToDouble(MemoryCounts::agreedWrongWeight).sum();
        return wrong + (int) Math.ceil(Math.max(0, weight - WEIGHT_SLACK));
    }

    @Override
    public boolean passes(List<MemoryCounts> writing, double limit, double tail) {
        return Certifier.boundPasses(k(writing), n(writing), limit, tail);
    }

    @Override
    public double upper(List<MemoryCounts> writing, double tail) {
        return Certifier.upperBound(k(writing), n(writing), tail);
    }

    @Override
    public double recallLower(List<MemoryCounts> labelled, double tail) {
        return Certifier.lowerBound(labelled.stream().mapToInt(MemoryCounts::right).sum(),
                labelled.stream().mapToInt(MemoryCounts::gold).sum(), tail);
    }

    /** P(at most the largest passing k wrong of n), with n this gate's records and each wrong at the given share. */
    @Override
    public double power(List<MemoryCounts> writing, double trueWrongShare, double limit, double tail) {
        int n = n(writing);
        int kmax = -1;
        while (kmax + 1 < n && Certifier.boundPasses(kmax + 1, n, limit, tail)) kmax++;
        return kmax < 0 ? 0.0 : Math.exp(Certifier.logCdf(kmax, n, trueWrongShare));
    }

    @Override
    public int recordsNeeded(int wrong, double limit, double tail) {
        for (int n = wrong + 1; n <= MAX_RECORDS; n++) {
            if (Certifier.boundPasses(wrong, n, limit, tail)) return n;
        }
        throw new IllegalArgumentException("no record count up to " + MAX_RECORDS + " passes with " + wrong + " wrong");
    }
}
