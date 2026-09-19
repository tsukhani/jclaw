package controllers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutating controller action as <em>deliberately</em> reachable by the agent
 * principal, and records what bounds it (JCLAW-1253).
 *
 * <p>The counterpart to {@link ChatHidden}. Neither the tool layer nor the request path
 * reads this marker — it changes no behaviour. Its whole job is to make "left open" a
 * decision someone wrote down, so that {@code CapabilityRulesTest}'s stance rule can tell
 * an adjudicated route from one nobody looked at. A new mutating route carrying neither
 * marker nor a {@code RequestPrincipal.isAgentOriginated} guard fails the build.
 *
 * <p>Reach for it when the action is inside an agent's own grants, or when something else
 * already bounds it — a scoped tool that is the intended path, the
 * {@code agents.DangerousActionGate} on mutating {@code jclaw_api} verbs,
 * {@code utils.SsrfGuard} on an outbound dial, or a separate auth gate such as
 * {@code LoadtestAuthCheck}. Name that bound in {@link #value()}: an empty reason is worse
 * than no annotation, because it reads as adjudicated when it is not.
 *
 * <p>Personal Edition keeps several mutating surfaces open on purpose —
 * {@code /api/providers} and {@code /api/mcp-servers} among them — and the remedy there is
 * the seam, never blocking the endpoint. This marker is how that verdict survives review.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentCallable {

    /** Why this action stays inside the agent's reach, and what bounds it. */
    String value();
}
