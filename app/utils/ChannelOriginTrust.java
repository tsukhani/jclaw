package utils;

/**
 * JCLAW-777: the single source of truth for whether an agent turn originates from
 * the operator's own trusted surface or from an untrusted external channel peer.
 *
 * <p>A <em>trusted operator origin</em> is the local web UI ({@code "web"}) — the
 * operator driving the agent directly — or no conversation context at all
 * ({@code null}, an internal/system-initiated turn). Every named inbound channel
 * (telegram, slack, whatsapp, and any future channel) is an <em>untrusted external
 * party</em> whose messages must never silently authorize a dangerous, unsandboxed
 * action.
 *
 * <p>Both the dangerous-tool approval gate ({@link agents.DangerousActionGate}) and
 * the ACP-harness sandbox/approval check ({@link tools.SubagentAcpRunner}) resolve
 * origin trust through this one predicate so the two can never disagree — a
 * disagreement was the root of VULN-001, where the gate treated {@code whatsapp} as
 * an off-channel {@code allow} while the sandbox already treated it as untrusted.
 * Default-untrusted (anything not explicitly {@code web}/{@code null}) means a new
 * inbound channel is fail-safe until it is deliberately granted a trust level.
 */
public final class ChannelOriginTrust {

    private ChannelOriginTrust() {}

    /**
     * True when {@code origin} is the operator's own trusted surface — the web UI
     * ({@code "web"}) or a context-less internal turn ({@code null}). Any other value
     * is a named inbound channel and is treated as an untrusted external peer.
     *
     * @param origin the conversation's {@code channelType}, or {@code null} when there
     *               is no conversation context
     */
    public static boolean isOperatorOrigin(String origin) {
        return origin == null || "web".equals(origin);
    }
}
