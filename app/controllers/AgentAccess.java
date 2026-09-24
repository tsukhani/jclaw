package controllers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What the agent principal may do with this action (JCLAW-1270).
 *
 * <p>Replaces the four overlapping mechanisms that used to answer this between them —
 * {@code @ChatHidden}, {@code @AgentCallable}, {@code JClawApiTool.PATH_BLOCKLIST} and a
 * hand-written {@code requireOperator()} — of which only the last actually refused anyone. Two of
 * the four merely hid a route from one tool, so an agent holding the internal bearer walked past
 * them; keeping the hiding and the refusing in one declaration is what stops them drifting apart.
 *
 * <p><b>The absence of this annotation means {@link Level#OPERATOR_ONLY}.</b> The old gate was
 * default-allow, so a route was agent-reachable until someone remembered to hide it, and every
 * missed route failed open. {@code AgentAccessConformanceTest} still requires an explicit
 * annotation on every routed action, so the default is a backstop rather than a license to omit
 * one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentAccess {

    Level value();

    /**
     * What bounds this route, for the levels that leave it reachable.
     *
     * <p>Required for {@link Level#OPEN} and {@link Level#OWN_ONLY} on a mutating route — an empty
     * reason there is worse than no annotation, because it records a decision without its
     * grounds. Names the thing that actually holds: a scoped tool argument,
     * {@code DangerousActionGate}, {@code SsrfGuard}, {@code LoadtestAuthCheck}.
     */
    String reason() default "";

    enum Level {

        /** Any agent may call it. */
        OPEN,

        /**
         * An agent may call it only for rows its own agent owns; {@code main} reaches every
         * agent's rows, nothing reaches up or across.
         *
         * <p>The row check cannot be central — only the action knows which row it is about — so
         * this level declares the intent and the action calls
         * {@link RequestPrincipal#mayReachAgentScopedRow}. The conformance test fails an
         * {@code OWN_ONLY} action that does not reach it.
         */
        OWN_ONLY,

        /** The agent principal is refused at the request layer, whatever tool it came through. */
        OPERATOR_ONLY
    }
}
