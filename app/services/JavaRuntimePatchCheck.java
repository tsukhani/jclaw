package services;

import models.Agent;
import models.Notification;
import org.jspecify.annotations.Nullable;
import services.JavaRuntimeService.Checked;
import services.JavaRuntimeService.Download;
import services.JavaRuntimeService.Environment;
import services.JavaRuntimeService.Kind;
import services.JavaRuntimeService.Release;
import services.JavaRuntimeService.Stager;
import utils.AppClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The daily look for a newer patch of the Java JClaw runs on. On the Java the installer set up, it downloads the
 * patch into {@code jre.staged/} exactly as Settings → Maintenance → Java runtime does, for the next start to
 * install; on a Java JClaw did not install it only tells the operator. Either way the operator hears once per
 * build, through a {@link Notification}, and once when the check keeps failing: the same build failing to download
 * on two daily checks in a row, or the catalog on three. Nothing here stops or restarts JClaw. A container's image
 * brings its Java and a source checkout runs its developer's, so neither is looked at.
 */
public final class JavaRuntimePatchCheck {

    /** Config DB key, not seeded: absent or {@code true} downloads a patch on its own, {@code false} only tells. */
    public static final String AUTO_DOWNLOAD_KEY = "javaRuntime.autoDownload";

    /**
     * Under {@code <app>/data/}: the builds, by archive name, this check downloaded or told of and those the
     * operator canceled, one a line, so none is downloaded or told of twice. The archive names the Zulu build, so
     * a respin that keeps its Java version counts as a build of its own.
     */
    public static final String HANDLED_FILE = "java-runtime.notified";

    /**
     * Under {@code <app>/data/}: how many daily checks in a row could not check the catalog or download one build,
     * when each count last rose, whether the operator was told of each, and the last failure, for the panel. On
     * disk, so a restart keeps the counts. Absent while nothing fails.
     */
    public static final String FAILURES_FILE = "java-runtime.failures";

    public static final String TITLE = "Java runtime";

    private static final String CATEGORY = "java-runtime";
    // Years of patches; the oldest falls off first.
    private static final int HANDLED_KEPT = 32;
    // One failed daily check can be a network blip; the notices name these counts in words.
    private static final int DOWNLOAD_RUNS = 2;
    private static final int CATALOG_RUNS = 3;
    // Each start runs the check too, so failed runs closer than this are one daily check; @Every's are 24 h apart.
    private static final Duration DAILY_CHECK_SPACING = Duration.ofHours(20);
    private static final int REASON_MAX_CHARS = 300;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String RESTART_HINT = " and installs the next time JClaw restarts. "
            + "Restart from Settings > Maintenance > Java runtime when it suits you.";

    /** The daily check's last failure, kept until a later run succeeds, and when it happened. */
    public record Failure(String line, Instant at) {}

    private JavaRuntimePatchCheck() {}

    /**
     * One look. A download that fails, or a {@code recipient} that does not exist yet, marks nothing handled, so
     * the next run tries again.
     *
     * @param latest    the running vendor's catalog asked for the newest patch of the running major, past its cache
     * @param recipient the agent the notification is filed under; the job passes main, which the operator's
     *                  notification bar shows
     */
    public static void run(Environment env, Supplier<Checked> latest, Download progress, Stager stager,
                           String recipient) {
        if (env.container() || JavaRuntimeService.sourceCheckout(env.app())) return;
        var checked = latest.get();
        boolean builtIn = JavaRuntimeService.kind(env) == Kind.BUILT_IN;
        var failures = Failures.read(env.app());
        try {
            catalogChecked(env, checked, builtIn, failures, recipient);
            var release = checked.answer().release();
            if (release == null) return;
            // stageable's own test, so what the panel offers and what this check stages never part.
            if (!JavaRuntimeService.isNewer(release, env) || handled(env.app()).contains(release.archive())) {
                failures.settled();
                return;
            }
            // Asked before a download, so a patch is never staged with no one to tell.
            if (Tx.run(() -> Agent.findByName(recipient)) == null) {
                EventLogger.warn(CATEGORY, "No agent '" + recipient + "' to tell about Java " + release.version());
                return;
            }
            String message;
            try {
                message = builtIn
                        ? builtIn(env, release, progress, stager)
                        : named(env, release) + " is available for the Java JClaw runs on, Java " + env.javaVersion()
                                + " at " + env.javaHome() + ". Update that Java the way you installed it.";
            } catch (IOException | IllegalStateException e) {
                downloadFailed(env, release, e, failures, recipient);
                return;
            }
            if (message == null) {
                // builtIn records a build Settings already staged; any other null leaves the counts as they are.
                if (handled(env.app()).contains(release.archive())) failures.settled();
                return;
            }
            failures.settled();
            if (notify(recipient, message, release.notesUrl(), release.notesLabel())) {
                EventLogger.info(CATEGORY, "Told the operator about Java " + release.version());
                markHandled(env.app(), release.archive());
            }
        } finally {
            failures.save(env.app());
        }
    }

    /**
     * Settings' Cancel: drops the runtime waiting for the next start and records its build, so this check never
     * downloads again what the operator threw away.
     *
     * @return false when none is waiting
     * @throws IllegalStateException while a download runs
     */
    public static boolean cancel(Environment env, Download progress) throws IOException {
        var staged = JavaRuntimeService.staged(env.home());
        if (!JavaRuntimeService.cancel(env.home(), progress)) return false;
        if (staged != null) markHandled(env.app(), staged.archive());
        return true;
    }

    /** The builds this check has handled, oldest first. */
    public static List<String> handled(Path app) {
        try {
            return Files.readAllLines(app.resolve("data").resolve(HANDLED_FILE), StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList();
        } catch (IOException _) {
            return List.of();
        }
    }

    /** The last failure of this check, or null when none is recorded or a later run succeeded. */
    public static @Nullable Failure lastFailure(Path app) {
        var f = Failures.read(app);
        return f.line == null || f.at == null ? null : new Failure(f.line, f.at);
    }

    /**
     * On the installer's Java: stage {@code release} or say how to, or null when there is nothing to tell yet.
     *
     * @throws IOException           when the download or its check fails, which stageNow logged
     * @throws IllegalStateException when stageable refuses it
     */
    private static @Nullable String builtIn(Environment env, Release release, Download progress, Stager stager)
            throws IOException {
        var staged = JavaRuntimeService.staged(env.home());
        // A switch to the other vendor waits: the operator chose it, and a patch of the Java it leaves would undo it.
        if (staged != null && staged.vendor() != null && staged.vendor() != release.vendor()) return null;
        if (staged != null && staged.archive().equals(release.archive())) {
            // Downloaded from Settings: the operator chose it, so there is nothing to download or tell.
            markHandled(env.app(), release.archive());
            return null;
        }
        // The panel's own download is running; the next run finds what it staged.
        if (progress.active()) return null;
        if (!ConfigService.getBoolean(AUTO_DOWNLOAD_KEY, true)) {
            return staged == null
                    ? named(env, release) + " is available. Download it from Settings > Maintenance > Java "
                            + "runtime; it installs the next time JClaw restarts."
                    : named(env, release) + " is available, newer than the Java " + staged.version()
                            + " waiting for the next restart. To install it instead, cancel Java " + staged.version()
                            + " in Settings > Maintenance > Java runtime and download it there.";
        }
        // A newer build replaces the one waiting, so a restart never installs a patch already superseded.
        if (staged != null) {
            try {
                JavaRuntimeService.cancel(env.home(), progress);
            } catch (IOException | IllegalStateException e) {
                EventLogger.warn(CATEGORY, "Java " + staged.version() + ", waiting for the next start, could not "
                        + "make way for Java " + release.version() + ": " + e.getMessage());
                return null;
            }
        }
        try {
            JavaRuntimeService.stageNow(env, release, progress, stager);
        } catch (IllegalStateException e) {
            EventLogger.warn(CATEGORY, "Java " + release.version() + " was not downloaded on its own: "
                    + e.getMessage());
            throw e;
        }
        return named(env, release) + " is downloaded"
                + (staged == null ? "" : " in place of Java " + staged.version()) + RESTART_HINT;
    }

    private static void catalogChecked(Environment env, Checked checked, boolean builtIn, Failures failures,
                                       String recipient) {
        var answer = checked.answer();
        var failure = checked.failure();
        // The built-in Java came from this catalog, so one that now lists none for this system has stopped patching it.
        if (failure == null && builtIn && answer.release() == null) {
            failure = Objects.requireNonNullElse(answer.why(), "it gave no answer");
        }
        if (failure == null) {
            failures.catalogReached();
            return;
        }
        var owner = JavaRuntimeService.running(env).owner;
        var reason = sentence(brief(failure));
        int runs = failures.catalogFailed("Couldn't check " + owner + "'s catalog for a newer Java: " + reason);
        if (runs < CATALOG_RUNS || failures.catalogTold) return;
        if (notify(recipient, "JClaw couldn't check " + owner + "'s catalog for a newer Java on three daily checks "
                + "in a row, so it cannot tell whether Java " + env.javaVersion() + " has a newer patch. The last "
                + "check failed with: " + reason + " JClaw keeps checking once a day.", null, null)) {
            failures.catalogTold = true;
            EventLogger.info(CATEGORY, "Told the operator that " + owner + "'s catalog could not be checked on "
                    + CATALOG_RUNS + " daily checks in a row");
        }
    }

    private static void downloadFailed(Environment env, Release release, Exception e, Failures failures,
                                       String recipient) {
        var reason = sentence(brief(e instanceof JavaRuntimeService.RefusedException refused ? refused.inWords()
                : Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())));
        int runs = failures.downloadFailed(release.archive(),
                name(env, release) + " could not be downloaded: " + reason);
        if (runs < DOWNLOAD_RUNS || failures.buildTold) return;
        if (notify(recipient, named(env, release) + " could not be downloaded on two daily checks in a row: " + reason
                + " JClaw tries again at each daily check, or try it now from Settings > Maintenance > Java runtime.",
                release.notesUrl(), release.notesLabel())) {
            failures.buildTold = true;
            EventLogger.info(CATEGORY, "Told the operator that Java " + release.version() + " could not be "
                    + "downloaded on " + DOWNLOAD_RUNS + " daily checks in a row");
        }
    }

    // A respin of the running Java version differs only in its Zulu build, so it is named by that, as the panel does.
    private static String name(Environment env, Release release) {
        var build = release.build();
        return build != null && env.build() != null
                && JavaRuntimeService.compareVersions(release.version(), env.javaVersion()) == 0
                ? "Zulu " + build : "Java " + release.version();
    }

    // "Java 25.0.5.1, a security and bug-fix release," reads as one phrase; with no type it is the name alone.
    private static String named(Environment env, Release release) {
        var words = JavaRuntimeService.releaseTypeInWords(release.releaseType());
        return words == null ? name(env, release) : name(env, release) + ", " + words + ",";
    }

    // One line, so the record keeps one failure a line and a notice never carries a whole catalog payload.
    private static String brief(String why) {
        var line = WHITESPACE.matcher(why).replaceAll(" ").strip();
        return line.length() <= REASON_MAX_CHARS ? line : line.substring(0, REASON_MAX_CHARS) + "…";
    }

    private static String sentence(String text) {
        return text.endsWith(".") ? text : text + ".";
    }

    private static boolean notify(String recipient, String message, @Nullable String linkUrl,
                                  @Nullable String linkLabel) {
        return Tx.run(() -> {
            var agent = Agent.findByName(recipient);
            if (agent == null) return false;
            var n = new Notification();
            n.agent = agent;
            n.title = TITLE;
            n.content = message;
            n.linkUrl = linkUrl;
            n.linkLabel = linkLabel;
            n.save();
            return true;
        });
    }

    // Synchronized because Settings' Cancel and the job's run can both record at once.
    private static synchronized void markHandled(Path app, String build) {
        var builds = new ArrayList<>(handled(app));
        if (builds.contains(build)) return;
        builds.add(build);
        var kept = builds.subList(Math.max(0, builds.size() - HANDLED_KEPT), builds.size());
        try {
            write(app, HANDLED_FILE, String.join("\n", kept) + "\n");
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not record Java build " + build + " as handled, so it may be "
                    + "downloaded or announced again: " + e.getMessage());
        }
    }

    private static void write(Path app, String file, String content) throws IOException {
        var dir = app.resolve("data");
        Files.createDirectories(dir);
        var part = dir.resolve(file + ".part");
        Files.writeString(part, content, StandardCharsets.UTF_8);
        Files.move(part, dir.resolve(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@link #FAILURES_FILE}, read as a run starts and saved as it ends. */
    private static final class Failures {
        int catalogRuns;
        boolean catalogTold;
        @Nullable Instant catalogAt;
        @Nullable String build;
        int buildRuns;
        @Nullable Instant buildAt;
        boolean buildTold;
        @Nullable String line;
        @Nullable Instant at;

        void catalogReached() {
            catalogRuns = 0;
            catalogAt = null;
            catalogTold = false;
        }

        /** Records {@code why}; @return the daily checks in a row that have now failed to check the catalog */
        int catalogFailed(String why) {
            var now = failed(why);
            if (newDailyCheck(catalogAt, now)) {
                catalogRuns++;
                catalogAt = now;
            }
            return catalogRuns;
        }

        /** Records {@code why}; @return the daily checks in a row that have now failed to download {@code archive} */
        int downloadFailed(String archive, String why) {
            var now = failed(why);
            if (!archive.equals(build)) {
                build = archive;
                buildRuns = 0;
                buildAt = null;
                buildTold = false;
            }
            if (newDailyCheck(buildAt, now)) {
                buildRuns++;
                buildAt = now;
            }
            return buildRuns;
        }

        /** A run that downloaded the newest build, found it handled, or found nothing newer. */
        void settled() {
            build = null;
            buildRuns = 0;
            buildAt = null;
            buildTold = false;
        }

        private Instant failed(String why) {
            var now = AppClock.now();
            line = why;
            at = now;
            return now;
        }

        // abs: once the clock is set back a day, a run counts rather than waiting for the clock to pass the record.
        private static boolean newDailyCheck(@Nullable Instant counted, Instant now) {
            return counted == null || Duration.between(counted, now).abs().compareTo(DAILY_CHECK_SPACING) >= 0;
        }

        static Failures read(Path app) {
            var f = new Failures();
            var fields = new HashMap<String, String>();
            try {
                for (var l : Files.readAllLines(app.resolve("data").resolve(FAILURES_FILE), StandardCharsets.UTF_8)) {
                    int eq = l.indexOf('=');
                    if (eq > 0) fields.putIfAbsent(l.substring(0, eq), l.substring(eq + 1));
                }
            } catch (IOException _) {
                return f;
            }
            f.catalogRuns = count(fields.get("catalog"));
            f.catalogAt = instant(fields.get("catalogAt"));
            f.catalogTold = "true".equals(fields.get("catalogTold"));
            f.build = fields.get("build");
            f.buildRuns = f.build == null ? 0 : count(fields.get("buildRuns"));
            f.buildAt = f.build == null ? null : instant(fields.get("buildAt"));
            f.buildTold = "true".equals(fields.get("buildTold"));
            f.line = fields.get("failure");
            f.at = instant(fields.get("failureAt"));
            return f;
        }

        void save(Path app) {
            boolean clear = catalogRuns == 0 && buildRuns == 0;
            try {
                if (clear) {
                    Files.deleteIfExists(app.resolve("data").resolve(FAILURES_FILE));
                    return;
                }
                var out = new StringBuilder("catalog=" + catalogRuns + "\ncatalogTold=" + catalogTold + "\n");
                if (catalogAt != null) out.append("catalogAt=").append(catalogAt).append('\n');
                if (build != null) {
                    out.append("build=").append(build).append("\nbuildRuns=").append(buildRuns).append("\nbuildTold=")
                            .append(buildTold).append('\n');
                    if (buildAt != null) out.append("buildAt=").append(buildAt).append('\n');
                }
                if (line != null && at != null) {
                    out.append("failure=").append(line).append("\nfailureAt=").append(at).append('\n');
                }
                write(app, FAILURES_FILE, out.toString());
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Could not record the Java patch check's failures, so a notice about "
                        + "them may come late or twice: " + e.getMessage());
            }
        }

        private static int count(@Nullable String value) {
            try {
                return value == null ? 0 : Math.max(0, Integer.parseInt(value.strip()));
            } catch (NumberFormatException _) {
                return 0;
            }
        }

        private static @Nullable Instant instant(@Nullable String value) {
            try {
                return value == null ? null : Instant.parse(value.strip());
            } catch (DateTimeParseException _) {
                return null;
            }
        }
    }
}
