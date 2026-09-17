package llm.routing;

import llm.routing.RouteDecision.Target;
import models.Conversation;
import org.jspecify.annotations.Nullable;

import java.lang.ScopedValue.CallableOp;

/**
 * Carries a routed turn's model to every helper that resolves "the model for this conversation"
 * (JCLAW-1222). {@code services.ModelOverrideResolver} consults it, so the context-window manager,
 * compaction, the prompt assembler and the usage record all see the routed model without a
 * parameter threaded through each of them.
 *
 * <p>The binding is a {@link ScopedValue}, so it has the propagation boundary AppClock documents:
 * it reaches this thread and nothing it hands work to. It applies only to the conversation it was
 * bound for — matched by id, so a re-fetched entity still resolves, or by identity for a task's
 * unsaved stub — so a subagent run nested inside the turn resolves its own model.
 */
public final class RoutedTurn {

    private static final ScopedValue<Binding> BOUND = ScopedValue.newInstance();

    private RoutedTurn() {}

    /** One routed turn: the decision, plus the model currently serving it. */
    public static final class Binding {

        private final RouteDecision decision;
        private final @Nullable Long conversationId;
        private final Conversation conversation;
        private volatile Target active;

        private Binding(RouteDecision decision, Conversation conversation) {
            this.decision = decision;
            this.conversationId = conversation.id;
            this.conversation = conversation;
            this.active = decision.primary();
        }

        public RouteDecision decision() {
            return decision;
        }

        public Target active() {
            return active;
        }

        public boolean failedOver() {
            return !active.equals(decision.primary());
        }

        /** The fallback while the primary still serves; none once it has taken over. */
        public @Nullable Target fallback() {
            return failedOver() ? null : decision.fallback();
        }

        /** Hand the rest of the turn to the fallback. Returns it, or null when there is none to promote. */
        public @Nullable Target promote() {
            var fallback = fallback();
            if (fallback != null) active = fallback;
            return fallback;
        }

        boolean appliesTo(Conversation candidate) {
            return conversationId != null ? conversationId.equals(candidate.id) : conversation == candidate;
        }
    }

    public static <R, X extends Throwable> R callWith(RouteDecision decision, Conversation conversation,
                                                      CallableOp<R, X> body) throws X {
        return ScopedValue.where(BOUND, new Binding(decision, conversation)).call(body);
    }

    /** The routed turn bound for {@code conversation}, or null outside one or inside another conversation's. */
    public static @Nullable Binding current(@Nullable Conversation conversation) {
        if (conversation == null || !BOUND.isBound()) return null;
        var binding = BOUND.get();
        return binding.appliesTo(conversation) ? binding : null;
    }
}
