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
                    + "scanned documents) will return empty text. Install with: "
                    + "brew install tesseract (macOS), apt-get install tesseract-ocr "
                    + "(Debian/Ubuntu), or the equivalent for your platform.", r.reason());
        }
    }
}
