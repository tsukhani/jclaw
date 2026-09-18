package llm.routing;

import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.ProviderRegistry;
import llm.routing.PromptClassifier.Classification;
import llm.routing.RouterPolicy.Candidate;
import org.jspecify.annotations.Nullable;
import services.EventLogger;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Labels a prompt with its {@link TaskClass}, through a model when the operator has named one
 * ({@code router.classifier.provider} / {@code .model}) and through {@link PromptClassifier}'s local
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

    private static final String INSTRUCTIONS = """
            You route one user message to the model class that should answer it. Reply with exactly one \
            word and nothing else, from this list:

            chat — greetings, small talk, clarifications, short factual lookups, light writing.
            summarize — a request to summarize, recap, condense or extract key points from material.
            agentic — an instruction to DO something with tools: run, search, send, schedule, download, \
            create a file/task/reminder/image, or multi-step work.
            reasoning — an answer that must be worked out rather than recalled: proofs, maths, judgement, \
            critique, comparisons, causes, open questions, design trade-offs.
            coding — code, stack traces, debugging, or writing and changing software.

            Choose the single best class. Answer with one word.""";

    private static final Pattern CLASS_WORD = Pattern.compile("(?<![a-z])(chat|summarize|agentic|reasoning|coding)(?![a-z])");

    private RouterClassifier() {}

    /**
     * The class for {@code message}, and how it was decided. Never throws: every classifier failure
     * degrades to the local rules.
     */
    public static Classification classify(String message, @Nullable TaskClass priorClass, int priorToolCalls,
                                          RouterPolicy policy) {
        var followUp = PromptClassifier.followUp(message, priorClass, priorToolCalls);
        if (followUp != null) return followUp;

        var classifier = policy.classifier();
        if (classifier == null) return PromptClassifier.classify(message, priorClass, priorToolCalls);

        var labelled = askModel(message, classifier, policy.classifierTimeoutSeconds());
        if (labelled != null) {
            return new Classification(labelled, List.of("classified by " + classifier.describe()));
        }
        return PromptClassifier.classify(message, priorClass, priorToolCalls);
    }

    /**
     * One classification call, or null when it could not produce a class.
     *
     * <p>The request carries exactly two messages — these instructions and the prompt — and no tools.
     * None of the turn's own context reaches it: no assembled system prompt, no standing orders, no
     * history, no memories, no tool catalog. That keeps the call one cheap question about one message,
     * and keeps a classifier on a different provider from becoming a second copy of the conversation.
     * {@code RouterClassifierTest.theClassifierRequestCarriesOnlyItsOwnInstructionAndThePrompt} pins it.
     */
    private static @Nullable TaskClass askModel(String message, Candidate classifier, int timeoutSeconds) {
        var provider = ProviderRegistry.get(classifier.provider());
        if (provider == null) {
            EventLogger.warn(ROUTER, "Classifier provider '%s' is not configured; using the keyword rules"
                    .formatted(classifier.provider()));
            return null;
        }
        var prompt = message.length() > MAX_PROMPT_CHARS ? message.substring(0, MAX_PROMPT_CHARS) : message;
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
        var parsed = parse(answer);
        if (parsed == null) {
            EventLogger.warn(ROUTER, "Classifier %s answered with no class (%s); using the keyword rules"
                    .formatted(classifier.describe(), answer == null ? "empty" : abbreviate(answer)));
        }
        return parsed;
    }

    /** The first class named anywhere in the answer, so {@code "reasoning."} and {@code {"class":"chat"}} both read. */
    static @Nullable TaskClass parse(@Nullable String answer) {
        if (answer == null || answer.isBlank()) return null;
        var m = CLASS_WORD.matcher(answer.toLowerCase(Locale.ROOT));
        return m.find() ? TaskClass.fromId(m.group(1)) : null;
    }

    private static @Nullable String firstContent(ChatResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) return null;
        var content = response.choices().getFirst().message().content();
        return content instanceof String text ? text : null;
    }

    private static String abbreviate(String text) {
        var oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }
}
