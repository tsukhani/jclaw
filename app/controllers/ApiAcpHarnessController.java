package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.AcpHarnessProbe;
import tools.AcpCommandPreview;
import utils.ApiResponses;

import java.util.List;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * ACP coding-harness detection: probes the host PATH for the CLIs the ACP
 * runtime ({@code runtime=acp}) can drive — {@code claude}, {@code pi}, {@code
 * codex}, {@code gemini}, {@code opencode}, plus operator-added custom commands.
 * Surfaced in Settings → Coding so the operator picks a detected harness
 * (autofilling {@code subagent.acp.command} + {@code subagent.acp.harness})
 * instead of typing the command by hand. Probed fresh on each call.
 */
@With(AuthCheck.class)
public class ApiAcpHarnessController extends Controller {

    private static final Gson gson = GSON;
    private static final String COMMAND_FIELD = "command";

    /** One probed harness: adapter {@code id}, a display {@code name}, the
     *  suggested {@code command} (the {@code subagent.acp.command} value), the
     *  {@code harness} adapter id to write ({@code subagent.acp.harness}),
     *  whether the binary is on PATH, a human-readable reason, whether it's an
     *  operator-added {@code custom} chip (removable), the ACP support badge
     *  ({@code acpSupport}: native/adapter/adapter-missing/none) and its tooltip
     *  ({@code acpDetail}). */
    public record HarnessEntry(String id, String name, String command, String harness,
                               boolean available, String reason, boolean custom,
                               String acpSupport, String acpDetail) {}

    public record HarnessesResponse(List<HarnessEntry> harnesses) {}

    public record CustomHarnessRequest(String command) {}

    /** GET /api/subagents/acp-harnesses — probe every known + custom harness. */
    @Operation(summary = "Detect installed ACP coding harnesses (claude/pi/codex/gemini/opencode + custom) on PATH")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HarnessesResponse.class)))
    @AgentAccess(OPEN)
    public static void list() {
        renderJSON(gson.toJson(new HarnessesResponse(toEntries(AcpHarnessProbe.probeAll()))));
    }

    /** Reject the agent principal on the custom-harness writes. The stored command is what a
     *  {@code runtime=acp} spawn executes, so writing one is arbitrary local execution behind a
     *  key the JCLAW-1022 config guard covers only on {@code POST /api/config} (JCLAW-1227). */
    private static void requireOperator() {
        if (RequestPrincipal.isAgentOriginated()) {
            ApiResponses.error(403, ApiResponses.OPERATOR_ONLY,
                    "ACP harness commands are operator-only; an agent cannot add or remove one.");
        }
    }

    /** POST /api/subagents/acp-harnesses — probe an operator-entered command;
     *  when its binary resolves it's persisted and returned as a custom chip,
     *  otherwise the {@code available:false} result carries the reason. */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = CustomHarnessRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HarnessEntry.class)))
    @Operation(summary = "Add + probe a custom ACP harness command")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "persists a command a runtime=acp spawn then executes -- privilege escalation")
    public static void add() {
        requireOperator();

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(COMMAND_FIELD) || body.get(COMMAND_FIELD).isJsonNull()) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var probed = AcpHarnessProbe.addCustom(body.get(COMMAND_FIELD).getAsString());
        renderJSON(gson.toJson(toEntry(probed)));
    }

    /** DELETE /api/subagents/acp-harnesses?command=... — remove an
     *  operator-added custom command (identified by its command string) and
     *  return the refreshed list. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HarnessesResponse.class)))
    @Operation(summary = "Remove a custom ACP harness command")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "edits the stored ACP harness list a runtime=acp spawn executes from")
    public static void remove(String command) {
        requireOperator();

        if (command == null || command.isBlank()) {
            badRequest();
        }
        var rejection = AcpHarnessProbe.removeCustom(command);
        if (rejection != null) {
            ApiResponses.error(403, ApiResponses.FORBIDDEN, rejection);
        }
        renderJSON(gson.toJson(new HarnessesResponse(toEntries(AcpHarnessProbe.probeAll()))));
    }

    /** GET /api/subagents/acp-command — what a {@code runtime="acp"} spawn
     *  launches under the current Settings, so the Coding panel can show the
     *  model override's flags without storing them in the command. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AcpCommandPreview.class)))
    @Operation(summary = "Preview the effective ACP harness launch command")
    @AgentAccess(OPEN)
    public static void commandPreview() {
        renderJSON(gson.toJson(AcpCommandPreview.current()));
    }

    private static List<HarnessEntry> toEntries(List<AcpHarnessProbe.Detected> detected) {
        return detected.stream().map(ApiAcpHarnessController::toEntry).toList();
    }

    private static HarnessEntry toEntry(AcpHarnessProbe.Detected d) {
        return new HarnessEntry(d.id(), d.displayName(), d.command(), d.harness(),
                d.available(), d.reason(), d.custom(), d.acpSupport(), d.acpDetail());
    }
}
