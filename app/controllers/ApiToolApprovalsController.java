package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import models.Agent;
import models.ToolApprovalGrant;
import play.mvc.Controller;
import play.mvc.With;
import services.Tx;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.List;

import static utils.GsonHolder.GSON;

/**
 * JCLAW-1062: list and revoke standing {@link ToolApprovalGrant} rows.
 *
 * <p>An "always allow" tap writes a grant that suppresses the approval prompt for that
 * {@code (agent, tool)} pair forever, survives restarts, and until now had no removal
 * path short of deleting the agent. These endpoints are the management surface.
 *
 * <p><b>Operator-only and {@link ChatHidden}, without exception.</b> Per JCLAW-1023/1058
 * an agent must not enumerate or edit its own approvals: the list answers "which tools can
 * I run without being challenged", which is reconnaissance for exactly the boundary the
 * prompt exists to hold. Revoke is gated for the mirror-image reason — an agent that could
 * revoke another agent's grant could not escalate itself, but it could disable a
 * deliberately-granted unattended workflow.
 *
 * <p>Deliberately no create endpoint. Grants are made by tapping "always allow" on a live
 * prompt; JCLAW-1061 removed most of the pressure to do that, and pre-granting from
 * Settings would rebuild the hazard this ticket exists to clean up.
 */
@With(AuthCheck.class)
public class ApiToolApprovalsController extends Controller {

    private static final Gson gson = GSON;


    /** One standing grant, as the agent page renders it. */
    public record GrantView(Long id, String toolName) {}

    /** One agent's grants, as the instance-wide roll-up renders it. */
    public record AgentGrantsView(Long agentId, String agentName, List<String> tools) {}

    /** Roll-up totals plus the per-agent breakdown the Settings panel links through. */
    public record SummaryView(int totalGrants, int agentsWithGrants, List<AgentGrantsView> agents) {}

    /** Reject the agent principal. Gated on how the request authenticated, not on
     *  self-reference: enumerating another agent's grants maps the same boundary. */
    private static void requireOperator() {
        if (RequestPrincipal.isAgentOriginated()) {
            ApiResponses.error(403, ApiResponses.OPERATOR_ONLY,
                    "Tool approvals are operator-only; an agent cannot list or revoke standing approvals.");
        }
    }

    private static Agent requireAgent(Long id) {
        var agent = (Agent) Agent.findById(id);
        if (agent == null) notFound();
        return agent;
    }

    /**
     * GET /api/agents/{id}/tool-approvals — standing approvals for one agent.
     */
    @ChatHidden("enumerates which dangerous tools an agent may run unprompted")
    @Operation(summary = "List standing tool-approval grants for an agent")
    public static void listForAgent(Long id) {
        requireOperator();
        var views = Tx.run(() -> {
            requireAgent(id);
            var out = new ArrayList<GrantView>();
            for (var g : ToolApprovalGrant.findByAgent(id)) out.add(new GrantView(g.id, g.toolName));
            return out;
        });
        renderJSON(gson.toJson(views));
    }

    /**
     * DELETE /api/agents/{id}/tool-approvals/{toolName} — revoke one standing approval.
     *
     * <p>404 when no grant exists, so a caller can tell "removed it" from "there was
     * nothing to remove" — the difference matters when the operator is clearing a list
     * they believe is stale.
     */
    @ChatHidden("revoking an approval is an operator security action")
    @Operation(summary = "Revoke a standing tool-approval grant")
    public static void revokeForAgent(Long id, String toolName) {
        requireOperator();
        var removed = Tx.run(() -> {
            requireAgent(id);
            return ToolApprovalGrant.revoke(id, toolName);
        });
        if (!Boolean.TRUE.equals(removed)) notFound();
        ok();
    }

    /**
     * GET /api/tool-approvals/summary — instance-wide roll-up for the Settings panel.
     *
     * <p>The per-agent pages cannot answer "does anything still hold a standing grant?"
     * without opening every agent in turn. That sweep is the case JCLAW-1062 is really
     * about: grants made before JCLAW-1061 are still in force and mostly unnecessary.
     */
    @ChatHidden("instance-wide map of which agents may run dangerous tools unprompted")
    @Operation(summary = "Roll-up of standing tool-approval grants across all agents")
    public static void summary() {
        requireOperator();
        var view = Tx.run(() -> {
            var byAgent = new java.util.LinkedHashMap<Long, AgentGrantsView>();
            var total = 0;
            for (var g : ToolApprovalGrant.findAllGrants()) {
                total++;
                var entry = byAgent.computeIfAbsent(g.agent.id,
                        _ -> new AgentGrantsView(g.agent.id, g.agent.name, new ArrayList<>()));
                entry.tools().add(g.toolName);
            }
            return new SummaryView(total, byAgent.size(), List.copyOf(byAgent.values()));
        });
        renderJSON(gson.toJson(view));
    }
}
