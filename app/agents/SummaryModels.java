package agents;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import llm.ProviderRegistry;
import llm.TokenUsageEstimator;
import llm.routing.ModelRouter;
import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.ModelOverrideResolver;
import services.SessionCompactor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The models that write a conversation's compaction summary, and the call that tries them in order (JF-160): the one
 * resolver {@link CompactionGate} and {@code /compact} share. An agent whose configured model, the conversation's
 * override else its own, is the router summarizes with the router's summarize class, its choice and then that choice's
 * fallback; any other agent, or a router with nothing to offer, summarizes with the caller's own model, so a
 * conversation never reaches a provider its agent was not set up to use. An agent's own compaction model (JF-239) goes
 * first, an operator's explicit choice for that agent, with the caller's own model behind it; the router entry there
 * means the router's summarize class, so a fixed-model agent can ask for it.
 */
public final class SummaryModels implements SessionCompactor.ModelCall {

    /** One model a summary may be written by. */
    public record Choice(LlmProvider provider, String modelId) {
        public String label() {
            return provider.config().name() + "/" + modelId;
        }

        @Nullable ModelInfo info() {
            return provider.config().models().stream().filter(m -> m.id().equals(modelId)).findFirst().orElse(null);
        }
    }

    private final List<Choice> choices;
    private final boolean routed;
    private final boolean overridden;
    private final int maxOutput;
    private final @Nullable String channel;
    private volatile @Nullable Choice answered;

    private SummaryModels(List<Choice> choices, boolean routed, boolean overridden, @Nullable String channel) {
        this.choices = choices;
        this.routed = routed;
        this.overridden = overridden;
        this.maxOutput = SessionCompactor.summaryMaxTokens();
        this.channel = channel;
    }

    /**
     * @param own     the caller's own model, asked for only when the router does not decide; null when there is none
     * @param channel the chat channel the summary call is recorded under, or null outside one
     */
    public static SummaryModels resolve(Agent agent, Conversation conversation, Supplier<@Nullable Choice> own,
                                        @Nullable String channel) {
        var chosen = compactionModelOf(agent);
        if (chosen != null) {
            var fallback = own.get();
            var list = fallback != null && !fallback.label().equals(chosen.label())
                    ? List.of(chosen, fallback) : List.of(chosen);
            return new SummaryModels(list, false, true, channel);
        }
        var configured = ModelOverrideResolver.configured(conversation, agent);
        var choices = new ArrayList<Choice>(2);
        if (ModelRouter.isRouter(configured.provider(), configured.modelId()) || choosesTheRouter(agent)) {
            for (var target : ModelRouter.summarizers()) {
                var provider = ProviderRegistry.get(target.provider());
                if (provider != null) choices.add(new Choice(provider, target.modelId()));
            }
        }
        if (!choices.isEmpty()) return new SummaryModels(List.copyOf(choices), true, false, channel);
        var fallback = own.get();
        return new SummaryModels(fallback != null ? List.of(fallback) : List.of(), false, false, channel);
    }

    /** True when the agent's compaction model is the router entry: summaries go to the router's summarize class. */
    private static boolean choosesTheRouter(Agent agent) {
        return agent.compactionModelOverridden()
                && ModelRouter.isRouter(agent.compactionProvider, agent.compactionModel);
    }

    /** The agent's own compaction model, or null when it sets none or that provider or model is no longer configured. */
    private static @Nullable Choice compactionModelOf(Agent agent) {
        if (!agent.compactionModelOverridden() || choosesTheRouter(agent)) return null;
        var provider = ProviderRegistry.get(agent.compactionProvider);
        if (provider == null) return null;
        var choice = new Choice(provider, agent.compactionModel);
        return choice.info() != null ? choice : null;
    }

    public boolean isEmpty() {
        return choices.isEmpty();
    }

    /** The model expected to write the summary; call only when not {@link #isEmpty}. */
    public Choice first() {
        return choices.getFirst();
    }

    /** The most tokens one summary call's rows may carry, by the first choice's window. */
    public int callTokens() {
        return SessionCompactor.summaryCallTokens(first().info());
    }

    @Override
    public ChatResponse call(List<ChatMessage> messages, List<ToolDef> tools) {
        var primary = first();
        answered = primary;
        if (overridden) return callOverride(primary, messages, tools);
        // The caller's own model fails as it always has: no fallback, and no router bench on its provider.
        if (!routed) return primary.provider().chat(primary.modelId(), messages, tools, maxOutput, null, channel);
        var next = choices.size() > 1 ? choices.get(1) : null;
        var fallback = next != null ? new LlmProvider.Fallback(next.provider(), next.modelId()) : null;
        return LlmProvider.chatWithFailover(primary.provider(), fallback, primary.modelId(), messages, tools,
                maxOutput, null, channel, () -> answered = next);
    }

    /** The agent's compaction model, then the caller's own model when it fails; neither is benched by the router. */
    private ChatResponse callOverride(Choice primary, List<ChatMessage> messages, List<ToolDef> tools) {
        try {
            return primary.provider().chat(primary.modelId(), messages, tools, maxOutput, null, channel);
        } catch (RuntimeException e) {
            if (choices.size() < 2) throw e;
            var next = choices.get(1);
            EventLogger.warn("compaction", null, channel, "Compaction model %s failed (%s); %s writes the summary"
                    .formatted(primary.label(), e.getMessage(), next.label()));
            answered = next;
            return next.provider().chat(next.modelId(), messages, tools, maxOutput, null, channel);
        }
    }

    @Override
    public boolean fits(List<ChatMessage> messages, List<ToolDef> tools) {
        // The caller's own model is the one the turn sends its whole prompt to, so a prefix of it needs no check.
        if (!routed && !overridden) return true;
        var primary = first();
        int tokens = ContextWindowManager.adjustedPromptTokens(primary.provider().config().name(), primary.modelId(),
                TokenUsageEstimator.estimateChatRequest(primary.modelId(), messages, tools));
        return tokens <= SessionCompactor.windowBudget(primary.info());
    }

    @Override
    public @Nullable String answeredBy() {
        var choice = answered;
        return choice != null ? choice.label() : null;
    }
}
