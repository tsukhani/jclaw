package services.grapheval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The thresholds a sequence run's end-to-end variant writes at (JCLAW-1367): the term threshold, one per enabled
 * relation type (an absent type writes nothing), and each qualifier class's state. An input to scoring only: it never
 * changes what is asked. Lineage is not configured; its threshold is the run's own lineage walk.
 */
public record Configuration(double terms, SortedMap<String, Double> relations,
                            SortedMap<String, ClassSetting> classes) {

    public static final String CERTIFIED = Certifier.CLASS_CERTIFIED;
    public static final String PROVISIONAL = Certifier.PROVISIONAL;
    public static final String DISABLED = Certifier.DISABLED;
    public static final String STATUS = "status";
    public static final String TIME = "time";
    public static final String NEGATION = "negation";
    public static final double DEFAULT_THRESHOLD = 0.85;
    private static final Set<String> KEYS = Set.of("terms", "relations", "classes");
    private static final Set<String> CLASS_KEYS = Set.of("state", "threshold");
    private static final List<String> CLASS_NAMES = List.of(STATUS, TIME, NEGATION);
    private static final Set<String> STATES = Set.of(CERTIFIED, PROVISIONAL, DISABLED);

    public Configuration {
        relations = Collections.unmodifiableSortedMap(new TreeMap<>(relations));
        classes = Collections.unmodifiableSortedMap(new TreeMap<>(classes));
    }

    /** A class's state; {@code threshold} is null exactly when it is disabled. */
    public record ClassSetting(String state, @Nullable Double threshold) {}

    /** Terms and every schema relation at 0.85; status, time and negation provisional at 0.85. */
    public static Configuration defaultFor(OntologySchema schema) {
        var relations = new TreeMap<String, Double>();
        schema.relations().keySet().forEach(r -> relations.put(r, DEFAULT_THRESHOLD));
        var classes = new TreeMap<String, ClassSetting>();
        CLASS_NAMES.forEach(c -> classes.put(c, new ClassSetting(PROVISIONAL, DEFAULT_THRESHOLD)));
        return new Configuration(DEFAULT_THRESHOLD, relations, classes);
    }

    /**
     * The configuration in {@code json}; absent {@code relations} or {@code classes} leave every relation off and
     * every class disabled.
     *
     * @throws IllegalArgumentException naming the key, on an unknown key, relation, class or state, or a bad threshold
     */
    public static Configuration parse(JsonObject json, OntologySchema schema) {
        GraphCases.onlyKeys(json, KEYS, "configuration");
        if (!json.has("terms")) throw new IllegalArgumentException("configuration: 'terms' is required");
        double terms = threshold(json.get("terms"), "configuration: 'terms'");
        var relations = new TreeMap<String, Double>();
        if (json.has("relations")) {
            for (var entry : object(json.get("relations"), "relations").entrySet()) {
                if (!schema.relations().containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("configuration: unknown relation '" + entry.getKey() + "'");
                }
                relations.put(entry.getKey(), threshold(entry.getValue(),
                        "configuration: relation '" + entry.getKey() + "'"));
            }
        }
        var classes = new TreeMap<String, ClassSetting>();
        if (json.has("classes")) {
            for (var entry : object(json.get("classes"), "classes").entrySet()) {
                var name = entry.getKey();
                var where = "configuration: class '" + name + "'";
                if (!CLASS_NAMES.contains(name)) {
                    throw new IllegalArgumentException("configuration: unknown class '" + name
                            + "' (status, time or negation; lineage comes from the run's own walk)");
                }
                var setting = object(entry.getValue(), "classes." + name);
                GraphCases.onlyKeys(setting, CLASS_KEYS, where);
                var state = setting.has("state") && setting.get("state").isJsonPrimitive()
                        ? setting.get("state").getAsString() : null;
                if (state == null || !STATES.contains(state)) {
                    throw new IllegalArgumentException(where + ": state must be certified, provisional or disabled");
                }
                Double threshold = null;
                if (state.equals(DISABLED)) {
                    if (setting.has("threshold")) {
                        throw new IllegalArgumentException(where + ": a disabled class takes no threshold");
                    }
                } else {
                    if (!setting.has("threshold")) {
                        throw new IllegalArgumentException(where + ": a " + state + " class needs a threshold");
                    }
                    threshold = threshold(setting.get("threshold"), where + " threshold");
                }
                classes.put(name, new ClassSetting(state, threshold));
            }
        }
        return new Configuration(terms, relations, classes);
    }

    /** The qualifier classes as {@link Statements} reads them: a disabled or absent class is null; lineage is null. */
    public Statements.Classes statementClasses() {
        return new Statements.Classes(threshold(STATUS), threshold(TIME), threshold(NEGATION), null);
    }

    private @Nullable Double threshold(String name) {
        var setting = classes.get(name);
        return setting == null ? null : setting.threshold();
    }

    private static JsonObject object(JsonElement e, String key) {
        if (!e.isJsonObject()) throw new IllegalArgumentException("configuration: '" + key + "' must be an object");
        return e.getAsJsonObject();
    }

    private static double threshold(JsonElement e, String where) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(where + " must be a number");
        }
        double t = e.getAsDouble();
        if (!(t >= 0 && t <= 1)) throw new IllegalArgumentException(where + " must be in [0, 1]");
        return t;
    }
}
