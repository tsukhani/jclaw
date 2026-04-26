package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.OcrHealthProbe;

import java.util.HashMap;
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

    public static void status() {
        var probe = OcrHealthProbe.lastResult();
        var enabled = "true".equalsIgnoreCase(
                ConfigService.get("ocr.tesseract.enabled", "true"));

        var tesseract = new HashMap<String, Object>();
        tesseract.put("name", "tesseract");
        tesseract.put("displayName", "Tesseract OCR");
        tesseract.put("available", probe.available());
        tesseract.put("version", probe.version());
        tesseract.put("reason", probe.reason());
        tesseract.put("enabled", enabled);
        tesseract.put("configKey", "ocr.tesseract.enabled");
        tesseract.put("description",
                "Apache Tika's TesseractOCRParser. Extracts text from images and "
                + "scanned PDFs by shelling out to the tesseract binary. Fast and "
                + "predictable for English-language print scans; weaker on "
                + "handwriting and complex layouts.");
        tesseract.put("installHint",
                "Install tesseract on the host: brew install tesseract (macOS), "
                + "apt-get install tesseract-ocr (Debian/Ubuntu). A JVM restart is "
                + "required for the startup probe to re-detect the binary.");

        var result = new HashMap<String, Object>();
        result.put("providers", List.of(tesseract));
        renderJSON(gson.toJson(result));
    }
}
