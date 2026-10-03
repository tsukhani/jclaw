package services.graphspike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Clusters typed mentions across memories by type plus normalized surface (JCLAW-1356). The baseline resolver: it
 * merges only what reads the same, so every miss it makes is a merge a smarter resolver would have to earn.
 */
public final class ExactMatchResolver {

    private static final String OPERATOR_KEY = "\u0000operator";

    private ExactMatchResolver() {}

    /** One typed mention; {@code ref} identifies it to the caller, and every operator mention joins one cluster. */
    public record Mention(String ref, String surface, String type, boolean operator) {}

    /** Case-folded, a leading {@code the } and a possessive {@code 's} stripped, whitespace collapsed. */
    public static String normalize(String surface) {
        var s = surface.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
        if (s.startsWith("the ")) s = s.substring(4);
        if (s.endsWith("'s") || s.endsWith("’s")) s = s.substring(0, s.length() - 2);
        return s.strip();
    }

    /** The clusters, each a list of mention refs, in order of first appearance. */
    public static List<List<String>> resolve(List<Mention> mentions) {
        var clusters = new LinkedHashMap<String, List<String>>();
        for (var m : mentions) {
            var key = m.operator() ? OPERATOR_KEY : m.type() + "\u0000" + normalize(m.surface());
            clusters.computeIfAbsent(key, _ -> new ArrayList<>()).add(m.ref());
        }
        return clusters.values().stream().map(List::copyOf).toList();
    }
}
