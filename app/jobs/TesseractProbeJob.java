package jobs;

import play.Logger;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.OcrHealthProbe;
import services.OcrInstallHint;

/**
 * JCLAW-177: probe the {@code tesseract} binary at boot. When missing, log
 * WARN naming the affected capability ("OCR-dependent inputs will return
 * empty text") so deployers see exactly what they lose without running an
 * OCR-bearing document through {@link tools.DocumentsTool} themselves.
 *
 * <p>Run-once-at-boot is intentional: re-probing on every parse adds a
 * subprocess fork to the hot path for no benefit (operators install or remove
 * tesseract via the OS package manager, not at runtime). A restart picks up
 * the new state. Off the boot thread (JF-270): waiting for {@code tesseract --version}
 * held the port closed for about 190 ms, and a reader that comes first probes itself.
 */
@OnApplicationStart(async = true)
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
                    r.reason(), OcrInstallHint.current());
        }
    }
}
