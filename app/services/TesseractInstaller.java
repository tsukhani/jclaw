package services;

import play.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1108: best-effort "install Tesseract for me" behind the Settings → OCR
 * button. The first thing in JClaw that installs anything outside its own data
 * directory, so the shape is deliberately conservative.
 *
 * <p>It cannot succeed everywhere, and does not pretend to:
 * <ul>
 *   <li><b>macOS</b> — {@code brew install tesseract} runs as the operator. Works.</li>
 *   <li><b>Windows</b> — winget drives an NSIS installer that writes to Program
 *       Files, so Windows raises a UAC prompt. That succeeds when a desktop
 *       session is there to answer it and fails when JClaw runs headless.</li>
 *   <li><b>Linux</b> — the package managers need root and JClaw runs as the
 *       operator. We deliberately do NOT invoke sudo: with no TTY it cannot
 *       prompt, so it would hang or fail obscurely. Unless already running as
 *       root (containers), the plan is returned for the operator to run.</li>
 * </ul>
 *
 * <p>A failed plan still earns its place: handing back the exact command beats
 * an operator guessing which package manager and package name their distro uses.
 *
 * <p>Note for callers: a successful install does NOT switch OCR on in this JVM.
 * Tika's TesseractOCRParser decides {@code hasTesseract} in its constructor and
 * {@code TikaHolder.PARSER} is built at class-init, so the running process still
 * holds a parser that concluded the binary was absent. A restart is required, and
 * the UI must say so — reporting success while OCR stays broken is worse than
 * reporting nothing.
 */
public final class TesseractInstaller {

    /** Long enough for a large download, short enough that an unanswered UAC prompt ends. */
    private static final long TIMEOUT_MINUTES = 10;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    public enum Status { IDLE, RUNNING, SUCCEEDED, FAILED, UNSUPPORTED }

    /**
     * @param runnable whether JClaw can run this itself, or the operator must
     * @param command  argv, never a shell string — nothing is interpolated into a shell
     */
    public record Plan(String manager, List<String> command, boolean runnable, String reason) {
        public String display() { return String.join(" ", command); }
    }

    public record State(Status status, String manager, String command, String output, String hint) {}

    private static final AtomicReference<State> STATE =
            new AtomicReference<>(new State(Status.IDLE, null, null, null, null));

    private TesseractInstaller() {}

    public static State state() { return STATE.get(); }

    /**
     * What would run on this host. Pure apart from the os.name read and the
     * which-manager-exists probes, so the platform mapping is unit-testable via
     * {@link #planFor(String, java.util.function.Predicate)}.
     */
    public static Plan plan() {
        return planFor(System.getProperty("os.name", ""), TesseractInstaller::onPath,
                "root".equals(System.getProperty("user.name")));
    }

    /**
     * Visible for testing: {@code available} answers "is this manager on PATH", and
     * {@code root} is passed rather than read so a test does not depend on the user
     * the suite happens to run as — a root container would otherwise flip the Linux
     * branch from "tell the operator" to "run it".
     */
    public static Plan planFor(String osName, java.util.function.Predicate<String> available, boolean root) {
        var os = osName == null ? "" : osName;
        if (os.startsWith("Windows")) {
            if (!available.test("winget")) {
                return new Plan("winget", List.of("winget", "install", "-e", "--id",
                        "UB-Mannheim.TesseractOCR"), false,
                        "winget was not found. Install it from the Microsoft Store (App Installer), "
                                + "or download Tesseract from the UB Mannheim project directly.");
            }
            return new Plan("winget", List.of("winget", "install", "-e", "--id",
                    "UB-Mannheim.TesseractOCR", "--silent",
                    "--accept-package-agreements", "--accept-source-agreements"), true,
                    "Windows will raise a UAC prompt — the installer writes to Program Files. "
                            + "Answer it on the desktop; a headless run cannot.");
        }
        if (os.startsWith("Mac")) {
            if (!available.test("brew")) {
                return new Plan("brew", List.of("brew", "install", "tesseract"), false,
                        "Homebrew was not found. Install it from https://brew.sh, then retry.");
            }
            return new Plan("brew", List.of("brew", "install", "tesseract"), true, null);
        }
        // Linux and the rest: name the distro's manager, but never invoke sudo.
        if (available.test("apt-get")) return linux("apt-get", List.of("apt-get", "install", "-y", "tesseract-ocr"), root);
        if (available.test("dnf"))     return linux("dnf",     List.of("dnf", "install", "-y", "tesseract"), root);
        if (available.test("pacman"))  return linux("pacman",  List.of("pacman", "-S", "--noconfirm", "tesseract"), root);
        if (available.test("zypper"))  return linux("zypper",  List.of("zypper", "install", "-y", "tesseract-ocr"), root);
        if (available.test("apk"))     return linux("apk",     List.of("apk", "add", "tesseract-ocr"), root);
        return new Plan(null, List.of(), false,
                "No supported package manager was found. Install tesseract with your "
                        + "distribution's package manager, then set ocr.tesseract.path if it "
                        + "does not land on PATH.");
    }

    private static Plan linux(String manager, List<String> argv, boolean root) {
        if (root) return new Plan(manager, argv, true, null);
        return new Plan(manager, argv, false,
                "This needs root and JClaw runs as your user. Run it yourself:  sudo "
                        + String.join(" ", argv));
    }

    /**
     * Kick off the install if one is not already running. Returns the state the
     * caller should render — single-flight, so a double-click cannot start two.
     */
    public static synchronized State start() {
        var current = STATE.get();
        if (current.status() == Status.RUNNING) return current;

        var plan = plan();
        if (!plan.runnable()) {
            var s = new State(Status.UNSUPPORTED, plan.manager(), plan.display(), null, plan.reason());
            STATE.set(s);
            return s;
        }
        STATE.set(new State(Status.RUNNING, plan.manager(), plan.display(), null, plan.reason()));
        Thread.ofVirtual().name("tesseract-install").start(() -> run(plan));
        return STATE.get();
    }

    private static void run(Plan plan) {
        try {
            var pb = new ProcessBuilder(plan.command()).redirectErrorStream(true);
            var proc = pb.start();
            String out;
            try (var in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                STATE.set(new State(Status.FAILED, plan.manager(), plan.display(), trim(out),
                        "Timed out after " + TIMEOUT_MINUTES + " minutes. On Windows this usually means "
                                + "a UAC prompt went unanswered. Run it yourself:  " + plan.display()));
                return;
            }
            int code = proc.exitValue();
            if (code == 0) {
                OcrHealthProbe.probe();   // refresh the cached availability for the status endpoint
                STATE.set(new State(Status.SUCCEEDED, plan.manager(), plan.display(), trim(out),
                        "Restart JClaw to activate OCR — the running process decided at startup that "
                                + "tesseract was absent and does not re-check."));
                Logger.info("OCR: tesseract installed via %s", plan.manager());
            } else {
                STATE.set(new State(Status.FAILED, plan.manager(), plan.display(), trim(out),
                        "Exited " + code + ". Run it yourself to see the full output:  " + plan.display()));
                Logger.warn("OCR: tesseract install via %s exited %d", plan.manager(), code);
            }
        } catch (IOException e) {
            STATE.set(new State(Status.FAILED, plan.manager(), plan.display(), null,
                    plan.manager() + " could not be started (" + e.getMessage() + "). Run it yourself:  "
                            + plan.display()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            STATE.set(new State(Status.FAILED, plan.manager(), plan.display(), null,
                    "Interrupted while installing."));
        }
    }

    /** Keep the tail: package managers put the useful error at the end. */
    private static String trim(String s) {
        if (s == null) return null;
        var t = s.strip();
        return t.length() <= MAX_OUTPUT_CHARS ? t : "…" + t.substring(t.length() - MAX_OUTPUT_CHARS);
    }

    private static boolean onPath(String binary) {
        try {
            var probe = System.getProperty("os.name", "").startsWith("Windows")
                    ? List.of("where", binary) : List.of("which", binary);
            var p = new ProcessBuilder(probe).redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
