package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Agreement between the committed labels and a blind second labeller over a fixed subset of the cases
 * (JCLAW-1356). Pure: no I/O, no clock.
 */
public final class Agreement {

    public static final double BLIND_SHARE = 0.15;
    private static final String SALT = "jclaw-1356:";

    private Agreement() {}

    /**
     * {@code covered} of the {@code selected} blind cases carry second labels; the scores are over those, each null
     * when it has nothing to measure.
     */
    public record Result(int selected, int covered, boolean complete, @Nullable Double entityF1,
                         @Nullable Double typeKappa, @Nullable Double relationF1) {}

    /** The blind subset's ids: ceil(15%) of the cases, by ascending SHA-256 of {@code "jclaw-1356:" + id}. */
    public static List<String> blindSelection(List<Case> cases) {
        int count = (int) Math.ceil(cases.size() * BLIND_SHARE);
        return cases.stream().map(Case::id).sorted(Comparator.comparing(Agreement::hash)).limit(count).toList();
    }

    /**
     * The sheet a blind labeller works from: the selected cases' ids and text, and the set's {@code userMd} when it
     * declares an owner, so the labeller knows whose name stands for the operator. Nothing else.
     */
    public static JsonObject blindSheet(List<Case> cases, @Nullable String userMd) {
        var selected = new HashSet<>(blindSelection(cases));
        var out = new JsonArray();
        for (var c : cases) {
            if (!selected.contains(c.id())) continue;
            var o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("text", c.text());
            out.add(o);
        }
        var root = new JsonObject();
        if (userMd != null) root.addProperty("userMd", userMd);
        root.add("cases", out);
        return root;
    }

    static String hash(String id) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((SALT + id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Compares {@code second} with {@code first} over the blind subset of {@code first}. */
    public static Result compare(List<Case> first, List<Case> second) {
        var selected = blindSelection(first);
        var firstById = byId(first);
        var secondById = byId(second);
        int covered = 0;
        int matchedEntities = 0;
        int firstEntities = 0;
        int secondEntities = 0;
        var typePairs = new ArrayList<String[]>();
        int matchedRelations = 0;
        int firstRelations = 0;
        int secondRelations = 0;
        for (var id : selected) {
            var a = firstById.get(id);
            var b = secondById.get(id);
            if (a == null || b == null) continue;
            covered++;
            firstEntities += a.entities().size();
            secondEntities += b.entities().size();
            // second-label id -> first-label id
            var match = new HashMap<String, String>();
            var taken = new HashSet<String>();
            for (var e : b.entities()) {
                for (var f : a.entities()) {
                    if (!taken.contains(f.id()) && matches(f, e)) {
                        match.put(e.id(), f.id());
                        taken.add(f.id());
                        typePairs.add(new String[] {f.type(), e.type()});
                        break;
                    }
                }
            }
            matchedEntities += match.size();

            var gold = new HashSet<List<String>>();
            a.relations().forEach(r -> gold.add(key(r.from(), r.type(), r.to())));
            firstRelations += gold.size();
            var seen = new HashSet<List<String>>();
            for (var r : b.relations()) {
                var from = match.get(r.from());
                var to = match.get(r.to());
                var k = from == null || to == null ? List.of(r.from(), r.type(), r.to(), "unmatched")
                        : key(from, r.type(), to);
                if (!seen.add(k)) continue;
                secondRelations++;
                if (gold.contains(k)) matchedRelations++;
            }
        }
        boolean complete = !selected.isEmpty() && covered == selected.size();
        return new Result(selected.size(), covered, complete, f1(matchedEntities, firstEntities, secondEntities),
                kappa(typePairs), f1(matchedRelations, firstRelations, secondRelations));
    }

    /** Same operator, or a span of one is a span of the other. */
    private static boolean matches(Entity first, Entity second) {
        if (first.operator() && second.operator()) return true;
        var mention = second.mention();
        if (mention != null && first.answersTo(mention)) return true;
        var firstMention = first.mention();
        return second.aliases().stream().anyMatch(first::answersTo)
                || (firstMention != null && second.answersTo(firstMention));
    }

    private static List<String> key(String from, String type, String to) {
        if (GraphEvalScorer.SYMMETRIC.contains(type) && from.compareTo(to) > 0) return List.of(to, type, from);
        return List.of(from, type, to);
    }

    private static @Nullable Double f1(int matched, int first, int second) {
        if (first + second == 0) return null;
        return 2.0 * matched / (first + second);
    }

    /** Cohen's kappa over (first type, second type) pairs; null with no pairs, 1.0 when chance agreement is total. */
    static @Nullable Double kappa(List<String[]> pairs) {
        if (pairs.isEmpty()) return null;
        int n = pairs.size();
        int agree = 0;
        var firstCounts = new HashMap<String, Integer>();
        var secondCounts = new HashMap<String, Integer>();
        for (var p : pairs) {
            if (p[0].equals(p[1])) agree++;
            firstCounts.merge(p[0], 1, Integer::sum);
            secondCounts.merge(p[1], 1, Integer::sum);
        }
        double observed = (double) agree / n;
        double chance = 0;
        for (var entry : firstCounts.entrySet()) {
            chance += (double) entry.getValue() * secondCounts.getOrDefault(entry.getKey(), 0) / ((double) n * n);
        }
        if (chance >= 1.0) return observed == 1.0 ? 1.0 : 0.0;
        return (observed - chance) / (1 - chance);
    }

    private static Map<String, Case> byId(List<Case> cases) {
        var out = new HashMap<String, Case>();
        cases.forEach(c -> out.put(c.id(), c));
        return out;
    }
}
