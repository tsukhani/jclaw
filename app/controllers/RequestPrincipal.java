package controllers;

import models.Agent;
import org.jspecify.annotations.Nullable;
import play.mvc.Http;
import play.mvc.Scope;
import services.InternalApiTokenService;

/**
 * Who is making this request — the operator at a browser, or an agent driving the API through
 * the {@code jclaw_api} tool (JCLAW-1023), and since JCLAW-1270 <em>which</em> agent.
 *
 * <p>Both arrive at a controller looking identical: {@link AuthCheck}'s bearer branch stashes
 * {@code authenticated}/{@code username} into the session exactly as a cookie login does, so
 * session state alone cannot separate them.
 *
 * <p>The test is therefore the authentication <em>mechanism</em>, stamped by {@link AuthCheck}
 * when it accepts a bearer token, with the owner name as a second signal. Gating on the
 * mechanism rather than on a username string matters twice: an operator who set their own
 * username to {@code system} would otherwise lock themselves out, and any future token row
 * stamped with some other owner would otherwise be trusted by default.
 */
public final class RequestPrincipal {

    /** Session key {@link AuthCheck} stamps when a request authenticated by bearer token. */
    static final String PRINCIPAL_KEY = "principal";

    /** Value of {@link #PRINCIPAL_KEY} for a bearer-authenticated (agent) request. */
    static final String AGENT = "agent";

    /**
     * Header {@code JClawApiTool} stamps with the calling agent's id.
     *
     * <p>One token serves every agent, so the credential cannot say which one is calling. The
     * tool builds the request from the {@link Agent} it is handed; the model supplies only method
     * and path and never sees this header, so it cannot forge one. Honored only on an
     * agent-originated request — an operator session that sent it is ignored.
     */
    public static final String AGENT_ID_HEADER = "x-jclaw-agent-id";

    private RequestPrincipal() {}

    /**
     * True when this request came in on the internal bearer rather than an operator session —
     * i.e. an agent is calling the API on its own behalf.
     *
     * <p>Either signal is sufficient. The mechanism marker is authoritative; the owner-name
     * check keeps the answer right for a session minted before this marker existed.
     */
    public static boolean isAgentOriginated() {
        var session = Scope.Session.current();
        if (session == null) return false;
        return AGENT.equals(session.get(PRINCIPAL_KEY))
                || InternalApiTokenService.SYSTEM_OWNER.equals(session.get("username"));
    }

    /**
     * The agent behind this request, or null when the operator is calling or the caller could not
     * be identified.
     *
     * <p>Null on an agent-originated request means the header was absent, unparseable or named a
     * row that no longer exists — a bearer used outside the tool, in other words. Callers treat
     * that as "not this agent" and refuse, so the unidentified case is the closed one.
     */
    public static @Nullable Agent callingAgent() {
        if (!isAgentOriginated()) return null;
        var request = Http.Request.current();
        if (request == null) return null;
        var header = request.headers.get(AGENT_ID_HEADER);
        var raw = header == null ? null : header.value();
        if (raw == null || raw.isBlank()) return null;
        try {
            return Agent.findById(Long.valueOf(raw.trim()));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * The operator, or the {@code main} agent — everyone who is trusted with more than their own
     * rows (JCLAW-1270).
     *
     * <p>{@code main} is the operator's own assistant and the one agent that spawns and supervises
     * the others, so the asymmetry is deliberate: it sees down the tree, nothing sees up or across.
     * A custom agent, and an agent-originated request that names no agent, are both false.
     */
    public static boolean isOperatorOrMainAgent() {
        return isOperatorOrMain(isAgentOriginated(), callingAgent());
    }

    /**
     * The policy behind {@link #isOperatorOrMainAgent}, with the request read out of it.
     *
     * <p>Separated so the matrix — operator, main, a custom agent, an agent-originated request
     * that names nobody — is covered by a table rather than by four HTTP round trips against a
     * {@code main} row that is provisioned at boot and that a sibling's
     * {@code Fixtures.deleteDatabase} can remove mid-suite.
     */
    // Public because Play's tests live in the default package.
    public static boolean isOperatorOrMain(boolean agentOriginated, @Nullable Agent caller) {
        if (!agentOriginated) return true;
        return caller != null && caller.isMain();
    }

    /** {@link #mayReachAgentScopedRow} with the request read out of it; see {@link
     *  #isOperatorOrMain}. */
    // Public because Play's tests live in the default package.
    public static boolean mayReachRow(boolean agentOriginated, @Nullable Agent caller,
                                      @Nullable Agent owner) {
        if (isOperatorOrMain(agentOriginated, caller)) return true;
        return caller != null && owner != null && owner.id != null && owner.id.equals(caller.id);
    }

    /**
     * Whether this caller may reach a row owned by {@code owner}: the operator and {@code main}
     * reach anything, every other agent only its own (JCLAW-1270).
     *
     * @param owner the agent the row belongs to; a null owner is reachable only by
     *              {@link #isOperatorOrMainAgent}
     */
    public static boolean mayReachAgentScopedRow(@Nullable Agent owner) {
        return mayReachRow(isAgentOriginated(), callingAgent(), owner);
    }
}
