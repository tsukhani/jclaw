package services.grapheval;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Walks a resolution threshold grid strictest first (JCLAW-1370): each step bounds the false-merge rate of the
 * attachments made at that threshold, and the walk stops at the first step whose bound fails. Gold-agnostic: the
 * counts come from whatever scored the outcomes.
 */
public final class ResolutionCalibration {

    private ResolutionCalibration() {}

    /** The attachments made at a threshold and how many of them were false merges. */
    public record Counts(int attachments, int falseMerges) {}

    /** {@code rate} is null with no attachments; {@code bound} and {@code passes} are {@link Certifier}'s. */
    public record Step(double t, int attachments, int falseMerges, @Nullable Double rate, double bound,
                       boolean passes) {}

    /** {@code threshold} is the lowest t of the passing run from the strictest down, null when the first step fails. */
    public record Walk(@Nullable Double threshold, List<Step> steps) {
        public Walk {
            steps = List.copyOf(steps);
        }

        /** The step at {@code t}, or null. */
        public @Nullable Step at(double t) {
            return steps.stream().filter(s -> s.t() == t).findFirst().orElse(null);
        }
    }

    public static Walk walk(List<Double> grid, DoubleFunction<Counts> at) {
        var steps = new ArrayList<Step>();
        Double threshold = null;
        for (double t : grid.stream().distinct().sorted(Comparator.reverseOrder()).toList()) {
            var c = at.apply(t);
            int n = c.attachments();
            int k = c.falseMerges();
            boolean passes = Certifier.boundPasses(k, n);
            steps.add(new Step(t, n, k, n == 0 ? null : (double) k / n, Certifier.upperBound(k, n), passes));
            if (!passes) break;
            threshold = t;
        }
        return new Walk(threshold, steps);
    }
}
