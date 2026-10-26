package services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import play.Play;
import utils.AppClock;
import utils.HttpFactories;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Keeps the Java runtime under JClaw on the newest patch of its major version (Settings → Maintenance →
 * Java runtime). Only the runtime the installer put beside the install, {@code <home>/jre}, is ever
 * replaced, and never by this JVM: it downloads and verifies the release into {@code <home>/jre.staged/},
 * and jclaw.sh's {@code apply_staged_jre} unpacks it and swaps the folders at the next start, once this
 * JVM has stopped. Anywhere else this class only reports that a newer patch exists.
 */
public final class JavaRuntimeService {

    private static final String CATEGORY = "java-runtime";

    /** The catalog install.sh and install.ps1 query; {@link #catalogUrl} asks it the same question. */
    static final String AZUL_API = "https://api.azul.com/metadata/v1/zulu/packages/";
    static final String DOWNLOAD_HOST = "cdn.azul.com";
    /** A Zulu JRE archive is about 50 MB. */
    static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;

    static final String JRE_DIR = "jre";
    public static final String STAGED_DIR = "jre.staged";
    public static final String MANIFEST = "manifest";
    /** Written by jclaw.sh under {@code data/}. */
    public static final String STATUS_FILE = "java-runtime.status";
    private static final int STATUS_MAX_CHARS = 300;

    private static final int TIMEOUT_SECONDS = 20;
    private static final int DOWNLOAD_TIMEOUT_MINUTES = 30;
    /** Polled by every settings-page mount, like the release check in {@link UpgradeService}. */
    private static final Duration CHECK_TTL = Duration.ofHours(1);

    // The shapes apply_staged_jre in jclaw.sh accepts, so a manifest written here is one it reads.
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)*+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ARCHIVE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.(tar\\.gz|zip)");

    public enum Kind {
        /** The runtime the installer put at {@code <home>/jre}, the only one this class replaces. */
        BUILT_IN("built-in"),
        /** A Java the operator installed, or a source checkout's: its vendor updates it. */
        EXTERNAL("external"),
        /** The image brings the runtime. */
        CONTAINER("container");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * Where this JVM runs, as every check here reads it.
     *
     * @param app         the install directory, {@code Play.applicationPath}
     * @param javaHome    {@code java.home}
     * @param javaVersion {@code java.version}
     * @param vendor      {@code java.vendor}
     * @param osName      {@code os.name}
     * @param osArch      {@code os.arch}
     * @param container   true inside a container
     */
    public record Environment(Path app, Path javaHome, String javaVersion, @Nullable String vendor,
                              String osName, String osArch, boolean container) {

        /** The directory that holds the install and its {@code jre/}. */
        public Path home() {
            var abs = app.toAbsolutePath().normalize();
            var parent = abs.getParent();
            return parent == null ? abs : parent;
        }

        public boolean windows() {
            return osName.toLowerCase(Locale.ROOT).contains("windows");
        }
    }

    /** A Zulu JRE in the catalog; {@code sha256} is null when the catalog gave none, which the download refuses. */
    public record Release(String version, String url, @Nullable String sha256, String archive) {}

    /** A finished, verified download in {@code jre.staged/}, waiting for the next start. */
    public record Staged(String version, String sha256, String archive) {}

    /** jclaw.sh's line about what the last start did with a staged runtime, and when it wrote it. */
    public record LastStart(String line, Instant at) {}

    @FunctionalInterface
    public interface Catalog {
        Release fetch() throws IOException;
    }

    @FunctionalInterface
    public interface Stager {
        void stage(Release release, Path home, Download progress) throws IOException;
    }

    @SuppressWarnings("java:S3077") // published whole and read once per call, never mutated
    private static volatile @Nullable Environment environmentOverride;
    @SuppressWarnings("java:S3077")
    private static volatile @Nullable Release latestOverride;
    @SuppressWarnings("java:S3077")
    private static volatile @Nullable Stager stagerOverride;

    /** Test seam: where this JVM runs, in place of the real install and runtime; null clears it. */
    public static void setEnvironmentForTest(@Nullable Environment override) {
        environmentOverride = override;
    }

    /** Test seam: the catalog's answer, in place of a call to Azul; null clears it. */
    public static void setLatestForTest(@Nullable Release override) {
        latestOverride = override;
    }

    /** Test seam: runs in place of {@link #download}. In test mode nothing downloads without it. */
    public static void setStagerForTest(@Nullable Stager override) {
        stagerOverride = override;
    }

    /** One download's progress. The app shares {@link #CURRENT}; tests build their own. */
    public static final class Download {
        private final AtomicBoolean active = new AtomicBoolean();
        private volatile @Nullable Integer percent;
        private volatile @Nullable String error;

        /** False when a download is already running. */
        public boolean begin() {
            if (!active.compareAndSet(false, true)) return false;
            percent = 0;
            error = null;
            return true;
        }

        public void percent(int value) {
            percent = Math.clamp(value, 0, 100);
        }

        /** A failure stays readable until the next {@link #begin}. */
        public void failed(String why) {
            error = why;
        }

        public void end() {
            percent = null;
            active.set(false);
        }

        public boolean active() { return active.get(); }
        public @Nullable Integer currentPercent() { return percent; }
        public @Nullable String lastError() { return error; }
    }

    public static final Download CURRENT = new Download();

    /** The newest patch, cached for {@link #CHECK_TTL}. The app shares {@link #LOOKUP}; tests build their own. */
    public static final class Lookup {
        private record Cached(Release release, Instant at) {}

        private volatile @Nullable Cached cached;

        /**
         * @param refresh bypass the cache for an operator's re-check
         * @return the newest patch, the last good one when the catalog cannot be reached, or null when
         *         it never could
         */
        public @Nullable Release latest(boolean refresh, Catalog catalog) {
            var c = cached;
            if (!refresh && c != null && Duration.between(c.at(), AppClock.now()).compareTo(CHECK_TTL) < 0) {
                return c.release();
            }
            try {
                var release = catalog.fetch();
                cached = new Cached(release, AppClock.now());
                return release;
            } catch (IOException | RuntimeException e) {
                EventLogger.warn(CATEGORY, "Could not check Azul's catalog for a newer Java: " + e.getMessage());
                return c != null ? c.release() : null;
            }
        }
    }

    static final Lookup LOOKUP = new Lookup();

    // Held across the checks and the change, so a cancel never deletes a staging a new download is writing.
    private static final ReentrantLock STAGING = new ReentrantLock();

    private JavaRuntimeService() {}

    public static Environment environment() {
        var test = environmentOverride;
        if (test != null) return test;
        return new Environment(Play.applicationPath.toPath(), Path.of(System.getProperty("java.home", "")),
                System.getProperty("java.version", ""), System.getProperty("java.vendor"),
                System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                UpgradeService.isContainer());
    }

    /** Compares real paths, so a link to the install or a {@code ..} in {@code java.home} still counts. */
    public static Kind kind(Environment env) {
        if (env.container()) return Kind.CONTAINER;
        if (Files.isDirectory(env.app().resolve("app")) || Files.exists(env.app().resolve(".git"))) {
            return Kind.EXTERNAL;
        }
        try {
            var jre = env.home().resolve(JRE_DIR).toRealPath();
            return env.javaHome().toRealPath().startsWith(jre) ? Kind.BUILT_IN : Kind.EXTERNAL;
        } catch (IOException _) {
            return Kind.EXTERNAL;
        }
    }

    // ── The catalog ──────────────────────────────────────────────────────────

    /** The newest patch of the running major, from {@link #setLatestForTest} or the cached catalog check. */
    public static @Nullable Release latest(boolean refresh) {
        var test = latestOverride;
        if (test != null) return test;
        return LOOKUP.latest(refresh, () -> fetchLatest(environment()));
    }

    /** One catalog query for {@code env}'s major, OS, C library and architecture, bypassing the cache. */
    public static Release fetchLatest(Environment env) throws IOException {
        var musl = musl(env.javaHome());
        var os = azulOs(env.osName(), musl);
        var arch = azulArch(env.osArch());
        if (os == null || arch == null) {
            throw new IOException("Azul builds no Java for " + env.osName() + " on " + env.osArch());
        }
        int major = major(env.javaVersion());
        var url = catalogUrl(major, os, arch);
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " from Azul's catalog");
            var release = pickLatest(resp.body().string(), major, musl);
            if (release == null) {
                throw new IOException("Azul's catalog lists no Java " + major + " runtime for " + os + "/" + arch);
            }
            return release;
        }
    }

    /** install.sh's query; Windows asks for the zip, as install.ps1 does. */
    public static String catalogUrl(int major, String os, String arch) {
        var archive = "windows".equals(os) ? "zip" : "tar.gz";
        return AZUL_API + "?java_version=" + major + "&os=" + os + "&arch=" + arch + "&archive_type=" + archive
                + "&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20";
    }

    /** install.sh's OS names; Azul lists musl builds under an OS of their own. */
    public static @Nullable String azulOs(String osName, boolean musl) {
        var name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("windows")) return "windows";
        if (name.contains("mac")) return "macos";
        if (name.contains("linux")) return musl ? "linux-musl" : "linux";
        return null;
    }

    public static @Nullable String azulArch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
    }

    /** The running runtime's own C library, which the replacement must share: its release file says musl. */
    public static boolean musl(Path javaHome) {
        try {
            return Files.readAllLines(javaHome.resolve("release")).stream()
                    .anyMatch(l -> l.strip().equals("LIBC=\"musl\""));
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * The newest plain JRE of {@code major} in a catalog payload. JavaFX (fx) and CRaC builds are skipped,
     * and musl builds unless this runtime is one, as install.sh skips them. The newest rather than the
     * first: the catalog lists the builds it flags latest ahead of newer ones it does not.
     */
    public static @Nullable Release pickLatest(String payload, int major, boolean musl) {
        Release best = null;
        int[] bestDistro = {};
        for (JsonElement el : JsonParser.parseString(payload).getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            var url = str(o, "download_url");
            var version = joined(o, "java_version");
            if (url == null || version == null || major(version) != major) continue;
            var name = str(o, "name");
            var label = ((name == null ? "" : name) + " " + url).toLowerCase(Locale.ROOT);
            if (label.contains("fx") || label.contains("crac") || (!musl && label.contains("musl"))) continue;
            var sha = str(o, "sha256_hash");
            var distro = parts(joined(o, "distro_version"));
            var archive = name != null ? name : url.substring(url.lastIndexOf('/') + 1);
            var candidate = new Release(version, url, sha == null ? null : sha.toLowerCase(Locale.ROOT), archive);
            int order = best == null ? 1 : compareVersions(version, best.version());
            if (order > 0 || (order == 0 && compare(distro, bestDistro) > 0)) {
                best = candidate;
                bestDistro = distro;
            }
        }
        return best;
    }

    private static @Nullable String str(JsonObject o, String key) {
        var v = o.get(key);
        return v == null || !v.isJsonPrimitive() ? null : v.getAsString();
    }

    private static @Nullable String joined(JsonObject o, String key) {
        var v = o.get(key);
        if (v == null || !v.isJsonArray() || v.getAsJsonArray().isEmpty()) return null;
        var out = new StringBuilder();
        for (var part : v.getAsJsonArray()) {
            if (!out.isEmpty()) out.append('.');
            out.append(part.getAsInt());
        }
        return out.toString();
    }

    // ── Versions ─────────────────────────────────────────────────────────────

    public static int major(String version) {
        var p = parts(version);
        return p.length == 0 ? 0 : p[0];
    }

    /**
     * Orders dotted versions numerically, part by part, a missing part counting as 0: 25 equals 25.0.0,
     * and 25.0.4.1 follows 25.0.4. Each part is read up to its first non-digit, so 27-ea is 27.
     */
    public static int compareVersions(String a, String b) {
        return compare(parts(a), parts(b));
    }

    public static boolean isNewer(@Nullable String candidate, String current) {
        return candidate != null && compareVersions(candidate, current) > 0;
    }

    private static int[] parts(@Nullable String version) {
        if (version == null || version.isBlank()) return new int[0];
        var split = version.strip().split("\\.");
        var out = new int[split.length];
        for (int i = 0; i < split.length; i++) {
            int end = 0;
            while (end < split[i].length() && Character.isDigit(split[i].charAt(end))) end++;
            out[i] = end == 0 ? 0 : Integer.parseInt(split[i].substring(0, Math.min(end, 9)));
        }
        return out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    // ── What is on disk ──────────────────────────────────────────────────────

    /** The runtime waiting in {@code <home>/jre.staged/}, or null when none is, or its manifest is not whole. */
    public static @Nullable Staged staged(Path home) {
        var dir = home.resolve(STAGED_DIR);
        var fields = new HashMap<String, String>();
        try {
            for (var line : Files.readAllLines(dir.resolve(MANIFEST))) {
                int eq = line.indexOf('=');
                if (eq > 0) fields.putIfAbsent(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException _) {
            return null;
        }
        var version = fields.get("version");
        var sha = fields.get("sha256");
        var archive = fields.get("archive");
        if (version == null || sha == null || archive == null || !VERSION.matcher(version).matches()
                || !SHA256.matcher(sha).matches() || !ARCHIVE.matcher(archive).matches()
                || !Files.isRegularFile(dir.resolve(archive))) {
            return null;
        }
        return new Staged(version, sha, archive);
    }

    /** {@code <app>/data/java-runtime.status}, or null when the last start had no staged runtime to install. */
    public static @Nullable LastStart lastStart(Path app) {
        var file = app.resolve("data").resolve(STATUS_FILE);
        try {
            if (!Files.isRegularFile(file)) return null;
            var line = Files.readAllLines(file).stream().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse(null);
            if (line == null) return null;
            if (line.length() > STATUS_MAX_CHARS) line = line.substring(0, STATUS_MAX_CHARS);
            return new LastStart(line, Files.getLastModifiedTime(file).toInstant());
        } catch (IOException _) {
            return null;
        }
    }

    // ── Staging ──────────────────────────────────────────────────────────────

    /**
     * The release a download would stage, or why there is none. Each refusal is a row of the panel's
     * matrix, so the POST never accepts what the GET would not offer.
     *
     * @throws IllegalStateException naming why nothing can be staged
     */
    public static Release stageable(Environment env, @Nullable Release latest, @Nullable Staged staged,
                                    boolean downloading) {
        switch (kind(env)) {
            case CONTAINER -> throw new IllegalStateException(
                    "The runtime comes with the Docker image; pull a newer image to update Java.");
            case EXTERNAL -> throw new IllegalStateException("JClaw runs on a Java it did not install ("
                    + env.javaHome() + "), so that Java's vendor provides its updates.");
            case BUILT_IN -> { }
        }
        if (downloading) throw new IllegalStateException("A newer Java is already downloading.");
        if (staged != null) {
            throw new IllegalStateException("Java " + staged.version() + " will be installed at the next restart.");
        }
        if (latest == null) throw new IllegalStateException("Couldn't check Azul's catalog for a newer Java.");
        if (!isNewer(latest.version(), env.javaVersion())) {
            throw new IllegalStateException("Java " + env.javaVersion() + " is up to date.");
        }
        if (!Files.isWritable(env.home())) {
            throw new IllegalStateException(env.home() + " is not writable, so a new Java cannot be staged beside "
                    + env.home().resolve(JRE_DIR) + ".");
        }
        return latest;
    }

    /**
     * Start downloading the newest patch for the next start to install, returning as soon as it runs.
     *
     * @throws IllegalStateException when {@link #stageable} refuses, checked before anything is written
     */
    public static Release requestStage() {
        var stager = stagerOverride;
        if (stager == null) {
            stager = Play.runningInTestMode() ? JavaRuntimeService::refuseInTestMode : JavaRuntimeService::download;
        }
        var env = environment();
        return requestStage(env, latest(false), CURRENT, stager);
    }

    // A request that outlives its test's seams must not delete or download anything.
    private static void refuseInTestMode(Release release, Path home, Download progress) throws IOException {
        throw new IOException("in test mode a Java runtime downloads only through setStagerForTest");
    }

    public static Release requestStage(Environment env, @Nullable Release latest, Download progress, Stager stager) {
        Release release;
        STAGING.lock();
        try {
            release = stageable(env, latest, staged(env.home()), progress.active());
            if (!progress.begin()) throw new IllegalStateException("A newer Java is already downloading.");
        } finally {
            STAGING.unlock();
        }
        EventLogger.info(CATEGORY, "Downloading Java " + release.version() + " for the next start to install",
                release.url());
        var home = env.home();
        Thread.ofVirtual().name("java-runtime-download").start(() -> {
            try {
                stager.stage(release, home, progress);
                EventLogger.info(CATEGORY, "Java " + release.version() + " is staged; the next start installs it");
            } catch (IOException | RuntimeException e) {
                progress.failed("Java " + release.version() + " could not be downloaded: " + e.getMessage());
                EventLogger.warn(CATEGORY, "Java runtime download failed: " + e.getMessage(), release.url());
            } finally {
                progress.end();
            }
        });
        return release;
    }

    /**
     * Download {@code release} into {@code <home>/jre.staged/}, verifying it against the catalog's SHA-256
     * as it arrives, and write the manifest last. Any failure removes the whole staging folder.
     */
    public static Staged download(Release release, Path home, Download progress) throws IOException {
        var url = HttpUrl.parse(release.url());
        if (url == null || !url.isHttps() || !DOWNLOAD_HOST.equals(url.host())) {
            throw new IOException("refusing " + release.url() + ": Java is only downloaded over HTTPS from "
                    + DOWNLOAD_HOST);
        }
        var sha = release.sha256();
        if (sha == null || !SHA256.matcher(sha).matches()) {
            throw new IOException("Azul's catalog gave no SHA-256 for " + release.archive());
        }
        if (!ARCHIVE.matcher(release.archive()).matches() || !VERSION.matcher(release.version()).matches()) {
            throw new IOException("refusing an archive named " + release.archive());
        }
        var dir = home.resolve(STAGED_DIR);
        deleteTree(dir);
        Files.createDirectories(dir);
        try {
            var part = dir.resolve(release.archive() + ".part");
            fetch(url, part, sha, progress);
            Files.move(part, dir.resolve(release.archive()), StandardCopyOption.ATOMIC_MOVE);
            var manifest = dir.resolve(MANIFEST + ".part");
            Files.writeString(manifest, "version=" + release.version() + "\nsha256=" + sha + "\narchive="
                    + release.archive() + "\n", StandardCharsets.UTF_8);
            Files.move(manifest, dir.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(dir);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return new Staged(release.version(), sha, release.archive());
    }

    private static void fetch(HttpUrl url, Path out, String sha256, Download progress) throws IOException {
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).build());
        // general()'s 60 s call timeout spans the body, too short for ~60 MB below 8 Mbit/s; its read timeout still ends a stall.
        call.timeout().timeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        try (var response = call.execute()) {
            // Redirects are followed, so the host is checked again where the bytes come from.
            var landed = response.request().url();
            if (!landed.isHttps() || !DOWNLOAD_HOST.equals(landed.host())) {
                throw new IOException("refusing a redirect to " + landed.host() + ": Java is only downloaded from "
                        + DOWNLOAD_HOST);
            }
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " fetching " + url);
            var body = response.body();
            long total = body.contentLength();
            if (total > MAX_ARCHIVE_BYTES) {
                throw new IOException("refusing a " + total + "-byte archive, over the " + MAX_ARCHIVE_BYTES + "-byte cap");
            }
            var digest = sha256();
            try (var in = new DigestInputStream(body.byteStream(), digest); OutputStream os = Files.newOutputStream(out)) {
                var buffer = new byte[64 * 1024];
                long read = 0;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    read += n;
                    if (read > MAX_ARCHIVE_BYTES) {
                        throw new IOException("the archive exceeds the " + MAX_ARCHIVE_BYTES + "-byte cap");
                    }
                    os.write(buffer, 0, n);
                    if (total > 0) progress.percent((int) (read * 100 / total));
                }
            }
            var actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(sha256)) {
                throw new IOException("checksum mismatch: Azul's catalog gives " + sha256 + ", the download is " + actual);
            }
        }
    }

    /**
     * Remove the runtime waiting for the next start.
     *
     * @return false when none is waiting
     * @throws IllegalStateException while a download runs
     */
    public static boolean cancel() throws IOException {
        return cancel(environment().home(), CURRENT);
    }

    public static boolean cancel(Path home, Download progress) throws IOException {
        STAGING.lock();
        try {
            if (progress.active()) {
                throw new IllegalStateException("A newer Java is still downloading; cancel it once the download has finished.");
            }
            if (staged(home) == null) return false;
            deleteTree(home.resolve(STAGED_DIR));
        } finally {
            STAGING.unlock();
        }
        EventLogger.info(CATEGORY, "The Java runtime waiting for the next start was cancelled");
        return true;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JRE", e);
        }
    }
}
