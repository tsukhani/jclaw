package services.grapheval;

import memory.LiteralSpans;
import memory.TemporalExpressions;
import memory.ontology.OntologyRecord;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The spans of a memory that may name a term, found by fixed rules rather than a model (JCLAW-1356, JCLAW-1357,
 * JCLAW-1372): the operator when the memory says "the user" or states no subject, capitalized runs less their time
 * words, URLs, file paths, ticket keys, email addresses, @handles, phone numbers, the object of a stated preference or
 * view, both sides of "X is a kind of Y", the agent's known Term names and aliases, the owner's name among them, and
 * an owner-possessive kin phrase ("Avery Lin's son") with no name in apposition. Each candidate records the
 * {@link Source}s that proposed it. Deterministic, so a run's candidates never vary between runs or models. Spans may
 * overlap; the decision model settles which one stands.
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

    /** Email: a local part, "@", and a domain with at least one dot. Possessive, or a long run overflows the stack. */
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\w.+\\-])[\\w][\\w.+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)++");
    private static final Pattern HANDLE = Pattern.compile("(?<![\\w.@])@[A-Za-z_]\\w++");
    /**
     * At least two digit groups, joined by single spaces, hyphens or dots, or by nothing after a parenthesised group;
     * every group after the first has two digits or more, so a version or a trailing count is not one.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+),.:/@\\-])\\+?(?:\\(\\d++\\)|\\d++)(?:(?:(?<=\\))[ .\\-]?|[ .\\-])(?:\\(\\d{2,}+\\)|\\d{2,}+))++");
    private static final Pattern PHONE_DATE = Pattern.compile(
            "\\d{4}([.\\-])\\d{1,2}\\1\\d{1,2}|\\d{1,2}([.\\-])\\d{1,2}\\2\\d{2,4}");
    private static final Pattern PHONE_YEAR_RANGE = Pattern.compile("(?:19|20)\\d\\d[ .\\-]+(?:19|20)\\d\\d");
    private static final Pattern PHONE_QUAD = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final int PHONE_MIN_DIGITS = 7;
    private static final int PHONE_MAX_DIGITS = 15;

    /** Modifiers a kin phrase may carry before its kin word. A closed list: extend it only by a reviewed edit. */
    public static final List<String> KIN_MODIFIERS = List.of("older", "younger", "elder", "eldest", "youngest", "twin",
            "little", "big");
    /** Singular kin words. A closed list: extend it only by a reviewed edit. */
    public static final List<String> KIN_WORDS = List.of("mother", "father", "mum", "mom", "dad", "parent", "son",
            "daughter", "child", "brother", "sister", "sibling", "wife", "husband", "spouse", "partner", "fiance",
            "fiancee", "grandmother", "grandfather", "grandparent", "grandson", "granddaughter", "grandchild", "aunt",
            "uncle", "cousin", "niece", "nephew", "stepmother", "stepfather", "stepson", "stepdaughter", "stepbrother",
            "stepsister", "mother-in-law", "father-in-law", "brother-in-law", "sister-in-law", "son-in-law",
            "daughter-in-law");
    private static final String KIN_TAIL = "['\u2019]s\\s+(?:(?:" + alternation(KIN_MODIFIERS) + ")\\s+)?("
            + alternation(KIN_WORDS) + ")(?![\\w\\-])";
    private static final String CLAUSE_CLOSE = ",;.!?)";

    /**
     * What proposed a candidate. OPERATOR is "the user" or the implicit operator, written by rule; KNOWN is the owner's
     * name or a known Term name or alias.
     */
    public enum Source { OPERATOR, KNOWN, URL, PATH, FILE, TICKET, EMAIL, HANDLE, PHONE,
                         PREFERENCE, VIEW, KIND_OF, CAPITALIZED, KIN }

    /** A found span at {@code [start, end)}; a KIN one carries its kin word and possessor range. */
    private record Found(int start, int end, Source source, @Nullable String kin, int possessorStart,
                         int possessorEnd) {
        Found(int start, int end, Source source) {
            this(start, end, source, null, -1, -1);
        }
    }

    private CandidateGenerator() {}

    private static String alternation(List<String> words) {
        return words.stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(w -> w))
                .map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow();
    }

    /**
     * A span to type, at {@code [start, end)} of the text. An operator candidate ("the user", or implicit) is written
     * as a Person without a question, and an implicit one has no span in the text and is named
     * {@link GraphCases#IMPLICIT_OPERATOR_SPAN}; an owner named in the text is an ordinary candidate. A candidate with
     * no position has offsets -1 and overlaps nothing. {@code sources} iterates in enum order; a KIN candidate's
     * {@code kin} is its lower-case kin word and {@code [possessorStart, possessorEnd)} its possessor, else null and -1.
     */
    public record Candidate(String span, boolean operator, boolean implicit, int start, int end,
                            @Nullable PreferenceFrame frame, Set<Source> sources, @Nullable String kin,
                            int possessorStart, int possessorEnd) {
        public Candidate {
            sources = sources.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(sources));
        }

        public Candidate(String span, boolean operator, boolean implicit, int start, int end,
                         @Nullable PreferenceFrame frame) {
            this(span, operator, implicit, start, end, frame, Set.of(), null, -1, -1);
        }

        public Candidate(String span, boolean operator, boolean implicit, int start, int end) {
            this(span, operator, implicit, start, end, null);
        }

        public Candidate(String span, boolean operator, boolean implicit) {
            this(span, operator, implicit, -1, -1);
        }

        public static Candidate of(String span) {
            return new Candidate(span, false, false);
        }

        /** Whether the spans share a character, unless one is the other's possessor. */
        public boolean overlaps(Candidate other) {
            if (possessorStart >= 0 && possessorStart == other.start && possessorEnd == other.end) return false;
            if (other.possessorStart >= 0 && other.possessorStart == start && other.possessorEnd == end) return false;
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

    /** {@link #generate(String, Collection, String)} with no owner name: "the user" is the only kin possessor. */
    public static List<Candidate> generate(String text, Collection<String> knownNames) {
        return generate(text, knownNames, null);
    }

    /**
     * The candidates in {@code text}: the operator first, then by position, each span once. Each of {@code knownNames},
     * and {@code ownerName}, is a candidate wherever it appears as whole words, in any case, even when it is a time
     * word; {@code ownerName} and "the user" are the possessors of a kin phrase. A recurring span keeps the occurrence
     * that overlaps another candidate, else its first, and the sources of every occurrence.
     */
    public static List<Candidate> generate(String text, Collection<String> knownNames, @Nullable String ownerName) {
        var out = new ArrayList<Candidate>();
        var user = THE_USER.matcher(text);
        boolean implicit = false;
        var operatorSource = Set.of(Source.OPERATOR);
        if (user.find()) {
            out.add(new Candidate(user.group(), true, false, user.start(), user.end(), null, operatorSource, null, -1,
                    -1));
        } else if (subjectless(text)) {
            implicit = true;
            out.add(new Candidate(GraphCases.IMPLICIT_OPERATOR_SPAN, true, true, -1, -1, null, operatorSource, null, -1,
                    -1));
        }

        var found = new ArrayList<Found>();
        var taken = new ArrayList<int[]>();
        var framed = new ArrayList<Framed>();
        var literals = LiteralSpans.spans(text);
        for (var literal : literals) {
            found.add(new Found(literal.start(), literal.end(), Source.valueOf(literal.kind().name())));
            if (literal.kind() == LiteralSpans.Kind.URL || literal.kind() == LiteralSpans.Kind.PATH) {
                taken.add(new int[] {literal.start(), literal.end()});
            }
        }
        contacts(text, literals, found);
        preferences(text, taken, found, framed);
        objects(text, VIEW, PREFERENCE_END, taken, found);
        kindOf(text, taken, found);
        var names = new ArrayList<String>();
        for (var name : knownNames) {
            if (!name.isBlank() && names.stream().noneMatch(n -> n.equalsIgnoreCase(name.strip()))) {
                names.add(name.strip());
            }
        }
        if (ownerName != null && !ownerName.isBlank()
                && names.stream().noneMatch(n -> n.equalsIgnoreCase(ownerName.strip()))) {
            names.add(ownerName.strip());
        }
        var known = new ArrayList<Found>();
        for (var name : names) {
            var m = Pattern.compile("(?i)(?<![\\w])" + Pattern.quote(name) + "(?![\\w])").matcher(text);
            while (m.find()) known.add(new Found(m.start(), m.end(), Source.KNOWN));
        }
        capitalizedRuns(text, implicit, found);
        found.removeIf(r -> onlyTime(text.substring(r.start(), r.end())));
        var claimed = TemporalExpressions.claimedSpans(text);
        found.removeIf(r -> claimed.stream().anyMatch(c -> c.start() <= r.start() && r.end() <= c.end()));
        found.addAll(known);
        found.addAll(kin(text, ownerName, found));

        found.sort(Comparator.comparingInt(Found::start));
        var operators = new HashSet<String>();
        out.forEach(c -> operators.add(c.span()));
        var chosen = new LinkedHashMap<String, Found>();
        var sources = new HashMap<String, Set<Source>>();
        var kinOf = new HashMap<String, Found>();
        for (var r : found) {
            var span = text.substring(r.start(), r.end());
            if (span.isEmpty() || span.equalsIgnoreCase(GraphCases.IMPLICIT_OPERATOR_SPAN) || operators.contains(span)) {
                continue;
            }
            sources.computeIfAbsent(span, _ -> EnumSet.noneOf(Source.class)).add(r.source());
            if (r.kin() != null) kinOf.putIfAbsent(span, r);
            var first = chosen.get(span);
            if (first == null || (!overlapsAnother(text, first, found) && overlapsAnother(text, r, found))) {
                chosen.put(span, r);
            }
        }
        var frames = frames(text, framed, found, out);
        chosen.values().stream().sorted(Comparator.comparingInt(Found::start)).forEach(r -> {
            var span = text.substring(r.start(), r.end());
            var k = kinOf.get(span);
            int possessorStart = k == null ? -1 : r.start() + k.possessorStart() - k.start();
            int possessorEnd = k == null ? -1 : r.start() + k.possessorEnd() - k.start();
            out.add(new Candidate(span, false, false, r.start(), r.end(), frames.get(span),
                    Objects.requireNonNull(sources.get(span)), k == null ? null : k.kin(), possessorStart,
                    possessorEnd));
        });
        return List.copyOf(out);
    }

    /** Email addresses, @handles and phone numbers; none of them inside a URL, path, file name or ticket key. */
    private static void contacts(String text, List<LiteralSpans.Span> literals, List<Found> found) {
        var emails = new ArrayList<Found>();
        var email = EMAIL.matcher(text);
        while (email.find()) {
            if (clear(literals, email.start(), email.end())) {
                emails.add(new Found(email.start(), email.end(), Source.EMAIL));
            }
        }
        found.addAll(emails);
        var handle = HANDLE.matcher(text);
        while (handle.find()) {
            int s = handle.start();
            int e = handle.end();
            if (clear(literals, s, e) && emails.stream().allMatch(r -> e <= r.start() || s >= r.end())) {
                found.add(new Found(s, e, Source.HANDLE));
            }
        }
        var phone = PHONE.matcher(text);
        while (phone.find()) {
            if (phone(text, phone.start(), phone.end()) && clear(literals, phone.start(), phone.end())) {
                found.add(new Found(phone.start(), phone.end(), Source.PHONE));
            }
        }
    }

    /** Whether the digit-group match at {@code [start, end)} reads as a phone number rather than a date or number. */
    private static boolean phone(String text, int start, int end) {
        if (end < text.length()) {
            char next = text.charAt(end);
            if (Character.isLetterOrDigit(next) || next == '_' || next == '(' || next == '@') return false;
            if (",:./-".indexOf(next) >= 0 && end + 1 < text.length() && Character.isDigit(text.charAt(end + 1))) {
                return false;
            }
        }
        var match = text.substring(start, end);
        long digits = match.chars().filter(Character::isDigit).count();
        if (digits < PHONE_MIN_DIGITS || digits > PHONE_MAX_DIGITS) return false;
        if (match.chars().filter(ch -> ch == '(').count() > 1) return false;
        return !PHONE_DATE.matcher(match).matches() && !PHONE_YEAR_RANGE.matcher(match).matches()
                && !PHONE_QUAD.matcher(match).matches();
    }

    private static boolean clear(List<LiteralSpans.Span> literals, int start, int end) {
        return literals.stream().allMatch(l -> end <= l.start() || start >= l.end());
    }

    /**
     * Each owner-possessive kin phrase ("Avery Lin's younger sister"), unless a capitalized name is in apposition: right
     * after the kin word ("son Wren"), or comma-separated when the appositive after the kin word ("son, Wren.") or
     * the kin phrase after a leading name ("Wren, Avery Lin's son.") closes its clause.
     */
    private static List<Found> kin(String text, @Nullable String ownerName, List<Found> found) {
        var possessor = "the user";
        if (ownerName != null && !ownerName.isBlank()) possessor = Pattern.quote(ownerName.strip()) + "|" + possessor;
        var m = Pattern.compile("(?i)(?<![\\w])(" + possessor + ")" + KIN_TAIL).matcher(text);
        var out = new ArrayList<Found>();
        while (m.find()) {
            int s = m.start(1);
            int e = m.end(2);
            boolean apposition = found.stream().filter(r -> r.source() == Source.CAPITALIZED).anyMatch(r ->
                    (r.start() == e + 1 && text.charAt(e) == ' ')
                            || (r.start() == e + 2 && text.startsWith(", ", e) && closes(text, r.end()))
                            || (r.end() + 2 == s && text.startsWith(", ", r.end()) && closes(text, e)));
            if (!apposition) {
                out.add(new Found(s, e, Source.KIN, m.group(2).toLowerCase(Locale.ROOT), s, m.end(1)));
            }
        }
        return out;
    }

    /** Whether {@code at} is the end of the text or one of {@link #CLAUSE_CLOSE}. */
    private static boolean closes(String text, int at) {
        return at == text.length() || CLAUSE_CLOSE.indexOf(text.charAt(at)) >= 0;
    }

    /** A preference object at {@code [start, end)} and the frame match {@code [verbStart, verbEnd)} governing it. */
    private record Framed(int start, int end, int verbStart, int verbEnd, OntologyRecord.Valence valence) {}

    /**
     * The object of every valence frame, read in {@link TemporalExpressions#valence}'s order -- favorable endings,
     * unfavorable stances, favorable stances -- so a match inside an earlier one is the same frame and is skipped.
     */
    private static void preferences(String text, List<int[]> taken, List<Found> found, List<Framed> framed) {
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
                                     List<Found> found, List<Framed> framed) {
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
                    found.add(new Found(s, e, Source.PREFERENCE));
                    framed.add(new Framed(s, e, vs, ve, valence));
                }
            }
        }
    }

    /** Each preference object's frame by span; a span framed twice keeps the frame of the verb that comes first. */
    private static Map<String, PreferenceFrame> frames(String text, List<Framed> framed, List<Found> found,
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
    private static @Nullable String subject(String text, int verb, List<Found> found, List<Candidate> operators) {
        int[] best = null;
        var ranges = new ArrayList<int[]>();
        for (var r : found) ranges.add(new int[] {r.start(), r.end()});
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

    /** Whether {@code r} overlaps a found range of a different span, other than its own possessor or possessive. */
    private static boolean overlapsAnother(String text, Found r, List<Found> found) {
        var span = text.substring(r.start(), r.end());
        return found.stream().anyMatch(q -> q.start() < r.end() && r.start() < q.end()
                && !text.substring(q.start(), q.end()).equals(span) && !possesses(q, r) && !possesses(r, q));
    }

    private static boolean possesses(Found kin, Found other) {
        return kin.possessorStart() >= 0 && kin.possessorStart() == other.start() && kin.possessorEnd() == other.end();
    }

    /** The span after each match of {@code frame}, up to {@code end}'s first match or the end of the text. */
    private static void objects(String text, Pattern frame, Pattern end, List<int[]> taken, List<Found> found) {
        var m = frame.matcher(text);
        while (m.find()) {
            int s = m.end();
            var stop = end.matcher(text).region(s, text.length());
            int e = stop.find() ? stop.start() : text.length();
            e = trimSpace(text, s, e);
            if (e > s && free(taken, s, e)) found.add(new Found(s, e, Source.VIEW));
        }
    }

    /**
     * Both sides of "X is a kind of Y" and "considers X a kind of Y"; X starts at its clause, and without the copula
     * there is an X only after a considers verb in the same clause.
     */
    private static void kindOf(String text, List<int[]> taken, List<Found> found) {
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
                found.add(new Found(xStart, xEnd, Source.KIND_OF));
            }
            int s = m.end();
            var stop = KIND_END.matcher(text).region(s, text.length());
            int e = trimSpace(text, s, stop.find() ? stop.start() : text.length());
            if (e > s && free(taken, s, e)) found.add(new Found(s, e, Source.KIND_OF));
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
    private static void capitalizedRuns(String text, boolean implicit, List<Found> found) {
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
    private static void close(List<Token> run, boolean implicit, List<Found> found) {
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

    private static void closeSegment(List<Token> segment, boolean implicit, List<Found> found) {
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
        if (!r.isEmpty()) found.add(new Found(r.getFirst().start(), r.getLast().end(), Source.CAPITALIZED));
    }

    private static boolean free(List<int[]> taken, int start, int end) {
        return taken.stream().allMatch(r -> end <= r[0] || start >= r[1]);
    }
}
