package agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.routing.ModelRouter;
import llm.routing.ReasoningEffort;
import llm.routing.RouteDecision;
import llm.routing.RouteDecision.Target;
import llm.routing.TaskClass;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.AttachmentService;
import services.EventLogger;
import services.ModelOverrideResolver;
import services.Tx;
import utils.JsonArgs;
import utils.LatencyTrace;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Routes a turn whose conversation runs on {@code router/auto} (JCLAW-1222): reads what the previous
 * turn left in its usage record, asks {@link ModelRouter} for a model, and logs the decision.
 */
public final class TurnRouting {

    public static final String ROUTER_UNAVAILABLE_ERROR =
            "The model router has no usable model. Add models under Settings > Model Router.";

    private TurnRouting() {}

    /**
     * The previous turn as the router reads it; the class, effort and model are null unless it was routed, and the
     * effort also on a record saved before routes carried it.
     */
    public record PriorTurn(@Nullable TaskClass taskClass, @Nullable ReasoningEffort effort, @Nullable Target target,
                            int toolCalls, int promptTokens) {
        static final PriorTurn NONE = new PriorTurn(null, null, null, 0, 0);
    }

    /** True when the conversation's configured model is the router. */
    public static boolean usesRouter(Agent agent, Conversation conversation) {
        return ModelRouter.isRouter(ModelOverrideResolver.provider(conversation, agent),
                ModelOverrideResolver.modelId(conversation, agent));
    }

    /**
     * The route for this turn. Null when the conversation is not on the router — nothing is read in
     * that case — and also when it is but no listed model resolves, which a caller tells apart with
     * {@link #usesRouter}.
     */
    public static @Nullable RouteDecision decide(Agent agent, Conversation conversation, String userMessage,
                                                 @Nullable List<AttachmentService.Input> attachments,
                                                 @Nullable String channel, @Nullable LatencyTrace trace) {
        if (!usesRouter(agent, conversation)) return null;
        return timed(trace, () -> decide(agent, conversation, userMessage, attachments, channel));
    }

    /** {@link #decide(Agent, Conversation, String, List, String, LatencyTrace)} for a turn whose prologue is not timed. */
    public static @Nullable RouteDecision decide(Agent agent, Conversation conversation, String userMessage,
                                                 @Nullable List<AttachmentService.Input> attachments,
                                                 @Nullable String channel) {
        if (!usesRouter(agent, conversation)) return null;
        return route(agent, priorTurn(conversation), userMessage, attachments, channel);
    }

    /**
     * {@link #decide(Agent, Conversation, String, List, String, LatencyTrace)} on {@code prior}, already read with
     * {@link #priorTurn}.
     */
    public static @Nullable RouteDecision decide(Agent agent, Conversation conversation, PriorTurn prior,
                                                 String userMessage, @Nullable List<AttachmentService.Input> attachments,
                                                 @Nullable String channel, @Nullable LatencyTrace trace) {
        if (!usesRouter(agent, conversation)) return null;
        return timed(trace, () -> route(agent, prior, userMessage, attachments, channel));
    }

    private static @Nullable RouteDecision timed(@Nullable LatencyTrace trace, Supplier<@Nullable RouteDecision> route) {
        long startNs = System.nanoTime();
        try {
            return route.get();
        } finally {
            if (trace != null) trace.recordRoute(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
        }
    }

    /** The previous turn of {@code conversation} since its {@code /reset}, or none for an unsaved one. */
    public static PriorTurn priorTurn(Conversation conversation) {
        var conversationId = conversation.id;
        var since = conversation.contextSince;
        return conversationId != null ? Tx.run(() -> readPriorTurn(conversationId, since)) : PriorTurn.NONE;
    }

    private static @Nullable RouteDecision route(Agent agent, PriorTurn prior, String userMessage,
                                                 @Nullable List<AttachmentService.Input> attachments,
                                                 @Nullable String channel) {
        var request = new ModelRouter.RouteRequest(userMessage, prior.taskClass(), prior.target(),
                prior.toolCalls(), prior.promptTokens() + userMessage.length() / 4,
                hasKind(attachments, MessageAttachment.KIND_IMAGE), hasKind(attachments, MessageAttachment.KIND_AUDIO),
                true);
        var decision = ModelRouter.route(request);
        if (decision == null) {
            EventLogger.warn("router", agent.name, channel, ROUTER_UNAVAILABLE_ERROR);
            return null;
        }
        EventLogger.info("router", agent.name, channel, decision.logLine());
        return decision;
    }

    /**
     * The latest assistant turn with a usage record since {@code since} (the {@code /reset} watermark):
     * its route, the model that served it, how full its final prompt was, and how many tool calls it made.
     */
    public static PriorTurn readPriorTurn(Long conversationId, @Nullable Instant since) {
        var floor = since != null ? since : Instant.EPOCH;
        Message assistant = Message.find(
                "conversation.id = ?1 AND role = ?2 AND usageJson IS NOT NULL AND createdAt > ?3 ORDER BY createdAt DESC",
                conversationId, MessageRole.ASSISTANT.value, floor).first();
        if (assistant == null) return PriorTurn.NONE;
        var usage = parseObject(assistant.usageJson);
        TaskClass taskClass = null;
        ReasoningEffort effort = null;
        Target target = null;
        var promptTokens = 0;
        if (usage != null) {
            var route = usage.get("route");
            if (route != null && route.isJsonObject()) {
                taskClass = TaskClass.fromId(JsonArgs.optString(route.getAsJsonObject(), "class"));
                effort = ReasoningEffort.fromId(JsonArgs.optString(route.getAsJsonObject(), "effort"));
                var provider = JsonArgs.optString(usage, "modelProvider");
                var model = JsonArgs.optString(usage, "modelId");
                if (provider != null && model != null) target = new Target(provider, model);
            }
            promptTokens = JsonArgs.optInt(usage, "lastPrompt", JsonArgs.optInt(usage, "prompt", 0));
        }
        Message user = Message.find(
                "conversation.id = ?1 AND role = ?2 AND createdAt < ?3 ORDER BY createdAt DESC",
                conversationId, MessageRole.USER.value, assistant.createdAt).first();
        var toolCalls = user == null ? 0L : Message.count(
                "conversation.id = ?1 AND role = ?2 AND createdAt > ?3 AND createdAt < ?4",
                conversationId, MessageRole.TOOL.value, user.createdAt, assistant.createdAt);
        return new PriorTurn(taskClass, effort, target, (int) Math.min(toolCalls, Integer.MAX_VALUE), promptTokens);
    }

    private static boolean hasKind(@Nullable List<AttachmentService.Input> attachments, String kind) {
        return attachments != null && attachments.stream().anyMatch(a -> kind.equalsIgnoreCase(a.kind()));
    }

    private static @Nullable JsonObject parseObject(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            var root = JsonParser.parseString(json);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException _) {
            return null;
        }
    }
}
