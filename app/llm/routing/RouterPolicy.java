package llm.routing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import org.jspecify.annotations.Nullable;
import services.ConfigService;

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
 */
public record RouterPolicy(Map<TaskClass, List<Candidate>> classes, double downshiftAt, double exhaustedAt) {

    public static final String PREFIX = "router.";
    public static final String DOWNSHIFT_AT = "router.budget.downshiftAt";
    public static final String EXHAUSTED_AT = "router.budget.exhaustedAt";
    public static final double DEFAULT_DOWNSHIFT_AT = 0.75;
    public static final double DEFAULT_EXHAUSTED_AT = 0.95;

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
                ConfigService.getDouble(EXHAUSTED_AT, DEFAULT_EXHAUSTED_AT));
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
        for (var c : TaskClass.values()) {
            if (key.equals(modelsKey(c))) return modelsRejection(key, value);
        }
        return key + " is not a router setting. Use " + modelsKey(TaskClass.CHAT)
                + " (or another task class: summarize, agentic, reasoning, coding), "
                + DOWNSHIFT_AT + " or " + EXHAUSTED_AT + ".";
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
                return "Provider '" + candidate.provider() + "' is not configured. " + key
                        + " must name providers from Settings > LLM Providers.";
            }
            var registered = provider.config().models().stream().anyMatch(m -> m.id().equals(candidate.model()));
            if (!registered) {
                return "Provider '" + candidate.provider() + "' has no model with id '" + candidate.model() + "'.";
            }
        }
        return null;
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
