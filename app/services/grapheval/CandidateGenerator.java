package services.grapheval;

import memory.LiteralSpans;
import memory.TemporalExpressions;
import memory.ontology.OntologyRecord;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The spans of a memory that may name a term, found by fixed rules rather than a model (JCLAW-1356, JCLAW-1357): the
 * operator when the memory says "the user" or states no subject, capitalized runs less their time words, URLs, file
 * paths, ticket keys, the object of a stated preference or view, both sides of "X is a kind of Y", and the agent's
 * known Term names, the owner's name among them. Deterministic, so a run's candidates never vary between runs or
 * models. Spans may overlap; the decision model settles which one stands.
 */
public final class CandidateGenerator {

    /** A memory opening with one of these has no stated subject, and is about the operator. */
    public static final Set<String> SUBJECTLESS_VERBS = Set.of("Prefers", "Likes", "Uses", "Works", "Lives", "Owns",
            "Has", "Wants", "Needs", "Thinks", "Keeps", "Drives", "Plans", "Dislikes", "Hates", "Loves", "Is", "Was");

    private static final Set<String> DETERMINERS = Set.of("the", "a", "an", "this", "that", "these", "those", "my",
            "our", "their", "his", "her", "its", "every", "each", "some");
    private static final Set<String> CONNECTORS = Set.of("of", "&", "-");
    private static final String LEAD = "([\"'\u201c\u2018";
    private static final String TRAIL = ".,;:!?)\"'\u201d\u2019";

    private static final Pattern THE_USER = Pattern.compile("(?i)\\bthe user\\b");
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    /** Words that may stand between a frame's subject and its verb, beside auxiliaries and negation. */
    public static final List<String> FRAME_ADVERBS = List.of("really", "still", "truly", "genuinely", "actually",
            "absolutely", "also", "just", "always", "definitely", "especially", "particularly", "simply");
    static final Set<String> FRAME_AUXILIARIES = Set.of("does", "do", "did", "is", "was", "has", "have", "had",
            "will", "would", "not", "never");
    static final Pattern ENDING_WORD = Pattern.compile("(?i)(?:anymore|any\\s+more|any\\s+longer)$");
    static final Pattern LIKE = Pattern.compile("(?i)\\blike\\b");
    private static final Pattern WORD = Pattern.compile("[\\w'\u2019]+");
    private static final Pattern PREFERENCE_END = Pattern.compile("[,;:!?]|\\.(?=\\s|$)|\\s(?:over|because|when|than)\\b");
    /** Topic frames: the view in "thinks that X", and both sides of "X is a kind of Y" or "considers X a kind of Y". */
    private static final Pattern VIEW = Pattern.compile("(?i)\\b(?:thinks|believes) that\\s+");
    private static final Pattern KIND_OF = Pattern.compile("(?i)\\s(is|are|as)?\\s*(?:a|an) (?:kind|type|form|sort) of\\s+");
    private static final Pattern CONSIDERS = Pattern.compile("(?i)\\b(?:considers|counts?|regards) ");
    private static final Pattern RELATIVE = Pattern.compile("(?i)(?:which|that|who|it|they|this)\\b");
    private static final int KIND_SUBJECT_WORDS = 4;
    private static final Pattern CLAUSE_END = Pattern.compile("[,;:!?.]\\s");
    private static final Pattern KIND_END = Pattern.compile(
            "[,;:!?]|\\.(?=\\s|$)|\\s(?:and|or|but|which|that|who|so|because|over|than|when)\\b");
    private static final Pattern CLAUSE_START = Pattern.compile(
            "(?i)(?:^|[,;:!?.]\\s|\\bthat\\s|\\bthinks\\s|\\bbelieves\\s|\\bconsiders\\s)");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?][\"'\u201d\u2019)]*$");

    private CandidateGenerator() {}

    /**
     * A span to type, at {@code [start, end)} of the text. An operator candidate ("the user", or implicit) is written
     * as a Person without a question, and an implicit one has no span in the text and is named
     * {@link GraphCases#IMPLICIT_OPERATOR_SPAN}; an owner named in the text is an ordinary candidate. A candidate with
     * no position has offsets -1 and overlaps nothing.
     */
    public record Candidate(String span, boolean operator, boolean implicit, int start, int end,
                            @Nullable PreferenceFrame frame) {
        public Candidate(String span, boolean operator, boolean implicit, int start, int end) {
            this(span, operator, implicit, start, end, null);
        }

        public Candidate(String span, boolean operator, boolean implicit) {
            this(span, operator, implicit, -1, -1);
        }

        public static Candidate of(String span) {
            return new Candidate(span, false, false);
        }

        public boolean overlaps(Candidate other) {
            return start >= 0 && other.start >= 0 && start < other.end && other.start < end;
        }
    }

    /**
     * The stance a preference verb takes towards the candidate it governs, and the span of the candidate stating it:
     * the one directly before the verb, the operator's span in a subject-less memory, else null.
     */
    public record PreferenceFrame(OntologyRecord.Valence valence, @Nullable String subject) {}

    /** Whether {@code text}'s first word is one of {@link #SUBJECTLESS_VERBS}. */
    public static boolean subjectless(String text) {
        var m = TOKEN.matcher(text);
        if (!m.find()) return false;
        var token = m.group();
        int s = 0;
        int e = token.length();
        while (s < e && LEAD.indexOf(token.charAt(s)) >= 0) s++;
        while (e > s && TRAIL.indexOf(token.charAt(e - 1)) >= 0) e--;
        return SUBJECTLESS_VERBS.contains(token.substring(s, e));
    }

    /** {@link #generate(String, Collection)} with no known Term names. */
    public static List<Candidate> generate(String text) {
        return generate(text, List.of());
    }

    /**
     * The candidates in {@code text}: the operator first, then by position, each span once. Each of {@code knownNames}
     * is a candidate wherever it appears as whole words, in any case, even when it is a time word. A recurring span
     * keeps the occurrence that overlaps another candidate, else its first.
     */
    public static List<Candidate> generate(String text, Collection<String> knownNames) {
        var out = new ArrayList<Candidate>();
        var user = THE_USER.matcher(text);
        boolean implicit = false;
        if (user.find()) {
            out.add(new Candidate(user.group(), true, false, user.start(), user.end()));
        } else if (subjectless(text)) {
            implicit = true;
            out.add(new Candidate(GraphCases.IMPLICIT_OPERATOR_SPAN, true, true));
        }

        var found = new ArrayList<int[]>();
        var taken = new ArrayList<int[]>();
        var framed = new ArrayList<Framed>();
        for (var literal : LiteralSpans.spans(text)) {
            var range = new int[] {literal.start(), literal.end()};
            found.add(range);
            if (literal.kind() == LiteralSpans.Kind.URL || literal.kind() == LiteralSpans.Kind.PATH) taken.add(range);
        }
        preferences(text, taken, found, framed);
        objects(text, VIEW, PREFERENCE_END, taken, found);
        kindOf(text, taken, found);
        var known = new ArrayList<int[]>();
        for (var name : knownNames) {
            if (name.isBlank()) continue;
            var m = Pattern.compile("(?i)(?<![\\w])" + Pattern.quote(name.strip()) + "(?![\\w])").matcher(text);
            while (m.find()) known.add(new int[] {m.start(), m.end()});
        }
        capitalizedRuns(text, implicit, found);
        found.removeIf(r -> onlyTime(text.substring(r[0], r[1])));
        var claimed = TemporalExpressions.claimedSpans(text);
        found.removeIf(r -> claimed.stream().anyMatch(c -> c.start() <= r[0] && r[1] <= c.end()));
        found.addAll(known);

        found.sort(Comparator.comparingInt(r -> r[0]));
        var operators = new HashSet<String>();
        out.forEach(c -> operators.add(c.span()));
        var chosen = new LinkedHashMap<String, int[]>();
        for (var r : found) {
            var span = text.substring(r[0], r[1]);
            if (span.isEmpty() || span.equalsIgnoreCase(GraphCases.IMPLICIT_OPERATOR_SPAN) || operators.contains(span)) {
                continue;
            }
            var first = chosen.get(span);
            if (first == null || (!overlapsAnother(text, first, found) && overlapsAnother(text, r, found))) {
                chosen.put(span, r);
            }
        }
        var frames = frames(text, framed, found, out);
        chosen.values().stream().sorted(Comparator.comparingInt(r -> r[0])).forEach(r -> {
            var span = text.substring(r[0], r[1]);
            out.add(new Candidate(span, false, false, r[0], r[1], frames.get(span)));
        });
        return List.copyOf(out);
    }

    /** A preference object at {@code [start, end)} and the frame match {@code [verbStart, verbEnd)} governing it. */
    private record Framed(int start, int end, int verbStart, int verbEnd, OntologyRecord.Valence valence) {}

    /**
     * The object of every valence frame, read in {@link TemporalExpressions#valence}'s order -- favorable endings,
     * unfavorable stances, favorable stances -- so a match inside an earlier one is the same frame and is skipped.
     */
    private static void preferences(String text, List<int[]> taken, List<int[]> found, List<Framed> framed) {
        var claimed = new ArrayList<int[]>();
        frameObjects(text, TemporalExpressions.FAVORABLE_FRAMES, true, OntologyRecord.Valence.FAVORABLE, claimed, taken,
                found, framed);
        frameObjects(text, TemporalExpressions.UNFAVORABLE_FRAMES, false, OntologyRecord.Valence.UNFAVORABLE, claimed,
                taken, found, framed);
        frameObjects(text, TemporalExpressions.FAVORABLE_FRAMES, false, OntologyRecord.Valence.FAVORABLE, claimed, taken,
                found, framed);
    }

    private static void frameObjects(String text, List<TemporalExpressions.Frame> frames, boolean ending,
                                     OntologyRecord.Valence valence, List<int[]> claimed, List<int[]> taken,
                                     List<int[]> found, List<Framed> framed) {
        for (var frame : frames) {
            if (frame.ending() != ending) continue;
            var m = frame.pattern().matcher(text);
            while (m.find()) {
                int vs = m.start();
                int ve = m.end();
                if (claimed.stream().anyMatch(r -> vs < r[1] && r[0] < ve)) continue;
                claimed.add(new int[] {vs, ve});
                int s = ve;
                int limit = text.length();
                var endingWord = ENDING_WORD.matcher(m.group());
                if (endingWord.find()) {
                    // "doesn't like X anymore": the object sits between the verb and the ending word.
                    var like = LIKE.matcher(text).region(vs, ve);
                    if (!like.find()) continue;
                    s = like.end();
                    limit = vs + endingWord.start();
                }
                while (s < limit && Character.isWhitespace(text.charAt(s))) s++;
                var stop = PREFERENCE_END.matcher(text).region(s, limit);
                int e = trimSpace(text, s, stop.find() ? stop.start() : limit);
                if (e > s && free(taken, s, e)) {
                    found.add(new int[] {s, e});
                    framed.add(new Framed(s, e, vs, ve, valence));
                }
            }
        }
    }

    /** Each preference object's frame by span; a span framed twice keeps the frame of the verb that comes first. */
    private static Map<String, PreferenceFrame> frames(String text, List<Framed> framed, List<int[]> found,
                                                       List<Candidate> operators) {
        var out = new HashMap<String, PreferenceFrame>();
        framed.stream().sorted(Comparator.comparingInt(Framed::verbStart)).forEach(f -> out.putIfAbsent(
                text.substring(f.start(), f.end()),
                new PreferenceFrame(f.valence(), subject(text, f.verbStart(), found, operators))));
        return out;
    }

    /**
     * The longest candidate span ending before {@code verb} in its sentence with only frame adverbs, auxiliaries or
     * negation between; else the implicit operator's span; else null.
     */
    private static @Nullable String subject(String text, int verb, List<int[]> found, List<Candidate> operators) {
        int[] best = null;
        var ranges = new ArrayList<>(found);
        for (var op : operators) if (!op.implicit()) ranges.add(new int[] {op.start(), op.end()});
        for (var r : ranges) {
            if (r[1] > verb || !onlyFrameWords(text.substring(r[1], verb))) continue;
            if (best == null || r[0] < best[0]) best = r;
        }
        if (best != null) return text.substring(best[0], best[1]);
        return operators.stream().filter(Candidate::implicit).map(Candidate::span).findFirst().orElse(null);
    }

    private static boolean onlyFrameWords(String between) {
        var rest = WORD.matcher(between).replaceAll(m -> {
            var w = m.group().toLowerCase(Locale.ROOT);
            boolean allowed = FRAME_ADVERBS.contains(w) || FRAME_AUXILIARIES.contains(w)
                    || w.endsWith("n't") || w.endsWith("n\u2019t");
            return allowed ? "" : "#";
        });
        return rest.isBlank();
    }

    /** Whether {@code r} overlaps a found range of a different span. */
    private static boolean overlapsAnother(String text, int[] r, List<int[]> found) {
        var span = text.substring(r[0], r[1]);
        return found.stream().anyMatch(q -> q[0] < r[1] && r[0] < q[1] && !text.substring(q[0], q[1]).equals(span));
    }

    /** The span after each match of {@code frame}, up to {@code end}'s first match or the end of the text. */
    private static void objects(String text, Pattern frame, Pattern end, List<int[]> taken, List<int[]> found) {
        var m = frame.matcher(text);
        while (m.find()) {
            int s = m.end();
            var stop = end.matcher(text).region(s, text.length());
            int e = stop.find() ? stop.start() : text.length();
            e = trimSpace(text, s, e);
            if (e > s && free(taken, s, e)) found.add(new int[] {s, e});
        }
    }

    /**
     * Both sides of "X is a kind of Y" and "considers X a kind of Y"; X starts at its clause, and without the copula
     * there is an X only after a considers verb in the same clause.
     */
    private static void kindOf(String text, List<int[]> taken, List<int[]> found) {
        var m = KIND_OF.matcher(text);
        while (m.find()) {
            int xEnd = trimSpace(text, 0, m.start());
            int clauseStart = 0;
            var clauseEnd = CLAUSE_END.matcher(text).region(0, xEnd);
            while (clauseEnd.find()) clauseStart = clauseEnd.end();
            int xStart = -1;
            var considers = CONSIDERS.matcher(text).region(clauseStart, xEnd);
            while (considers.find()) xStart = considers.end();
            if (xStart < 0 && m.group(1) == null) {
                xStart = xEnd;
            } else if (xStart < 0) {
                var clause = CLAUSE_START.matcher(text).region(0, xEnd);
                xStart = 0;
                while (clause.find()) xStart = clause.end();
            }
            xStart = lastWords(text, xStart, xEnd, KIND_SUBJECT_WORDS);
            var x = text.substring(xStart, xEnd);
            if (xEnd > xStart && !RELATIVE.matcher(x).lookingAt() && free(taken, xStart, xEnd)) {
                found.add(new int[] {xStart, xEnd});
            }
            int s = m.end();
            var stop = KIND_END.matcher(text).region(s, text.length());
            int e = trimSpace(text, s, stop.find() ? stop.start() : text.length());
            if (e > s && free(taken, s, e)) found.add(new int[] {s, e});
        }
    }

    /** The start of the last {@code words} words of {@code [start, end)}. */
    private static int lastWords(String text, int start, int end, int words) {
        var m = TOKEN.matcher(text).region(start, end);
        var starts = new ArrayList<Integer>();
        while (m.find()) starts.add(m.start());
        return starts.isEmpty() ? end : starts.get(Math.max(0, starts.size() - words));
    }

    private static int trimSpace(String text, int start, int end) {
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end;
    }

    /** Whether every word of {@code span} is a time word or a connector, with at least one time word. */
    private static boolean onlyTime(String span) {
        boolean time = false;
        for (var word : span.split("\\s+")) {
            var core = word.toLowerCase(Locale.ROOT);
            if (TemporalExpressions.TIME_WORDS.contains(core)) {
                time = true;
            } else if (!CONNECTORS.contains(core) && !core.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return time;
    }

    private record Token(String core, int start, int end, boolean connector, boolean sentenceInitial) {}

    /** Maximal capitalized-token runs, connectors allowed inside; punctuation and a possessive end a run. */
    private static void capitalizedRuns(String text, boolean implicit, List<int[]> found) {
        var run = new ArrayList<Token>();
        boolean sentenceStart = true;
        var m = TOKEN.matcher(text);
        while (m.find()) {
            int s = m.start();
            int e = m.end();
            while (s < e && LEAD.indexOf(text.charAt(s)) >= 0) s++;
            boolean leadBroken = s > m.start();
            while (e > s && TRAIL.indexOf(text.charAt(e - 1)) >= 0) e--;
            boolean trailBroken = e < m.end();
            var core = text.substring(s, e);
            if (core.endsWith("'s") || core.endsWith("\u2019s")) {
                e -= 2;
                core = text.substring(s, e);
                trailBroken = true;
            }
            boolean initial = sentenceStart;
            sentenceStart = SENTENCE_END.matcher(m.group()).find();
            if (leadBroken) close(run, implicit, found);
            if (core.isEmpty()) {
                close(run, implicit, found);
                continue;
            }
            boolean capital = Character.isUpperCase(core.charAt(0));
            boolean connector = !capital && (CONNECTORS.contains(core) || core.chars().allMatch(Character::isDigit));
            if (capital || (connector && !run.isEmpty())) {
                run.add(new Token(core, s, e, connector, initial));
            } else {
                close(run, implicit, found);
            }
            if (trailBroken) close(run, implicit, found);
        }
        close(run, implicit, found);
    }

    /**
     * Closes a run, split at its time words, which are dropped. A time word directly followed by a capitalized word
     * that is not one opens a name ("May Chen", "Fridays Ltd") and stays.
     */
    private static void close(List<Token> run, boolean implicit, List<int[]> found) {
        var tokens = new ArrayList<>(run);
        run.clear();
        var segment = new ArrayList<Token>();
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            var next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            boolean opensName = next != null && !next.connector() && !time(next);
            if (time(token) && !opensName) {
                closeSegment(segment, implicit, found);
                segment.clear();
            } else {
                segment.add(token);
            }
        }
        closeSegment(segment, implicit, found);
    }

    private static boolean time(Token token) {
        return !token.connector() && TemporalExpressions.TIME_WORDS.contains(token.core().toLowerCase(Locale.ROOT));
    }

    private static void closeSegment(List<Token> segment, boolean implicit, List<int[]> found) {
        var r = new ArrayList<>(segment);
        while (!r.isEmpty() && r.getFirst().connector()) r.removeFirst();
        while (!r.isEmpty() && r.getLast().connector()) r.removeLast();
        if (!r.isEmpty() && r.getFirst().sentenceInitial()) {
            var first = r.getFirst();
            boolean determiner = DETERMINERS.contains(first.core().toLowerCase(Locale.ROOT));
            boolean openingVerb = implicit && first.start() == 0 && SUBJECTLESS_VERBS.contains(first.core());
            if (determiner || openingVerb) {
                r.removeFirst();
                while (!r.isEmpty() && r.getFirst().connector()) r.removeFirst();
            }
        }
        if (!r.isEmpty()) found.add(new int[] {r.getFirst().start(), r.getLast().end()});
    }

    private static boolean free(List<int[]> taken, int start, int end) {
        return taken.stream().allMatch(r -> end <= r[0] || start >= r[1]);
    }
}
