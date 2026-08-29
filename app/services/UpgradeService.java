package services;

import com.google.gson.JsonParser;
import okhttp3.Request;
import play.Play;
import utils.HandoffShell;
import utils.HttpFactories;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Installs a newer JClaw release over this one on operator request (the
 * Settings → Upgrade button, {@code POST /api/system/upgrade}).
 *
 * <p>Like {@link RestartService}, this is a <em>handoff</em>: the tree being
 * replaced is the tree this JVM is running from, so the work is done by a
 * detached {@code jclaw.sh upgrade} that outlives us. jclaw.sh owns the whole
 * sequence — download, checksum, stop, database backup, tree swap, restart,
 * health gate, rollback — for the same reason the restart does: it already
 * encodes the stop/start invariants, and it is also the CLI entry point, so
 * duplicating any of it here would give the two front doors a chance to
 * disagree.
 *
 * <p>This class contributes only what the shell cannot: a preflight the UI can
 * render before committing, and a cached release check that does not re-hit
 * GitHub on every settings-page mount.
 */
public final class UpgradeService {

    private static final String CATEGORY = "upgrade";

    /** Seconds the helper waits before touching this install — see {@link RestartService#RESPONSE_FLUSH_DELAY_SECONDS}. */
    static final int RESPONSE_FLUSH_DELAY_SECONDS = 2;

    static final String UPGRADE_LOG = "logs/upgrade.log";
    static final String STATUS_FILE = "logs/upgrade-status.json";

    private static final String DEFAULT_REPO = "tsukhani/jclaw";
    private static final String DEFAULT_API = "https://api.github.com";
    private static final String REPO_PROPERTY = "jclaw.upgrade.repo";
    private static final String API_URL_PROPERTY = "jclaw.upgrade.api.url";
    private static final String KEY_TAG_NAME = "tag_name";
    private static final int TIMEOUT_SECONDS = 20;

    /**
     * How long a resolved release tag is reused. GitHub allows 60 unauthenticated
     * API calls an hour per IP, and this is polled by every settings-page mount,
     * so an uncached check would be a self-inflicted rate limit. Operators who
     * want a fresh answer get one via {@code refresh=true}.
     */
    private static final Duration CHECK_TTL = Duration.ofHours(1);

    /**
     * The only shape a release version may have. The pinned {@code tsukhani/jclaw}
     * release path is the sole anchor the download has — the SHA256SUMS manifest is
     * fetched from the same prefix as the asset, so it cannot vouch for it — and a
     * literal of this shape cannot carry the slash or dot-segment that would move
     * that prefix.
     */
    private static final Pattern RELEASE_VERSION = Pattern.compile("v?\\d+\\.\\d+\\.\\d+");

    /** Test seam: when non-null, {@link #requestUpgrade(String)} hands the plan here instead of spawning it. */
    public static Consumer<Plan> spawnerForTest;

    /** Test seam: when non-null, {@link #latestVersion(boolean)} returns this instead of calling GitHub. */
    public static String latestVersionForTest;

    private record CachedTag(String tag, Instant at) {}

    private static volatile CachedTag cachedTag;

    /**
     * @param command        argv handed to {@link ProcessBuilder}
     * @param currentVersion the version running now
     * @param targetVersion  the version that will be installed, without a leading {@code v}
     * @param installKind    {@code "bundle"} (self-contained, upgrades from
     *                       {@code jclaw-bundle.zip}) or {@code "dist"}
     *                       ({@code jclaw.zip}, against a system {@code play})
     */
    public record Plan(List<String> command, String currentVersion, String targetVersion, String installKind) {}

    /**
     * Progress written by the helper, or null when no upgrade has ever run.
     * Mirrors {@code logs/upgrade-status.json} — see {@code upgrade_status} in
     * jclaw.sh for the phase vocabulary.
     */
    public record Status(String phase, int pct, String message,
                         String fromVersion, String toVersion, String startedAt) {}

    private UpgradeService() {}

    /** The {@code jclaw.sh} that manages this installation. */
    public static File script() {
        return new File(Play.applicationPath, "jclaw.sh");
    }

    /**
     * Why this instance cannot upgrade itself, or null when it can. Kept in
     * lockstep with {@code upgrade_unavailable_reason} in jclaw.sh — the helper
     * re-checks before doing anything, so a disagreement costs a clean refusal
     * rather than a half-applied upgrade, but the UI should never offer a
     * button the helper would reject.
     */
    public static String unavailableReason() {
        if (isDeveloperClone()) {
            return "This is a source checkout — update it with 'git pull'.";
        }
        if (isContainer()) {
            return "This instance runs in a container — upgrade the image instead "
                    + "(docker compose pull && docker compose up -d).";
        }
        if (HandoffShell.locate() == null) {
            return "No shell was found to run jclaw.sh — " + HandoffShell.MISSING_SHELL_HINT;
        }
        var script = script();
        if (!script.isFile() || !script.canExecute()) {
            return "jclaw.sh is missing or not executable at " + script.getAbsolutePath()
                    + " — this installation is not managed by jclaw.sh.";
        }
        if (!Play.applicationPath.canWrite()) {
            return Play.applicationPath.getAbsolutePath()
                    + " is not writable — the upgrade needs to replace it in place.";
        }
        var parent = Play.applicationPath.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.canWrite()) {
            return "The parent of " + Play.applicationPath.getAbsolutePath()
                    + " is not writable — the upgrade needs to swap the install directory.";
        }
        return null;
    }

    /**
     * True when this tree carries sources. {@code app/} is the same signal
     * {@link RestartService#isDeveloperClone()} and {@code do_start_prod} use;
     * {@code .git} additionally catches a clone whose sources were removed.
     */
    static boolean isDeveloperClone() {
        return new File(Play.applicationPath, "app").isDirectory()
                || new File(Play.applicationPath, ".git").exists();
    }

    /**
     * True inside a container, where the image is the upgrade unit — an
     * in-place tree swap is discarded by the next {@code docker compose up}.
     */
    static boolean isContainer() {
        return System.getenv("JCLAW_CONTAINER") != null || new File("/.dockerenv").exists();
    }

    /**
     * {@code "bundle"} when the tree carries its own framework and {@code play}
     * launcher, {@code "dist"} otherwise. Decides which release asset the
     * helper fetches, so a bundle install is never swapped for a dist tree that
     * has no launcher to start it.
     */
    public static String installKind() {
        var bundled = new File(Play.applicationPath, "framework").isDirectory()
                && new File(Play.applicationPath, "play").canExecute();
        return bundled ? "bundle" : "dist";
    }

    public static String currentVersion() {
        return Play.configuration.getProperty("application.version", "0.0.0");
    }

    /**
     * Newest published release tag without its leading {@code v}, or null when
     * GitHub could not be reached. Cached for {@link #CHECK_TTL}.
     *
     * @param refresh bypass the cache for an operator-initiated re-check
     */
    public static String latestVersion(boolean refresh) {
        if (latestVersionForTest != null) return latestVersionForTest;

        var cached = cachedTag;
        if (!refresh && cached != null && Duration.between(cached.at(), Instant.now()).compareTo(CHECK_TTL) < 0) {
            return cached.tag();
        }
        try {
            var tag = fetchLatestTag();
            cachedTag = new CachedTag(tag, Instant.now());
            return tag;
        } catch (IOException | RuntimeException e) {
            EventLogger.warn(CATEGORY, "Could not check for a newer release: " + e.getMessage());
            // Serve a stale answer rather than none — an hour-old version number
            // is still more useful to the operator than an empty panel.
            return cached != null ? cached.tag() : null;
        }
    }

    private static String fetchLatestTag() throws IOException {
        var url = base(API_URL_PROPERTY, DEFAULT_API) + "/repos/" + repo() + "/releases/latest";
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " for " + url);
            }
            var json = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            if (!json.has(KEY_TAG_NAME) || json.get(KEY_TAG_NAME).isJsonNull()) {
                throw new IOException("release payload has no " + KEY_TAG_NAME);
            }
            return stripV(json.get(KEY_TAG_NAME).getAsString());
        }
    }

    /** True when {@code candidate} is a strictly newer release than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        if (candidate == null || current == null) return false;
        var a = stripV(candidate).split("\\.");
        var b = stripV(current).split("\\.");
        for (var i = 0; i < 3; i++) {
            var x = numericAt(a, i);
            var y = numericAt(b, i);
            if (x != y) return x > y;
        }
        return false;
    }

    private static int numericAt(String[] parts, int i) {
        if (i >= parts.length) return 0;
        var digits = parts[i].replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private static String stripV(String v) {
        return v.startsWith("v") ? v.substring(1) : v;
    }

    /**
     * Gate for every version that reaches the helper argv. Rejects rather than
     * rewrites: a value that is not a release version is an attempt at something
     * else, and quietly sanitizing one hides it from the operator and the log.
     *
     * @param version a release version, or null/blank for "the newest release"
     * @throws IllegalArgumentException if {@code version} is present and is not
     *         {@code MAJOR.MINOR.PATCH}, optionally {@code v}-prefixed
     */
    private static void requireReleaseVersion(String version) {
        if (version == null || version.isBlank()) return;
        if (!RELEASE_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Not a release version: '" + version + "' — expected MAJOR.MINOR.PATCH, for example 0.17.78.");
        }
    }

    /**
     * Resolve what an upgrade would run, without running it. The target version
     * is pinned into the argv so the helper installs exactly what the operator
     * was shown, even if a new release lands between the preflight and the POST.
     *
     * @throws IllegalArgumentException if {@code targetVersion} is not a release version
     */
    public static Plan plan(String targetVersion) {
        requireReleaseVersion(targetVersion);
        var args = new ArrayList<String>();
        args.add(script().getAbsolutePath());
        args.add("upgrade");
        args.add("--yes");
        if (targetVersion != null && !targetVersion.isBlank()) {
            args.add("--version");
            args.add("v" + stripV(targetVersion));
        }
        return new Plan(List.copyOf(args), currentVersion(), stripV(targetVersion == null ? "" : targetVersion),
                installKind());
    }

    /**
     * Launch the upgrade. Returns as soon as the helper is spawned — this JVM
     * keeps serving through the download and only goes down when the helper
     * reaches its stop step, minutes later.
     *
     * @throws IllegalArgumentException if {@code targetVersion} is not a release version
     * @throws IllegalStateException if this install cannot be upgraded, or if
     *         there is no newer release, both checked <em>before</em> anything
     *         is spawned so a refusal leaves the app untouched
     * @throws IOException if the helper process cannot be started
     */
    public static Plan requestUpgrade(String targetVersion) throws IOException {
        requireReleaseVersion(targetVersion);

        var unavailable = unavailableReason();
        if (unavailable != null) {
            throw new IllegalStateException("Cannot upgrade: " + unavailable);
        }

        var target = targetVersion;
        if (target == null || target.isBlank()) {
            target = latestVersion(false);
            if (target == null) {
                throw new IllegalStateException(
                        "Cannot upgrade: could not reach GitHub to resolve the latest release.");
            }
            if (!isNewer(target, currentVersion())) {
                throw new IllegalStateException("Cannot upgrade: already running the latest release ("
                        + currentVersion() + ").");
            }
        }

        var plan = plan(target);

        if (spawnerForTest != null) {
            spawnerForTest.accept(plan);
            return plan;
        }

        EventLogger.warn(CATEGORY, "Upgrading on operator request — handing off to jclaw.sh",
                String.join(" ", plan.command()));
        spawn(plan);
        return plan;
    }

    /**
     * Spawn the helper so it survives this JVM's death — same
     * {@code sh -c 'sleep N; exec …'} reparenting trick as
     * {@link RestartService}, and for the same reason.
     */
    private static void spawn(Plan plan) throws IOException {
        var log = new File(Play.applicationPath, UPGRADE_LOG);
        //noinspection ResultOfMethodCallIgnored
        log.getParentFile().mkdirs();

        new ProcessBuilder(HandoffShell.detached(plan.command(), RESPONSE_FLUSH_DELAY_SECONDS))
                .directory(Play.applicationPath)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectErrorStream(true)
                .start();
    }

    /**
     * Last reported helper progress, or null when no upgrade has run here.
     *
     * <p>Readable across the swap because {@code logs/} is carried into the new
     * tree, so the instance that comes back up serves the file the helper wrote
     * on its way there — which is how the panel reports the outcome of the very
     * upgrade that replaced it.
     */
    public static Status status() {
        var file = new File(Play.applicationPath, STATUS_FILE);
        if (!file.isFile()) return null;
        try {
            var json = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
            return new Status(
                    str(json, "phase"),
                    json.has("pct") && !json.get("pct").isJsonNull() ? json.get("pct").getAsInt() : 0,
                    str(json, "message"),
                    str(json, "fromVersion"),
                    str(json, "toVersion"),
                    str(json, "startedAt"));
        } catch (IOException | RuntimeException _) {
            // A torn read of a file the helper is mid-write on is expected while
            // an upgrade runs; the next poll a second later gets a whole one.
            return null;
        }
    }

    private static String str(com.google.gson.JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String repo() {
        return base(REPO_PROPERTY, DEFAULT_REPO);
    }

    private static String base(String property, String fallback) {
        var configured = Play.configuration.getProperty(property, "");
        return configured.isBlank() ? fallback : configured.trim();
    }
}
