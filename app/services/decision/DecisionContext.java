package services.decision;

import llm.TokenUsageEstimator;

import java.util.Locale;
import java.util.Map;

/** How much of a {@code /v1/systemone} request each local decision model can read (JCLAW-1357). */
public final class DecisionContext {

    /** Measured context limits in tokens, by model name without its {@code :tag}. */
    private static final Map<String, Integer> LIMITS = Map.of("tev1", 2050, "nimble", 8194, "clef-flash", 16384);
    /** An unmeasured model gets the smallest measured limit. */
    private static final int SMALLEST = 2050;
    /** cl100k is not the decision models' tokenizer, so its count is inflated by a quarter before comparing. */
    static final double MARGIN = 1.25;

    private DecisionContext() {}

    public static int promptBudget(String model) {
        int colon = model.indexOf(':');
        var name = (colon < 0 ? model : model.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
        return LIMITS.getOrDefault(name, SMALLEST);
    }

    /** Whether {@code requestJson}'s estimated tokens, with the margin, are at most {@code model}'s budget. */
    public static boolean fits(String model, String requestJson) {
        return TokenUsageEstimator.estimateText(null, requestJson).tokens() * MARGIN <= promptBudget(model);
    }
}
