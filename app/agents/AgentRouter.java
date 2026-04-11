package agents;

import models.Agent;
import models.AgentBinding;
import services.EventLogger;

/**
 * Resolves which agent handles an inbound message using 3-tier routing:
 * 1. Exact peer match (channel_type + peer_id)
 * 2. Channel-wide match (channel_type + peer_id IS NULL)
 * 3. Default agent fallback
 */
public class AgentRouter {

    public record RouteResult(Agent agent, String matchedBy) {}

    /**
     * Resolve the target agent for an inbound message.
     *
     * @param channelType the channel type (telegram, slack, whatsapp)
     * @param peerId      the peer identifier (chat ID, user ID, etc.)
     * @return the resolved agent and how it was matched, or null if no route found
     */
    public static RouteResult resolve(String channelType, String peerId) {
        // Tier 1: Exact peer match
        if (peerId != null) {
            var binding = AgentBinding.findByChannelAndPeer(channelType, peerId);
            if (binding != null && binding.agent.enabled) {
                return new RouteResult(binding.agent, "peer");
            }
        }

        // Tier 2: Channel-wide match
        var channelBinding = AgentBinding.findByChannel(channelType);
        if (channelBinding != null && channelBinding.agent.enabled) {
            return new RouteResult(channelBinding.agent, "channel");
        }

        // Tier 3: Main agent fallback
        var mainAgent = Agent.findByName(Agent.MAIN_AGENT_NAME);
        if (mainAgent != null && mainAgent.enabled) {
            return new RouteResult(mainAgent, "main");
        }

        EventLogger.error("agent", "No route found for %s/%s and main agent unavailable"
                .formatted(channelType, peerId));
        return null;
    }
}
