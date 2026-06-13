package services;

import channels.SlackWebApi;
import models.Agent;
import models.SlackBinding;

/**
 * JCLAW-455: non-blocking advisory for a task's declared delivery target — the
 * preflight half of the JCLAW-454 delivery work. Today it only probes Slack
 * channel targets (the one channel that can silently fail because the bot isn't
 * a member of a private/uninvited channel); every other channel, tool delivery,
 * and {@code none} returns {@code null} (no advisory).
 *
 * <p>Surfaced in two places: appended to the {@code task_manager} tool result so
 * the agent relays it in chat (create/edit time), and via
 * {@code GET /api/tasks/{id}/delivery-advisory} for the Tasks page to render
 * below the delivery value.
 */
public final class DeliveryAdvisor {

    private DeliveryAdvisor() {}

    /**
     * Advisory for {@code deliverySpec} on behalf of {@code agent}, or {@code null}
     * when none is warranted (not a Slack channel, no/disabled binding, channel
     * reachable, or anything unclassifiable). Never throws — best-effort.
     *
     * <p>The bot-token lookup runs in a short JPA tx (joins an active one); the
     * Slack {@code conversations.list} probe runs off-tx so a network call never
     * holds a transaction open.
     */
    public static String advisoryFor(Agent agent, String deliverySpec) {
        if (agent == null || deliverySpec == null || deliverySpec.isBlank()) return null;
        var spec = DeliverySpec.parse(deliverySpec);
        if (spec.kind() != DeliverySpec.Kind.CHANNEL || !"slack".equals(spec.channel())) return null;
        var target = spec.target();
        if (target == null || target.isBlank()) return null;

        final Long agentId = agent.id;
        // JCLAW-456: probe with the identity delivery will actually use — the user token when
        // user-token writes are enabled (it reaches private channels the bot isn't in), else
        // the bot token. So a channel reachable only as the user shows no advisory exactly when
        // delivery would succeed as the user.
        String probeToken = Tx.run(() -> {
            var fresh = (Agent) Agent.findById(agentId);
            if (fresh == null) return null;
            var binding = SlackBinding.findByAgentOrAncestor(fresh);
            if (binding == null || !binding.enabled) return null;
            boolean userWrites = binding.userToken != null && !binding.userToken.isBlank()
                    && !binding.userTokenReadOnly;
            return userWrites ? binding.userToken : binding.botToken;
        });
        if (probeToken == null || probeToken.isBlank()) return null;

        try {
            return SlackWebApi.probeChannel(probeToken, target).advisory();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
