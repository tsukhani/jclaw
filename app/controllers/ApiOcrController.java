package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.OcrHealthProbe;

import java.util.List;

import static utils.GsonHolder.INSTANCE;

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

    private static final Gson gson = INSTANCE;

    public record OcrProvider(String name, String displayName, boolean available,
                              String version, String reason, boolean enabled,
                              String configKey, String description, String installHint) {}

    public record OcrStatusResponse(List<OcrProvider> providers) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = OcrStatusResponse.class)))
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
}
