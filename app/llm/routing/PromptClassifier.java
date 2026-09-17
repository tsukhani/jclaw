package llm.routing;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sorts a prompt into a {@link TaskClass} by keyword and structure, with the signals that decided
 * it. Deterministic and local: it costs no model call, and every decision can be explained from
 * the signals alone. Modeled on LiteLLM's complexity router, but biased toward {@link TaskClass#CHAT}
 * — a light model answering a heavy prompt costs quality once, while a heavy model answering every
 * light prompt is the credit drain the router exists to stop.
 */
public final class PromptClassifier {

    /** @param signals what matched, for the decision log and the persisted route; never empty */
    public record Classification(TaskClass taskClass, List<String> signals) {}

    /** A follow-up longer than this is a new request, whatever words it is made of. */
    static final int FOLLOW_UP_MAX_CHARS = 40;

    /** Tool calls in the previous turn at which a bare continuation counts as agent work. */
    public static final int AGENTIC_PRIOR_TOOL_CALLS = 3;

    // A pasted document is classified on its head: the instruction is almost always there, and
    // scanning megabytes of it with every pattern below would cost more than the routing saves.
    private static final int MAX_SCANNED_CHARS = 16_000;

    private static final Set<String> ACK_WORDS = Set.of("yes", "yep", "yeah", "ok", "okay", "sure",
            "go", "ahead", "on", "and", "continue", "proceed", "do", "it", "please", "thanks", "thank",
            "you", "ty", "try", "again", "retry", "next", "keep", "going", "carry", "sounds", "good",
            "perfect", "great", "cool", "fine", "that", "works", "lgtm", "nice", "done", "right",
            "correct", "exactly", "agreed");

    private static final Pattern LETTERS = Pattern.compile("[a-z]+");

    private static final Pattern CODE_FENCE = Pattern.compile("```");

    private static final Pattern STACK_TRACE = Pattern.compile(
            "(?m)^\\s*at [\\w.$<>]+\\([^)]*\\)\\s*$|Traceback \\(most recent call last\\)|\\b\\w+(?:Error|Exception): ");

    private static final Pattern SOURCE_FILE = Pattern.compile(
            "\\b[\\w/-]+\\.(?:java|kts?|scala|py|jsx?|tsx?|vue|go|rs|rb|php|cs|cpp|hpp|swift|sql|sh|gradle|ya?ml|toml)\\b");

    private static final List<Pattern> CODE_TERMS = phrases("refactor", "refactoring", "compile",
            "compiler", "stack trace", "stacktrace", "unit test", "unit tests", "test case", "regex",
            "function", "method", "class", "variable", "bug", "debug", "debugging", "endpoint", "api",
            "sql", "query", "schema", "repository", "repo", "pull request", "commit", "merge conflict",
            "git", "dockerfile", "typescript", "javascript", "python", "java", "golang", "kotlin", "rust",
            "lint", "linter", "null pointer", "npe", "segfault", "exception", "syntax error", "code",
            "script", "implement", "implementation", "algorithm", "data structure", "frontend", "backend",
            "css", "html", "json", "yaml", "gradle", "npm", "pnpm", "maven");

    private static final List<Pattern> STRONG_REASONING = phrases("step by step", "step-by-step",
            "think hard", "think harder", "think carefully", "think deeply", "ultrathink", "reason through",
            "prove", "proof", "derive", "derivation", "first principles", "rigorous", "rigorously",
            "root cause", "trade-offs", "tradeoffs", "trade offs", "pros and cons", "compare and contrast");

    private static final List<Pattern> WEAK_REASONING = phrases("analyze", "analyse", "analysis",
            "evaluate", "assess", "weigh", "deduce", "infer", "why does", "why do", "why is",
            "implications", "optimal", "strategy", "architecture", "design a", "plan for");

    private static final List<Pattern> MATH_TERMS = phrases("integral", "derivative", "eigenvalue",
            "eigenvector", "matrix", "probability", "expected value", "variance", "theorem", "lemma",
            "equation", "polynomial", "modulo", "prime number", "combinatorics", "differential",
            "logarithm", "converge", "converges");

    // Not \b after the command: a subscript follows it directly, as in \int_0, and '_' is a word character.
    private static final Pattern LATEX = Pattern.compile("\\\\(?:frac|sum|int|sqrt|lim|prod|begin)(?![A-Za-z])|\\$\\$");

    private static final Pattern DIGIT_OR_EQUALS = Pattern.compile("[0-9=]");

    private static final List<Pattern> ACTIONS = phrases("schedule", "remind", "send", "email", "post",
            "publish", "set up", "configure", "install", "deploy", "run", "execute", "launch", "restart",
            "download", "upload", "fetch", "scrape", "crawl", "search", "look up", "research", "browse",
            "book", "order", "organize", "organise", "clean up", "rename", "move", "delete", "update",
            "migrate", "monitor", "track", "check", "notify", "create", "save", "export", "import", "find",
            "gather", "collect");

    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^\\s*(?:\\d+[.)]|[-*•])\\s+\\S");

    private static final Pattern STEP_CONNECTOR = Pattern.compile(
            "\\b(?:and then|then|after that|afterwards|once (?:that's|that is|it's|it is) done|finally)\\b");

    private static final Pattern SUMMARY = Pattern.compile(
            "\\b(?:summari[sz]e|summary|summari[sz]ation|tl;?dr|recap|condense|key (?:points|takeaways)|main points|gist|digest|shorten)\\b");

    private PromptClassifier() {}

    /**
     * Classify one user message.
     *
     * @param priorClass     the class the previous turn in this conversation was routed as, or null
     * @param priorToolCalls tool calls the previous turn made; 0 when unknown
     */
    public static Classification classify(String message, @Nullable TaskClass priorClass, int priorToolCalls) {
        var text = message.length() > MAX_SCANNED_CHARS ? message.substring(0, MAX_SCANNED_CHARS) : message;
        var lower = text.toLowerCase(Locale.ROOT);

        if (isFollowUp(text, lower)) {
            if (priorClass != null) {
                return new Classification(priorClass, List.of("follow-up inherits " + priorClass.id()));
            }
            if (priorToolCalls >= AGENTIC_PRIOR_TOOL_CALLS) {
                return new Classification(TaskClass.AGENTIC,
                        List.of("follow-up after %d tool calls".formatted(priorToolCalls)));
            }
        }

        var hardCode = new ArrayList<String>();
        if (CODE_FENCE.matcher(text).find()) hardCode.add("code block");
        if (STACK_TRACE.matcher(text).find()) hardCode.add("stack trace");
        if (!hardCode.isEmpty()) return new Classification(TaskClass.CODING, hardCode);

        var reasoning = reasoningSignals(text, lower);
        if (!reasoning.isEmpty()) return new Classification(TaskClass.REASONING, reasoning);

        var codeTerms = matches(lower, CODE_TERMS);
        var file = SOURCE_FILE.matcher(text);
        if (file.find()) codeTerms.add(file.group());
        if (codeTerms.size() >= 2) {
            return new Classification(TaskClass.CODING, List.of("code terms: " + String.join(", ", codeTerms)));
        }

        var agentic = agenticSignals(text, lower, priorToolCalls);
        if (!agentic.isEmpty()) return new Classification(TaskClass.AGENTIC, agentic);

        var summary = SUMMARY.matcher(lower);
        if (summary.find()) {
            return new Classification(TaskClass.SUMMARIZE, List.of("summary request: " + summary.group()));
        }
        return new Classification(TaskClass.CHAT, List.of("no task markers"));
    }

    private static boolean isFollowUp(String text, String lower) {
        if (text.isBlank() || text.strip().length() > FOLLOW_UP_MAX_CHARS) return false;
        var words = LETTERS.matcher(lower);
        var any = false;
        while (words.find()) {
            if (!ACK_WORDS.contains(words.group())) return false;
            any = true;
        }
        return any;
    }

    private static List<String> reasoningSignals(String text, String lower) {
        var signals = new ArrayList<String>();
        var strong = matches(lower, STRONG_REASONING);
        var weak = matches(lower, WEAK_REASONING);
        if (!strong.isEmpty() || weak.size() >= 2) {
            var markers = new LinkedHashSet<>(strong);
            markers.addAll(weak);
            signals.add("reasoning markers: " + String.join(", ", markers));
        }
        if (LATEX.matcher(text).find()) {
            signals.add("math notation");
        } else {
            var math = matches(lower, MATH_TERMS);
            if (!math.isEmpty() && DIGIT_OR_EQUALS.matcher(lower).find()) {
                signals.add("math: " + String.join(", ", math));
            }
        }
        return signals;
    }

    private static List<String> agenticSignals(String text, String lower, int priorToolCalls) {
        var actions = matches(lower, ACTIONS);
        if (actions.isEmpty()) return List.of();
        var listItems = LIST_ITEM.matcher(text).results().count();
        var multiStep = listItems >= 2 || STEP_CONNECTOR.matcher(lower).find();
        var toolHeavy = priorToolCalls >= AGENTIC_PRIOR_TOOL_CALLS;
        if (actions.size() < 2 && !multiStep && !toolHeavy) return List.of();
        var signals = new ArrayList<String>();
        signals.add("actions: " + String.join(", ", actions));
        if (multiStep) signals.add("multi-step");
        if (toolHeavy) signals.add("previous turn used %d tool calls".formatted(priorToolCalls));
        return signals;
    }

    private static List<String> matches(String lower, List<Pattern> patterns) {
        var found = new ArrayList<String>();
        for (var p : patterns) {
            var m = p.matcher(lower);
            if (m.find()) found.add(m.group());
        }
        return found;
    }

    private static List<Pattern> phrases(String... phrases) {
        var out = new ArrayList<Pattern>(phrases.length);
        for (var phrase : phrases) {
            out.add(Pattern.compile("(?<![\\w-])" + Pattern.quote(phrase) + "(?![\\w-])"));
        }
        return List.copyOf(out);
    }
}
