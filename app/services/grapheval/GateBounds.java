package services.grapheval;

import java.util.List;

/** Every bound the certifier reads. The placeholder pools records; a memory-clustered one replaces it later. */
public interface GateBounds {
    /**
     * One memory's counts for one gate at one threshold. written leaves noise out, as today; wrong counts its unmatched
     * written records; agreedWrongWeight sums 1 / inclusion probability over its sampled agreed records judged wrong.
     */
    record MemoryCounts(String memoryId, int written, int wrong, double agreedWrongWeight, int gold, int right) {}

    boolean passes(List<MemoryCounts> writing, double limit, double tail);
    double upper(List<MemoryCounts> writing, double tail);
    double recallLower(List<MemoryCounts> labelled, double tail);
    double power(List<MemoryCounts> writing, double trueWrongShare, double limit, double tail);
    int recordsNeeded(int wrong, double limit, double tail);
}
