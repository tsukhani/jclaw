package jobs;

import play.Logger;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.OcrHealthProbe;

/**
 * JCLAW-177: probe the {@code tesseract} binary at boot. When missing, log
 * WARN naming the affected capability ("OCR-dependent inputs will return
 * empty text") so deployers see exactly what they lose without running an
 * OCR-bearing document through {@link tools.DocumentsTool} themselves.
 *
 * <p>Run-once-at-boot is intentional: re-probing on every parse adds a
 * subprocess fork to the hot path for no benefit (operators install or remove
 * tesseract via the OS package manager, not at runtime). A restart picks up
 * the new state.
 */
@OnApplicationStart
@NoTransaction // No DB access — just probes the tesseract binary; skip the JPA wrapper.
public class TesseractProbeJob extends Job<Void> {

    @Override
    public void doJob() {
        var r = OcrHealthProbe.probe();
        if (r.available()) {
            Logger.info("OCR: tesseract available — %s", r.version());
        } else {
            Logger.warn("OCR: %s. OCR-dependent inputs (image-only PDFs, plain images, "
                    + "scanned documents) will return empty text. Install with: %s",
                    r.reason(), installHint());
        }
    }

    /**
     * Name the platform the operator is actually on. The previous message offered
     * brew and apt to everyone, which on Windows is advice for two operating systems
     * they are not running. The Windows installer does not put tesseract on PATH, so
     * the caveat and the ocr.tesseract.path escape hatch both matter there
     * (JCLAW-1107).
     */
    private static String installHint() {
        var os = System.getProperty("os.name", "");
        if (os.startsWith("Windows")) {
            return "winget install -e --id UB-Mannheim.TesseractOCR — then either reopen "
                    + "your terminal so the new PATH is visible, or set ocr.tesseract.path "
                    + "to the install directory (e.g. C:\\Program Files\\Tesseract-OCR).";
        }
        if (os.startsWith("Mac")) {
            return "brew install tesseract.";
        }
        return "apt-get install tesseract-ocr (Debian/Ubuntu), dnf install tesseract "
                + "(Fedora/RHEL), or the equivalent for your distribution.";
    }
}
