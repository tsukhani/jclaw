package services;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final ProbeResult UNRUN = new ProbeResult(false, null,
            "tesseract probe has not run yet");

    private static final AtomicReference<ProbeResult> result = new AtomicReference<>(UNRUN);

    /**
     * Execute {@code tesseract --version} and cache the outcome. Safe to call
     * repeatedly (e.g. from tests) — each call replaces the cached result.
     * IOException from {@link ProcessBuilder#start()} is the canonical signal
     * that the binary is not on PATH; we translate it into a non-cryptic
     * {@link ProbeResult} rather than letting the raw exception escape.
     */
    public static ProbeResult probe() {
        var pb = new ProcessBuilder("tesseract", "--version");
        pb.redirectErrorStream(true);
        try {
            var proc = pb.start();
            String output;
            try (var in = proc.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            int exit = proc.waitFor();
            if (exit == 0) {
                var firstLine = output.lines().findFirst().orElse("(no version output)").trim();
                return setResult(new ProbeResult(true, firstLine, null));
            }
            return setResult(new ProbeResult(false, null,
                    "tesseract --version exited %d: %s".formatted(exit, output.strip())));
        } catch (IOException e) {
            return setResult(new ProbeResult(false, null,
                    "tesseract binary not found on PATH (%s)".formatted(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return setResult(new ProbeResult(false, null,
                    "tesseract --version interrupted: " + e.getMessage()));
        }
    }

    public static ProbeResult lastResult() {
        return result.get();
    }

    /**
     * Test seam: replace the cached probe result without invoking the binary.
     * Lets tests exercise the missing-tesseract code path on a host where the
     * binary is actually installed (and vice versa).
     */
    public static void setForTest(ProbeResult forced) {
        result.set(forced == null ? UNRUN : forced);
    }

    private static ProbeResult setResult(ProbeResult r) {
        result.set(r);
        return r;
    }
}
