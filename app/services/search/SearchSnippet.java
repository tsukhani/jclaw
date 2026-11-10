package services.search;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A search hit's excerpt: at most {@link #WINDOW_CHARS} characters of the message, with whitespace runs
 * collapsed to one space, centred on the first case-insensitive match of a query term and marked with
 * {@link #CUT_MARK} at each end that was cut. A message that fits comes back whole and unmarked; one with no
 * literal match (the analyzer's stemming can match a form the text does not spell) shows its opening.
 */
public final class SearchSnippet {

    /** Characters of the message a snippet shows; each cut end adds one {@link #CUT_MARK}. */
    public static final int WINDOW_CHARS = 200;
    public static final String CUT_MARK = "…";

    /** Control characters too: JSON escapes each to six characters, which would break a page's size bound. */
    private static final Pattern BLANK_RUN = Pattern.compile("[\\s\\p{Cntrl}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final String NO_WORD_CHAR_BEFORE = "(?<![\\p{L}\\p{N}])";
    private static final String NO_WORD_CHAR_AFTER = "(?![\\p{L}\\p{N}])";

    /** The search ANDs every term, so each hit holds the first ones; more needles only lengthen the scan. */
    private static final int MAX_NEEDLES = 32;

    private SearchSnippet() {}

    public static String around(@Nullable String content, String query) {
        if (content == null) return "";
        var flat = BLANK_RUN.matcher(content).replaceAll(" ").strip();
        if (flat.length() <= WINDOW_CHARS) return flat;

        var match = firstMatch(flat, query);
        int matchStart = match != null ? match.start() : 0;
        int matchLength = match != null ? match.end() - match.start() : 0;
        int start = Math.clamp(matchStart - Math.max(0, WINDOW_CHARS - matchLength) / 2,
                0, flat.length() - WINDOW_CHARS);
        int end = start + WINDOW_CHARS;
        // A lone surrogate at a cut end would reach the wire as an unpaired code unit.
        if (start > 0 && Character.isLowSurrogate(flat.charAt(start))) start++;
        if (end < flat.length() && Character.isLowSurrogate(flat.charAt(end))) end--;
        return (start > 0 ? CUT_MARK : "") + flat.substring(start, end) + (end < flat.length() ? CUT_MARK : "");
    }

    /**
     * Earliest place a needle starts a word, as the search's prefix match does. The needles are the query's
     * words and the analyzer's terms for them, so a plural query still finds the singular it matched.
     */
    private static @Nullable MatchResult firstMatch(String text, String query) {
        var needles = new LinkedHashSet<String>();
        try {
            needles.addAll(DirectLuceneMessageSearchRepository.analyzeToTerms(query));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (var word : NON_WORD.split(query)) {
            if (!word.isEmpty()) needles.add(word.toLowerCase(Locale.ROOT));
        }
        if (needles.isEmpty()) return null;
        var alternatives = needles.stream()
                .limit(MAX_NEEDLES)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(n -> Pattern.quote(n)
                        + (n.length() < DirectLuceneMessageSearchRepository.MIN_PREFIX_LEN ? NO_WORD_CHAR_AFTER : ""))
                .collect(Collectors.joining("|"));
        var matcher = Pattern.compile(NO_WORD_CHAR_BEFORE + "(?:" + alternatives + ")",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return matcher.find() ? matcher.toMatchResult() : null;
    }
}
