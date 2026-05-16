package agents;

import java.util.Optional;

import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import models.Agent;
import models.Conversation;
import services.ModelOverrideResolver;

/**
 * Conversation-override-aware lookups for the model identity and
 * configuration that should drive a turn. Extracted from
 * {@link AgentRunner} as part of JCLAW-299; all four helpers center on
 * a single question — "for this {agent, conversation, provider}
 * triple, what model are we actually running?" — and the JCLAW-108 /
 * JCLAW-269 override resolution that answers it.
 *
 * <h3>Resolution chain</h3>
 * <ul>
 *   <li>{@link #effectiveModelId} and {@link #effectiveModelProvider}
 *   are thin wrappers over {@link ModelOverrideResolver}. They surface
 *   the per-conversation override (set by {@code /model} or JCLAW-269
 *   per-spawn) when present, otherwise return the agent's default. The
 *   wrappers exist so call sites in {@link AgentRunner} and its sibling
 *   classes read naturally next to the rest of the runner's helpers
 *   rather than reaching into {@code services} directly.</li>
 *   <li>{@link #resolveModelInfo} layers on top: it composes
 *   {@code effectiveModelId} with the provider's configured model list
 *   to return the full {@link ModelInfo} record (context window,
 *   pricing, capabilities), or {@link Optional#empty()} when the
 *   resolved id isn't on the provider.</li>
 *   <li>{@link #resolveThinkingMode} composes again: agent's persisted
 *   {@code thinkingMode} ∩ resolved model's advertised levels. Returns
 *   {@code null} when reasoning should be disabled — either because
 *   the agent isn't configured for it or because the active model no
 *   longer advertises the level the agent stored.</li>
 * </ul>
 *
 * <p>The {@code null} path on {@link #resolveThinkingMode} is not
 * redundant with {@link services.AgentService#normalizeThinkingMode}:
 * agents can persist a valid level today and see their model's levels
 * change tomorrow (operator edits the provider config), so we prefer
 * to silently disable reasoning rather than send a level the model no
 * longer understands.
 */
public final class ModelResolver {

    private ModelResolver() {}

    /**
     * Effective model id for this turn — honors the conversation-scoped
     * override (JCLAW-108 per-conversation, JCLAW-269 per-spawn) when
     * present, otherwise returns the agent's default. Thin wrapper over
     * {@link ModelOverrideResolver#modelId} kept here so call sites read
     * naturally next to the rest of the runner's helpers.
     */
    public static String effectiveModelId(Agent agent, Conversation conv) {
        return ModelOverrideResolver.modelId(conv, agent);
    }

    /** Companion to {@link #effectiveModelId} — returns the effective provider name. */
    public static String effectiveModelProvider(Agent agent, Conversation conv) {
        return ModelOverrideResolver.provider(conv, agent);
    }

    /**
     * Resolve the model's {@link ModelInfo} from the provider's
     * configured model list. Honors the conversation-scoped override
     * (JCLAW-108): when {@code conv.modelIdOverride} is set, looks up
     * that id instead of the agent's default.
     */
    public static Optional<ModelInfo> resolveModelInfo(Agent agent, Conversation conv, LlmProvider provider) {
        var modelId = effectiveModelId(agent, conv);
        if (modelId == null) return Optional.empty();
        return provider.config().models().stream()
                .filter(m -> modelId.equals(m.id()))
                .findFirst();
    }

    /**
     * Resolve the reasoning-effort level this call should use. Combines
     * the agent's persisted {@code thinkingMode} with the model's
     * capability: the setting only takes effect when the model supports
     * thinking and the stored level is still advertised by the model.
     * Otherwise returns {@code null} (reasoning disabled).
     */
    public static String resolveThinkingMode(Agent agent, Conversation conv, LlmProvider provider) {
        if (agent.thinkingMode == null || agent.thinkingMode.isBlank()) return null;
        return resolveModelInfo(agent, conv, provider)
                .filter(ModelInfo::supportsThinking)
                .filter(m -> m.effectiveThinkingLevels().contains(agent.thinkingMode))
                .map(_ -> agent.thinkingMode)
                .orElse(null);
    }
}
