package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.AcpHarnessProbe;

import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * ACP coding-harness detection: probes the host PATH for the CLIs the ACP
 * runtime ({@code runtime=acp}) can drive — {@code claude}, {@code pi}, {@code
 * codex}, {@code gemini}, {@code opencode}. Surfaced in Settings → Subagents so
 * the operator picks a detected
 * harness (auto-filling {@code subagent.acp.command} + {@code
 * subagent.acp.harness}) instead of typing the command by hand. Probed fresh
 * on each call; the panel queries it once on open.
 */
@With(AuthCheck.class)
public class ApiAcpHarnessController extends Controller {

    private static final Gson gson = INSTANCE;

    /** One probed harness: adapter {@code id} (the {@code subagent.acp.harness}
     *  value), a display {@code name}, the suggested {@code command} (the {@code
     *  subagent.acp.command} value), whether the binary is on PATH, and a
     *  human-readable reason (version line or the not-found detail). */
    public record HarnessEntry(String id, String name, String command, boolean available, String reason) {}

    public record HarnessesResponse(List<HarnessEntry> harnesses) {}

    /** GET /api/subagents/acp-harnesses — probe every known harness on PATH. */
    @Operation(summary = "Detect installed ACP coding harnesses (claude/pi/codex) on the host PATH")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HarnessesResponse.class)))
    public static void list() {
        var harnesses = AcpHarnessProbe.probeAll().stream()
                .map(d -> new HarnessEntry(d.id(), d.displayName(), d.command(), d.available(), d.reason()))
                .toList();
        renderJSON(gson.toJson(new HarnessesResponse(harnesses)));
    }
}
