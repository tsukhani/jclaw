package services.graphspike;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The spans of a memory that may name a term, found by fixed rules rather than a model (JCLAW-1356): the operator,
 * capitalized runs, URLs, file paths, ticket keys and the object of a stated preference. Deterministic, so a run's
 * candidates never vary between runs or models.
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
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern PATH = Pattern.compile("(?<![\\w:/.~])(?:~/|\\./|/)[\\w.\\-/]*\\w");
    private static final Pattern FILE = Pattern.compile(
            "\\b[\\w\\-]+\\.(?:md|txt|pdf|csv|json|yaml|yml|xlsx|docx|pptx|py|java|sh|log|sql|zip)\\b");
    private static final Pattern TICKET = Pattern.compile("\\b[A-Z]+-\\d+\\b");
    private static final Pattern PREFERENCE = Pattern.compile(
            "(?i)\\b(?:prefers|likes|loves|dislikes|hates|thinks that|is interested in)\\s+");
    private static final Pattern PREFERENCE_END = Pattern.compile("[,.;:!?]|\\s(?:over|because|when|than)\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?][\"'\u201d\u2019)]*$");

    private CandidateGenerator() {}

    /**
     * A span to type. An operator candidate is written as a Person without a question; an implicit one has no span
     * in the text and is named {@link GraphCases#IMPLICIT_OPERATOR_SPAN}.
     */
    public record Candidate(String span, boolean operator, boolean implicit) {
        public static Candidate of(String span) {
            return new Candidate(span, false, false);
        }
    }

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

    /** The candidates in {@code text}: the operator first, then by position, each span once. */
    public static List<Candidate> generate(String text) {
        var out = new ArrayList<Candidate>();
        var user = THE_USER.matcher(text);
        boolean implicit = false;
        if (user.find()) {
            out.add(new Candidate(user.group(), true, false));
        } else if (subjectless(text)) {
            implicit = true;
            out.add(new Candidate(GraphCases.IMPLICIT_OPERATOR_SPAN, true, true));
        }

        var found = new ArrayList<int[]>();
        var taken = new ArrayList<int[]>();
        var url = URL.matcher(text);
        while (url.find()) {
            int e = trimTrailing(text, url.start(), url.end());
            taken.add(new int[] {url.start(), e});
            found.add(new int[] {url.start(), e});
        }
        var path = PATH.matcher(text);
        while (path.find()) {
            if (free(taken, path.start(), path.end())) {
                taken.add(new int[] {path.start(), path.end()});
                found.add(new int[] {path.start(), path.end()});
            }
        }
        var file = FILE.matcher(text);
        while (file.find()) {
            if (free(taken, file.start(), file.end())) found.add(new int[] {file.start(), file.end()});
        }
        var ticket = TICKET.matcher(text);
        while (ticket.find()) found.add(new int[] {ticket.start(), ticket.end()});
        var preference = PREFERENCE.matcher(text);
        while (preference.find()) {
            int s = preference.end();
            var stop = PREFERENCE_END.matcher(text).region(s, text.length());
            int e = stop.find() ? stop.start() : text.length();
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (e > s) found.add(new int[] {s, e});
        }
        capitalizedRuns(text, implicit, found);

        found.sort(Comparator.comparingInt(r -> r[0]));
        var seen = new LinkedHashSet<String>();
        out.forEach(c -> seen.add(c.span()));
        for (var r : found) {
            var span = text.substring(r[0], r[1]);
            if (span.isEmpty() || span.equalsIgnoreCase(GraphCases.IMPLICIT_OPERATOR_SPAN)) continue;
            if (seen.add(span)) out.add(Candidate.of(span));
        }
        return List.copyOf(out);
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

    private static void close(List<Token> run, boolean implicit, List<int[]> found) {
        var r = new ArrayList<>(run);
        run.clear();
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

    private static int trimTrailing(String text, int start, int end) {
        while (end > start && TRAIL.indexOf(text.charAt(end - 1)) >= 0) end--;
        return end;
    }

    private static boolean free(List<int[]> taken, int start, int end) {
        return taken.stream().allMatch(r -> end <= r[0] || start >= r[1]);
    }
}
