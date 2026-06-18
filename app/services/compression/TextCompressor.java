package services.compression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Statistical plain-text compressor (JCLAW-464) for unstructured tool outputs —
 * logs, prose, documentation — using cheap string statistics, no ML model.
 *
 * <p>Per the Headroom-style recipe:
 * <ol>
 *   <li>Normalize whitespace (tabs→spaces, collapse runs of spaces and 3+ blank
 *       lines).</li>
 *   <li>Split into blocks on blank lines.</li>
 *   <li>Per block: a long block (&gt; {@value #LONG_BLOCK_WORDS} words) keeps its
 *       first unit and a summary marker; a short block has its units (sentences
 *       or lines) deduplicated by Jaccard similarity &gt; {@value #JACCARD_THRESHOLD}.</li>
 *   <li>Rejoin.</li>
 * </ol>
 *
 * <p>{@code targetRatio} (default {@value #DEFAULT_TARGET_RATIO}) is the
 * aggressiveness knob: the result is only accepted if it's at least that much
 * smaller than the input ({@code result ≤ original × (1 − targetRatio)}),
 * otherwise the original is returned unchanged. Combined with the pipeline's
 * token-level guard, the compressor never inflates.
 */
public final class TextCompressor implements ContentCompressor {

    public static final String ALGORITHM_NAME = "text-statistical";
    public static final double DEFAULT_TARGET_RATIO = 0.3;

    private static final double JACCARD_THRESHOLD = 0.9;
    private static final int LONG_BLOCK_WORDS = 200;

    // A "unit" boundary: a sentence end (.!?) followed by space, or a newline.
    private static final Pattern UNIT_SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final Pattern BLOCK_SPLIT = Pattern.compile("\\n[ \\t]*\\n");
    private static final Pattern WORD = Pattern.compile("\\s+");
    private static final Pattern NON_WORD = Pattern.compile("\\W+");

    private final double targetRatio;

    public TextCompressor() {
        this(DEFAULT_TARGET_RATIO);
    }

    public TextCompressor(double targetRatio) {
        // Clamp to a sane band so a bad config can't disable or invert the guard.
        this.targetRatio = Math.clamp(targetRatio, 0.05, 0.95);
    }

    @Override
    public String algorithm() {
        return ALGORITHM_NAME;
    }

    @Override
    public CompressionResult compress(String content) {
        if (content == null || content.isBlank()) return CompressionResult.unchanged(content, ALGORITHM_NAME);

        var normalized = normalizeWhitespace(content);
        var out = new ArrayList<String>();
        for (var block : BLOCK_SPLIT.split(normalized)) {
            if (block.isBlank()) continue;
            out.add(compressBlock(block));
        }
        var result = String.join("\n\n", out);

        // Effectiveness guard: only keep the rewrite if it hit the target shrink.
        if (result.length() > content.length() * (1.0 - targetRatio)) {
            return CompressionResult.unchanged(content, ALGORITHM_NAME);
        }
        return CompressionResult.compressed(result, ALGORITHM_NAME);
    }

    private static String compressBlock(String block) {
        var units = new ArrayList<String>();
        for (var u : UNIT_SPLIT.split(block.strip())) {
            if (!u.isBlank()) units.add(u.strip());
        }
        if (units.isEmpty()) return block.strip();

        int words = WORD.split(block.strip()).length;
        if (words > LONG_BLOCK_WORDS) {
            // Long block: keep the first unit (always preserved) + a marker.
            return units.get(0) + "\n[… summarized — " + (units.size() - 1) + " more lines/sentences]";
        }
        // Short block: drop near-duplicate units, keep first occurrence.
        return String.join("\n", dedupByJaccard(units));
    }

    private static List<String> dedupByJaccard(List<String> units) {
        var kept = new ArrayList<String>();
        var keptTokens = new ArrayList<Set<String>>();
        for (var u : units) {
            var toks = tokenize(u);
            var dup = false;
            for (var seen : keptTokens) {
                if (jaccard(toks, seen) > JACCARD_THRESHOLD) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                kept.add(u);
                keptTokens.add(toks);
            }
        }
        return kept;
    }

    private static Set<String> tokenize(String s) {
        var set = new HashSet<String>();
        for (var w : NON_WORD.split(s.toLowerCase())) {
            if (!w.isBlank()) set.add(w);
        }
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        var inter = 0;
        for (var x : a) {
            if (b.contains(x)) inter++;
        }
        var union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    private static String normalizeWhitespace(String text) {
        return text
                .replace('\t', ' ')
                .replaceAll("(?m)[ ]++$", "")   // trailing spaces per line (possessive: no ReDoS backtracking)
                .replaceAll("[ ]{2,}", " ")    // runs of spaces
                .replaceAll("\\n{3,}", "\n\n"); // 3+ newlines -> a single blank line
    }
}
