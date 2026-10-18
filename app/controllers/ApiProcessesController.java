package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import play.mvc.Controller;
import play.mvc.With;
import services.BackgroundProcesses;
import services.Tx;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.List;

import static controllers.AgentAccess.Level.OWN_ONLY;
import static utils.GsonHolder.GSON;

/** Processes agents' exec commands left running in the background (JCLAW-1565). */
@With(AuthCheck.class)
public class ApiProcessesController extends Controller {

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(
            schema = @Schema(implementation = BackgroundProcesses.Entry.class))))
    @Operation(summary = "List the processes agents' shell commands left running in the background, oldest first")
    @AgentAccess(value = OWN_ONLY, reason = "pinned to the calling agent's workspace; main reaches every agent's")
    public static void list() {
        renderJSON(GSON.toJson(BackgroundProcesses.list(agentsInScope())));
    }

    @Operation(summary = "Stop a process an agent's shell command left running in the background, with its descendants")
    @AgentAccess(value = OWN_ONLY,
            reason = "only a process holding an output file in the calling agent's workspace; main reaches every agent's")
    public static void stop(Long pid) {
        if (pid != null) {
            for (var agent : agentsInScope()) {
                if (BackgroundProcesses.stop(agent, pid)) ApiResponses.ok();
            }
        }
        notFound();
    }

    /** The agents a request reaches: the calling agent alone, unless it is {@code main} or the operator. */
    private static List<Agent> agentsInScope() {
        if (RequestPrincipal.isAgentOriginated()) {
            var caller = RequestPrincipal.callingAgent();
            if (caller == null) {
                ApiResponses.error(403, ApiResponses.AGENT_SCOPE,
                        "This request is agent-originated but names no agent, so it cannot be scoped.");
                throw ApiResponses.unreachable();
            }
            if (!caller.isMain()) return List.of(caller);
        }
        // A subagent works in its root agent's workspace, so the root agents cover every workspace.
        return Tx.run(() -> {
            var roots = new ArrayList<Agent>();
            for (Object agent : Agent.find("parentAgent is null order by id").fetch()) roots.add((Agent) agent);
            return roots;
        });
    }
}
