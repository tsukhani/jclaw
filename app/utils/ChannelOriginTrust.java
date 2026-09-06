package utils;

import org.jspecify.annotations.Nullable;

/**
 * JCLAW-777: the single source of truth for whether an agent turn originates from
 * the operator's own trusted surface or from an untrusted external channel peer.
 *
 * <p>Trust is three-valued. {@link Trust#OPERATOR} is the local web UI
 * ({@value #WEB}) — the operator driving the agent directly. Every named inbound
 * channel (telegram, slack, whatsapp, and any future channel) is an
 * {@link Trust#UNTRUSTED_CHANNEL} external party whose messages must never silently
 * authorize a dangerous, unsandboxed action. An absent origin is
 * {@link Trust#UNKNOWN}.
 *
 * <p>JCLAW-1021: {@code UNKNOWN} is emphatically <em>not</em> the operator. An
 * unrecorded origin is missing provenance, not evidence of a trusted caller —
 * reading it as operator trust let a task fire (whose tool loop carries no
 * conversation) and a parentless ACP run take the permissive branch of both sinks
 * below. Callers that need a boolean get one from {@link #isOperatorOrigin}, which
 * is true for {@code OPERATOR} alone.
 *
 * <p>Both the dangerous-tool approval gate ({@link agents.DangerousActionGate}) and
 * the ACP-harness sandbox/approval check ({@link tools.SubagentAcpRunner}) resolve
 * origin trust through this one classifier so the two can never disagree — a
 * disagreement was the root of VULN-001, where the gate treated {@code whatsapp} as
 * an off-channel {@code allow} while the sandbox already treated it as untrusted.
 * Default-untrusted (anything not explicitly {@value #WEB}) means a new inbound
 * channel is fail-safe until it is deliberately granted a trust level.
 */
public final class ChannelOriginTrust {

    /** The operator's own web UI — the only origin that carries operator trust. */
    public static final String WEB = "web";

    /** How much authority an origin carries. Only {@link #OPERATOR} is permissive. */
    public enum Trust {
        /** The operator's own surface (the web UI). */
        OPERATOR,
        /** A named inbound channel peer — an external party. */
        UNTRUSTED_CHANNEL,
        /** No origin was recorded; treat as untrusted (JCLAW-1021). */
        UNKNOWN
    }

    private ChannelOriginTrust() {}

    /**
     * Classify a conversation's {@code channelType}.
     *
     * @param origin the conversation's {@code channelType}, or {@code null}/blank when
     *               no origin was recorded for the turn
     */
    public static Trust classify(@Nullable String origin) {
        if (origin == null || origin.isBlank()) {
            return Trust.UNKNOWN;
        }
        return WEB.equals(origin) ? Trust.OPERATOR : Trust.UNTRUSTED_CHANNEL;
    }

    /**
     * True only when {@code origin} classifies as {@link Trust#OPERATOR}. An
     * unrecorded origin is {@link Trust#UNKNOWN} and returns false, so a caller with
     * no provenance can never land on a permissive branch.
     *
     * @param origin the conversation's {@code channelType}, or {@code null} when there
     *               is no conversation context
     */
    public static boolean isOperatorOrigin(@Nullable String origin) {
        return classify(origin) == Trust.OPERATOR;
    }
}
