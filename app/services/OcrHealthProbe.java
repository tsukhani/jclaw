package services;

import play.Play;

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

    /**
     * The command Tika itself will run, so a green probe means OCR actually works.
     * Mirrors {@code TesseractOCRParser}: the executable is {@code tesseract.exe} on
     * Windows, and {@code ocr.tesseract.path} pins the directory when the binary is
     * not on PATH — the default on Windows, whose installer does not add itself.
     * Probing a bare "tesseract" against PATH would report unavailable for an install
     * the parser can reach perfectly well, and vice versa (JCLAW-1107).
     */
    static String tesseractCommand() {
        return tesseractCommand(System.getProperty("os.name", ""),
                Play.configuration != null
                        ? Play.configuration.getProperty(utils.TikaHolder.OCR_PATH_KEY) : null);
    }

    /**
     * Pure so it is testable: os.name and Play.configuration are process-global, and
     * the suite runs tests concurrently, so a test that flipped either to exercise the
     * Windows branch would corrupt whatever ran beside it. Public because Play 1.x
     * tests live in the default package.
     */
    public static String tesseractCommand(String osName, String configuredDir) {
        var prog = osName != null && osName.startsWith("Windows") ? "tesseract.exe" : "tesseract";
        if (configuredDir == null || configuredDir.isBlank()) return prog;
        return java.nio.file.Paths.get(configuredDir.trim()).resolve(prog).toString();
    }

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
        var r = ExecutableProbeSupport.probeCapturing(tesseractCommand(), "--version", "");
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
