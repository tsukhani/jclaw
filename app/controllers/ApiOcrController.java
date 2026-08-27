package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.OcrHealthProbe;
import services.TesseractInstaller;
import utils.ApiResponses;

import java.util.List;

import static utils.GsonHolder.GSON;

/**
 * Reports OCR backend availability and configured-enabled state for the
 * Settings page. The page needs both signals together: whether the binary
 * is on PATH (from {@link OcrHealthProbe}) and whether the operator has
 * toggled the backend on (from the Config DB). The frontend renders the
 * toggle uninteractive when {@code available=false}, so a host without
 * tesseract installed cannot have the toggle flipped on by accident.
 *
 * <p>Shaped as a {@code providers} array even though only Tesseract exists
 * today — JCLAW-179 (GLM-OCR via ollama-local) will append a second entry
 * without changing the response contract.
 */
@With(AuthCheck.class)
public class ApiOcrController extends Controller {

    private static final Gson gson = GSON;

    private static final String OPERATOR_ONLY = "operator_only";

    public record OcrProvider(String name, String displayName, boolean available,
                              String version, String reason, boolean enabled,
                              String configKey, String description, String installHint) {}

    public record OcrStatusResponse(List<OcrProvider> providers) {}

    /** What the install button would run here, so the UI can label it before it is pressed. */
    public record InstallPlanResponse(String manager, String command, boolean runnable, String reason) {}

    public record InstallStateResponse(String status, String manager, String command,
                                       String output, String hint) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = OcrStatusResponse.class)))
    @Operation(summary = "Report OCR provider availability and operator-enabled state for the Settings page")
    public static void status() {
        var probe = OcrHealthProbe.lastResult();
        var enabled = "true".equalsIgnoreCase(
                ConfigService.get("ocr.tesseract.enabled", "true"));

        var tesseract = new OcrProvider(
                "tesseract",
                "Tesseract OCR",
                probe.available(),
                probe.version(),
                probe.reason(),
                enabled,
                "ocr.tesseract.enabled",
                "Apache Tika's TesseractOCRParser. Extracts text from images and "
                        + "scanned PDFs by shelling out to the tesseract binary. Fast and "
                        + "predictable for English-language print scans; weaker on "
                        + "handwriting and complex layouts.",
                "Install tesseract on the host: brew install tesseract (macOS), "
                        + "apt-get install tesseract-ocr (Debian/Ubuntu). A JVM restart is "
                        + "required for the startup probe to re-detect the binary.");

        renderJSON(gson.toJson(new OcrStatusResponse(List.of(tesseract))));
    }

    /**
     * The command the install button would run on this host, and whether JClaw can
     * run it itself. Fetched before the button is pressed so the operator sees what
     * is about to happen — and on Linux, where we will not invoke sudo, sees the
     * command to run instead of a button that would only fail (JCLAW-1108).
     */
    @ChatHidden("names the host's package manager; exists only for the operator-only install button")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = InstallPlanResponse.class)))
    @Operation(summary = "Report how Tesseract would be installed on this host")
    public static void installPlan() {
        var p = TesseractInstaller.plan();
        renderJSON(gson.toJson(new InstallPlanResponse(p.manager(), p.display(), p.runnable(), p.reason())));
    }

    /**
     * Start the install. Operator-only: this runs a system package manager, which is
     * a wider capability than anything an agent is trusted with — the same reasoning
     * that gates config writes. Returns immediately; the UI polls installState.
     */
    @ChatHidden("runs a system package manager -- installs software outside JClaw's own directory")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = InstallStateResponse.class)))
    @Operation(summary = "Install Tesseract using this host's package manager")
    public static void install() {
        if (RequestPrincipal.isAgentOriginated()) {
            ApiResponses.error(403, OPERATOR_ONLY,
                    "Installing system packages is operator-only; an agent cannot trigger it.");
        }
        renderState(TesseractInstaller.start());
    }

    @ChatHidden("paired with the hidden install action; nothing for an agent to act on")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = InstallStateResponse.class)))
    @Operation(summary = "Poll the Tesseract install")
    public static void installState() {
        renderState(TesseractInstaller.state());
    }

    private static void renderState(TesseractInstaller.State s) {
        renderJSON(gson.toJson(new InstallStateResponse(
                s.status().name(), s.manager(), s.command(), s.output(), s.hint())));
    }
}
