package memory;

import memory.ontology.EdtfDate;
import memory.ontology.EdtfInterval;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dates in memory text, found and normalized by code (JCLAW-1361): a model never emits a date. Two entry points share
 * one month lexicon — {@link #valueKinds}, the capture supersession guard's lower-cased shape reader, and
 * {@link #find}, the case-sensitive graph finder, which turns each span into EDTF readings against an anchor passed
 * in. Neither reads a clock. Also holds the time-word set the candidate generator splits on, and the ending, negation
 * and valence lexicons.
 */
public final class TemporalExpressions {

    /** Full month names, lower case, January first. */
    public static final List<String> MONTH_NAMES = List.of("january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december");

    /** Abbreviations as regex fragments; May has none, and {@code sept?} covers Sep and Sept. */
    private static final List<String> MONTH_ABBREVIATIONS = List.of("jan", "feb", "mar", "apr", "jun", "jul", "aug",
            "sept?", "oct", "nov", "dec");

    private static final String MONTHS =
            String.join("|", MONTH_NAMES) + "|" + String.join("|", MONTH_ABBREVIATIONS);

    /** The graph finder's month alternation: capitalized only, so "you may march on" names no month. */
    private static final String MONTHS_CAPITALIZED = String.join("|",
            java.util.stream.Stream.concat(MONTH_NAMES.stream(), MONTH_ABBREVIATIONS.stream())
                    .map(m -> Character.toUpperCase(m.charAt(0)) + m.substring(1)).toList());

    /** A bare month stands alone only by its full name: "Jan" is as often a person. */
    private static final String FULL_MONTHS_CAPITALIZED = String.join("|",
            MONTH_NAMES.stream().map(m -> Character.toUpperCase(m.charAt(0)) + m.substring(1)).toList());

    /**
     * Weekday and month names, their abbreviations, and the relative day and period words: a capitalized run never
     * stands on one of these alone.
     */
    public static final Set<String> TIME_WORDS = orderedSet("monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "mondays", "tuesdays", "wednesdays", "thursdays", "fridays", "saturdays", "sundays",
            "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun", "january", "february", "march",
            "april", "may", "june", "july", "august", "september", "october", "november", "december", "jan", "feb",
            "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "today", "tomorrow", "yesterday",
            "tonight", "morning", "afternoon", "evening", "night", "weekend", "week", "month", "year", "mornings",
            "afternoons", "evenings", "nights", "weekends", "weeks", "months", "years");

    private TemporalExpressions() {}

    // ─── Capture guard ───────────────────────────────────────────────────────

    /** A value-bearing shape and the kind of value it pins. */
    private record ValueShape(Pattern pattern, String kind) {}

    /**
     * Ordered most specific first. {@link #valueKinds} strips each shape's matches before
     * trying the next, so "7:00 pm" is read as a clock and not additionally as the bare
     * numbers 7 and 0 — without that, any text holding a time would also count as carrying
     * a quantity and the guard would wave through a replacement that dropped one.
     */
    private static final List<ValueShape> VALUE_SHAPES = List.of(
            new ValueShape(Pattern.compile(
                    "\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)?|\\b\\d{1,2}\\s*(?:am|pm)\\b"), "clock"),
            // Possessive throughout: the two \s around the optional hyphen are an overlapping
            // pair, so trailing spaces backtrack polynomially on conversation text (JCLAW-1048).
            new ValueShape(Pattern.compile(
                    "\\b\\d++\\s*+-?+\\s*+(?:second|minute|hour|day|week|month|year|decade)s?+\\b"), "duration"),
            new ValueShape(Pattern.compile(
                    "\\b(?:" + MONTHS + ")\\b\\s*\\d{0,4}(?:st|nd|rd|th)?"
                            + "|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:" + MONTHS + ")\\b"), "date"),
            new ValueShape(Pattern.compile(
                    "\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b|\\b\\d{1,2}[-/]\\d{1,2}(?:[-/]\\d{2,4})?\\b"), "date"),
            new ValueShape(Pattern.compile("\\b(?:19|20)\\d{2}\\b"), "year"),
            new ValueShape(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), "quantity"));

    /** Which kinds of value a text pins down: clock, duration, date, year, quantity; read lower-cased. */
    public static Set<String> valueKinds(String text) {
        var remaining = text.toLowerCase(Locale.ROOT);
        var kinds = new HashSet<String>();
        for (var shape : VALUE_SHAPES) {
            var m = shape.pattern().matcher(remaining);
            if (!m.find()) continue;
            kinds.add(shape.kind());
            remaining = m.replaceAll(" ");
        }
        return kinds;
    }

    // ─── Graph finder ────────────────────────────────────────────────────────

    /** The unit a found span is stated in. */
    public enum Kind { DAY, WEEK, MONTH, SEASON, QUARTER, YEAR, RANGE, DURATION }

    /** Why a date-like span yields no reading. */
    public enum Reason { WEEKDAY, CLOCK, VAGUE, RECURRING, NUMERIC_DM, FISCAL, EVENT_RELATIVE, LITERAL }

    /**
     * A date at {@code [start, end)}, inside the wider {@code phrase} that carries its preposition. {@code relative}
     * means the readings depend on the anchor. A duration ({@code duration} an ISO 8601 period) reads as its start.
     */
    public record DateSpan(int start, int end, String span, String phrase, Kind kind, boolean relative,
            List<EdtfInterval> readings, @Nullable String duration) {}

    /** A date-like span at {@code [start, end)} that yields no reading. */
    public record Refused(int start, int end, String span, Reason reason) {}

    /** Both lists ordered by position. */
    public record Result(List<DateSpan> found, List<Refused> refused) {}

    /** {@code [start, end)} of a text. */
    public record Range(int start, int end) {}

    private sealed interface Outcome permits Reading, Refusal {}

    private record Reading(Kind kind, boolean relative, List<EdtfInterval> readings, @Nullable String duration)
            implements Outcome {}

    private record Refusal(Reason reason) implements Outcome {}

    @FunctionalInterface
    private interface Normalizer {
        @Nullable Outcome apply(MatchResult m, String text, LocalDate anchor);
    }

    /** A shape; {@code group} is the reported span, the whole match the region it claims. */
    private record Rule(Pattern pattern, int group, Normalizer normalizer) {}

    private record Hit(int start, int end, int spanStart, int spanEnd, int priority, Outcome outcome) {}

    private static final String NUMBER =
            "(\\d{1,2}|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
    private static final String UNIT = "(day|week|month|year|decade)s?";
    private static final String SEASON = "(spring|summer|autumn|fall|winter)";
    private static final String DEICTIC = "(this|last|next|coming)";
    private static final String YEAR = "((?:19|20)\\d{2})";
    private static final String RANGE_END = "(?:(?:" + MONTHS_CAPITALIZED + ")\\s+(?:of\\s+)?)?(?:19|20)\\d{2}";
    /** Words after which a bare month or season names one; "the last June" is refused before this rule sees it. */
    private static final String YEARLESS_OPENERS =
            "in|on|since|until|by|from|before|after|the|early|late|mid|every|each|per";
    private static final String WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday";

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1),
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12));

    /** Words that open a date phrase; the phrase runs from the nearest one before the span. */
    private static final Set<String> PHRASE_OPENERS = orderedSet("in", "on", "at", "by", "since", "until", "from",
            "before", "after", "during", "this", "last", "next", "every", "each", "per", "the", "early", "late", "mid", "for", "between", "through", "throughout", "over",
            "around", "till");
    private static final Set<String> RECURRENCE_WORDS = orderedSet("every", "each", "per");
    private static final int PHRASE_LOOKBACK = 3;
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private static final Pattern FISCAL_CUE = Pattern.compile("(?i:\\bfiscal\\b)|\\bFY(?=\\s?'?\\d)");
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[.;!?](?=\\s|$)");
    private static final Pattern PRECEDING_THE = Pattern.compile("(?i)\\bthe\\s+$");
    private static final String CURRENCY_OR_HASH = "$£€#";
    private static final LocalDate CLAIM_ANCHOR = LocalDate.of(2000, 6, 15);

    private static final List<Rule> RULES = List.of(
            rule("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b", 0, (m, t, a) -> absolute(Kind.DAY, EdtfDate.ofDay(
                    LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3))), false))),
            rule("(?<![\\w/.-])\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?(?![\\w/])"
                    + "|(?<![\\w/.-])\\d{1,2}-\\d{1,2}-\\d{2,4}(?![\\w-])", 0, refuse(Reason.NUMERIC_DM)),
            rule("(?i)\\b\\d{1,2}:\\d{2}(?:\\s*[ap]m\\b)?|\\b\\d{1,2}\\s*[ap]m\\b|\\b(?:noon|midnight)\\b", 0,
                    refuse(Reason.CLOCK)),
            rule("\\b(?i:from)\\s+((" + RANGE_END + ")\\s+(?i:to|until|till|through)\\s+(" + RANGE_END + "))\\b", 1,
                    TemporalExpressions::range),
            rule("\\b(?i:between)\\s+((" + RANGE_END + ")\\s+(?i:and)\\s+(" + RANGE_END + "))\\b", 1,
                    TemporalExpressions::range),
            rule("\\b(((?:19|20)\\d{2})\\s*[\u2013-]\\s*((?:19|20)\\d{2}))\\b", 1, TemporalExpressions::range),
            rule("\\b(\\d{1,2})\\s*[\u2013-]\\s*(\\d{1,2})\\s+(" + MONTHS_CAPITALIZED + ")\\.?,?\\s+" + YEAR + "\\b", 0,
                    TemporalExpressions::dayRange),
            rule("\\b(" + MONTHS_CAPITALIZED + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,\\s*|\\s+)" + YEAR + "\\b", 0,
                    (m, t, a) -> absolute(Kind.DAY, EdtfDate.ofDay(LocalDate.of(Integer.parseInt(m.group(3)),
                            month(m.group(1)), Integer.parseInt(m.group(2))), false))),
            rule("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + MONTHS_CAPITALIZED + ")\\.?,?\\s+" + YEAR + "\\b",
                    0, (m, t, a) -> absolute(Kind.DAY, EdtfDate.ofDay(LocalDate.of(Integer.parseInt(m.group(3)),
                            month(m.group(2)), Integer.parseInt(m.group(1))), false))),
            rule("\\b(" + MONTHS_CAPITALIZED + ")\\s+(?:of\\s+)?" + YEAR + "\\b", 0, (m, t, a) -> absolute(
                    Kind.MONTH, EdtfDate.ofMonth(YearMonth.of(Integer.parseInt(m.group(2)), month(m.group(1))),
                            false))),
            rule("\\b(" + MONTHS_CAPITALIZED + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?![:\\d])", 0,
                    (m, t, a) -> yearless(Kind.DAY, a, y -> EdtfDate.ofDay(
                            LocalDate.of(y, month(m.group(1)), Integer.parseInt(m.group(2))), false))),
            rule("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + MONTHS_CAPITALIZED + ")\\b", 0,
                    (m, t, a) -> yearless(Kind.DAY, a, y -> EdtfDate.ofDay(
                            LocalDate.of(y, month(m.group(2)), Integer.parseInt(m.group(1))), false))),
            rule("\\b(?i:" + DEICTIC + ")\\s+(" + MONTHS_CAPITALIZED + ")\\b", 0, TemporalExpressions::deicticMonth),
            rule("(?<=\\b(?i:" + YEARLESS_OPENERS + ")[\\s-]{1,3})(" + FULL_MONTHS_CAPITALIZED + ")\\b", 0,
                    (m, t, a) -> yearless(Kind.MONTH, a, y -> EdtfDate.ofMonth(YearMonth.of(y, month(m.group(1))),
                            false))),
            rule("(?i)\\b" + SEASON + "\\s+(?:of\\s+)?" + YEAR + "\\b", 0, (m, t, a) -> absolute(Kind.SEASON,
                    EdtfDate.ofSeason(Integer.parseInt(m.group(2)), season(m.group(1))))),
            rule("(?i)\\b" + DEICTIC + "\\s+" + SEASON + "\\b", 0, TemporalExpressions::deicticSeason),
            rule("(?i)(?<=\\b(?:" + YEARLESS_OPENERS + "|during|over|through|throughout)[\\s-]{1,3})"
                    + "(spring|summer|autumn|winter)\\b(?!\\s+(?:of\\s+)?(?:19|20)\\d{2}\\b)", 0,
                    (m, t, a) -> yearless(Kind.SEASON, a, y -> EdtfDate.ofSeason(y, season(m.group(1))))),
            rule("\\bQ([1-4])\\s+(?:of\\s+)?" + YEAR + "\\b", 0, (m, t, a) -> absolute(Kind.QUARTER,
                    EdtfDate.ofQuarter(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))))),
            rule("(?i)\\b(?:the\\s+)?(first|second|third|fourth|1st|2nd|3rd|4th)\\s+quarter\\s+of\\s+" + YEAR + "\\b",
                    0, (m, t, a) -> absolute(Kind.QUARTER, EdtfDate.ofQuarter(Integer.parseInt(m.group(2)),
                            ordinal(m.group(1))))),
            rule("\\bQ([1-4])\\b", 0, (m, t, a) -> relative(Kind.QUARTER,
                    EdtfDate.ofQuarter(a.getYear(), Integer.parseInt(m.group(1))))),
            rule("(?i)\\b" + DEICTIC + "\\s+(week|month|quarter|year)\\b", 0, TemporalExpressions::deicticUnit),
            rule("(?i)\\bthe\\s+day\\s+(?:before\\s+yesterday|after\\s+tomorrow)\\b"
                    + "|\\b(today|tomorrow|yesterday|tonight)\\b|\\blast\\s+night\\b"
                    + "|\\bthis\\s+(?:morning|afternoon|evening)\\b", 0, TemporalExpressions::dayWord),
            rule("(?i)\\b" + NUMBER + "\\s+" + UNIT + "\\s+ago\\b", 0, (m, t, a) -> offset(m, a, -1)),
            rule("(?i)\\bin\\s+" + NUMBER + "\\s+" + UNIT + "\\b", 0, (m, t, a) -> offset(m, a, 1)),
            rule("(?i)\\bfor\\s+(?:the\\s+(?:past|last)\\s+)?(" + NUMBER + "\\s+" + UNIT + ")\\b", 1,
                    TemporalExpressions::duration),
            rule("(?<!\\d)" + YEAR + "(?!\\d)", 0, TemporalExpressions::year),
            rule("(?i)\\b(?:" + NUMBER + "|the|a few|several)\\s+(?:day|week|month|year|night|morning)s?\\s+"
                    + "(?:before|after|prior\\s+to)\\b(?!\\s+(?:tomorrow|yesterday|today)\\b)", 0,
                    refuse(Reason.EVENT_RELATIVE)),
            rule("(?i)\\b(?:mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays|weekends|mornings"
                    + "|afternoons|evenings|nights|springs|summers|autumns|winters)\\b", 0, refuse(Reason.RECURRING)),
            rule("\\b(?:Januarys|Februarys|Marches|Aprils|Junes|Julys|Augusts|Septembers|Octobers|Novembers"
                    + "|Decembers)\\b", 0, refuse(Reason.RECURRING)),
            rule("(?i)\\b(?:every|each|per)\\s+(?:other\\s+)?(?:" + NUMBER + "\\s+)?(?:day|week|month|quarter|year"
                    + "|decade|morning|afternoon|evening|night|weekend|weekday|spring|summer|autumn|fall|winter)s?\\b",
                    0, refuse(Reason.RECURRING)),
            rule("(?i)\\b(?:" + WEEKDAYS + "|weekend)\\b", 0, refuse(Reason.WEEKDAY)),
            rule("\\bFY\\s?'?\\d{2,4}\\b|(?i:\\bfiscal\\s+(?:year\\s+)?(?:19|20)\\d{2}\\b)", 0, refuse(Reason.FISCAL)),
            rule("(?i)\\b(?:recently|lately|soon|someday|nowadays|eventually|(?:some|one)\\s+day|a\\s+while\\s+"
                    + "(?:ago|back)|(?:a\\s+few|a\\s+couple\\s+of|several|many|some|few)\\s+" + UNIT + "\\s+ago"
                    + "|(?:days|weeks|months|years|decades)\\s+ago|the\\s+other\\s+day|back\\s+then|long\\s+ago"
                    + "|in\\s+the\\s+(?:past|future)|these\\s+days|at\\s+some\\s+point|for\\s+(?:days|weeks|months"
                    + "|years|decades)|(?:the\\s+)?(?:early\\s+|mid\\s+|late\\s+)?(?:19|20)?\\d0s)\\b", 0,
                    refuse(Reason.VAGUE)));

    /**
     * The date spans in {@code text}, normalized against {@code anchor}, and the date-like spans refused. Overlapping
     * shapes resolve leftmost-longest. A span whose phrase holds every, each or per is recurring; a quarter or year in
     * a clause that mentions fiscal or FY is fiscal; any shape touching a URL, path, file name or ticket key is a
     * literal.
     */
    public static Result find(String text, LocalDate anchor) {
        var literals = LiteralSpans.spans(text);
        var hits = new ArrayList<Hit>();
        for (int p = 0; p < RULES.size(); p++) {
            var rule = RULES.get(p);
            var m = rule.pattern().matcher(text);
            while (m.find()) {
                if (m.group(rule.group()) == null) continue;
                @Nullable Outcome outcome;
                if (touchesLiteral(literals, m.start(), m.end())) {
                    outcome = new Refusal(Reason.LITERAL);
                } else {
                    try {
                        outcome = rule.normalizer().apply(m.toMatchResult(), text, anchor);
                    } catch (IllegalArgumentException | java.time.DateTimeException e) {
                        outcome = null;
                    }
                }
                if (outcome != null) {
                    hits.add(new Hit(m.start(), m.end(), m.start(rule.group()), m.end(rule.group()), p, outcome));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::start).thenComparingInt(h -> h.start() - h.end())
                .thenComparingInt(Hit::priority));
        var chosen = new ArrayList<Hit>();
        for (var h : hits) {
            if (chosen.stream().allMatch(c -> h.end() <= c.start() || h.start() >= c.end())) chosen.add(h);
        }
        chosen.sort(Comparator.comparingInt(Hit::spanStart));

        var found = new ArrayList<DateSpan>();
        var refused = new ArrayList<Refused>();
        for (var h : chosen) {
            var span = text.substring(h.spanStart(), h.spanEnd());
            int phraseStart = phraseStart(text, h.start());
            var phrase = text.substring(phraseStart, Math.max(h.end(), h.spanEnd()));
            boolean recurring = recurring(text, phraseStart, Math.max(h.end(), h.spanEnd()));
            switch (h.outcome()) {
                case Refusal r -> refused.add(new Refused(h.spanStart(), h.spanEnd(), span,
                        recurring && r.reason() == Reason.WEEKDAY ? Reason.RECURRING : r.reason()));
                case Reading ignored when recurring ->
                        refused.add(new Refused(h.spanStart(), h.spanEnd(), span, Reason.RECURRING));
                case Reading r when (r.kind() == Kind.QUARTER || r.kind() == Kind.YEAR)
                        && FISCAL_CUE.matcher(clause(text, h.start())).find() ->
                        refused.add(new Refused(h.spanStart(), h.spanEnd(), span, Reason.FISCAL));
                case Reading r -> found.add(new DateSpan(h.spanStart(), h.spanEnd(), span, phrase, r.kind(),
                        r.relative(), r.readings(), r.duration()));
            }
        }
        return new Result(List.copyOf(found), List.copyOf(refused));
    }

    /** The offsets {@link #find} reports as found; offsets never depend on the anchor, so any fixed one serves. */
    public static List<Range> claimedSpans(String text) {
        return find(text, CLAIM_ANCHOR).found().stream().map(d -> new Range(d.start(), d.end())).toList();
    }

    private static Rule rule(String regex, int group, Normalizer normalizer) {
        return new Rule(Pattern.compile(regex), group, normalizer);
    }

    private static Normalizer refuse(Reason reason) {
        var refusal = new Refusal(reason);
        return (m, t, a) -> refusal;
    }

    private static Reading absolute(Kind kind, EdtfDate date) {
        return new Reading(kind, false, List.of(EdtfInterval.of(date)), null);
    }

    private static Reading relative(Kind kind, EdtfDate date) {
        return new Reading(kind, true, List.of(EdtfInterval.of(date)), null);
    }

    private static boolean touchesLiteral(List<LiteralSpans.Span> literals, int start, int end) {
        return literals.stream().anyMatch(l -> start < l.end() && l.start() < end);
    }

    private static @Nullable Outcome range(MatchResult m, String text, LocalDate anchor) {
        if (afterCurrencyOrHash(text, m.start(1))) return new Refusal(Reason.LITERAL);
        var from = rangeEnd(m.group(2));
        var to = rangeEnd(m.group(3));
        var interval = EdtfInterval.between(new EdtfInterval.Point(from), new EdtfInterval.Point(to));
        return new Reading(Kind.RANGE, false, List.of(interval), null);
    }

    private static Outcome dayRange(MatchResult m, String text, LocalDate anchor) {
        var month = YearMonth.of(Integer.parseInt(m.group(4)), month(m.group(3)));
        var from = EdtfDate.ofDay(month.atDay(Integer.parseInt(m.group(1))), false);
        var to = EdtfDate.ofDay(month.atDay(Integer.parseInt(m.group(2))), false);
        var interval = EdtfInterval.between(new EdtfInterval.Point(from), new EdtfInterval.Point(to));
        return new Reading(Kind.RANGE, false, List.of(interval), null);
    }

    /**
     * A date stated without its year: the latest instance begun by the anchor and, unless that one still holds the
     * anchor, the earliest after it. A year with no such date (February 29) is skipped.
     */
    private static Outcome yearless(Kind kind, LocalDate anchor, IntFunction<EdtfDate> inYear) {
        @Nullable EdtfDate before = null;
        for (int y = anchor.getYear() + 1; before == null && y >= anchor.getYear() - 8; y--) {
            var date = tryYear(inYear, y);
            if (date != null && !date.lo().isAfter(anchor)) before = date;
        }
        if (before == null) throw new IllegalArgumentException("no such date");
        if (before.hi().isAfter(anchor)) return relative(kind, before);
        @Nullable EdtfDate after = null;
        for (int y = before.year() + 1; after == null && y <= before.year() + 8; y++) after = tryYear(inYear, y);
        if (after == null) throw new IllegalArgumentException("no such date");
        return new Reading(kind, true, List.of(EdtfInterval.of(before), EdtfInterval.of(after)), null);
    }

    private static @Nullable EdtfDate tryYear(IntFunction<EdtfDate> inYear, int year) {
        try {
            return inYear.apply(year);
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            return null;
        }
    }

    private static EdtfDate rangeEnd(String side) {
        var parts = side.trim().split("\\s+");
        int year = Integer.parseInt(parts[parts.length - 1]);
        return parts.length == 1 ? EdtfDate.ofYear(year, false)
                : EdtfDate.ofMonth(YearMonth.of(year, month(parts[0])), false);
    }

    private static @Nullable Outcome year(MatchResult m, String text, LocalDate anchor) {
        if (afterCurrencyOrHash(text, m.start())) return new Refusal(Reason.LITERAL);
        if (joined(text, m.start(), m.end())) return null;
        return absolute(Kind.YEAR, EdtfDate.ofYear(Integer.parseInt(m.group(1)), false));
    }

    /** Touching a letter, '-', '_' or a '.' inside a token: a version, identifier or decimal, never a year. */
    private static boolean joined(String text, int start, int end) {
        if (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetter(c) || c == '-' || c == '_' || c == '.') return true;
        }
        if (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isLetter(c) || c == '-' || c == '_') return true;
            if (c == '.' && end + 1 < text.length() && Character.isLetterOrDigit(text.charAt(end + 1))) return true;
        }
        return false;
    }

    private static boolean afterCurrencyOrHash(String text, int start) {
        return start > 0 && CURRENCY_OR_HASH.indexOf(text.charAt(start - 1)) >= 0;
    }

    /** "the last June" and "the next week" are ordinals, not deixis: refused so no shorter shape claims them. */
    private static boolean afterThe(String text, int start) {
        return PRECEDING_THE.matcher(text.substring(Math.max(0, start - 8), start)).find();
    }

    private static @Nullable Outcome deicticMonth(MatchResult m, String text, LocalDate anchor) {
        if (afterThe(text, m.start())) return new Refusal(Reason.VAGUE);
        var which = m.group(1).toLowerCase(Locale.ROOT);
        int month = month(m.group(2));
        int year = anchor.getYear();
        if (which.equals("last")) {
            year = month < anchor.getMonthValue() ? year : year - 1;
        } else if (which.equals("next") || which.equals("coming")) {
            year = month > anchor.getMonthValue() ? year : year + 1;
        }
        return relative(Kind.MONTH, EdtfDate.ofMonth(YearMonth.of(year, month), false));
    }

    private static @Nullable Outcome deicticSeason(MatchResult m, String text, LocalDate anchor) {
        if (afterThe(text, m.start())) return new Refusal(Reason.VAGUE);
        return relative(Kind.SEASON, seasonInstance(m.group(1).toLowerCase(Locale.ROOT), season(m.group(2)), anchor));
    }

    /**
     * "this" is the instance of the anchor's year, except that a winter begun last December is still this winter;
     * "last" is the latest that ended by the anchor, "next" the earliest that starts after it.
     */
    private static EdtfDate seasonInstance(String which, int season, LocalDate anchor) {
        int year = anchor.getYear();
        return switch (which) {
            case "last" -> {
                int y = year + 1;
                while (EdtfDate.ofSeason(y, season).hi().isAfter(anchor)) y--;
                yield EdtfDate.ofSeason(y, season);
            }
            case "next", "coming" -> {
                int y = year - 1;
                while (!EdtfDate.ofSeason(y, season).lo().isAfter(anchor)) y++;
                yield EdtfDate.ofSeason(y, season);
            }
            default -> EdtfDate.ofSeason(season == 4 && anchor.getMonthValue() <= 2 ? year - 1 : year, season);
        };
    }

    private static @Nullable Outcome deicticUnit(MatchResult m, String text, LocalDate anchor) {
        if (afterThe(text, m.start())) return new Refusal(Reason.VAGUE);
        var which = m.group(1).toLowerCase(Locale.ROOT);
        int step = switch (which) {
            case "last" -> -1;
            case "next", "coming" -> 1;
            default -> 0;
        };
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "week" -> relative(Kind.WEEK, EdtfDate.ofDay(anchor.plusWeeks(step), true));
            case "month" -> relative(Kind.MONTH, EdtfDate.ofMonth(YearMonth.from(anchor).plusMonths(step), false));
            case "quarter" -> {
                var first = YearMonth.of(anchor.getYear(), 3 * ((anchor.getMonthValue() - 1) / 3) + 1)
                        .plusMonths(3L * step);
                yield relative(Kind.QUARTER, EdtfDate.ofQuarter(first.getYear(), (first.getMonthValue() + 2) / 3));
            }
            default -> relative(Kind.YEAR, EdtfDate.ofYear(anchor.getYear() + step, false));
        };
    }

    private static Outcome dayWord(MatchResult m, String text, LocalDate anchor) {
        var word = m.group().toLowerCase(Locale.ROOT);
        var day = word.startsWith("the") ? anchor.plusDays(word.endsWith("yesterday") ? -2 : 2)
                : word.equals("tomorrow") ? anchor.plusDays(1)
                : word.equals("yesterday") || word.startsWith("last") ? anchor.minusDays(1) : anchor;
        return relative(Kind.DAY, EdtfDate.ofDay(day, false));
    }

    /** "N units ago" ({@code sign} -1) or "in N units" (+1): a year, month or week back is approximate, a day exact. */
    private static Outcome offset(MatchResult m, LocalDate anchor, int sign) {
        int n = number(m.group(1));
        var unit = m.group(2).toLowerCase(Locale.ROOT);
        return new Reading(kindOf(unit), true, List.of(EdtfInterval.of(shift(anchor, unit, sign * n))), null);
    }

    private static Outcome duration(MatchResult m, String text, LocalDate anchor) {
        int n = number(m.group(2));
        var unit = m.group(3).toLowerCase(Locale.ROOT);
        var iso = switch (unit) {
            case "decade" -> "P" + 10 * n + "Y";
            case "year" -> "P" + n + "Y";
            case "month" -> "P" + n + "M";
            case "week" -> "P" + n + "W";
            default -> "P" + n + "D";
        };
        return new Reading(Kind.DURATION, true, List.of(EdtfInterval.of(shift(anchor, unit, -n))), iso);
    }

    private static Kind kindOf(String unit) {
        return switch (unit) {
            case "decade", "year" -> Kind.YEAR;
            case "month" -> Kind.MONTH;
            case "week" -> Kind.WEEK;
            default -> Kind.DAY;
        };
    }

    private static EdtfDate shift(LocalDate anchor, String unit, int n) {
        return switch (unit) {
            case "decade" -> EdtfDate.ofYear(anchor.getYear() + 10 * n, true);
            case "year" -> EdtfDate.ofYear(anchor.getYear() + n, true);
            case "month" -> EdtfDate.ofMonth(YearMonth.from(anchor).plusMonths(n), true);
            case "week" -> EdtfDate.ofDay(anchor.plusWeeks(n), true);
            default -> EdtfDate.ofDay(anchor.plusDays(n), false);
        };
    }

    private static int number(String word) {
        var lower = word.toLowerCase(Locale.ROOT);
        var n = NUMBER_WORDS.get(lower);
        return n != null ? n : Integer.parseInt(lower);
    }

    /** 1–12 from a full name or an abbreviation, in any case. */
    private static int month(String name) {
        var prefix = name.toLowerCase(Locale.ROOT).substring(0, 3);
        for (int i = 0; i < MONTH_NAMES.size(); i++) {
            if (MONTH_NAMES.get(i).startsWith(prefix)) return i + 1;
        }
        throw new IllegalArgumentException("not a month: " + name);
    }

    /** 1 spring … 4 winter. */
    private static int season(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "spring" -> 1;
            case "summer" -> 2;
            case "autumn", "fall" -> 3;
            default -> 4;
        };
    }

    private static int ordinal(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "first", "1st" -> 1;
            case "second", "2nd" -> 2;
            case "third", "3rd" -> 3;
            default -> 4;
        };
    }

    /**
     * The nearest phrase opener among the few words before {@code start}, crossing no punctuation; {@code start} itself
     * when the match opens with one or none is found.
     */
    private static int phraseStart(String text, int start) {
        if (PHRASE_OPENERS.contains(wordAt(text, start))) return start;
        int i = start;
        for (int words = 0; words < PHRASE_LOOKBACK; words++) {
            int e = i;
            while (e > 0 && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (e == i) return start;
            int s = e;
            while (s > 0 && Character.isLetter(text.charAt(s - 1))) s--;
            if (s == e) return start;
            if (PHRASE_OPENERS.contains(text.substring(s, e).toLowerCase(Locale.ROOT))) return s;
            i = s;
        }
        return start;
    }

    private static String wordAt(String text, int start) {
        int e = start;
        while (e < text.length() && Character.isLetter(text.charAt(e))) e++;
        return text.substring(start, e).toLowerCase(Locale.ROOT);
    }

    /** Whether {@code text[start, end)} holds a recurrence word; "as per" cites, it does not recur. */
    private static boolean recurring(String text, int start, int end) {
        var m = WORD.matcher(text).region(start, end);
        @Nullable String previous = previousWord(text, start);
        while (m.find()) {
            var word = m.group().toLowerCase(Locale.ROOT);
            if (RECURRENCE_WORDS.contains(word) && !(word.equals("per") && "as".equals(previous))) return true;
            previous = word;
        }
        return false;
    }

    private static @Nullable String previousWord(String text, int start) {
        int e = start;
        while (e > 0 && Character.isWhitespace(text.charAt(e - 1))) e--;
        int s = e;
        while (s > 0 && Character.isLetter(text.charAt(s - 1))) s--;
        return s == e ? null : text.substring(s, e).toLowerCase(Locale.ROOT);
    }

    private static String clause(String text, int at) {
        int start = 0;
        int end = text.length();
        var m = CLAUSE_BREAK.matcher(text);
        while (m.find()) {
            if (m.end() <= at) {
                start = m.end();
            } else {
                end = m.start();
                break;
            }
        }
        return text.substring(start, Math.max(start, end));
    }

    // ─── Endings, negation, valence ─────────────────────────────────────────

    private static final String NEG = "(?:\\w+n['\u2019]t|not)";

    /** A state that held and stopped; read before negation, so "hasn't used X since 2024" negates nothing. */
    public static final List<Pattern> ENDING_PATTERNS = List.of(
            Pattern.compile("(?i)\\bno\\s+longer\\b"),
            Pattern.compile("(?i)\\bno\\s+more\\b"),
            Pattern.compile("(?i)\\b" + NEG + "\\b[^.;!?]*?\\b(?:anymore|any\\s+more|any\\s+longer)\\b"),
            Pattern.compile("(?i)\\b(?:has|have|had)(?:n['\u2019]t|\\s+not)\\b[^.;!?]*?\\bsince\\b"),
            Pattern.compile("(?i)\\bnot\\s+since\\b"),
            Pattern.compile("(?i)\\b(?:stopped|quit|gave\\s+up)\\b"));

    /** Negation words; any word ending in n't is a cue too. */
    public static final List<String> NEGATION_CUES = List.of("not", "n't", "never", "no", "none", "neither", "nor",
            "without", "refuses to", "refused to", "instead of", "rather than");

    /** A perfect-tense never: the negation holds from the start of time up to the anchor. */
    public static final List<String> PERFECT_NEVER =
            List.of("has never", "have never", "had never", "'s never", "'ve never", "'d never");

    private static final Pattern NEGATION = Pattern.compile("(?i)" + NEGATION_CUES.stream()
            .map(c -> (c.startsWith("n'") ? "\\b\\w+" : "\\b") + lexiconRegex(c) + "\\b")
            .collect(Collectors.joining("|")));

    private static final Pattern PERFECT_NEVER_PATTERN = Pattern.compile("(?i)" + PERFECT_NEVER.stream()
            .map(p -> (Character.isLetter(p.charAt(0)) ? "\\b" : "") + lexiconRegex(p) + "\\b")
            .collect(Collectors.joining("|")));

    /** A lexicon entry as a regex: an apostrophe matches ' or \u2019, a space any run of whitespace. */
    private static String lexiconRegex(String entry) {
        return entry.replace("'", "['\u2019]").replace(" ", "\\s+");
    }

    /** A valence frame; {@code ending} marks one that says the stance stopped. */
    public record Frame(Pattern pattern, boolean ending) {}

    public enum Polarity { FAVORABLE, UNFAVORABLE }

    /** The frame that matched, at {@code [start, end)}. */
    public record Valence(Polarity polarity, boolean ending, int start, int end) {}

    public static final List<Frame> FAVORABLE_FRAMES = List.of(
            new Frame(Pattern.compile("(?i)\\b" + NEG + "\\s+like\\b[^.;!?]*?\\b(?:anymore|any\\s+more|any\\s+longer)\\b"),
                    true),
            new Frame(Pattern.compile("(?i)\\bno\\s+longer\\s+likes\\b"), true),
            new Frame(Pattern.compile("(?i)\\b(?:prefers|likes|loves|enjoys|is\\s+interested\\s+in)\\b"), false));

    public static final List<Frame> UNFAVORABLE_FRAMES = List.of(
            new Frame(Pattern.compile("(?i)\\b(?:dislikes|hates|can['\u2019]t\\s+stand|cannot\\s+stand|never\\s+liked)\\b"),
                    false),
            new Frame(Pattern.compile("(?i)\\b" + NEG + "\\s+like\\b"), false));

    /** The negation cues in {@code text} in order, lower-cased, after every ending pattern's match is removed. */
    public static List<String> negationCues(String text) {
        var remaining = text;
        for (var ending : ENDING_PATTERNS) remaining = ending.matcher(remaining).replaceAll(" ");
        var cues = new ArrayList<String>();
        var m = NEGATION.matcher(remaining);
        while (m.find()) cues.add(m.group().toLowerCase(Locale.ROOT));
        return List.copyOf(cues);
    }

    /** A negation cue at {@code [start, end)}; {@code perfectNever} when it is the never of "has never" or kin. */
    public record NegationCue(int start, int end, boolean perfectNever) {}

    /**
     * The negation cues in {@code text} in order, at their offsets in {@code text}: ending matches are blanked first,
     * so a cue inside one is never returned.
     */
    public static List<NegationCue> negationCueRanges(String text) {
        var blanked = new StringBuilder(text);
        for (var ending : ENDING_PATTERNS) {
            var m = ending.matcher(blanked);
            while (m.find()) {
                for (int i = m.start(); i < m.end(); i++) blanked.setCharAt(i, ' ');
            }
        }
        var perfectEnds = new HashSet<Integer>();
        var p = PERFECT_NEVER_PATTERN.matcher(blanked);
        while (p.find()) perfectEnds.add(p.end());
        var cues = new ArrayList<NegationCue>();
        var m = NEGATION.matcher(blanked);
        while (m.find()) cues.add(new NegationCue(m.start(), m.end(), perfectEnds.contains(m.end())));
        return List.copyOf(cues);
    }

    /** Whether {@code text} holds a perfect-tense never ("has never", "'ve never"). */
    public static boolean perfectNever(String text) {
        return PERFECT_NEVER_PATTERN.matcher(text).find();
    }

    /**
     * The first frame that matches: favorable endings, then unfavorable stances, then favorable stances — so "doesn't
     * like X anymore" is a favorable ending, never an unfavorable stance.
     */
    public static Optional<Valence> valence(String text) {
        return firstFrame(text, FAVORABLE_FRAMES, Polarity.FAVORABLE, true)
                .or(() -> firstFrame(text, UNFAVORABLE_FRAMES, Polarity.UNFAVORABLE, false))
                .or(() -> firstFrame(text, FAVORABLE_FRAMES, Polarity.FAVORABLE, false));
    }

    private static Optional<Valence> firstFrame(String text, List<Frame> frames, Polarity polarity, boolean ending) {
        for (var frame : frames) {
            if (frame.ending() != ending) continue;
            var m = frame.pattern().matcher(text);
            if (m.find()) return Optional.of(new Valence(polarity, ending, m.start(), m.end()));
        }
        return Optional.empty();
    }

    // ─── Probes ──────────────────────────────────────────────────────────────

    /** Anchors the normalizer probes render at: a month end, a year end and a leap day. */
    public static final List<LocalDate> NORMALIZER_PROBE_ANCHORS =
            List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 31), LocalDate.of(2028, 2, 29));

    /** One phrase per normalizer row. */
    public static final List<String> NORMALIZER_PROBES = List.of("today", "tomorrow", "yesterday", "tonight",
            "last night", "this morning", "this week", "last week", "next week", "this month", "last month",
            "next month", "this year", "last year", "next year", "this quarter", "last quarter", "next quarter", "Q3",
            "Q3 2027", "the third quarter of 2027", "this June", "last June", "next June", "in June", "June 5",
            "5 June", "12 December", "June 2026", "June 5, 2026", "5 June 2026", "2026-02-15", "this spring", "this winter",
            "last summer", "next winter", "in the spring", "autumn 2025", "fall of 2024", "3 years ago", "a year ago",
            "two months ago", "3 weeks ago", "10 days ago", "a decade ago", "in 2 years", "in three days",
            "for three years", "for 6 months", "for two weeks", "for 10 days", "in 2019", "since 2019",
            "from 2019 to 2021", "12\u201314 June 2027", "between March 2020 and June 2021", "2019-2021");

    /** Sentences covering every finder rule, refusal and guard. */
    public static final List<String> FINDER_PROBES = List.of(
            "Avery Lin has a standing call with Mateo Castillo every Sunday evening to plan the Thornbury Marathon"
                    + " in April.",
            "Is renewing the Larkspur Inn booking for the Marrow Bay Retreat, which Harborlight Analytics is holding"
                    + " there this June.",
            "Jonah Pell coaches juniors at the Ashgrove Rowing Club every Thursday evening and is training for the"
                    + " Thornbury Marathon this spring.",
            "Avery Lin says Ashgrove includes Larchmere House, their home since 2019, and the Juniper Clinic branch"
                    + " nearby.",
            "Avery Lin mentioned that Vela Design hired Felix Amari last year as its lead illustrator.",
            "Loves bouldering and has climbed there with Tomas Varga for three years.",
            "Avery Lin checks rota changes there on Fridays.",
            "Avery Lin joins the reading circle every other Thursday.",
            "Avery Lin is the on-call lead during the last week of every quarter.",
            "Jonah Pell tracks every late shipment before noon.",
            "The launch slipped to Q3 2027.",
            "The team closed the deal this quarter, ahead of the fiscal plan.",
            "Revenue for FY2026 doubled and Q2 FY27 looks flat.",
            "The invoice is due on 7/10 and the renewal on 12-03-2026.",
            "The gym runs a retreat every June.",
            "They go hiking on Sundays and spend summers in Marrow Bay.",
            "The standup is at 9:30 am on Monday.",
            "She moved here recently and plans to visit soon.",
            "Two weeks before the offsite, Avery booked the inn.",
            "The file report-2019.pdf sits at https://x.example/2020 and /srv/2021, tracked as JCLAW-2022.",
            "The firm was founded in 2019. Its logo (2019) still hangs there.",
            "The budget was $2020 and ticket #2021 closed.",
            "You may march on if you like.",
            "Avery Lin moved to Ashgrove in May 2019.",
            "Avery Lin visited Lisbon on June 5, 2026 and again on 12th of March 2027.",
            "Avery Lin met Dana on March 3.",
            "The user lived in Porto from 2015 to 2019 and in Lisbon between March 2020 and June 2021.",
            "The lease ran 2016\u20132018.",
            "The clinic opened 3 years ago and moved two months ago.",
            "The roof was fixed 10 days ago and inspected yesterday.",
            "The user will retire in 2 years.",
            "Avery Lin has lived in Ashgrove for 6 months.",
            "The house was renovated in the spring and painted last summer.",
            "The user grew up in the 1990s.",
            "The user hasn't used Osprey Dashboard since 2024.",
            "Avery Lin doesn't like Kale anymore.",
            "The user's anniversary is on 2026-02-15.",
            "The board meets each spring and every year in April.",
            "The festival is this winter, next to the Larkspur Inn.",
            "The reunion is the day after the wedding, in autumn 2025.");

    /** Every probe's finder and normalizer output as deterministic text. */
    public static String renderProbes() {
        var out = new StringBuilder();
        var anchor = NORMALIZER_PROBE_ANCHORS.getFirst();
        out.append("FINDER @").append(anchor).append('\n');
        for (var probe : FINDER_PROBES) {
            out.append(probe).append('\n');
            var result = find(probe, anchor);
            for (var d : result.found()) out.append("  + ").append(render(d)).append('\n');
            for (var r : result.refused()) {
                out.append("  - [").append(r.start()).append(',').append(r.end()).append(") \"").append(r.span())
                        .append("\" ").append(r.reason()).append('\n');
            }
        }
        out.append("NORMALIZER");
        for (var a : NORMALIZER_PROBE_ANCHORS) out.append(" | @").append(a);
        out.append('\n');
        for (var probe : NORMALIZER_PROBES) {
            out.append(probe);
            for (var a : NORMALIZER_PROBE_ANCHORS) {
                var result = find(probe, a);
                var cells = new ArrayList<String>();
                for (var d : result.found()) {
                    cells.add(d.readings().stream().map(EdtfInterval::toString).collect(Collectors.joining(","))
                            + (d.duration() != null ? " " + d.duration() : ""));
                }
                for (var r : result.refused()) cells.add(r.reason().toString());
                out.append(" | ").append(cells.isEmpty() ? "-" : String.join("; ", cells));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String render(DateSpan d) {
        return "[" + d.start() + "," + d.end() + ") \"" + d.span() + "\" phrase=\"" + d.phrase() + "\" " + d.kind()
                + (d.relative() ? " relative" : " absolute") + " " + d.readings()
                + (d.duration() != null ? " " + d.duration() : "");
    }

    @SafeVarargs
    private static <T> Set<T> orderedSet(T... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(items)));
    }
}
