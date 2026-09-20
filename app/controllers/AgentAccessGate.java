package controllers;

import org.jspecify.annotations.Nullable;
import play.mvc.Http;
import utils.ApiResponses;

import java.lang.reflect.Method;

/**
 * Enforces {@link AgentAccess} at the request layer (JCLAW-1270).
 *
 * <p>Called from {@link AuthCheck#checkAuthentication} and {@link LoadtestAuthCheck}, which
 * between them stand in front of every routed {@code /api} action except the deliberately public
 * {@code GET /api/status}. {@code AgentAccessConformanceTest} fails the build if a route escapes
 * both, so the coverage is checked rather than assumed.
 *
 * <p>Play resolves the action and assigns {@code Http.Request.invokedMethod} before it runs any
 * {@code @Before}, so the interceptor reads the annotation off the method that is about to run
 * rather than re-deriving it from the path.
 */
public final class AgentAccessGate {

    private AgentAccessGate() {}

    /**
     * The declared level, or {@link AgentAccess.Level#OPERATOR_ONLY} when the action declares
     * nothing — an unannotated route fails closed.
     */
    public static AgentAccess.Level levelFor(@Nullable Method action) {
        if (action == null) return AgentAccess.Level.OPERATOR_ONLY;
        var declared = action.getAnnotation(AgentAccess.class);
        return declared == null ? AgentAccess.Level.OPERATOR_ONLY : declared.value();
    }

    /**
     * Refuse an agent-originated request to an operator-only action.
     *
     * <p>{@link AgentAccess.Level#OWN_ONLY} passes here: only the action knows which row it is
     * about, so it calls {@link RequestPrincipal#mayReachAgentScopedRow} itself.
     */
    static void enforce() {
        if (!RequestPrincipal.isAgentOriginated()) return;
        var request = Http.Request.current();
        if (request == null) return;
        if (levelFor(request.invokedMethod) == AgentAccess.Level.OPERATOR_ONLY) {
            ApiResponses.error(403, ApiResponses.OPERATOR_ONLY,
                    "This endpoint is operator-only; an agent principal cannot call it.");
        }
    }
}
