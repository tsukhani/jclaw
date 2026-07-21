package services;

/**
 * One-shot health check for the {@code tesseract} binary that Apache Tika
 * shells out to for OCR. Apache Tika's TesseractOCRParser fires opportunistically
 * when the binary is on PATH and silently no-ops otherwise — so without an
 * explicit probe, deployers learn that OCR is broken only by feeding an image
 * to {@code DocumentsTool} and getting empty text back.
 *
 * <p>{@link jobs.TesseractProbeJob} runs {@link #probe()} once at boot and
 * logs at WARN if the binary is missing or returns non-zero. {@link tools.DocumentsTool}
 * consults {@link #lastResult()} when a parse returns empty text, and appends
 * a clear install hint instead of the silently-empty default.
 */
public class OcrHealthProbe {

    public record ProbeResult(boolean available, String version, String reason) { }

    private static final ProbeCache<ProbeResult> CACHE = new ProbeCache<>(
            new ProbeResult(false, null, "tesseract probe has not run yet"));

    /**
     * Execute {@code tesseract --version} and cache the outcome. Safe to call
     * repeatedly (e.g. from tests) — each call replaces the cached result.
     * Delegates the bounded child-process exec to
     * {@link ExecutableProbeSupport#probeCapturing} so a tesseract binary that
     * accepts the invocation but never exits can't stall the synchronous boot
     * probe ({@link jobs.TesseractProbeJob}) forever — the shared helper caps
     * the wait and {@code destroyForcibly()}'s a hung child, yielding an
     * unavailable result instead of hanging. On success the printed version's
     * first line is surfaced as before.
     */
    public static ProbeResult probe() {
        var r = ExecutableProbeSupport.probeCapturing("tesseract", "--version", "");
        if (r.available()) {
            var firstLine = r.output().lines().findFirst().orElse("(no version output)").trim();
            return CACHE.set(new ProbeResult(true, firstLine, null));
        }
        return CACHE.set(new ProbeResult(false, null, r.reason()));
    }

    public static ProbeResult lastResult() {
        return CACHE.get();
    }

    /**
     * Test seam: replace the cached probe result without invoking the binary.
     * Lets tests exercise the missing-tesseract code path on a host where the
     * binary is actually installed (and vice versa).
     */
    public static void setForTest(ProbeResult forced) {
        CACHE.setForTest(forced);
    }
}
