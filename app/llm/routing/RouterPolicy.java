package llm.routing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.decision.JevApi;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The operator's routing policy (JCLAW-1222), read from the Config DB: an ordered model list per
 * {@link TaskClass} and the two budget thresholds. Nothing is seeded — the router is available
 * exactly when {@code router.chat.models} names at least one model.
 *
 * @param downshiftAt usage fraction of a prepaid provider at which non-chat classes stop using it
 *                    and drop to the chat list
 * @param exhaustedAt usage fraction at which a prepaid provider is skipped for every class
 * @param classifier  the model that labels each prompt, {@link #JEV} for TypeSafe's judge, or null to
 *                    use the local keyword rules
 * @param classifierTimeoutSeconds how long a classifier call may take before the rules answer instead
 * @param preferPrepaid whether subscriptions and self-hosted models are tried before per-token ones
 *                      whatever the listed order; false follows the operator's order exactly
 * @param jevMinConfidence the probability JEV's class must reach before it is used over the rules
 */
public record RouterPolicy(Map<TaskClass, List<Candidate>> classes, double downshiftAt, double exhaustedAt,
                           @Nullable Candidate classifier, int classifierTimeoutSeconds, boolean preferPrepaid,
                           double jevMinConfidence) {

    public static final String PREFIX = "router.";
    public static final String DOWNSHIFT_AT = "router.budget.downshiftAt";
    public static final String EXHAUSTED_AT = "router.budget.exhaustedAt";
    public static final String CLASSIFIER_PROVIDER = "router.classifier.provider";
    public static final String CLASSIFIER_MODEL = "router.classifier.model";
    public static final String CLASSIFIER_TIMEOUT_SECONDS = "router.classifier.timeoutSeconds";
    public static final String JEV_MIN_CONFIDENCE = "router.classifier.jev.minConfidence";
    /** The classifier provider that names TypeSafe's JEV rather than an LLM; its one model is {@link JevApi#MODEL}. */
    public static final String JEV = "jev";
    /** Absent means true: credit protection is what an operator who has set nothing should get. */
    public static final String PREFER_PREPAID = "router.preferPrepaid";
    public static final double DEFAULT_DOWNSHIFT_AT = 0.75;
    public static final double DEFAULT_EXHAUSTED_AT = 0.95;
    public static final int DEFAULT_CLASSIFIER_TIMEOUT_SECONDS = 8;
    /** Fitted on 60 labeled prompts (JCLAW-1300): every answer JEV gave scored 0.50 or more, and JEV beat the rules even below 0.70. */
    public static final double DEFAULT_JEV_MIN_CONFIDENCE = 0.50;

    private static final String MODELS_SUFFIX = ".models";

    /** One configured model, in the order the operator ranked it. */
    public record Candidate(String provider, String model) {
        public String describe() {
            return provider + "/" + model;
        }
    }

    public RouterPolicy {
        classes = Map.copyOf(classes);
    }

    /** Convenience for a policy with no classifier model: the keyword rules label every prompt. */
    public RouterPolicy(Map<TaskClass, List<Candidate>> classes, double downshiftAt, double exhaustedAt) {
        this(classes, downshiftAt, exhaustedAt, null, DEFAULT_CLASSIFIER_TIMEOUT_SECONDS, true);
    }

    /** Convenience for a policy with a classifier but the default prepaid-first ordering. */
    public RouterPolicy(Map<TaskClass, List<Candidate>> classes, double downshiftAt, double exhaustedAt,
                        @Nullable Candidate classifier, int classifierTimeoutSeconds) {
        this(classes, downshiftAt, exhaustedAt, classifier, classifierTimeoutSeconds, true);
    }

    /** Convenience for a policy with JEV's default minimum confidence. */
    public RouterPolicy(Map<TaskClass, List<Candidate>> classes, double downshiftAt, double exhaustedAt,
                        @Nullable Candidate classifier, int classifierTimeoutSeconds, boolean preferPrepaid) {
        this(classes, downshiftAt, exhaustedAt, classifier, classifierTimeoutSeconds, preferPrepaid,
                DEFAULT_JEV_MIN_CONFIDENCE);
    }

    public static String modelsKey(TaskClass taskClass) {
        return PREFIX + taskClass.id() + MODELS_SUFFIX;
    }

    /** The current policy. Reads go through {@link ConfigService}'s cache, so this is cheap per turn. */
    public static RouterPolicy load() {
        var classes = new EnumMap<TaskClass, List<Candidate>>(TaskClass.class);
        for (var c : TaskClass.values()) {
            classes.put(c, parseLenient(ConfigService.get(modelsKey(c))));
        }
        return new RouterPolicy(classes,
                ConfigService.getDouble(DOWNSHIFT_AT, DEFAULT_DOWNSHIFT_AT),
                ConfigService.getDouble(EXHAUSTED_AT, DEFAULT_EXHAUSTED_AT),
                configuredClassifier(),
                ConfigService.getInt(CLASSIFIER_TIMEOUT_SECONDS, DEFAULT_CLASSIFIER_TIMEOUT_SECONDS),
                ConfigService.getBoolean(PREFER_PREPAID, true),
                ConfigService.getDouble(JEV_MIN_CONFIDENCE, DEFAULT_JEV_MIN_CONFIDENCE));
    }

    /** The classifier model, or null unless the operator named both halves of the pair. */
    private static @Nullable Candidate configuredClassifier() {
        var provider = ConfigService.get(CLASSIFIER_PROVIDER);
        var model = ConfigService.get(CLASSIFIER_MODEL);
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) return null;
        return new Candidate(provider.strip(), model.strip());
    }

    public boolean available() {
        return !candidates(TaskClass.CHAT).isEmpty();
    }

    /** The class's own list, or the chat list when the operator configured none for it. */
    public List<Candidate> candidates(TaskClass taskClass) {
        var own = classes.getOrDefault(taskClass, List.of());
        return own.isEmpty() ? classes.getOrDefault(TaskClass.CHAT, List.of()) : own;
    }

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        if (key.equals(DOWNSHIFT_AT) || key.equals(EXHAUSTED_AT)) {
            return thresholdRejection(key, value);
        }
        if (key.equals(CLASSIFIER_PROVIDER) || key.equals(CLASSIFIER_MODEL)) {
            return classifierRejection(key, value);
        }
        if (key.equals(CLASSIFIER_TIMEOUT_SECONDS)) {
            return timeoutRejection(value);
        }
        if (key.equals(JEV_MIN_CONFIDENCE)) {
            return minConfidenceRejection(value);
        }
        if (key.equals(PREFER_PREPAID)) {
            // Boolean.parseBoolean maps anything unrecognised to false, which would silently turn the
            // credit protection off on a typo.
            return "true".equalsIgnoreCase(String.valueOf(value).trim())
                    || "false".equalsIgnoreCase(String.valueOf(value).trim())
                    ? null : PREFER_PREPAID + " must be true or false.";
        }
        for (var c : TaskClass.values()) {
            if (key.equals(modelsKey(c))) return modelsRejection(key, value);
        }
        return key + " is not a router setting. Use " + modelsKey(TaskClass.CHAT)
                + " (or another task class: summarize, agentic, reasoning, coding), "
                + DOWNSHIFT_AT + ", " + EXHAUSTED_AT + ", " + PREFER_PREPAID + ", " + CLASSIFIER_PROVIDER
                + ", " + CLASSIFIER_MODEL + ", " + CLASSIFIER_TIMEOUT_SECONDS + " or " + JEV_MIN_CONFIDENCE + ".";
    }

    /**
     * The classifier is a pair, so each half is checked against the other's stored value: a provider
     * that is not configured, a model it does not register, or the router itself — which would ask the
     * router to classify the prompt it is routing — is refused. {@link #JEV} is not a registered
     * provider and has one model, so its provider write is not checked against the stored model.
     */
    private static @Nullable String classifierRejection(String key, @Nullable String value) {
        if (value == null || value.isBlank()) return null;
        var v = value.strip();
        if (ModelRouter.PROVIDER.equals(v)) {
            return CLASSIFIER_PROVIDER + " cannot be the router itself; name a concrete model to classify with.";
        }
        var provider = key.equals(CLASSIFIER_PROVIDER) ? v : ConfigService.get(CLASSIFIER_PROVIDER);
        var model = key.equals(CLASSIFIER_MODEL) ? v : ConfigService.get(CLASSIFIER_MODEL);
        if (provider == null || provider.isBlank()) {
            return key.equals(CLASSIFIER_MODEL)
                    ? CLASSIFIER_MODEL + " needs " + CLASSIFIER_PROVIDER + " as well." : null;
        }
        if (JEV.equals(provider.strip())) {
            return key.equals(CLASSIFIER_PROVIDER) || JevApi.MODEL.equals(v) ? null
                    : "%s must be %s when %s is %s.".formatted(CLASSIFIER_MODEL, JevApi.MODEL, CLASSIFIER_PROVIDER, JEV);
        }
        var registered = ProviderRegistry.get(provider);
        if (registered == null) {
            return notConfigured(provider, CLASSIFIER_PROVIDER, "a provider");
        }
        if (model == null || model.isBlank()) return null;
        var known = registered.config().models().stream().anyMatch(m -> m.id().equals(model));
        return known ? null : noSuchModel(provider, model);
    }

    private static @Nullable String timeoutRejection(@Nullable String value) {
        try {
            var seconds = Integer.parseInt(value == null ? "" : value.trim());
            if (seconds >= 1 && seconds <= 60) return null;
        } catch (NumberFormatException _) {
            // Falls through to the message below.
        }
        return CLASSIFIER_TIMEOUT_SECONDS + " must be a whole number of seconds from 1 to 60.";
    }

    private static @Nullable String minConfidenceRejection(@Nullable String value) {
        try {
            var p = Double.parseDouble(value == null ? "" : value.trim());
            if (p >= 0 && p <= 1) return null;
        } catch (NumberFormatException _) {
            // Falls through to the message below.
        }
        return JEV_MIN_CONFIDENCE + " must be a probability from 0 to 1, such as 0.5.";
    }

    private static @Nullable String modelsRejection(String key, @Nullable String value) {
        if (value == null || value.isBlank()) return null;
        var shape = key + " must be a JSON array of {\"provider\":\"…\",\"model\":\"…\"} objects.";
        JsonElement root;
        try {
            root = JsonParser.parseString(value);
        } catch (JsonParseException _) {
            return shape;
        }
        if (!root.isJsonArray()) return shape;
        for (var entry : root.getAsJsonArray()) {
            var candidate = candidateOf(entry);
            if (candidate == null) return shape;
            if (ModelRouter.PROVIDER.equals(candidate.provider())) {
                return key + " cannot list the router itself.";
            }
            var provider = ProviderRegistry.get(candidate.provider());
            if (provider == null) {
                return notConfigured(candidate.provider(), key, "providers");
            }
            var registered = provider.config().models().stream().anyMatch(m -> m.id().equals(candidate.model()));
            if (!registered) {
                return noSuchModel(candidate.provider(), candidate.model());
            }
        }
        return null;
    }

    /** {@code noun} carries the count: one key names a single provider, a class list names several. */
    private static String notConfigured(String provider, String key, String noun) {
        return "Provider '" + provider + "' is not configured. " + key
                + " must name " + noun + " from Settings > LLM Providers.";
    }

    private static String noSuchModel(String provider, String model) {
        return "Provider '" + provider + "' has no model with id '" + model + "'.";
    }

    private static @Nullable String thresholdRejection(String key, @Nullable String value) {
        double v;
        try {
            v = Double.parseDouble(value == null ? "" : value.trim());
        } catch (NumberFormatException _) {
            v = Double.NaN;
        }
        if (!Double.isFinite(v) || v <= 0 || v > 1) {
            return key + " must be a fraction greater than 0 and at most 1, such as 0.75.";
        }
        // Equal thresholds would skip a provider the moment it downshifts, so the lighter list never runs on it.
        var downshift = key.equals(DOWNSHIFT_AT) ? v : ConfigService.getDouble(DOWNSHIFT_AT, DEFAULT_DOWNSHIFT_AT);
        var exhausted = key.equals(EXHAUSTED_AT) ? v : ConfigService.getDouble(EXHAUSTED_AT, DEFAULT_EXHAUSTED_AT);
        if (downshift >= exhausted) {
            return DOWNSHIFT_AT + " (" + downshift + ") must be below " + EXHAUSTED_AT + " (" + exhausted + ").";
        }
        return null;
    }

    /** Stored values were validated on write; a hand-edited row that no longer parses routes nothing. */
    private static List<Candidate> parseLenient(@Nullable String value) {
        if (value == null || value.isBlank()) return List.of();
        JsonArray array;
        try {
            var root = JsonParser.parseString(value);
            if (!root.isJsonArray()) return List.of();
            array = root.getAsJsonArray();
        } catch (JsonParseException _) {
            return List.of();
        }
        var out = new ArrayList<Candidate>(array.size());
        for (var entry : array) {
            var candidate = candidateOf(entry);
            if (candidate != null && !out.contains(candidate)) out.add(candidate);
        }
        return List.copyOf(out);
    }

    private static @Nullable Candidate candidateOf(JsonElement entry) {
        if (!entry.isJsonObject()) return null;
        var provider = nonBlank(entry.getAsJsonObject(), "provider");
        var model = nonBlank(entry.getAsJsonObject(), "model");
        return provider == null || model == null ? null : new Candidate(provider, model);
    }

    private static @Nullable String nonBlank(JsonObject obj, String field) {
        var el = obj.get(field);
        if (el == null || !el.isJsonPrimitive()) return null;
        var s = el.getAsString().strip();
        return s.isEmpty() ? null : s;
    }
}
