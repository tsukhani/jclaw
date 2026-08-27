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
import services.OcrInstallHint;

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

    public record OcrProvider(String name, String displayName, boolean available,
                              String version, String reason, boolean enabled,
                              String configKey, String description, String installHint) {}

    public record OcrStatusResponse(List<OcrProvider> providers) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = OcrStatusResponse.class)))
    @Operation(summary = "Report OCR provider availability and operator-enabled state for the Settings page")
    public static void status() {
        // Probe now rather than serving the boot snapshot: an operator who just
        // installed tesseract, or an agent asking through jclaw_api whether OCR
        // works, would otherwise be told no until the next restart.
        var probe = OcrHealthProbe.refresh();
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
                // Worded for this host, and shared with the boot WARN so the operator
                // reads the same instructions wherever they meet them (JCLAW-1108).
                OcrInstallHint.current());

        renderJSON(gson.toJson(new OcrStatusResponse(List.of(tesseract))));
    }
}
