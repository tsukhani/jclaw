package services;

import models.Agent;
import models.Notification;
import org.jspecify.annotations.Nullable;
import services.JavaRuntimeService.Download;
import services.JavaRuntimeService.Environment;
import services.JavaRuntimeService.Kind;
import services.JavaRuntimeService.Release;
import services.JavaRuntimeService.Stager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The daily look for a newer patch of the Java JClaw runs on. On the Java the installer set up, it downloads the
 * patch into {@code jre.staged/} exactly as Settings → Maintenance → Java runtime does, for the next start to
 * install; on a Java JClaw did not install it only tells the operator. Either way the operator hears once per
 * build, through a {@link Notification}. Nothing here stops or restarts JClaw. A container's image brings its
 * Java and a source checkout runs its developer's, so neither is looked at.
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

    public static final String TITLE = "Java runtime";
    /** Worded as Azul's latest notes, not the offered build's: a staged build or the catalog can briefly lag that page. */
    public static final String LINK_LABEL = "Azul's latest release notes";

    private static final String CATEGORY = "java-runtime";
    // Years of patches; the oldest falls off first.
    private static final int HANDLED_KEPT = 32;
    private static final String RESTART_HINT = " and installs the next time JClaw restarts. "
            + "Restart from Settings > Maintenance > Java runtime when it suits you.";

    private JavaRuntimePatchCheck() {}

    /**
     * One look. A download that fails, or a {@code recipient} that does not exist yet, records nothing, so the
     * next run tries again.
     *
     * @param latest    the newest patch of the running major, or null when the catalog cannot be reached
     * @param recipient the agent the notification is filed under; the job passes main, which the operator's
     *                  notification bar shows
     */
    public static void run(Environment env, Supplier<@Nullable Release> latest, Download progress, Stager stager,
                           String recipient) {
        if (env.container() || JavaRuntimeService.sourceCheckout(env.app())) return;
        var release = latest.get();
        // stageable's own test, so what the panel offers and what this check stages never part.
        if (release == null || !JavaRuntimeService.isNewer(release, env)) return;
        if (handled(env.app()).contains(release.archive())) return;
        // Asked before a download, so a patch is never staged with no one to tell.
        if (Tx.run(() -> Agent.findByName(recipient)) == null) {
            EventLogger.warn(CATEGORY, "No agent '" + recipient + "' to tell about Java " + release.version());
            return;
        }

        var message = JavaRuntimeService.kind(env) == Kind.BUILT_IN
                ? builtIn(env, release, progress, stager)
                : "Java " + named(release) + " is available for the Java JClaw runs on, Java " + env.javaVersion()
                        + " at " + env.javaHome() + ". Update that Java the way you installed it.";
        if (message != null && notify(recipient, message)) {
            EventLogger.info(CATEGORY, "Told the operator about Java " + release.version());
            markHandled(env.app(), release.archive());
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

    /** On the installer's Java: stage {@code release} or say how to, or null when there is nothing to tell yet. */
    private static @Nullable String builtIn(Environment env, Release release, Download progress, Stager stager) {
        var staged = JavaRuntimeService.staged(env.home());
        if (staged != null && staged.archive().equals(release.archive())) {
            // Downloaded from Settings: the operator chose it, so there is nothing to download or tell.
            markHandled(env.app(), release.archive());
            return null;
        }
        // The panel's own download is running; the next run finds what it staged.
        if (progress.active()) return null;
        if (!ConfigService.getBoolean(AUTO_DOWNLOAD_KEY, true)) {
            return staged == null
                    ? "Java " + named(release) + " is available. Download it from Settings > Maintenance > Java "
                            + "runtime; it installs the next time JClaw restarts."
                    : "Java " + named(release) + " is available, newer than the Java " + staged.version()
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
        } catch (IOException _) {
            return null; // stageNow logged it, and nothing was staged
        } catch (IllegalStateException e) {
            EventLogger.warn(CATEGORY, "Java " + release.version() + " was not downloaded on its own: "
                    + e.getMessage());
            return null;
        }
        return "Java " + named(release) + " is downloaded"
                + (staged == null ? "" : " in place of Java " + staged.version()) + RESTART_HINT;
    }

    // "Java 25.0.5.1, a security and bug-fix release," reads as one phrase; with no type it is the version alone.
    private static String named(Release release) {
        var words = JavaRuntimeService.releaseTypeInWords(release.releaseType());
        return words == null ? release.version() : release.version() + ", " + words + ",";
    }

    private static boolean notify(String recipient, String message) {
        return Tx.run(() -> {
            var agent = Agent.findByName(recipient);
            if (agent == null) return false;
            var n = new Notification();
            n.agent = agent;
            n.title = TITLE;
            n.content = message;
            n.linkUrl = JavaRuntimeService.RELEASE_NOTES;
            n.linkLabel = LINK_LABEL;
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
        var dir = app.resolve("data");
        try {
            Files.createDirectories(dir);
            var part = dir.resolve(HANDLED_FILE + ".part");
            Files.writeString(part, String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
            Files.move(part, dir.resolve(HANDLED_FILE), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not record Java build " + build + " as handled, so it may be "
                    + "downloaded or announced again: " + e.getMessage());
        }
    }
}
