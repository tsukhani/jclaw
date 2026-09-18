package llm.routing;

import llm.LlmProvider;
import llm.LlmResilience;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.PaymentModality;
import llm.ProviderLocality;
import llm.ProviderRegistry;
import llm.ToolCapabilityMemo;
import llm.routing.RouteDecision.Target;
import llm.routing.RouterPolicy.Candidate;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The {@code router/auto} virtual model (JCLAW-1222): picks a concrete model per turn from the
 * operator's {@link RouterPolicy}.
 *
 * <p>For each turn it classifies the prompt, drops candidates that cannot serve (unconfigured,
 * out of credit, breaker cooling down), moves non-chat classes onto the chat list when a prepaid
 * provider's usage crosses the downshift threshold, prefers models that fit the prompt and its
 * attachments, then orders prepaid providers — subscriptions and self-hosted ones — ahead of
 * per-token ones. Within each group the operator's ranking stands. A routing failure never fails
 * the turn: when nothing survives, the filters are dropped one at a time.
 *
 * <p>The router is a catalog entry, not an {@link LlmProvider}: nothing ever dispatches to it. A turn
 * resolves it through {@link RoutedTurn}; a call made outside a turn resolves it through
 * {@link #concrete}.
 */
public final class ModelRouter {

    public static final String PROVIDER = "router";
    public static final String MODEL_ID = "auto";
    public static final String DISPLAY_NAME = "Auto (best value)";

    // A prompt that already fills this much of a window leaves no room for the reply and the tool rounds.
    private static final double CONTEXT_FILL_LIMIT = 0.9;

    private static final List<String> THINKING_LADDER =
            List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

    /**
     * What one turn brings to the choice.
     *
     * @param priorClass            the class the conversation's previous routed turn ran as, or null
     * @param priorTarget           the model that served it, or null
     * @param priorToolCalls        tool calls the previous turn made
     * @param estimatedPromptTokens the prompt this turn will send, or 0 when unknown
     */
    public record RouteRequest(String userMessage, @Nullable TaskClass priorClass, @Nullable Target priorTarget,
                               int priorToolCalls, int estimatedPromptTokens,
                               boolean needsVision, boolean needsAudio, boolean needsTools) {}

    private record Choice(Candidate candidate, ProviderConfig provider, ModelInfo model, boolean prepaid) {
        Target target() {
            return new Target(candidate.provider(), candidate.model());
        }
    }

    private record Eligible(List<Choice> choices, boolean downshifted) {}

    private ModelRouter() {}

    public static boolean isRouter(@Nullable String provider, @Nullable String modelId) {
        return PROVIDER.equals(provider) && MODEL_ID.equals(modelId);
    }

    public static boolean isAvailable() {
        return RouterPolicy.load().available();
    }

    /** Route one turn, or null when the router is not configured or no listed model resolves at all. */
    public static @Nullable RouteDecision route(RouteRequest request) {
        return route(request, RouterPolicy.load());
    }

    /** {@link #route(RouteRequest)} under an explicit policy. */
    public static @Nullable RouteDecision route(RouteRequest request, RouterPolicy policy) {
        if (!policy.available()) return null;
        var classification = RouterClassifier.classify(
                request.userMessage(), request.priorClass(), request.priorToolCalls(), policy);
        return select(policy, classification.taskClass(), classification.signals(), request);
    }

    /**
     * The concrete model behind a pair, for a call made outside a chat turn — memory capture, skill
     * review, prompt generation. A normal pair is returned as-is; the router resolves to its best
     * chat-class model under the budget guard. Null when either id is missing or the router has
     * nothing to offer.
     */
    public static @Nullable Target concrete(@Nullable String provider, @Nullable String modelId) {
        if (provider == null || modelId == null) return null;
        if (!isRouter(provider, modelId)) return new Target(provider, modelId);
        var policy = RouterPolicy.load();
        if (!policy.available()) return null;
        var request = new RouteRequest("", null, null, 0, 0, false, false, false);
        var decision = select(policy, TaskClass.CHAT, List.of("call outside a chat turn"), request);
        return decision != null ? decision.primary() : null;
    }

    /** The model a provider/model pair names, the router's own catalog entry included. */
    public static Optional<ModelInfo> findModel(@Nullable String provider, @Nullable String modelId) {
        if (provider == null || modelId == null) return Optional.empty();
        if (PROVIDER.equals(provider)) {
            var policy = RouterPolicy.load();
            return MODEL_ID.equals(modelId) && policy.available()
                    ? Optional.of(catalogModel(policy)) : Optional.empty();
        }
        var registered = ProviderRegistry.get(provider);
        if (registered == null) return Optional.empty();
        return registered.config().models().stream().filter(m -> m.id().equals(modelId)).findFirst();
    }

    /**
     * The router as a model: what the pickers show and what an attachment check reads. A capability is
     * advertised when any listed model has it, because the router steers a turn that needs it to one
     * that does; the context window is the largest on offer.
     */
    public static ModelInfo catalogModel(RouterPolicy policy) {
        var context = 0;
        var thinking = false;
        var vision = false;
        var audio = false;
        var video = false;
        var levels = new LinkedHashSet<String>();
        for (var taskClass : TaskClass.values()) {
            for (var candidate : policy.candidates(taskClass)) {
                var model = registeredModel(candidate);
                if (model == null) continue;
                context = Math.max(context, model.contextWindow());
                thinking |= model.supportsThinking();
                vision |= model.supportsVision();
                audio |= model.supportsAudio();
                video |= model.supportsVideo();
                levels.addAll(model.effectiveThinkingLevels());
            }
        }
        var ordered = levels.stream().sorted(Comparator.comparingInt(ModelRouter::ladderRank)).toList();
        return new ModelInfo(MODEL_ID, DISPLAY_NAME, context, 0, thinking, vision, audio, video, true,
                -1, -1, -1, -1, ordered.isEmpty() ? null : ordered, false);
    }

    /** Subscriptions and self-hosted providers: the money is spent whether or not a turn uses them. */
    public static boolean isPrepaid(ProviderConfig provider) {
        return provider.paymentModality() == PaymentModality.SUBSCRIPTION
                || (PaymentModality.supportedFor(provider.name()).isEmpty() && ProviderLocality.isLocal(provider.name()));
    }

    private static @Nullable RouteDecision select(RouterPolicy policy, TaskClass taskClass, List<String> signals,
                                                  RouteRequest request) {
        var skipped = new ArrayList<String>();
        var own = resolve(policy.candidates(taskClass), skipped);
        var chat = resolve(policy.candidates(TaskClass.CHAT), new ArrayList<>());

        var eligible = eligible(own, chat, taskClass, policy, true, true, true, skipped);
        var relaxed = false;
        if (eligible.choices().isEmpty()) {
            relaxed = true;
            eligible = eligible(own, chat, taskClass, policy, false, true, true, new ArrayList<>());
        }
        if (eligible.choices().isEmpty()) {
            eligible = eligible(own, chat, taskClass, policy, false, false, true, new ArrayList<>());
        }
        if (eligible.choices().isEmpty()) {
            eligible = eligible(own, chat, taskClass, policy, false, false, false, new ArrayList<>());
        }
        if (eligible.choices().isEmpty()) return null;

        var choices = eligible.choices();
        var tokens = request.estimatedPromptTokens();
        choices = preferring(choices, c -> tokens <= 0 || c.model().contextWindow() <= 0
                || tokens <= c.model().contextWindow() * CONTEXT_FILL_LIMIT, "context window too small", skipped);
        if (request.needsTools()) {
            choices = preferring(choices, c -> c.model().toolCallingSupported()
                    && !ToolCapabilityMemo.isKnownIncapable(c.candidate().provider(), c.candidate().model()),
                    "cannot call tools", skipped);
        }
        if (request.needsVision()) {
            choices = preferring(choices, c -> c.model().supportsVision(), "no image input", skipped);
        }
        if (request.needsAudio()) {
            choices = preferring(choices, c -> c.model().supportsAudio(), "no audio input", skipped);
        }

        // Prepaid first by default — the money is already spent, so spending it again per token is the
        // waste the router exists to stop. An operator who means the opposite turns preferPrepaid off,
        // and then the list is followed exactly as written.
        var ordered = new ArrayList<Choice>(choices.size());
        if (policy.preferPrepaid()) {
            choices.stream().filter(Choice::prepaid).forEach(ordered::add);
            choices.stream().filter(c -> !c.prepaid()).forEach(ordered::add);
        } else {
            ordered.addAll(choices);
        }

        var sticky = false;
        var priorTarget = request.priorTarget();
        if (request.priorClass() == taskClass && priorTarget != null) {
            var index = indexOf(ordered, priorTarget);
            if (index > 0) {
                var prior = ordered.remove(index);
                // Stickiness may reorder within a payment group, never across one: keeping last turn's
                // model must not quietly promote a per-token model over a prepaid one.
                var front = 0;
                if (policy.preferPrepaid()) {
                    while (front < ordered.size() && ordered.get(front).prepaid() != prior.prepaid()) front++;
                }
                ordered.add(front, prior);
                sticky = front == 0;
            }
        }

        var primary = ordered.getFirst();
        var fallback = ordered.stream()
                .filter(c -> !c.candidate().provider().equals(primary.candidate().provider()))
                .findFirst()
                .map(Choice::target)
                .orElse(null);
        return new RouteDecision(taskClass, primary.target(), fallback, signals,
                List.copyOf(new LinkedHashSet<>(skipped)), eligible.downshifted(), sticky, relaxed);
    }

    private static List<Choice> resolve(List<Candidate> candidates, List<String> skipped) {
        var out = new ArrayList<Choice>(candidates.size());
        for (var candidate : candidates) {
            if (PROVIDER.equals(candidate.provider())) continue;
            var provider = ProviderRegistry.get(candidate.provider());
            if (provider == null) {
                skipped.add(candidate.describe() + ": provider not configured");
                continue;
            }
            if ("false".equalsIgnoreCase(ConfigService.get("provider." + candidate.provider() + ".enabled"))) {
                skipped.add(candidate.describe() + ": provider disabled");
                continue;
            }
            var model = provider.config().models().stream()
                    .filter(m -> m.id().equals(candidate.model())).findFirst().orElse(null);
            if (model == null) {
                skipped.add(candidate.describe() + ": model not registered");
                continue;
            }
            out.add(new Choice(candidate, provider.config(), model, isPrepaid(provider.config())));
        }
        return out;
    }

    @SuppressWarnings("java:S107") // one filter pass: the lists, the policy, and which filters are still in force
    private static Eligible eligible(List<Choice> own, List<Choice> chat, TaskClass taskClass, RouterPolicy policy,
                                     boolean applyDownshift, boolean applyBreaker, boolean applyExhaustion,
                                     List<String> skipped) {
        var out = new ArrayList<Choice>();
        var downshifted = false;
        for (var c : own) {
            var unavailable = unavailability(c, policy, applyBreaker, applyExhaustion);
            if (unavailable != null) {
                skipped.add(c.candidate().describe() + ": " + unavailable);
                continue;
            }
            if (applyDownshift && taskClass != TaskClass.CHAT && c.prepaid()) {
                var usage = SubscriptionUsage.current(c.provider());
                if (usage != null && usage.pressure() >= policy.downshiftAt()) {
                    downshifted = true;
                    skipped.add("%s: %s ≥ downshift %s".formatted(
                            c.candidate().describe(), usageText(usage), percent(policy.downshiftAt())));
                    continue;
                }
            }
            out.add(c);
        }
        if (downshifted) {
            for (var c : chat) {
                if (indexOf(out, c.target()) >= 0) continue;
                var unavailable = unavailability(c, policy, applyBreaker, applyExhaustion);
                if (unavailable != null) {
                    skipped.add(c.candidate().describe() + ": " + unavailable);
                    continue;
                }
                out.add(c);
            }
        }
        return new Eligible(out, downshifted);
    }

    private static @Nullable String unavailability(Choice c, RouterPolicy policy,
                                                   boolean applyBreaker, boolean applyExhaustion) {
        if (applyExhaustion) {
            if (SubscriptionUsage.isMarkedUnavailable(c.candidate().provider(), c.candidate().model())) {
                return "out of credit";
            }
            var usage = SubscriptionUsage.current(c.provider());
            if (usage != null && usage.pressure() >= policy.exhaustedAt()) {
                return "%s ≥ exhausted %s".formatted(usageText(usage), percent(policy.exhaustedAt()));
            }
        }
        if (applyBreaker && breakerCoolingDown(c.candidate().provider())) {
            return "circuit breaker open";
        }
        return null;
    }

    private static boolean breakerCoolingDown(String provider) {
        return CircuitBreakers.find(LlmResilience.breakerName(provider))
                .map(CircuitBreaker::isCoolingDown)
                .orElse(false);
    }

    /** Keep the choices {@code test} accepts, unless that would leave none. */
    private static List<Choice> preferring(List<Choice> choices, Predicate<Choice> test, String reason,
                                           List<String> skipped) {
        var kept = choices.stream().filter(test).toList();
        if (kept.isEmpty() || kept.size() == choices.size()) return choices;
        choices.stream().filter(test.negate()).forEach(c -> skipped.add(c.candidate().describe() + ": " + reason));
        return kept;
    }

    private static int indexOf(List<Choice> choices, Target target) {
        for (var i = 0; i < choices.size(); i++) {
            if (choices.get(i).target().equals(target)) return i;
        }
        return -1;
    }

    private static @Nullable ModelInfo registeredModel(Candidate candidate) {
        var provider = ProviderRegistry.get(candidate.provider());
        if (provider == null) return null;
        return provider.config().models().stream()
                .filter(m -> m.id().equals(candidate.model())).findFirst().orElse(null);
    }

    private static String usageText(SubscriptionUsage.Snapshot usage) {
        var window = usage.fullestWindow();
        return (window != null ? window + " " : "") + percent(usage.pressure());
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    private static int ladderRank(String level) {
        var index = THINKING_LADDER.indexOf(level);
        return index >= 0 ? index : THINKING_LADDER.size();
    }
}
