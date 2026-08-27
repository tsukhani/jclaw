package services;

/**
 * JCLAW-1108: how to install Tesseract, worded for the platform the operator is
 * actually on. Single-sourced because two callers need the same words — the boot
 * WARN from {@link jobs.TesseractProbeJob} and the {@code installHint} that
 * {@code /api/ocr/status} renders in Settings → OCR. Two copies would drift, and
 * the wrong copy is what an operator reads while OCR is not working.
 *
 * <p>Instructions rather than an install button. JClaw could shell out to a
 * package manager, but not usefully everywhere: Linux managers need root and a
 * process with no TTY cannot prompt for sudo, and the Windows installer raises a
 * UAC prompt that a headless run cannot answer. A button that works on one
 * platform and fails on another teaches the operator the feature is broken; the
 * command they can paste works on all of them.
 */
public final class OcrInstallHint {

    private OcrInstallHint() {}

    /** For this host. */
    public static String current() {
        return forOs(System.getProperty("os.name", ""));
    }

    /**
     * Visible for testing: os.name is process-global and the suite runs tests
     * concurrently, so the platform branches have to be reachable without setting it.
     */
    public static String forOs(String osName) {
        var os = osName == null ? "" : osName;
        if (os.startsWith("Windows")) {
            // The UB Mannheim installer does not add itself to PATH, and a PATH change
            // is invisible to terminals that were already open — between them the two
            // commonest reasons a correct Windows install still reports "not detected".
            return "winget install -e --id UB-Mannheim.TesseractOCR — then either open a "
                    + "NEW terminal so the updated PATH is visible, or set ocr.tesseract.path "
                    + "in conf/application.conf to the install directory "
                    + "(C:\\Program Files\\Tesseract-OCR). Restart JClaw either way.";
        }
        if (os.startsWith("Mac")) {
            return "brew install tesseract — then restart JClaw.";
        }
        return "apt-get install tesseract-ocr (Debian/Ubuntu), dnf install tesseract "
                + "(Fedora/RHEL), pacman -S tesseract (Arch), or the equivalent for your "
                + "distribution — then restart JClaw.";
    }
}
