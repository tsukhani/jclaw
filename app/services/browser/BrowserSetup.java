package services.browser;

import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import play.Play;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The browser tool's first-use setup as the chat progress bar and the Settings browser panel see
 * it: the Playwright driver's Node.js and Playwright's own Chromium install. PlaywrightBrowserTool
 * runs both under one lock, so a single set of fields describes whatever setup is in flight.
 */
public final class BrowserSetup {

    private BrowserSetup() {}

    /**
     * One setup's progress. {@code active} spans the whole setup, not each download, so a reader
     * polling between the driver and Chromium never sees a finished setup that is still running.
     * The app shares {@link #CURRENT}; tests build their own.
     */
    public static final class Progress {
        private volatile boolean active;
        private volatile @Nullable String step;
        private volatile @Nullable Integer percent;
        private volatile @Nullable String error;

        public void begin() {
            step = null;
            percent = null;
            error = null;
            active = true;
        }

        public void step(String what) {
            step = what;
            percent = 0;
        }

        public void percent(int value) {
            percent = Math.clamp(value, 0, 100);
        }

        /** Ends the setup; a failure recorded during it stays readable until the next begin. */
        public void end() {
            active = false;
            step = null;
            percent = null;
        }

        public void failed(String why) {
            error = why;
        }

        public void onInstallLine(String raw) {
            var parsed = parseInstallLine(raw);
            if (!active) return;
            if (parsed.step() != null) step(parsed.step());
            else if (parsed.percent() != null) percent(parsed.percent());
        }

        public boolean active() { return active; }
        public @Nullable String currentStep() { return step; }
        public @Nullable Integer currentPercent() { return percent; }
        public @Nullable String lastError() { return error; }
    }

    public static final Progress CURRENT = new Progress();

    /** What the API returns. {@code driverSource} is a lower-cased {@link PlaywrightNode.Source}. */
    public record Snapshot(boolean active, @Nullable String step, @Nullable Integer percent,
                           @Nullable String error, String driverSource, @Nullable String platform,
                           String nodeVersion, boolean chromiumInstalled) {}

    public static void begin() { CURRENT.begin(); }
    public static void step(String what) { CURRENT.step(what); }
    public static void percent(int value) { CURRENT.percent(value); }
    public static void end() { CURRENT.end(); }
    public static void failed(String why) { CURRENT.failed(why); }
    public static boolean active() { return CURRENT.active(); }
    public static void onInstallLine(String raw) { CURRENT.onInstallLine(raw); }

    public static Path cacheRoot() {
        return Play.applicationPath.toPath().resolve("data").resolve("playwright-node");
    }

    // driver-bundle, when present, sits on the JVM classpath that the Play launcher builds.
    public static PlaywrightNode.Status driver() {
        return PlaywrightNode.resolve(cacheRoot(), System.getenv(), ClassLoader.getSystemClassLoader(),
                PlaywrightNode.hostPlatform());
    }

    public static Snapshot snapshot() {
        var d = driver();
        return new Snapshot(CURRENT.active(), CURRENT.currentStep(), CURRENT.currentPercent(), CURRENT.lastError(),
                d.source().name().toLowerCase(Locale.ROOT),
                d.platform(), PlaywrightNode.VERSION, chromiumInstalled());
    }

    // ── Playwright's `install chromium` output ────────────────────────────────
    // Not a terminal, so it prints one line per ~10% rather than redrawing a bar:
    //   Downloading Chrome for Testing 153.0.8010.12 (playwright chromium v1243) from https://…
    //   |■■■■■■■■                                                                        |  10% of 182.1 MiB

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final Pattern DOWNLOADING = Pattern.compile("^Downloading (.+?) \\(playwright ");
    private static final Pattern PROGRESS = Pattern.compile("\\|\\s*(\\d{1,3})% of ");

    /** One installer line, read as a new item being downloaded, a percent, or neither. */
    public record InstallLine(@Nullable String step, @Nullable Integer percent) {}

    public static InstallLine parseInstallLine(String raw) {
        var line = ANSI.matcher(raw).replaceAll("");
        var d = DOWNLOADING.matcher(line);
        if (d.find()) return new InstallLine("Downloading " + d.group(1), null);
        var p = PROGRESS.matcher(line);
        if (p.find()) return new InstallLine(null, Integer.parseInt(p.group(1)));
        return new InstallLine(null, null);
    }

    // ── Is Chromium already installed? ────────────────────────────────────────
    // `install chromium` fetches these three; each finished one gets an INSTALLATION_COMPLETE marker
    // in <browsers>/<name>-<revision>/, with the revision this Playwright expects in its browsers.json.
    private static final List<String> CHROMIUM_SET = List.of("chromium", "chromium-headless-shell", "ffmpeg");

    public static boolean chromiumInstalled() {
        var root = browsersPath(System.getenv(), System.getProperty("os.name", ""), System.getProperty("user.home", ""));
        return root != null && chromiumInstalledAt(root, expectedRevisions());
    }

    public static boolean chromiumInstalledAt(Path root, Map<String, String> revisions) {
        if (!revisions.keySet().containsAll(CHROMIUM_SET)) return false;
        return CHROMIUM_SET.stream().allMatch(name ->
                Files.exists(root.resolve(name.replace('-', '_') + "-" + revisions.get(name))
                        .resolve("INSTALLATION_COMPLETE")));
    }

    /** Playwright's browsers directory: PLAYWRIGHT_BROWSERS_PATH, else its per-OS default cache. */
    public static @Nullable Path browsersPath(Map<String, String> env, String osName, String home) {
        var explicit = env.get("PLAYWRIGHT_BROWSERS_PATH");
        // "0" means browsers live inside the driver package itself, which has no fixed location.
        if (explicit != null && !explicit.isBlank()) return explicit.equals("0") ? null : Path.of(explicit);
        var os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            var local = env.get("LOCALAPPDATA");
            return (local != null && !local.isBlank() ? Path.of(local) : Path.of(home, "AppData", "Local"))
                    .resolve("ms-playwright");
        }
        if (os.contains("mac os x")) return Path.of(home, "Library", "Caches", "ms-playwright");
        var xdg = env.get("XDG_CACHE_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".cache")).resolve("ms-playwright");
    }

    /** The revisions the driver jar's browsers.json pins, by browser name. */
    public static Map<String, String> expectedRevisions() {
        var in = ClassLoader.getSystemClassLoader().getResourceAsStream("driver/package/browsers.json");
        if (in == null) return Map.of();
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var out = new LinkedHashMap<String, String>();
            for (var b : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("browsers")) {
                var o = b.getAsJsonObject();
                out.put(o.get("name").getAsString(), o.get("revision").getAsString());
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
