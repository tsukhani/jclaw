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
import java.util.function.Supplier;

/**
 * The daily look for a newer patch of the Java JClaw runs on. On the Java the installer set up, it downloads the
 * patch into {@code jre.staged/} exactly as Settings → Maintenance → Java runtime does, for the next start to
 * install; on a Java JClaw did not install it only tells the operator. Either way the operator hears once per
 * version, through a {@link Notification}. Nothing here stops or restarts JClaw. A container's image brings its
 * Java and a source checkout runs its developer's, so neither is looked at.
 */
public final class JavaRuntimePatchCheck {

    /** Config DB key, not seeded: absent or {@code true} downloads a patch on its own, {@code false} only tells. */
    public static final String AUTO_DOWNLOAD_KEY = "javaRuntime.autoDownload";

    /** Under {@code <app>/data/}: the last version this check downloaded or told of, so neither happens twice. */
    public static final String HANDLED_FILE = "java-runtime.notified";

    public static final String TITLE = "Java runtime";
    public static final String LINK_LABEL = "Release notes";

    private static final String CATEGORY = "java-runtime";

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
        if (release == null || !JavaRuntimeService.isNewer(release.version(), env.javaVersion())) return;
        if (release.version().equals(handled(env.app()))) return;
        // Asked before a download, so a patch is never staged with no one to tell.
        if (Tx.run(() -> Agent.findByName(recipient)) == null) {
            EventLogger.warn(CATEGORY, "No agent '" + recipient + "' to tell about Java " + release.version());
            return;
        }

        String message;
        var kind = JavaRuntimeService.kind(env);
        if (kind == Kind.BUILT_IN && ConfigService.getBoolean(AUTO_DOWNLOAD_KEY, true)) {
            var staged = JavaRuntimeService.staged(env.home());
            if (staged != null) {
                // Staged from Settings: the operator chose it, so there is nothing to download or tell.
                if (staged.version().equals(release.version())) markHandled(env.app(), release.version());
                return;
            }
            // The panel's own download is running; the next run finds it staged.
            if (progress.active()) return;
            try {
                JavaRuntimeService.stageNow(env, release, progress, stager);
            } catch (IOException _) {
                return; // stageNow logged it, and nothing was staged
            } catch (IllegalStateException e) {
                EventLogger.warn(CATEGORY, "Java " + release.version() + " was not downloaded on its own: "
                        + e.getMessage());
                return;
            }
            message = "Java " + named(release) + " is downloaded and installs the next time JClaw restarts. "
                    + "Restart from Settings > Maintenance > Java runtime when it suits you.";
        } else if (kind == Kind.BUILT_IN) {
            message = "Java " + named(release) + " is available. Download it from Settings > Maintenance > "
                    + "Java runtime; it installs the next time JClaw restarts.";
        } else {
            message = "Java " + named(release) + " is available for the Java JClaw runs on, Java "
                    + env.javaVersion() + " at " + env.javaHome() + ". Update that Java the way you installed it.";
        }
        if (notify(recipient, message)) {
            EventLogger.info(CATEGORY, "Told the operator about Java " + release.version());
            markHandled(env.app(), release.version());
        }
    }

    /** The version this check last downloaded or told of, or null when it has handled none. */
    public static @Nullable String handled(Path app) {
        try {
            var lines = Files.readAllLines(app.resolve("data").resolve(HANDLED_FILE), StandardCharsets.UTF_8);
            return lines.isEmpty() ? null : lines.getFirst().strip();
        } catch (IOException _) {
            return null;
        }
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

    private static void markHandled(Path app, String version) {
        var dir = app.resolve("data");
        try {
            Files.createDirectories(dir);
            var part = dir.resolve(HANDLED_FILE + ".part");
            Files.writeString(part, version + "\n", StandardCharsets.UTF_8);
            Files.move(part, dir.resolve(HANDLED_FILE), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not record Java " + version + " as handled, so it may be announced again: "
                    + e.getMessage());
        }
    }
}
