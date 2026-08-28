package utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Locates the shell that JClaw's detached {@code jclaw.sh} handoffs run through,
 * and renders the argv it is handed.
 *
 * <p>Both handoffs — {@code services.RestartService} and
 * {@code services.UpgradeService} — re-exec jclaw.sh through a shell so the
 * helper outlives the JVM it is about to replace. On macOS and Linux that shell
 * is {@code /bin/sh} and nothing here is interesting.
 *
 * <p>It cannot be {@code /bin/sh} on Windows. The one-line installer's Git Bash
 * path deliberately runs a <em>Windows</em> JVM (install.ps1 reuses the Windows
 * Java it verified), and {@code CreateProcess} resolves {@code /bin/sh} against
 * the current drive — {@code C:\bin\sh}, which does not exist. So the shell has
 * to be found, and the argv rewritten into a form that shell can open.
 */
public final class HandoffShell {

    private static final String POSIX_SHELL = "/bin/sh";

    /** What an operator can do about {@link #locate()} finding nothing. */
    public static final String MISSING_SHELL_HINT =
            "install Git for Windows (which provides Git Bash), or run JClaw under WSL.";

    private HandoffShell() {}

    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /** Absolute path of the shell to hand off through, or null when none is installed. */
    public static String locate() {
        return locate(System.getProperty("os.name", ""), System::getenv, p -> new File(p).canExecute());
    }

    /**
     * Visible for testing: {@code os.name} and the environment are process-global, and
     * the suite runs tests concurrently, so the platform branches have to be reachable
     * without setting either.
     */
    public static String locate(String osName, UnaryOperator<String> env, Predicate<String> executable) {
        if (!isWindows(osName)) {
            return POSIX_SHELL;
        }
        for (var candidate : windowsCandidates(env)) {
            if (executable.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Git Bash installs first — {@code EXEPATH} when the app was started from one,
     * then the three locations install.ps1 probes — and only then whatever is on
     * PATH. Anything under {@code %SystemRoot%} is skipped: {@code System32\bash.exe}
     * is WSL's launcher, and handing the helper to it would upgrade a tree inside
     * the distro rather than the Windows install that is actually running.
     */
    private static List<String> windowsCandidates(UnaryOperator<String> env) {
        var out = new ArrayList<String>();
        addUnder(out, env.apply("EXEPATH"), "bin\\bash.exe");
        addUnder(out, env.apply("ProgramFiles"), "Git\\bin\\bash.exe");
        addUnder(out, env.apply("ProgramFiles(x86)"), "Git\\bin\\bash.exe");
        addUnder(out, env.apply("LOCALAPPDATA"), "Programs\\Git\\bin\\bash.exe");

        var systemRoot = env.apply("SystemRoot");
        // Hardcoded ';': File.pathSeparator answers for the host running the JVM,
        // which is ':' whenever this branch is exercised from the test suite.
        for (var dir : splitOrEmpty(env.apply("PATH"))) {
            if (!dir.isBlank() && !isUnder(dir, systemRoot)) {
                addUnder(out, dir, "bash.exe");
            }
        }
        return out;
    }

    /**
     * Joins with a literal backslash rather than {@link File#File(String, String)}:
     * that separator is the one the JVM's <em>own</em> host uses, which is the wrong
     * one whenever these Windows paths are being reasoned about from anywhere else.
     */
    private static void addUnder(List<String> out, String dir, String relative) {
        if (dir != null && !dir.isBlank()) {
            out.add(trimTrailingSeparators(dir) + "\\" + relative);
        }
    }

    /** Trailing separator trim. The regex form {@code [\\/]+$} is quadratic under
     *  {@code replaceAll} — the anchored class retries at every start offset, which
     *  possessive quantifiers do not fix (java:S5852). */
    private static String trimTrailingSeparators(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\\' || s.charAt(end - 1) == '/')) end--;
        return end == s.length() ? s : s.substring(0, end);
    }

    private static boolean isUnder(String dir, String parent) {
        if (parent == null || parent.isBlank()) return false;
        var d = dir.toLowerCase(Locale.ROOT);
        var p = trimTrailingSeparators(parent.toLowerCase(Locale.ROOT));
        // The trailing separator matters: C:\WindowsApps is not under C:\Windows.
        return d.equals(p) || d.startsWith(p + "\\") || d.startsWith(p + "/");
    }

    private static String[] splitOrEmpty(String path) {
        return path == null ? new String[0] : path.split(";");
    }

    /**
     * argv for {@link ProcessBuilder} that waits {@code delaySeconds}, then replaces
     * the shell with {@code command} so the helper survives this JVM's death.
     *
     * @throws IOException when no shell is installed to hand off through
     */
    public static List<String> detached(List<String> command, int delaySeconds) throws IOException {
        var shell = locate();
        if (shell == null) {
            throw new IOException("no shell found to run " + String.join(" ", command) + " — " + MISSING_SHELL_HINT);
        }
        return List.of(shell, "-c", commandLine(command, delaySeconds, isWindows()));
    }

    /** Visible for testing: the Windows branch cannot be reached from a POSIX host. */
    public static String commandLine(List<String> command, int delaySeconds, boolean windows) {
        return "sleep " + delaySeconds + "; exec " + shellQuote(windows ? forwardSlashes(command) : command);
    }

    /**
     * Git Bash's MSYS layer resolves {@code C:/dir/file}, but the backslash form
     * {@link File#getAbsolutePath()} returns is not a POSIX path at all.
     */
    private static List<String> forwardSlashes(List<String> argv) {
        return argv.stream().map(arg -> arg.replace('\\', '/')).toList();
    }

    /**
     * Render argv as a single-quoted shell word list. Needed because the command is
     * handed to {@code -c} as one string: an application path containing a space (or
     * worse) would otherwise word-split into a different command than the one the
     * caller resolved.
     */
    public static String shellQuote(List<String> argv) {
        var sb = new StringBuilder();
        for (var arg : argv) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append('\'').append(arg.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
    }
}
