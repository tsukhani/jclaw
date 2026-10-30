package services.grapheval;

/**
 * One-sided Clopper-Pearson bounds on a binomial count, decided on the exact CDF so a boundary is never a rounding
 * question. The leaf under {@link Certifier} and every {@link GateBounds}. Pure.
 */
public final class ClopperPearson {

    public static final double LIMIT = 0.05;
    public static final double CONFIDENCE_TAIL = 0.05;

    private ClopperPearson() {}

    /**
     * The smallest p at which seeing at most {@code wrong} wrong of {@code written} has probability 5%: the one-sided
     * 95% Clopper-Pearson upper bound. 1.0 when nothing was written.
     */
    public static double upperBound(int wrong, int written) {
        return upperBound(wrong, written, CONFIDENCE_TAIL);
    }

    /** The one-sided Clopper-Pearson upper bound at {@code tail}: the p at which P(X <= wrong) is {@code tail}. */
    public static double upperBound(int wrong, int written, double tail) {
        if (written <= 0 || wrong >= written) return 1.0;
        double lo = (double) wrong / written;
        double hi = 1.0;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (logCdf(wrong, written, mid) > Math.log(tail)) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /**
     * The one-sided Clopper-Pearson lower bound at {@code tail} on {@code right} of {@code total}: the p at which
     * P(X >= right) is {@code tail}. 0 when nothing is right or nothing is labeled.
     */
    public static double lowerBound(int right, int total, double tail) {
        if (total <= 0 || right <= 0) return 0.0;
        if (right >= total) return Math.pow(tail, 1.0 / total);
        double lo = 0.0;
        double hi = (double) right / total;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            // P(X >= right) is P(Y <= total - right) for Y = total - X ~ Binomial(total, 1 - p), rising in p.
            if (logCdf(total - right, total, 1 - mid) < Math.log(tail)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Whether the bound is within {@link #LIMIT}; decided on the CDF itself, so the boundary is exact. */
    public static boolean boundPasses(int wrong, int written) {
        return boundPasses(wrong, written, LIMIT, CONFIDENCE_TAIL);
    }

    /** Whether the bound on {@code k} wrong of {@code n} is within {@code limit}. */
    public static boolean boundPasses(int k, int n, double limit) {
        return boundPasses(k, n, limit, CONFIDENCE_TAIL);
    }

    /** Whether the bound at {@code tail} on {@code k} wrong of {@code n} is within {@code limit}. */
    public static boolean boundPasses(int k, int n, double limit, double tail) {
        return n > 0 && k < n && logCdf(k, n, limit) <= Math.log(tail);
    }

    /** log P(X <= x) for X ~ Binomial(n, p), summed in log space; the binomial coefficient is built up term by term. */
    static double logCdf(int x, int n, double p) {
        double logP = Math.log(p);
        double logQ = Math.log1p(-p);
        double max = Double.NEGATIVE_INFINITY;
        var terms = new double[x + 1];
        double logChoose = 0;
        for (int k = 0; k <= x; k++) {
            if (k > 0) logChoose += Math.log((double) n - k + 1) - Math.log(k);
            terms[k] = logChoose + k * logP + (n - k) * logQ;
            max = Math.max(max, terms[k]);
        }
        double sum = 0;
        for (double term : terms) sum += Math.exp(term - max);
        return max + Math.log(sum);
    }
}
