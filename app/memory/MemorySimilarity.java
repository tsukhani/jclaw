package memory;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import services.search.LuceneIndexer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Near-duplicate detection for capture-time dedup (JCLAW-922). Deterministic and
 * dialect-free — no embedding round-trip, no search backend — so the NOOP decides
 * identically on H2 and Postgres, and with vector memory on or off. That matters
 * because the decision runs inside the plan transaction, where a blocking call is
 * forbidden.
 *
 * <p>Two independent tests, either of which marks a pair duplicate:
 *
 * <ul>
 *   <li><b>Token Jaccard</b> — the original JCLAW-39 metric, unchanged, for
 *       near-identical restatements.</li>
 *   <li><b>Content containment</b> — the overlap coefficient over
 *       boilerplate-stripped tokens, gated on a length ratio. Catches what Jaccard
 *       structurally cannot: a restatement that is a subset of an existing memory,
 *       where the length difference alone drags Jaccard below the threshold.</li>
 * </ul>
 */
public final class MemorySimilarity {

    private MemorySimilarity() {}

    /**
     * Stripped before the containment test only. Auto-capture writes every memory
     * in the third person ("The user ...", per {@code EXTRACTION_INSTRUCTIONS}), so
     * these tokens are near-universal and inflate overlap between unrelated facts.
     */
    static final Set<String> BOILERPLATE = Set.of(
            "the", "user", "s", "a", "an", "and", "or", "to", "of", "for", "in", "is",
            "are", "that", "with", "it", "as", "on", "by", "be", "this", "their",
            "they", "them", "has", "have", "had", "was", "were", "at", "from", "not",
            "but", "which", "who", "when", "where", "assistant", "also", "its");

    /**
     * {@link #BOILERPLATE} through the same normalization as everything it is subtracted
     * from. Skipping this is the quiet way to break the containment test: unnormalized
     * stopwords stop matching normalized tokens, {@code removeAll} silently does nothing,
     * and every content set inflates at once. Measured on the shipped analyzer the two sets
     * happen to coincide — KStem leaves all forty alone — but that is a property of today's
     * word list, not a guarantee, so it is derived rather than assumed.
     */
    private static final Set<String> BOILERPLATE_NORMALIZED = BOILERPLATE.stream()
            .flatMap(w -> analyze(w).stream())
            .collect(Collectors.toUnmodifiableSet());

    /** Both token views of one text, from a single analysis pass. */
    public record Tokens(Set<String> raw, Set<String> content) {
        public static Tokens of(String text) {
            var raw = analyze(text);
            var content = new HashSet<>(raw);
            content.removeAll(BOILERPLATE_NORMALIZED);
            return new Tokens(raw, content);
        }
    }

    /**
     * The one normalization, shared with search (JCLAW-1054).
     *
     * <p>Was lowercase plus {@code split("[^a-z0-9]+")}, which left this rule and the search
     * analyzer disagreeing about what "the same text" means. Routing both through
     * {@link LuceneIndexer#ANALYZER} settles that, and measurably improves dedup: swept over
     * 792 real memory texts at the unchanged 0.85/0.82 thresholds it catches 29 more
     * restatement pairs and drops 8, of which about half were false positives the old rule
     * made — including two <em>different</em> printers at different IP addresses that it had
     * been collapsing into one.
     *
     * <p>Still deterministic and backend-free, which is what the class contract above
     * requires: an analyzer is a pure in-memory token pipeline, not a search round-trip, so
     * this remains safe to call inside the plan transaction.
     */
    private static Set<String> analyze(String text) {
        var set = new HashSet<String>();
        if (text == null) return set;
        try (var stream = LuceneIndexer.ANALYZER.tokenStream("memory", text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) set.add(term.toString());
            stream.end();
        } catch (IOException e) {
            // Analysis reads a StringReader, so this cannot happen. Failing loudly beats
            // falling back to a second tokenization rule and diverging silently.
            throw new UncheckedIOException("memory tokenization failed", e);
        }
        return set;
    }

    public static Set<String> tokenize(String text) {
        return analyze(text);
    }

    public static Set<String> contentTokens(String text) {
        var set = analyze(text);
        set.removeAll(BOILERPLATE_NORMALIZED);
        return set;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = intersectionSize(a, b);
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /** Overlap coefficient — the fraction of the smaller set the larger one covers. */
    public static double containment(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        return (double) intersectionSize(a, b) / Math.min(a.size(), b.size());
    }

    private static double lengthRatio(Set<String> a, Set<String> b) {
        int max = Math.max(a.size(), b.size());
        return max == 0 ? 1.0 : (double) Math.min(a.size(), b.size()) / max;
    }

    private static int intersectionSize(Set<String> a, Set<String> b) {
        var smaller = a.size() <= b.size() ? a : b;
        var larger = a.size() <= b.size() ? b : a;
        int n = 0;
        for (var t : smaller) {
            if (larger.contains(t)) n++;
        }
        return n;
    }

    /**
     * Whether two memory texts state the same fact.
     *
     * @param minLengthRatio floor on {@code min/max} content-token count, required
     *        for the containment test to fire. Without it a short memory swallowed
     *        by a long unrelated one reads as a full subset: a ten-item recurring-task
     *        digest covers every content token of "recurring reminder to pay salaries
     *        on the last Friday of every month at 5:00 PM" (containment 0.91) while
     *        sharing no subject with it. Both false positives found while validating
     *        this rule against a 1248-row store had that shape and sat below a 0.1
     *        ratio; every true duplicate sat above 0.5.
     */
    public static boolean isDuplicate(Tokens a, Tokens b, double jaccardThreshold,
            double containmentThreshold, double minLengthRatio) {
        if (jaccard(a.raw(), b.raw()) >= jaccardThreshold) {
            return true;
        }
        return containment(a.content(), b.content()) >= containmentThreshold
                && lengthRatio(a.content(), b.content()) >= minLengthRatio;
    }
}
