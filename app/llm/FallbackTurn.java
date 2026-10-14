package llm;

import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.ModelOverrideResolver;

import java.lang.ScopedValue.CallableOp;

/**
 * A turn whose provider is not configured runs on the agent's own fallback, provider and model,
 * and never on whichever provider happens to be configured first: that one is a company the
 * operator never chose for this agent, and the prompt would reach it before its model id failed.
 * {@code services.ModelOverrideResolver} consults the binding, so every helper of the turn sizes,
 * prompts and records for the fallback model. The binding has {@link llm.routing.RoutedTurn}'s
 * boundary and matches the conversation the same way.
 */
public final class FallbackTurn {

    private static final ScopedValue<Binding> BOUND = ScopedValue.newInstance();

    private FallbackTurn() {}

    /** The fallback serving one conversation's turn. */
    public static final class Binding {
        private final @Nullable Long conversationId;
        private final Conversation conversation;
        private final String provider;
        private final String modelId;

        private Binding(Conversation conversation, LlmProvider.Fallback fallback) {
            this.conversationId = conversation.id;
            this.conversation = conversation;
            this.provider = fallback.provider().config().name();
            this.modelId = fallback.modelId();
        }

        public String provider() {
            return provider;
        }

        public String modelId() {
            return modelId;
        }

        boolean appliesTo(Conversation candidate) {
            return conversationId != null ? conversationId.equals(candidate.id) : conversation == candidate;
        }
    }

    /**
     * Run {@code body} on the agent's fallback when the turn's provider is not configured and the
     * fallback is; otherwise run it as it is, and a missing provider fails the turn where the
     * provider is resolved.
     */
    public static <R, X extends Throwable> R run(Agent agent, Conversation conversation, CallableOp<R, X> body)
            throws X {
        var name = ModelOverrideResolver.provider(conversation, agent);
        if (name != null && ProviderRegistry.get(name) != null) return body.call();
        var fallback = LlmProvider.Fallback.forAgent(agent);
        if (fallback == null) return body.call();
        EventLogger.warn("llm", agent.name, conversation.channelType,
                "Provider '%s' is not configured; the turn runs on the agent's fallback, %s"
                        .formatted(name, fallback.describe()));
        return ScopedValue.where(BOUND, new Binding(conversation, fallback)).call(body);
    }

    /** The fallback bound for {@code conversation}, or null outside one or inside another conversation's. */
    public static @Nullable Binding current(@Nullable Conversation conversation) {
        if (conversation == null || !BOUND.isBound()) return null;
        var binding = BOUND.get();
        return binding.appliesTo(conversation) ? binding : null;
    }
}
