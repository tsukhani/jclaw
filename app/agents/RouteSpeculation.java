package agents;

import io.opentelemetry.context.Context;
import llm.LlmProvider;
import llm.LlmResilience;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import llm.TokenUsageEstimator;
import llm.routing.ModelRouter;
import llm.routing.RouteDecision;
import llm.routing.RouteDecision.Target;
import llm.routing.RoutedTurn;
import llm.routing.RouterPolicy;
import llm.routing.SubscriptionUsage;
import llm.routing.TaskClass;
import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.AttachmentService;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.LatencyTrace;

import java.lang.ScopedValue.CallableOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A routed turn's model call, started on the previous turn's model while the router decides (JF-377). On the Main
 * Agent the classifier took 377 ms at p50 and 3.0 s at p99, and over 302 consecutive routed turns the router kept the
 * previous model 78% of the time, so starting the call at once takes that wait off most turns.
 *
 * <p>The router runs on a forked virtual thread while the turn thread builds the prompt for the previous model and
 * starts the call. Its output is held from the reader in {@link Held}, and the turn runs no tool until the route is
 * known. When the router keeps the model and every input the request took from it is unchanged ({@link ModelInputs}),
 * the held output is released in order and the turn carries on; otherwise the call is cancelled by aborting its HTTP
 * request, never by interrupting a thread (AGENTS.md, Known pitfalls), and the turn runs on the chosen model as before.
 *
 * <p>A discarded call is still billed, so a turn speculates only where {@link RouterPolicy#SPECULATE} allows, by
 * default on a prepaid provider alone.
 */
final class RouteSpeculation {

    private static final ScopedValue<Long> BUILDING = ScopedValue.newInstance();

    private final RouteDecision speculative;
    private final CompletableFuture<@Nullable RouteDecision> route = new CompletableFuture<>();

    private RouteSpeculation(Target target, TurnRouting.PriorTurn prior) {
        var priorClass = prior.taskClass();
        var taskClass = priorClass != null ? priorClass : TaskClass.CHAT;
        var priorEffort = prior.effort();
        this.speculative = new RouteDecision(taskClass, priorEffort != null ? priorEffort : taskClass.defaultEffort(),
                target, null, List.of("the previous turn's model, while the router decides"), List.of(),
                false, false, false, null);
    }

    /** The previous turn's model, which the call starts on. */
    Target target() {
        return speculative.primary();
    }

    /** The turn's speculation with the router started beside it, or null when the turn routes first, as before. */
    static @Nullable RouteSpeculation start(Agent agent, Conversation conversation, TurnRouting.PriorTurn prior,
                                            String userMessage, @Nullable List<AttachmentService.Input> attachments,
                                            @Nullable String channel, @Nullable LatencyTrace trace) {
        var target = speculativeTarget(prior, attachments);
        if (target == null || conversation.id == null) return null;
        var speculation = new RouteSpeculation(target, prior);
        Thread.ofVirtual().name("route-decide").start(Context.current().wrap(() -> {
            try (var _ = LatencyTrace.bind(trace)) {
                speculation.route.complete(
                        TurnRouting.decide(agent, conversation, prior, userMessage, attachments, channel, trace));
            } catch (RuntimeException | Error e) {
                speculation.route.completeExceptionally(e);
            }
        }));
        return speculation;
    }

    /**
     * The previous turn's model, when this turn may start on it: the turn brings no attachment, whose handling the
     * prompt build takes from the model and the router routes on; the model is still registered; nothing would make the
     * router pass it over at once; and {@link RouterPolicy#SPECULATE} allows its provider.
     */
    static @Nullable Target speculativeTarget(TurnRouting.PriorTurn prior,
                                              @Nullable List<AttachmentService.Input> attachments) {
        var target = prior.target();
        if (target == null || (attachments != null && !attachments.isEmpty())) return null;
        var provider = ProviderRegistry.get(target.provider());
        if (provider == null || provider.config().models().stream().noneMatch(m -> m.id().equals(target.modelId()))) {
            return null;
        }
        if (SubscriptionUsage.isMarkedUnavailable(target.provider(), target.modelId())) return null;
        // A breaker that is not closed admits only probes, and a call the router may discard must not spend one.
        var breaker = CircuitBreakers.find(LlmResilience.breakerName(target.provider()));
        if (breaker.isPresent() && breaker.get().state() != CircuitBreaker.State.CLOSED) return null;
        return switch (RouterPolicy.speculation()) {
            case OFF -> null;
            case ALL -> target;
            case PREPAID -> ModelRouter.isPrepaid(provider.config()) ? target : null;
        };
    }

    /** {@code body} on the previous turn's model, its prompt build giving up rather than compacting inline. */
    <R, X extends Throwable> R onPreviousModel(Conversation conversation, CallableOp<R, X> body) throws X {
        var conversationId = Objects.requireNonNull(conversation.id);
        return RoutedTurn.callWith(speculative, conversation, () -> ScopedValue.where(BUILDING, conversationId).call(body));
    }

    /** The router's decision, waited for: the classifier's own timeout bounds the wait. */
    @Nullable RouteDecision awaitRoute() {
        try {
            return route.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * Called before a prompt build compacts inline. A speculative build gives up instead, so the summary call and its
     * progress frames come from the chosen route's build, as before, and never from a build the router may discard.
     */
    static void yieldBeforeInlineCompaction(@Nullable Long conversationId) {
        if (BUILDING.isBound() && BUILDING.get().equals(conversationId)) throw new Abandoned();
    }

    /** A speculative prompt build that gave up; the turn routes first and builds on the chosen model. */
    static final class Abandoned extends RuntimeException {
        Abandoned() {
            super("a speculative prompt build reached an inline compaction", null, false, false);
        }
    }

    /**
     * Ends {@code call} unread, its output dropped, and returns what it used: the provider's own counts when it had
     * finished, else the local tokenizer's, from the prompt sent and the text it had streamed.
     */
    static LlmProvider.TurnUsage.DiscardedCall discard(LlmProvider.StreamAccumulator call, Held held,
                                                       ModelInputs inputs) {
        held.discard();
        call.cancel();
        var model = inputs.modelInfo();
        double promptPrice = model != null ? model.promptPrice() : -1;
        double completionPrice = model != null ? model.completionPrice() : -1;
        var provider = Objects.requireNonNullElse(inputs.provider(), "");
        var modelId = Objects.requireNonNullElse(inputs.modelId(), "");
        boolean finished = call.complete() && call.error() == null;
        var usage = finished ? call.usage() : null;
        if (usage != null) {
            return new LlmProvider.TurnUsage.DiscardedCall(provider, modelId, usage.promptTokens(),
                    usage.completionTokens(), true, usage.costUsd(), promptPrice, completionPrice);
        }
        var prompt = call.promptTokenEstimate();
        var completion = finished ? call.completionTokenEstimate() : null;
        int completionTokens = completion != null ? completion.tokens()
                : TokenUsageEstimator.estimateCompletion(modelId, held.content(), List.of(), held.reasoning()).tokens();
        return new LlmProvider.TurnUsage.DiscardedCall(provider, modelId, prompt != null ? prompt.promptTokens() : 0,
                completionTokens, false, 0d, promptPrice, completionPrice);
    }

    /**
     * What a request takes from the model it is built for: the model, its catalog entry (context window, output limit,
     * tool, vision and audio support) and the thinking level the route's effort fits to on it. The tools offered come
     * from the agent and the conversation alone, and whether they are sent is decided from the model at dispatch, so
     * equal inputs under the speculative and the chosen route mean the started request is the one the route would send.
     */
    record ModelInputs(@Nullable String provider, @Nullable String modelId, @Nullable ModelInfo modelInfo,
                       @Nullable String thinkingMode) {

        /** The inputs under the route bound for {@code conversation}. */
        static ModelInputs of(Agent agent, Conversation conversation) {
            var providerName = ModelResolver.effectiveModelProvider(agent, conversation);
            var provider = providerName != null ? ProviderRegistry.get(providerName) : null;
            var modelId = ModelResolver.effectiveModelId(agent, conversation);
            if (provider == null) return new ModelInputs(providerName, modelId, null, null);
            return new ModelInputs(providerName, modelId, ModelResolver.resolveModelInfo(agent, conversation, provider).orElse(null),
                    ModelResolver.resolveThinkingMode(agent, conversation, provider));
        }
    }

    /**
     * The started call's output, held from the reader until the route is known: replayed in the order it arrived on
     * {@link #release}, then passed straight through; dropped from {@link #discard} on.
     */
    static final class Held {

        private final AgentRunner.StreamingCallbacks real;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<Consumer<AgentRunner.StreamingCallbacks>> pending = new ArrayList<>();
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private boolean released;
        private boolean discarded;

        Held(AgentRunner.StreamingCallbacks real) {
            this.real = real;
        }

        /**
         * The callbacks the speculative build and call report to. A cancel goes straight through, since it ends the
         * turn whichever route wins; a build's error is dropped, the chosen route's build being the one to meet and
         * report it.
         */
        AgentRunner.StreamingCallbacks callbacks() {
            return new AgentRunner.StreamingCallbacks(
                    real.onInit(),
                    token -> hold(cb -> cb.onToken().accept(token), content, token),
                    text -> hold(cb -> cb.onReasoning().accept(text), reasoning, text),
                    status -> hold(cb -> cb.onStatus().accept(status), null, null),
                    toolCall -> hold(cb -> cb.onToolCall().accept(toolCall), null, null),
                    reply -> hold(cb -> cb.onComplete().accept(reply), null, null),
                    _ -> { },
                    real.onCancel());
        }

        private void hold(Consumer<AgentRunner.StreamingCallbacks> event, @Nullable StringBuilder text,
                          @Nullable String part) {
            lock.lock();
            try {
                if (discarded) return;
                if (released) {
                    event.accept(real);
                    return;
                }
                if (text != null && part != null) text.append(part);
                pending.add(event);
            } finally {
                lock.unlock();
            }
        }

        void release() {
            lock.lock();
            try {
                for (var event : pending) {
                    try {
                        event.accept(real);
                    } catch (RuntimeException _) {
                        // A reader that fails on one event must not end the turn before its stream is read; the SSE
                        // wire's chunk loop swallows the same failure delivered live.
                    }
                }
                pending.clear();
                released = true;
            } finally {
                lock.unlock();
            }
        }

        void discard() {
            lock.lock();
            try {
                discarded = true;
                pending.clear();
            } finally {
                lock.unlock();
            }
        }

        String content() {
            lock.lock();
            try {
                return content.toString();
            } finally {
                lock.unlock();
            }
        }

        String reasoning() {
            lock.lock();
            try {
                return reasoning.toString();
            } finally {
                lock.unlock();
            }
        }
    }
}
