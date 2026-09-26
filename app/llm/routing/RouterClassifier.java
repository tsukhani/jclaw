package llm.routing;

import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.ProviderRegistry;
import llm.routing.PromptClassifier.Classification;
import llm.routing.RouterPolicy.Candidate;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.decision.DecisionSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Labels a prompt with its {@link TaskClass} and the {@link ReasoningEffort} it deserves, through a model when the operator has named one
 * ({@code router.classifier.provider} / {@code .model}), TypeSafe's JEV judge when that pair is
 * {@code jev} / {@code jev-latest} (JCLAW-1300), and through {@link PromptClassifier}'s local
 * rules otherwise (JCLAW-1222).
 *
 * <p>The rules cost nothing and explain themselves, but they read words rather than intent, so a
 * demanding prompt phrased outside their vocabulary stays on the cheap model. A small model asked one
 * question — "which of these five classes is this?" — reads the intent instead. The trade is one extra
 * call on the turn's critical path, which is why it is opt-in rather than the default.
 *
 * <p>The rules remain the floor. A classifier that is unreachable, slow, or answers with something that
 * is not a class never fails the turn: the failure is logged once and the rules answer instead. A bare
 * acknowledgment is settled before either runs, so "thanks" never costs a model call.
 */
public final class RouterClassifier {

    /** How much of the prompt the classifier is shown. The ask is almost always in the first lines. */
    public static final int MAX_PROMPT_CHARS = 4_000;

    /**
     * Output budget for a one-word answer. Generous on purpose: a model that always reasons
     * (glm-5.3-flash, GLM 5.3, the o-series) spends this budget thinking before it writes anything, and
     * a tight cap returns an empty completion rather than a class.
     */
    private static final int MAX_ANSWER_TOKENS = 256;

    /** Event-log category and the caller tag the provider records for these calls. */
    private static final String ROUTER = "router";

    /** What each class means, in the order both classifiers read it: the LLM in its prompt, JEV as its choices. */
    static final Map<TaskClass, String> CLASS_DEFINITIONS = ordered(Map.of(
            TaskClass.CHAT, "greetings, small talk, clarifications, short factual lookups, light writing.",
            TaskClass.SUMMARIZE, "a request to summarize, recap, condense or extract key points from material.",
            TaskClass.AGENTIC, "an instruction to DO something with tools: run, search, send, schedule, download, "
                    + "create a file/task/reminder/image, or multi-step work.",
            TaskClass.REASONING, "an answer that must be worked out rather than recalled: proofs, maths, judgement, "
                    + "critique, comparisons, causes, open questions, design trade-offs.",
            TaskClass.CODING, "code, stack traces, debugging, or writing and changing software."));

    static final Map<ReasoningEffort, String> EFFORT_DEFINITIONS = ordered(Map.of(
            ReasoningEffort.LOW, "the answer is quick to give: small talk, a lookup, a routine edit.",
            ReasoningEffort.MEDIUM, "some care is needed: a few steps, a moderate amount of code, a balanced judgement.",
            ReasoningEffort.HIGH, "hard: a proof, a subtle bug, a design with real trade-offs, anything easy to get wrong."));

    private static final String INSTRUCTIONS = """
            You route one user message to the model class that should answer it, and say how hard that \
            model should think. Reply with exactly two words and nothing else: the class, then the effort.

            Classes:

            %s
            Efforts:

            %s
            Choose the single best class and effort, e.g. "reasoning high". Answer with two words."""
            .formatted(lines(CLASS_DEFINITIONS, TaskClass::id), lines(EFFORT_DEFINITIONS, ReasoningEffort::id));

    private static final Pattern CLASS_WORD = Pattern.compile("(?<![a-z])(chat|summarize|agentic|reasoning|coding)(?![a-z])");
    private static final Pattern EFFORT_WORD = Pattern.compile("(?<![a-z])(low|medium|high)(?![a-z])");

    private RouterClassifier() {}

    /**
     * The class for {@code message}, and how it was decided. Never throws: every classifier failure
     * degrades to the local rules.
     */
    public static Classification classify(String message, @Nullable TaskClass priorClass, int priorToolCalls,
                                          RouterPolicy policy) {
        return classify(message, priorClass, priorToolCalls, policy, DecisionSettings::apiKey);
    }

    /** {@link #classify(String, TaskClass, int, RouterPolicy)}, reading the TypeSafe key from {@code jevKey} only when JEV is asked. */
    public static Classification classify(String message, @Nullable TaskClass priorClass, int priorToolCalls,
                                          RouterPolicy policy, Supplier<@Nullable String> jevKey) {
        var followUp = PromptClassifier.followUp(message, priorClass, priorToolCalls);
        if (followUp != null) return followUp;

        var classifier = policy.classifier();
        if (classifier == null) return PromptClassifier.classify(message, priorClass, priorToolCalls);
        if (RouterPolicy.JEV.equals(classifier.provider())) {
            return askJev(message, priorClass, priorToolCalls, policy, jevKey.get());
        }

        var answer = askModel(message, classifier, policy.classifierTimeoutSeconds());
        var labeled = parse(answer);
        if (labeled != null) {
            var effort = parseEffort(answer);
            return new Classification(labeled, effort != null ? effort : labeled.defaultEffort(),
                    List.of("classified by " + classifier.describe()));
        }
        if (answer != null) {
            EventLogger.warn(ROUTER, "Classifier %s answered with no class (%s); using the keyword rules"
                    .formatted(classifier.describe(), answer.isBlank() ? "empty" : abbreviate(answer)));
        }
        return PromptClassifier.classify(message, priorClass, priorToolCalls);
    }

    /** JEV's class and effort, or the keyword rules' when JEV cannot answer, is not sure enough, or its breaker is open. */
    private static Classification askJev(String message, @Nullable TaskClass priorClass, int priorToolCalls,
                                         RouterPolicy policy, @Nullable String apiKey) {
        var verdict = JevRouterClassifier.classify(message, apiKey, policy.jevMinConfidence(),
                policy.classifierTimeoutSeconds());
        if (verdict.classification() != null) return verdict.classification();
        var rules = PromptClassifier.classify(message, priorClass, priorToolCalls);
        if (!verdict.signal() || verdict.reason() == null) return rules;
        var signals = new ArrayList<>(rules.signals());
        signals.add(verdict.reason());
        return new Classification(rules.taskClass(), rules.effort(), List.copyOf(signals));
    }

    /**
     * The classifier's raw answer, or null when the call itself failed.
     *
     * <p>The request carries exactly two messages — these instructions and the prompt — and no tools.
     * None of the turn's own context reaches it: no assembled system prompt, no standing orders, no
     * history, no memories, no tool catalog. That keeps the call one cheap question about one message,
     * and keeps a classifier on a different provider from becoming a second copy of the conversation.
     * {@code RouterClassifierTest.theClassifierRequestCarriesOnlyItsOwnInstructionAndThePrompt} pins it.
     */
    private static @Nullable String askModel(String message, Candidate classifier, int timeoutSeconds) {
        var provider = ProviderRegistry.get(classifier.provider());
        if (provider == null) {
            EventLogger.warn(ROUTER, "Classifier provider '%s' is not configured; using the keyword rules"
                    .formatted(classifier.provider()));
            return null;
        }
        var prompt = truncate(message);
        ChatResponse response;
        try {
            response = provider.chat(classifier.model(),
                    List.of(ChatMessage.system(INSTRUCTIONS), ChatMessage.user(prompt)),
                    List.of(), MAX_ANSWER_TOKENS, null, timeoutSeconds, ROUTER);
        } catch (RuntimeException e) {
            EventLogger.warn(ROUTER, "Classifier %s failed (%s); using the keyword rules"
                    .formatted(classifier.describe(), e.getMessage()));
            return null;
        }
        var answer = firstContent(response);
        return answer != null ? answer : "";
    }

    /** The first class named anywhere in the answer, so {@code "reasoning."} and {@code {"class":"chat"}} both read. */
    static @Nullable TaskClass parse(@Nullable String answer) {
        if (answer == null || answer.isBlank()) return null;
        var m = CLASS_WORD.matcher(answer.toLowerCase(Locale.ROOT));
        return m.find() ? TaskClass.fromId(m.group(1)) : null;
    }

    /** The first effort named in the answer; null when the model gave only a class. */
    static @Nullable ReasoningEffort parseEffort(@Nullable String answer) {
        if (answer == null || answer.isBlank()) return null;
        var m = EFFORT_WORD.matcher(answer.toLowerCase(Locale.ROOT));
        return m.find() ? ReasoningEffort.fromId(m.group(1)) : null;
    }

    private static @Nullable String firstContent(ChatResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) return null;
        var content = response.choices().getFirst().message().content();
        return content instanceof String text ? text : null;
    }

    static String truncate(String message) {
        return message.length() > MAX_PROMPT_CHARS ? message.substring(0, MAX_PROMPT_CHARS) : message;
    }

    private static <E extends Enum<E>> Map<E, String> ordered(Map<E, String> definitions) {
        return Collections.unmodifiableMap(new EnumMap<>(definitions));
    }

    private static <E extends Enum<E>> String lines(Map<E, String> definitions, Function<E, String> id) {
        var out = new StringBuilder();
        definitions.forEach((k, v) -> out.append(id.apply(k)).append(" — ").append(v).append('\n'));
        return out.toString();
    }

    private static String abbreviate(String text) {
        var oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }
}
