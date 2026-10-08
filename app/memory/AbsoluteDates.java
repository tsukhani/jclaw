package memory;

import memory.ontology.EdtfDate;
import memory.ontology.EdtfInterval;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites a captured memory's relative dates ("last year", "yesterday", "next June") into absolute written forms
 * against the turn's anchor (JCLAW-1383), so the stored text means the same thing on any later day. Every date comes
 * from {@link TemporalExpressions#find}; a rewrite is kept only when that same finder reads the written form back to
 * the original value.
 */
public final class AbsoluteDates {

    private AbsoluteDates() {}

    private static final Pattern REWRITE_SET = Pattern.compile("today|yesterday|tomorrow|(?:this|last|next)\\s+(?:month"
            + "|quarter|year|" + String.join("|", TemporalExpressions.MONTH_NAMES) + ")");

    private static final Set<String> PREPOSITIONS = Set.of("in", "on", "at", "by", "since", "until", "from", "before",
            "after", "during", "early", "late", "mid");

    private static final Set<String> POSSESSIVES = Set.of("my", "your", "his", "her", "its", "our", "their", "whose");

    private record Replacement(TemporalExpressions.DateSpan span, int start, String prefix, String form) {}

    /** {@code text} with each qualifying relative date written absolutely at {@code anchor}; otherwise unchanged. */
    public static String rewrite(String text, LocalDate anchor) {
        var pending = new ArrayList<Replacement>();
        for (var span : TemporalExpressions.find(text, anchor).found()) {
            var r = replacement(text, span);
            if (r != null) pending.add(r);
        }
        while (!pending.isEmpty()) {
            var out = new StringBuilder();
            var formStarts = new int[pending.size()];
            int copied = 0;
            for (int i = 0; i < pending.size(); i++) {
                var r = pending.get(i);
                out.append(text, copied, r.start()).append(r.prefix());
                formStarts[i] = out.length();
                out.append(r.form());
                copied = r.span().end();
            }
            out.append(text, copied, text.length());
            var rewritten = out.toString();
            var found = TemporalExpressions.find(rewritten, anchor).found();
            var passing = new ArrayList<Replacement>();
            for (int i = 0; i < pending.size(); i++) {
                if (readsBack(found, formStarts[i], pending.get(i))) passing.add(pending.get(i));
            }
            if (passing.size() == pending.size()) return rewritten;
            pending = passing;
        }
        return text;
    }

    private static boolean readsBack(List<TemporalExpressions.DateSpan> found, int start, Replacement r) {
        int end = start + r.form().length();
        return found.stream().anyMatch(d -> d.start() == start && d.end() == end && !d.relative()
                && d.readings().equals(r.span().readings()));
    }

    private static @Nullable Replacement replacement(String text, TemporalExpressions.DateSpan span) {
        if (!REWRITE_SET.matcher(span.span().toLowerCase(Locale.ROOT)).matches()) return null;
        if (span.readings().size() != 1
                || !(span.readings().getFirst() instanceof EdtfInterval(EdtfInterval.Point(var date), _, var single))
                || !single) {
            return null;
        }
        if (span.start() > 0 && text.charAt(span.start() - 1) == '-') return null;
        if (followedByJoin(text, span.end())) return null;
        var previous = previousToken(text, span.start());
        if (previous != null && (previous.equalsIgnoreCase("the") || possessive(previous))) return null;
        var form = written(date);
        if (form == null) return null;
        if (previous != null && PREPOSITIONS.contains(previous.toLowerCase(Locale.ROOT))) {
            return new Replacement(span, span.start(), "", form);
        }
        boolean day = date.precision() == EdtfDate.Precision.DAY;
        boolean capital = sentenceStart(text, span.start()) || Character.isUpperCase(text.charAt(span.start()));
        String preposition;
        if (day) preposition = capital ? "On " : "on ";
        else preposition = capital ? "In " : "in ";
        return new Replacement(span, span.start(), preposition, form);
    }

    /** A genitive, hyphen-joined or digit-followed phrase ("last year's", "this year-end", "this June 5"). */
    private static boolean followedByJoin(String text, int end) {
        if (end >= text.length()) return false;
        char c = text.charAt(end);
        if (c == '\'' || c == '’' || c == '-') return true;
        int i = end;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i < text.length() && Character.isDigit(text.charAt(i));
    }

    /** The letters-and-apostrophes token separated from {@code start} by whitespace alone, or null. */
    private static @Nullable String previousToken(String text, int start) {
        int e = start;
        while (e > 0 && Character.isWhitespace(text.charAt(e - 1))) e--;
        if (e == start) return null;
        int s = e;
        while (s > 0 && isTokenChar(text.charAt(s - 1))) s--;
        return s == e ? null : text.substring(s, e);
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetter(c) || c == '\'' || c == '’';
    }

    private static boolean possessive(String token) {
        var lower = token.toLowerCase(Locale.ROOT);
        return POSSESSIVES.contains(lower) || lower.endsWith("'s") || lower.endsWith("’s");
    }

    private static boolean sentenceStart(String text, int start) {
        int i = start;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        if (i == 0) return true;
        return i < start && ".!?".indexOf(text.charAt(i - 1)) >= 0;
    }

    private static @Nullable String written(EdtfDate date) {
        return switch (date.precision()) {
            case DAY -> date.day() + " " + monthName(date.month()) + " " + date.year();
            case MONTH -> monthName(date.month()) + " " + date.year();
            case YEAR -> Integer.toString(date.year());
            case QUARTER -> "Q" + (date.code() - 32) + " " + date.year();
            case SEASON -> null;
        };
    }

    private static String monthName(int month) {
        var name = TemporalExpressions.MONTH_NAMES.get(month - 1);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
