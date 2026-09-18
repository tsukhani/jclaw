package llm.routing;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sorts a prompt into a {@link TaskClass} by what it asks for, with the signals that decided it.
 * Deterministic and local: it costs no model call, and every decision is explainable from the
 * signals alone.
 *
 * <p>Four questions, in order of how sure each answer is. Does the prompt carry code? Does it ask
 * for an answer that has to be worked out rather than recalled — a proof, a judgment, a comparison,
 * a cause? Does it tell the assistant to <em>do</em> something? Does it ask for a summary? Anything
 * else is chat.
 *
 * <p>The first cut of this keyed on a fixed marker vocabulary and under-fired badly on real traffic:
 * "Reason about why LLMs cannot reach ASI" stayed on the flash model because the list held "reason
 * through" and not "reason about", and "Run daily briefing skill" needed a second verb to count as
 * agent work. So a cue now fires on the <em>shape</em> of the ask — a leading imperative naming a
 * system action, a negated capability, a judgment noun — and a single unambiguous cue is enough,
 * while weaker cues need a second one before they escalate. Both rules are what keeps an ordinary
 * question ("Why is the sky blue?") on the cheap model.
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

    /** Cues that only appear when the answer has to be worked out. One is enough. */
    private static final List<Pattern> STRONG_REASONING = phrases("prove", "proof", "derive",
            "derivation", "step by step", "step-by-step", "first principles", "root cause", "trade-off",
            "trade-offs", "tradeoff", "tradeoffs", "trade offs", "pros and cons", "compare and contrast",
            "critique", "critiquing", "make the case", "argue", "reason about", "reason through",
            "think through", "think hard", "think harder", "think carefully", "think deeply", "ultrathink",
            "rigorous", "rigorously", "justify", "stance", "weigh", "deduce", "what would happen if",
            "devil's advocate", "steelman", "counterargument", "counterarguments");

    /** Cues that only escalate in company: two or more mean the prompt wants judgment, not recall. */
    private static final List<Pattern> MEDIUM_REASONING = phrases("why", "how come", "what if",
            "suppose", "opinion", "opinions", "view on", "implication", "implications", "consequence",
            "consequences", "risk", "risks", "benefit", "benefits", "versus", "vs", "difference between",
            "better than", "which is better", "should i", "should we", "analyze", "analyse", "analysis",
            "assess", "evaluate", "consider", "cannot", "can't", "impossible", "limits", "limitation",
            "limitations", "fundamental", "fundamentally", "theory", "theoretical", "philosophy",
            "philosophical", "ethics", "ethical", "alignment", "asi", "agi", "consciousness", "paradox",
            "complexity", "strategy", "architecture", "explain why", "explain how", "in principle",
            "argument", "arguments", "couldn't", "wouldn't", "shouldn't", "how could", "how would",
            "rather than", "instead of", "alternative", "alternatives", "downside", "downsides",
            "better to", "worth it", "approach", "approaches", "applied to", "apply to");

    /** Verbs that mean "use a tool on my behalf" whatever follows them. */
    private static final Set<String> SYSTEM_ACTIONS = Set.of("run", "execute", "launch", "restart",
            "deploy", "install", "configure", "schedule", "remind", "send", "email", "message", "post",
            "publish", "download", "upload", "fetch", "scrape", "crawl", "search", "browse", "open",
            "transcribe", "translate", "print", "backup", "restore", "migrate", "monitor", "track",
            "check", "look", "find", "research", "book", "order", "buy", "cancel", "delete", "remove",
            "rename", "move", "sync", "refresh", "retry", "kill", "stop", "start", "pause", "resume",
            "call", "notify", "alert", "export", "import", "commit", "push", "pull", "merge",
            "spawn", "delegate", "invoke", "trigger", "follow", "apply", "review", "audit", "inspect",
            "verify", "benchmark", "profile", "measure", "fix", "clean", "diff", "compile");

    /** Verbs that only mean agent work when their object is something JClaw manages. */
    private static final Set<String> OBJECT_ACTIONS = Set.of("create", "add", "make", "update", "edit",
            "set", "generate", "build", "write", "save", "draft", "record", "register", "attach", "upload");

    private static final List<Pattern> ACTION_OBJECTS = phrases("task", "tasks", "reminder", "reminders",
            "file", "files", "folder", "document", "spreadsheet", "note", "notes", "calendar", "event",
            "meeting", "ticket", "issue", "pull request", "branch", "agent", "agents", "skill", "skills",
            "prompt", "prompts", "cron", "schedule", "image", "images", "video", "picture", "chart",
            "diagram", "report", "playlist", "memory", "memories", "workspace", "channel", "binding",
            "mcp server", "app", "page", "entry", "backup", "config", "setting", "settings");

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[.!?;\\n]+|\\band then\\b|\\bthen\\b|\\band also\\b|\\bafter that\\b");

    /** Politeness and discourse glue that can precede the verb without changing the ask. */
    private static final Pattern LEAD_IN = Pattern.compile(
            "^(?:hey|hi|ok|okay|now|then|also|and|so|well|please|pls|kindly|just|quickly"
                    + "|can you|could you|would you|will you|can we|could we|would it be possible to"
                    + "|i want you to|i'd like you to|i would like you to|i need you to|let's|lets"
                    + "|go|go and|help me|try to|try and|maybe)\\s+");

    private static final Pattern FIRST_WORD = Pattern.compile("^([a-z]+)");

    /** "why LLMs cannot", "why it can't" — a negated capability is the shape of an open question. */
    private static final Pattern NEGATED_CAPABILITY =
            Pattern.compile("\\b(?:why|whether|if)\\b[^.?!]{0,60}\\b(?:cannot|can't|couldn't|won't|will never|fails? to|unable to|impossible)\\b");

    private static final List<Pattern> MATH_TERMS = phrases("integral", "derivative", "eigenvalue",
            "eigenvector", "matrix", "probability", "expected value", "variance", "theorem", "lemma",
            "equation", "polynomial", "modulo", "prime number", "primes", "combinatorics", "differential",
            "logarithm", "converge", "converges");

    // Not \b after the command: a subscript follows it directly, as in \int_0, and '_' is a word character.
    private static final Pattern LATEX = Pattern.compile("\\\\(?:frac|sum|int|sqrt|lim|prod|begin)(?![A-Za-z])|\\$\\$");

    private static final Pattern DIGIT_OR_EQUALS = Pattern.compile("[0-9=]");

    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^\\s*(?:\\d+[.)]|[-*•])\\s+\\S");

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

        var followUp = followUp(message, priorClass, priorToolCalls);
        if (followUp != null) return followUp;

        var hardCode = new ArrayList<String>();
        if (CODE_FENCE.matcher(text).find()) hardCode.add("code block");
        if (STACK_TRACE.matcher(text).find()) hardCode.add("stack trace");
        if (!hardCode.isEmpty()) return new Classification(TaskClass.CODING, hardCode);

        var strong = strongReasoning(text, lower);
        if (!strong.isEmpty()) return new Classification(TaskClass.REASONING, strong);

        var codeTerms = matches(lower, CODE_TERMS);
        var file = SOURCE_FILE.matcher(text);
        if (file.find()) codeTerms.add(file.group());
        if (codeTerms.size() >= 2) {
            return new Classification(TaskClass.CODING, List.of("code terms: " + String.join(", ", codeTerms)));
        }

        var agentic = agenticSignals(text, lower, priorToolCalls);
        if (!agentic.isEmpty()) return new Classification(TaskClass.AGENTIC, agentic);

        var medium = matches(lower, MEDIUM_REASONING);
        if (NEGATED_CAPABILITY.matcher(lower).find()) medium.add("a negated capability");
        if (medium.size() >= 2) {
            return new Classification(TaskClass.REASONING, List.of("judgement cues: " + String.join(", ", medium)));
        }

        var summary = SUMMARY.matcher(lower);
        if (summary.find()) {
            return new Classification(TaskClass.SUMMARIZE, List.of("summary request: " + summary.group()));
        }
        return new Classification(TaskClass.CHAT, List.of("no task markers"));
    }

    /**
     * A bare acknowledgment ("yes", "go ahead") carries no ask of its own, so it inherits the previous
     * turn's class. Split out because it settles the turn without reading the prompt for cues — which is
     * also what lets {@link RouterClassifier} skip a model call on "thanks".
     */
    public static @Nullable Classification followUp(String message, @Nullable TaskClass priorClass,
                                                    int priorToolCalls) {
        var text = message.length() > MAX_SCANNED_CHARS ? message.substring(0, MAX_SCANNED_CHARS) : message;
        if (!isFollowUp(text, text.toLowerCase(Locale.ROOT))) return null;
        if (priorClass != null) {
            return new Classification(priorClass, List.of("follow-up inherits " + priorClass.id()));
        }
        if (priorToolCalls >= AGENTIC_PRIOR_TOOL_CALLS) {
            return new Classification(TaskClass.AGENTIC,
                    List.of("follow-up after %d tool calls".formatted(priorToolCalls)));
        }
        return null;
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

    /** Cues sure enough on their own: an explicit ask to work something out, or maths. */
    private static List<String> strongReasoning(String text, String lower) {
        var signals = new ArrayList<String>();
        var markers = matches(lower, STRONG_REASONING);
        if (!markers.isEmpty()) signals.add("reasoning markers: " + String.join(", ", markers));
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

    /**
     * Agent work: a clause whose leading verb tells the assistant to act — on its own for a system
     * action, and for {@code create}-style verbs only when the object is something JClaw manages —
     * or two such verbs anywhere, or a numbered plan, or a continuation of a tool-heavy turn.
     */
    private static List<String> agenticSignals(String text, String lower, int priorToolCalls) {
        var leading = new LinkedHashSet<String>();
        var mentioned = new LinkedHashSet<String>();
        for (var rawClause : CLAUSE_SPLIT.split(lower)) {
            var clause = stripLeadIn(rawClause.strip());
            if (clause.isEmpty()) continue;
            var first = FIRST_WORD.matcher(clause);
            if (!first.find()) continue;
            var verb = first.group(1);
            // A system action instructs on its own; a create-style verb only when its object is
            // something JClaw manages.
            if (SYSTEM_ACTIONS.contains(verb)
                    || (OBJECT_ACTIONS.contains(verb) && !matches(clause, ACTION_OBJECTS).isEmpty())) {
                leading.add(verb);
            }
        }
        for (var verb : SYSTEM_ACTIONS) {
            if (Pattern.compile("(?<![\\w-])" + verb + "(?![\\w-])").matcher(lower).find()) mentioned.add(verb);
        }
        var listItems = LIST_ITEM.matcher(text).results().count();
        var toolHeavy = priorToolCalls >= AGENTIC_PRIOR_TOOL_CALLS;
        // A verb merely mentioned mid-sentence ("you may want to look at…") is not an instruction, so
        // it only counts alongside a plan or a tool-heavy turn that already established the intent.
        var actionable = !leading.isEmpty()
                || (mentioned.size() >= 2 && listItems >= 2)
                || (toolHeavy && !mentioned.isEmpty());
        if (!actionable) return List.of();

        var signals = new ArrayList<String>();
        if (!leading.isEmpty()) signals.add("asks to " + String.join(", ", leading));
        else signals.add("actions: " + String.join(", ", mentioned));
        if (listItems >= 2) signals.add("numbered plan");
        if (toolHeavy) signals.add("previous turn used %d tool calls".formatted(priorToolCalls));
        return signals;
    }

    private static String stripLeadIn(String clause) {
        var out = clause;
        for (var i = 0; i < 4; i++) {
            var stripped = LEAD_IN.matcher(out).replaceFirst("");
            if (stripped.equals(out)) break;
            out = stripped;
        }
        return out;
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
