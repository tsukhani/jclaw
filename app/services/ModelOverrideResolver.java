package services;

import models.Agent;
import models.Conversation;

/**
 * Single source of truth for the (provider, modelId) pair driving a given
 * turn. The agent carries the default; a {@link Conversation} may carry an
 * override that takes precedence for its own turns. Two override columns
 * exist (provider + id) and the resolver treats them as a unit — either
 * both are set (override active) or both are null (use the agent default).
 *
 * <p><b>Precedence.</b> When {@code conversation.modelProviderOverride} and
 * {@code conversation.modelIdOverride} are both non-null, those values win;
 * otherwise the resolver falls back to {@code agent.modelProvider} and
 * {@code agent.modelId}. A half-set override (one column non-null, the
 * other null) is undefined state — callers should write both via
 * {@link ConversationService#setModelOverride} and clear both via
 * {@link ConversationService#clearModelOverride}. The resolver tolerates
 * such states defensively by treating them as "no override" rather than
 * fishing values from across the columns.
 *
 * <p><b>Why one helper.</b> The same precedence rule appears in JCLAW-108
 * ({@code /model} slash command, per-conversation override) and in
 * JCLAW-269 (per-spawn override recorded on the child Conversation).
 * Centralising it means the cost-attribution dashboard (JCLAW-28), the
 * AgentRunner LLM dispatch path, the Telegram model picker, and the slash
 * command handlers all read the same source — no risk of a fourth call
 * site rolling its own slightly-different copy.
 */
public final class ModelOverrideResolver {

    private ModelOverrideResolver() {}

    /**
     * The resolved (provider, modelId) pair for a turn. Either field may be
     * {@code null} when neither the conversation override nor the agent
     * default supplies a value — callers handle that by surfacing a
     * "no LLM provider configured" error to the user.
     */
    public record Resolved(String provider, String modelId) {}

    /**
     * Resolve the effective provider + model id. Null-safe on both arguments
     * so legacy callers and test fixtures that don't thread a conversation
     * (or an agent) keep working.
     */
    public static Resolved resolve(Conversation conversation, Agent agent) {
        return new Resolved(provider(conversation, agent), modelId(conversation, agent));
    }

    /** Effective provider name. See {@link #resolve} for precedence. */
    public static String provider(Conversation conversation, Agent agent) {
        if (hasOverride(conversation)) {
            return conversation.modelProviderOverride;
        }
        return agent != null ? agent.modelProvider : null;
    }

    /** Effective model id. See {@link #resolve} for precedence. */
    public static String modelId(Conversation conversation, Agent agent) {
        if (hasOverride(conversation)) {
            return conversation.modelIdOverride;
        }
        return agent != null ? agent.modelId : null;
    }

    /**
     * True when the conversation carries a fully-populated override (both
     * columns non-null). Exposed for UI surfaces (Telegram picker, slash
     * command status output) that need to distinguish "override active"
     * from "inheriting from agent" — the resolved values alone don't
     * reveal which side won.
     */
    public static boolean hasOverride(Conversation conversation) {
        return conversation != null
                && conversation.modelProviderOverride != null
                && conversation.modelIdOverride != null;
    }
}
