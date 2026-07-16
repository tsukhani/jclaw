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

import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * ACP coding-harness detection: probes the host PATH for the CLIs the ACP
 * runtime ({@code runtime=acp}) can drive — {@code claude}, {@code pi}, {@code
 * codex}, {@code gemini}, {@code opencode}, plus operator-added custom commands.
 * Surfaced in Settings → Subagents so the operator picks a detected harness
 * (auto-filling {@code subagent.acp.command} + {@code subagent.acp.harness})
 * instead of typing the command by hand. Probed fresh on each call.
 */
@With(AuthCheck.class)
public class ApiAcpHarnessController extends Controller {

    private static final Gson gson = INSTANCE;
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
    public static void list() {
        renderJSON(gson.toJson(new HarnessesResponse(toEntries(AcpHarnessProbe.probeAll()))));
    }

    /** POST /api/subagents/acp-harnesses — probe an operator-entered command;
     *  when its binary resolves it's persisted and returned as a custom chip,
     *  otherwise the {@code available:false} result carries the reason. */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = CustomHarnessRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HarnessEntry.class)))
    @Operation(summary = "Add + probe a custom ACP harness command")
    public static void add() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(COMMAND_FIELD) || body.get(COMMAND_FIELD).isJsonNull()) {
            badRequest();
        }
        var probed = AcpHarnessProbe.addCustom(body.get(COMMAND_FIELD).getAsString());
        renderJSON(gson.toJson(toEntry(probed)));
    }

    /** DELETE /api/subagents/acp-harnesses?command=... — remove an
     *  operator-added custom command (identified by its command string) and
     *  return the refreshed list. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HarnessesResponse.class)))
    @Operation(summary = "Remove a custom ACP harness command")
    public static void remove(String command) {
        if (command == null || command.isBlank()) {
            badRequest();
        }
        AcpHarnessProbe.removeCustom(command);
        renderJSON(gson.toJson(new HarnessesResponse(toEntries(AcpHarnessProbe.probeAll()))));
    }

    private static List<HarnessEntry> toEntries(List<AcpHarnessProbe.Detected> detected) {
        return detected.stream().map(ApiAcpHarnessController::toEntry).toList();
    }

    private static HarnessEntry toEntry(AcpHarnessProbe.Detected d) {
        return new HarnessEntry(d.id(), d.displayName(), d.command(), d.harness(),
                d.available(), d.reason(), d.custom(), d.acpSupport(), d.acpDetail());
    }
}
