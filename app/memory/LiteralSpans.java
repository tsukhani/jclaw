package memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * URLs, file paths, file names and ticket keys in a text (JCLAW-1356, JCLAW-1361): the spans the graph candidate
 * generator offers as literals and the date finder never reads digits out of.
 */
public final class LiteralSpans {

    public static final Pattern URL = Pattern.compile("https?://\\S+");
    public static final Pattern PATH = Pattern.compile("(?<![\\w:/.~])(?:~/|\\./|/)[\\w.\\-/]*\\w");
    public static final Pattern FILE = Pattern.compile(
            "\\b[\\w\\-]+\\.(?:md|txt|pdf|csv|json|yaml|yml|xlsx|docx|pptx|py|java|sh|log|sql|zip)\\b");
    public static final Pattern TICKET = Pattern.compile("\\b[A-Z]+-\\d+\\b");

    private static final String TRAIL = ".,;:!?)\"'\u201d\u2019";

    public enum Kind { URL, PATH, FILE, TICKET }

    /** A literal at {@code [start, end)}. */
    public record Span(Kind kind, int start, int end) {}

    private LiteralSpans() {}

    /**
     * Every URL (trailing punctuation trimmed), then each path, file name and ticket key that overlaps no URL or
     * earlier path. File names and ticket keys may overlap each other.
     */
    public static List<Span> spans(String text) {
        var out = new ArrayList<Span>();
        var taken = new ArrayList<Span>();
        var url = URL.matcher(text);
        while (url.find()) {
            int e = url.end();
            while (e > url.start() && TRAIL.indexOf(text.charAt(e - 1)) >= 0) e--;
            var span = new Span(Kind.URL, url.start(), e);
            taken.add(span);
            out.add(span);
        }
        var path = PATH.matcher(text);
        while (path.find()) {
            if (free(taken, path.start(), path.end())) {
                var span = new Span(Kind.PATH, path.start(), path.end());
                taken.add(span);
                out.add(span);
            }
        }
        var file = FILE.matcher(text);
        while (file.find()) {
            if (free(taken, file.start(), file.end())) out.add(new Span(Kind.FILE, file.start(), file.end()));
        }
        var ticket = TICKET.matcher(text);
        while (ticket.find()) {
            if (free(taken, ticket.start(), ticket.end())) out.add(new Span(Kind.TICKET, ticket.start(), ticket.end()));
        }
        return List.copyOf(out);
    }

    private static boolean free(List<Span> taken, int start, int end) {
        return taken.stream().allMatch(r -> end <= r.start() || start >= r.end());
    }
}
