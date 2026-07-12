package controllers;

import com.google.gson.JsonObject;
import models.Agent;
import models.AgentBoundBinding;
import play.mvc.Controller;
import services.AgentService;
import utils.ApiResponses;

import java.util.function.Function;

import static controllers.BindingKeys.KEY_AGENT_ID;

/**
 * JCLAW-723: shared CRUD flow for the per-agent channel-binding controllers
 * ({@link ApiTelegramBindingsController}, {@link ApiSlackBindingsController},
 * {@link ApiWhatsAppBindingsController}). Play routes each action to a concrete
 * controller's static method, so the entry points ({@code list}/{@code create}/
 * {@code update}/{@code delete}/{@code test}) stay per-channel; what factors out
 * here is the part of the flow every channel shares — agent resolution and the
 * 1:1 agent&harr;binding privacy invariant.
 *
 * <p>The invariant matters because agent memory is scoped by agentId alone: a
 * second binding on one agent would share that agent's memories across two
 * identities (two bots / numbers / workspaces). Enforcing it in one place —
 * {@link #rejectAgentAlreadyBound} on create and {@link #applyAgentUpdate} on
 * update — keeps the guard from drifting between the three controllers.
 *
 * <p>The channel-specific error <em>code</em> and display <em>label</em> are
 * passed in so each controller's JSON error body stays byte-identical to what it
 * emitted before (Telegram/WhatsApp use {@code agent_already_bound}; Slack uses
 * the generic {@code conflict}).
 */
public abstract class ApiBindingController extends Controller {

    /**
     * Resolve an already-parsed {@code agentId} to an enabled {@link Agent}, or
     * send a 400. The "must reference an enabled agent" message is identical
     * across every channel, so it lives here; each create action keeps its own
     * (differently-worded) "agentId is required" presence check upstream.
     */
    @SuppressWarnings("java:S2259")
    protected static Agent requireEnabledAgent(Long agentId) {
        Agent agent = AgentService.findById(agentId);
        if (agent == null || !agent.enabled) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId must reference an enabled agent");
            throw new AssertionError("unreachable: error() throws");
        }
        return agent;
    }

    /**
     * Create-time 1:1 guard: reject with 409 when {@code agent} is already bound.
     *
     * @param findByAgent  the channel's own {@code findByAgent} finder
     * @param channelLabel display name in the message, e.g. {@code "Telegram"}
     * @param conflictCode the channel's error code for this conflict
     */
    protected static void rejectAgentAlreadyBound(Agent agent,
            Function<Agent, ? extends AgentBoundBinding> findByAgent,
            String channelLabel, String conflictCode) {
        if (findByAgent.apply(agent) != null) {
            ApiResponses.error(409, conflictCode,
                    "Agent '%s' is already bound to another %s binding".formatted(agent.name, channelLabel));
        }
    }

    /**
     * Update-time agent reassignment preserving the 1:1 guard: an absent or
     * JSON-null {@code agentId} is a no-op (partial PUT); otherwise the target
     * must resolve to an enabled agent that isn't already bound to a different
     * binding. Reads {@code binding.agent}/{@code binding.id} through
     * {@link AgentBoundBinding}, so it is generic over the concrete binding type.
     */
    @SuppressWarnings("java:S2259")
    protected static <T extends AgentBoundBinding> void applyAgentUpdate(T binding, JsonObject body,
            Function<Agent, T> findByAgent, String channelLabel, String conflictCode) {
        if (!body.has(KEY_AGENT_ID) || body.get(KEY_AGENT_ID).isJsonNull()) return;
        Agent agent = AgentService.findById(body.get(KEY_AGENT_ID).getAsLong());
        if (agent == null || !agent.enabled) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId must reference an enabled agent");
        }
        if (binding.agent == null || !agent.id.equals(binding.agent.id)) {
            var other = findByAgent.apply(agent);
            if (other != null && !other.id.equals(binding.id)) {
                ApiResponses.error(409, conflictCode,
                        "Agent '%s' is already bound to another %s binding".formatted(agent.name, channelLabel));
            }
        }
        binding.agent = agent;
    }

    /** Optional trimmed string from the JSON body (blank collapses to null). */
    protected static String readOptionalString(JsonObject body, String key) {
        return JsonBodyReader.optString(body, key, true);
    }
}
