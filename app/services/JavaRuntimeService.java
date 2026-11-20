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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Keeps the Java runtime under JClaw on the newest patch of its major version (Settings → Maintenance →
 * Java runtime), from the vendor it runs: Azul's Zulu, which the installers put in, or Eclipse Adoptium's
 * Temurin, which the panel switches it to and back. Only the runtime the installer put beside the install,
 * {@code <home>/jre}, is ever replaced, and never by this JVM: it downloads and verifies the release into
 * {@code <home>/jre.staged/}, and jclaw.sh's {@code apply_staged_jre} unpacks it and swaps the folders at the
 * next start, once this JVM has stopped. A bundle install on a Java it did not install can switch to one of
 * its own the same way: the next start installs the download as the first {@code <home>/jre}. A container or
 * a source checkout only hears that a newer patch exists.
 */
public final class JavaRuntimeService {

    private static final String CATEGORY = "java-runtime";

    /** The major JClaw installs and is tested on: install.sh's MIN_JAVA, install.ps1's $MinJava, jclaw.sh's JRE_MAJOR. */
    public static final int MANAGED_MAJOR = 25;

    /** The catalog install.sh and install.ps1 query; {@link #catalogUrl} asks it the same question. */
    static final String AZUL_API = "https://api.azul.com/metadata/v1/zulu/packages/";
    static final String ADOPTIUM_API = "https://api.adoptium.net/v3/assets/latest/";
    /**
     * Azul's notes for its newest release, always this one address (JF-537): the catalog entry links none, and
     * no per-build page can be derived from one. The panel and the daily check offer only the catalog's newest
     * build, the release this page shows, but the two update apart and a staged build can be overtaken, so the
     * link is worded as Azul's latest notes, never as the offered build's.
     */
    public static final String RELEASE_NOTES = "https://docs.azul.com/core/release-notes";
    /** Adoptium's notes page, which shows one release's notes given its {@code release_name} as {@code version}. */
    static final String ADOPTIUM_NOTES = "https://adoptium.net/temurin/release-notes/";
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
    // A network that drops a catalog's packets costs TIMEOUT_SECONDS a call, so a failed check holds this long
    // rather than stalling every load of the panel.
    private static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    // The shapes apply_staged_jre in jclaw.sh accepts, so a manifest written here is one it reads.
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)*+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ARCHIVE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.(tar\\.gz|zip)");
    /** Zulu's {@code java.vendor.version} and {@code IMPLEMENTOR_VERSION}: {@code Zulu25.38+105-CA} is build 25.38.105. */
    private static final Pattern ZULU = Pattern.compile("Zulu(\\d+(?:\\.\\d+)*+)\\+(\\d+(?:\\.\\d+)*+)");
    /** Adoptium's {@code release_name}, {@code jdk-25.0.5.1+2}, the one value its notes page takes. */
    private static final Pattern TEMURIN_RELEASE = Pattern.compile("jdk-\\d+(?:\\.\\d+)*+\\+\\d+");

    /**
     * The two builds of OpenJDK JClaw installs at {@code <home>/jre}. Each has its own catalog, download hosts and
     * build numbers; across the two only the OpenJDK version compares.
     */
    public enum Vendor {
        ZULU("Zulu", "Azul", "azul", "cdn.azul.com", "/", List.of()),
        // Adoptium's binaries are GitHub release assets: each github.com link redirects there (measured 2026-11-20).
        TEMURIN("Temurin", "Adoptium", "adoptium", "github.com", "/adoptium/",
                List.of("release-assets.githubusercontent.com"));

        /** The name a staged manifest, jclaw.sh and the panel use. */
        public final String label;
        /** Whose catalog and download it is. */
        public final String owner;
        private final String implementor;
        private final String linkHost;
        private final String linkPath;
        private final List<String> assetHosts;

        Vendor(String label, String owner, String implementor, String linkHost, String linkPath, List<String> assetHosts) {
            this.label = label;
            this.owner = owner;
            this.implementor = implementor;
            this.linkHost = linkHost;
            this.linkPath = linkPath;
            this.assetHosts = assetHosts;
        }

        public Vendor other() {
            return this == ZULU ? TEMURIN : ZULU;
        }

        /** The vendor a {@code java.vendor} or a release file's {@code IMPLEMENTOR} names, or null for any other. */
        public static @Nullable Vendor of(@Nullable String implementor) {
            if (implementor == null) return null;
            var name = implementor.toLowerCase(Locale.ROOT);
            for (var v : values()) {
                if (name.contains(v.implementor)) return v;
            }
            return null;
        }

        /** The vendor whose {@link #label} this is, case aside, or null. */
        public static @Nullable Vendor named(@Nullable String label) {
            for (var v : values()) {
                if (v.label.equalsIgnoreCase(label == null ? "" : label.strip())) return v;
            }
            return null;
        }

        /** HTTPS to where the catalog's download links point. */
        boolean allowsLink(HttpUrl url) {
            return url.isHttps() && linkHost.equals(url.host()) && url.encodedPath().startsWith(linkPath);
        }

        /** Where a download may land once its redirects are followed: a link {@link #allowsLink} takes, or an asset host. */
        boolean allowsLanding(HttpUrl url) {
            return allowsLink(url) || (url.isHttps() && assetHosts.contains(url.host()));
        }

        String linkPlace() {
            return linkHost + linkPath.substring(0, linkPath.length() - 1);
        }

        String landingPlaces() {
            var places = new ArrayList<String>();
            places.add(linkPlace());
            places.addAll(assetHosts);
            return String.join(" or ", places);
        }
    }

    public enum Kind {
        /** The runtime the installer put at {@code <home>/jre}, the only one this class replaces. */
        BUILT_IN("built-in"),
        /** A Java the operator installed, or a source checkout's: its vendor updates it, until a bundle install {@link JavaRuntimeService#switchable switches}. */
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
     * @param vendorVersion {@code java.vendor.version}, or null when the JVM sets none
     * @param osName      {@code os.name}
     * @param osArch      {@code os.arch}
     * @param container   true inside a container
     * @param wslWindowsDrive true when JClaw runs in WSL from a Windows drive, as install.ps1's WSL route
     *                        installs it
     */
    public record Environment(Path app, Path javaHome, String javaVersion, @Nullable String vendor,
                              @Nullable String vendorVersion, String osName, String osArch, boolean container,
                              boolean wslWindowsDrive) {

        public Environment(Path app, Path javaHome, String javaVersion, @Nullable String vendor,
                           String osName, String osArch, boolean container) {
            this(app, javaHome, javaVersion, vendor, null, osName, osArch, container, false);
        }

        public Environment(Path app, Path javaHome, String javaVersion, @Nullable String vendor,
                           String osName, String osArch, boolean container, boolean wslWindowsDrive) {
            this(app, javaHome, javaVersion, vendor, null, osName, osArch, container, wslWindowsDrive);
        }

        public Environment(Path app, Path javaHome, String javaVersion, @Nullable String vendor,
                           @Nullable String vendorVersion, String osName, String osArch, boolean container) {
            this(app, javaHome, javaVersion, vendor, vendorVersion, osName, osArch, container, false);
        }

        /** The directory that holds the install and its {@code jre/}. */
        public Path home() {
            var abs = app.toAbsolutePath().normalize();
            var parent = abs.getParent();
            return parent == null ? abs : parent;
        }

        public boolean windows() {
            return osName.toLowerCase(Locale.ROOT).contains("windows");
        }

        /**
         * The running Java's Zulu build, from {@code java.vendor.version} or, when the JVM sets none, the
         * runtime's release file; null for another vendor's Java.
         */
        public @Nullable String build() {
            return zuluBuild(vendorVersion != null ? vendorVersion : releaseField(javaHome, "IMPLEMENTOR_VERSION"));
        }

        /**
         * The running Java's vendor, from {@code java.vendor} or, when the JVM sets none, the runtime's release file;
         * null for a vendor JClaw never installs. Read from the Java itself, so it never disagrees with what runs.
         */
        public @Nullable Vendor distribution() {
            return Vendor.of(vendor != null ? vendor : releaseField(javaHome, "IMPLEMENTOR"));
        }
    }

    /**
     * A JRE in a vendor's catalog; {@code sha256} is null when the catalog gave none, which the download refuses.
     *
     * @param releaseType the catalog's {@code release_type} ({@code PSU}, {@code CPU} or {@code LU}), or null
     *                    when the entry gives none, as Adoptium's never do
     * @param build       Azul's {@code distro_version}, its own number for the build, or null when it gives none;
     *                    a Temurin release has none, so it orders by Java version alone
     * @param vendor      whose catalog listed it, and so where it may download from
     * @param notes       this release's own notes page, or null where only the vendor's latest notes can be linked
     */
    public record Release(String version, String url, @Nullable String sha256, String archive,
                          @Nullable String releaseType, @Nullable String build, Vendor vendor,
                          @Nullable String notes) {

        public Release(String version, String url, @Nullable String sha256, String archive) {
            this(version, url, sha256, archive, null, null);
        }

        public Release(String version, String url, @Nullable String sha256, String archive,
                       @Nullable String releaseType) {
            this(version, url, sha256, archive, releaseType, null);
        }

        public Release(String version, String url, @Nullable String sha256, String archive,
                       @Nullable String releaseType, @Nullable String build) {
            this(version, url, sha256, archive, releaseType, build, Vendor.ZULU, null);
        }

        /** The notes page the panel and the notification link beside this release. */
        public String notesUrl() {
            if (notes != null) return notes;
            return vendor == Vendor.ZULU ? RELEASE_NOTES : ADOPTIUM_NOTES;
        }

        /** The link's words: a page of this release's own notes says so, and a vendor's latest notes say that. */
        public String notesLabel() {
            return notes != null ? "Release notes for " + vendor.label + " " + version
                    : vendor.owner + "'s latest release notes";
        }
    }

    /**
     * A finished, verified download in {@code jre.staged/}, waiting for the next start.
     *
     * @param build  the Zulu build jclaw.sh checks the unpacked Java reports, or null when the manifest names none
     * @param vendor the vendor jclaw.sh checks the unpacked Java reports, or null when the manifest, from a release
     *               before JF-538, names none
     */
    public record Staged(String version, String sha256, String archive, @Nullable String build,
                         @Nullable Vendor vendor) {

        public Staged(String version, String sha256, String archive) {
            this(version, sha256, archive, null, null);
        }

        public Staged(String version, String sha256, String archive, @Nullable String build) {
            this(version, sha256, archive, build, null);
        }
    }

    /**
     * One catalog's answer for this system: its newest JRE or, with none, why: the catalog could not be reached, or
     * it lists no JRE for this OS, C library and architecture.
     */
    public record Answer(@Nullable Release release, @Nullable String why) {}

    /**
     * One query of a catalog past its cache, for the daily check: the answer, the last good one when the catalog
     * could not be reached, and then why it could not, or {@code failure} null when it answered.
     */
    public record Checked(Answer answer, @Nullable String failure) {}

    /**
     * A download JClaw turned down: its message for the log, and {@link #inWords()} for the operator's notice.
     */
    public static final class RefusedException extends IOException {
        private final String inWords;

        RefusedException(String message, String inWords) {
            super(message);
            this.inWords = inWords;
        }

        /** Why, in plain words that follow "could not be downloaded:". */
        public String inWords() {
            return inWords;
        }
    }

    /**
     * A switch to {@code vendor}'s Java {@code version} that the panel offers, or with a {@code refusal}, does not,
     * saying why. Each is judged by {@link #stageable}, so the panel never offers a switch the POST refuses.
     */
    public record Switch(Vendor vendor, @Nullable String version, @Nullable String refusal) {}

    /** A catalog that answers but lists no JRE for this system: an answer, so it is cached rather than retried. */
    static final class NoRuntimeException extends IOException {
        NoRuntimeException(String message) {
            super(message);
        }
    }

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
    private static volatile @Nullable Function<Vendor, Answer> catalogOverride;
    @SuppressWarnings("java:S3077")
    private static volatile @Nullable Stager stagerOverride;

    /** Test seam: where this JVM runs, in place of the real install and runtime; null clears it. */
    public static void setEnvironmentForTest(@Nullable Environment override) {
        environmentOverride = override;
    }

    /**
     * Test seam: each vendor's catalog answer, whichever major it is asked for, in place of a call to Azul or
     * Adoptium; null clears it.
     */
    public static void setCatalogForTest(@Nullable Function<Vendor, Answer> override) {
        catalogOverride = override;
    }

    /** Test seam: {@code override} as its own vendor's catalog answer, the other vendor's catalog unreachable. */
    public static void setLatestForTest(@Nullable Release override) {
        catalogOverride = override == null ? null
                : v -> v == override.vendor() ? new Answer(override, null) : unreachable(v);
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
        @SuppressWarnings("java:S3077") // published whole, never mutated
        private volatile @Nullable Release target;

        /** False when a download is already running. */
        public boolean begin() {
            return begin(null);
        }

        /** As {@link #begin()}, recording {@code release} as what downloads, which the panel names while it runs. */
        public boolean begin(@Nullable Release release) {
            if (!active.compareAndSet(false, true)) return false;
            percent = 0;
            error = null;
            target = release;
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
            target = null;
            active.set(false);
        }

        public boolean active() { return active.get(); }
        public @Nullable Integer currentPercent() { return percent; }
        public @Nullable String lastError() { return error; }
        /** What the running download stages, or null when none runs or it was begun without one. */
        public @Nullable Release target() { return target; }
    }

    public static final Download CURRENT = new Download();

    /**
     * One vendor's newest JRE of one major, cached for {@link #CHECK_TTL}, and a check that could not reach the
     * catalog for {@link #RETRY_AFTER}. The app keeps one per vendor and major; tests build their own.
     */
    public static final class Lookup {
        private record Cached(Answer answer, Instant at) {}

        private final Vendor vendor;
        private volatile @Nullable Cached cached;
        private volatile @Nullable Instant failedAt;

        public Lookup() {
            this(Vendor.ZULU);
        }

        public Lookup(Vendor vendor) {
            this.vendor = vendor;
        }

        /**
         * @param refresh bypass the cache for an operator's re-check
         * @return the newest patch, the last good one when the catalog cannot be reached, or null when
         *         it never could
         */
        public @Nullable Release latest(boolean refresh, Catalog catalog) {
            return answer(refresh, catalog).release();
        }

        /** As {@link #latest}, saying why when there is no release. */
        public Answer answer(boolean refresh, Catalog catalog) {
            var c = cached;
            var now = AppClock.now();
            if (!refresh && c != null && Duration.between(c.at(), now).compareTo(CHECK_TTL) < 0) {
                return c.answer();
            }
            var failed = failedAt;
            if (!refresh && failed != null && Duration.between(failed, now).compareTo(RETRY_AFTER) < 0) {
                return c != null ? c.answer() : unreachable(vendor);
            }
            return check(catalog).answer();
        }

        /** As {@link #answer} with {@code refresh}, saying why when the catalog could not be reached. */
        public Checked check(Catalog catalog) {
            var c = cached;
            Answer answer;
            try {
                answer = new Answer(catalog.fetch(), null);
            } catch (NoRuntimeException e) {
                answer = new Answer(null, e.getMessage());
            } catch (IOException | RuntimeException e) {
                failedAt = AppClock.now();
                EventLogger.warn(CATEGORY, "Could not check " + vendor.owner + "'s catalog for a newer Java: "
                        + e.getMessage());
                return new Checked(c != null ? c.answer() : unreachable(vendor),
                        Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            }
            failedAt = null;
            cached = new Cached(answer, AppClock.now());
            return new Checked(answer, null);
        }
    }

    // Keyed by vendor and major: a host Java of another major asks for MANAGED_MAJOR apart from its own.
    private static final ConcurrentHashMap<String, Lookup> LOOKUPS = new ConcurrentHashMap<>();

    // Held across the checks and the change, so a cancel never deletes a staging a new download is writing.
    private static final ReentrantLock STAGING = new ReentrantLock();

    private JavaRuntimeService() {}

    public static Environment environment() {
        var test = environmentOverride;
        if (test != null) return test;
        var app = Play.applicationPath.toPath();
        var osName = System.getProperty("os.name", "");
        return new Environment(app, Path.of(System.getProperty("java.home", "")),
                System.getProperty("java.version", ""), System.getProperty("java.vendor"),
                System.getProperty("java.vendor.version"),
                osName, System.getProperty("os.arch", ""),
                UpgradeService.isContainer(),
                osName.toLowerCase(Locale.ROOT).contains("linux") && wslWindowsDrive(kernelRelease(), fileStoreType(app)));
    }

    /**
     * True for a kernel WSL runs ({@code 5.15.167.4-microsoft-standard-WSL2}, WSL 1's {@code 4.4.0-19041-Microsoft})
     * and a file system of a Windows drive there: DrvFs, which WSL 2 serves over 9p.
     */
    public static boolean wslWindowsDrive(@Nullable String kernelRelease, @Nullable String fileStoreType) {
        return kernelRelease != null && kernelRelease.toLowerCase(Locale.ROOT).contains("microsoft")
                && ("drvfs".equals(fileStoreType) || "9p".equals(fileStoreType));
    }

    private static @Nullable String kernelRelease() {
        try {
            return Files.readString(Path.of("/proc/sys/kernel/osrelease"), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static @Nullable String fileStoreType(Path app) {
        try {
            return Files.getFileStore(app).type();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** Compares real paths, so a link to the install or a {@code ..} in {@code java.home} still counts. */
    public static Kind kind(Environment env) {
        if (env.container()) return Kind.CONTAINER;
        if (sourceCheckout(env.app())) {
            return Kind.EXTERNAL;
        }
        try {
            var jre = env.home().resolve(JRE_DIR).toRealPath();
            return env.javaHome().toRealPath().startsWith(jre) ? Kind.BUILT_IN : Kind.EXTERNAL;
        } catch (IOException _) {
            return Kind.EXTERNAL;
        }
    }

    /** A clone runs on its developer's Java, which JClaw never replaces. */
    static boolean sourceCheckout(Path app) {
        return Files.isDirectory(app.resolve("app")) || Files.exists(app.resolve(".git"));
    }

    /**
     * True on a bundle install that runs on a Java it did not install, outside install.ps1's WSL route. A
     * download there is the switch to a Java of JClaw's own, {@link #managed}, which the next start installs
     * as {@code <home>/jre}.
     */
    public static boolean switchable(Environment env) {
        return kind(env) == Kind.EXTERNAL && !sourceCheckout(env.app()) && !env.wslWindowsDrive();
    }

    /**
     * True when switching to {@code managed} would take a host Java back to an older patch of its own major, by
     * {@link #compareBuilds}, which {@link #stageable} refuses and the panel therefore never offers. A host Java of
     * another major gives way to the one JClaw is tested on.
     */
    public static boolean undoesFixes(Environment env, Release managed) {
        return major(managed.version()) == major(env.javaVersion())
                && compareBuilds(env.distribution(), env.javaVersion(), env.build(),
                        managed.vendor(), managed.version(), managed.build()) > 0;
    }

    /**
     * The vendor whose catalog a patch check asks: the running Java's, or Azul's, the installers' vendor, for a Java
     * of a vendor JClaw never installs.
     */
    public static Vendor running(Environment env) {
        var vendor = env.distribution();
        return vendor != null ? vendor : Vendor.ZULU;
    }

    // ── The catalog ──────────────────────────────────────────────────────────

    /** The newest patch of the running major from the running vendor, from the test seam or the cached check. */
    public static @Nullable Release latest(boolean refresh) {
        var env = environment();
        return answer(env, running(env), major(env.javaVersion()), refresh).release();
    }

    /**
     * {@code vendor}'s newest JRE of {@code major} for {@code env}'s system, from the test seam or that vendor's
     * cached catalog check.
     *
     * @param refresh bypass the cache for an operator's re-check
     */
    public static Answer answer(Environment env, Vendor vendor, int major, boolean refresh) {
        var test = catalogOverride;
        if (test != null) return test.apply(vendor);
        return lookup(vendor, major).answer(refresh, () -> fetchLatest(env, vendor, major));
    }

    /** The daily check's query: {@link #latest} past the cache, saying why when the catalog could not be reached. */
    public static Checked check(Environment env) {
        var vendor = running(env);
        var test = catalogOverride;
        if (test != null) return new Checked(test.apply(vendor), null);
        int major = major(env.javaVersion());
        return lookup(vendor, major).check(() -> fetchLatest(env, vendor, major));
    }

    private static Lookup lookup(Vendor vendor, int major) {
        return LOOKUPS.computeIfAbsent(vendor.name() + ":" + major, _ -> new Lookup(vendor));
    }

    /**
     * The Java a switch from a host Java to {@code vendor} installs: its newest JRE of {@link #MANAGED_MAJOR},
     * whatever major runs now.
     */
    public static Answer managed(Environment env, Vendor vendor, boolean refresh) {
        return answer(env, vendor, MANAGED_MAJOR, refresh);
    }

    static Answer unreachable(Vendor vendor) {
        return new Answer(null, unreachableWhy(vendor));
    }

    private static String unreachableWhy(Vendor vendor) {
        return "Couldn't check " + vendor.owner + "'s catalog for a newer Java.";
    }

    /** One query of the running vendor's catalog for {@code env}'s major, OS, C library and architecture, bypassing the cache. */
    public static Release fetchLatest(Environment env) throws IOException {
        return fetchLatest(env, running(env), major(env.javaVersion()));
    }

    /**
     * One query of {@code vendor}'s catalog for {@code major} on {@code env}'s OS, C library and architecture,
     * bypassing the cache.
     *
     * @throws NoRuntimeException when the vendor builds or lists no such JRE
     */
    public static Release fetchLatest(Environment env, Vendor vendor, int major) throws IOException {
        var musl = musl(env.javaHome());
        var os = vendor == Vendor.ZULU ? azulOs(env.osName(), musl) : adoptiumOs(env.osName(), musl);
        // Adoptium names the two architectures as Azul does.
        var arch = azulArch(env.osArch());
        if (os == null || arch == null) {
            throw new NoRuntimeException(vendor.owner + " builds no Java for " + env.osName() + " on " + env.osArch() + ".");
        }
        var url = vendor == Vendor.ZULU ? catalogUrl(major, os, arch) : adoptiumUrl(major, os, arch);
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " from " + vendor.owner + "'s catalog");
            var body = resp.body().string();
            var release = vendor == Vendor.ZULU ? pickLatest(body, major, musl) : pickTemurin(body, major, os, arch);
            if (release == null) {
                throw new NoRuntimeException(vendor.owner + "'s catalog lists no Java " + major + " runtime for " + os
                        + "/" + arch + ".");
            }
            return release;
        }
    }

    /**
     * Adoptium's newest Temurin JRE of {@code major} for one OS and architecture: one entry, or none where it
     * builds no such JRE.
     */
    public static String adoptiumUrl(int major, String os, String arch) {
        return ADOPTIUM_API + major + "/hotspot?architecture=" + arch + "&image_type=jre&os=" + os + "&vendor=eclipse";
    }

    /** Adoptium's OS names; it lists musl builds as Alpine's. */
    public static @Nullable String adoptiumOs(String osName, boolean musl) {
        var name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("windows")) return "windows";
        if (name.contains("mac")) return "mac";
        if (name.contains("linux")) return musl ? "alpine-linux" : "linux";
        return null;
    }

    /** install.sh's query; Windows asks for the zip, as install.ps1 does. */
    public static String catalogUrl(int major, String os, String arch) {
        var archive = "windows".equals(os) ? "zip" : "tar.gz";
        return AZUL_API + "?java_version=" + major + "&os=" + os + "&arch=" + arch + "&archive_type=" + archive
                + "&java_package_type=jre&release_status=ga&include_fields=sha256_hash,release_type&page_size=20";
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

    /** {@code key}'s value in the runtime's release file, unquoted, or null when it has none. */
    static @Nullable String releaseField(Path javaHome, String key) {
        try {
            for (var line : Files.readAllLines(javaHome.resolve("release"))) {
                var l = line.strip();
                if (l.startsWith(key + "=")) return l.substring(key.length() + 1).replace("\"", "");
            }
        } catch (IOException _) {
            return null;
        }
        return null;
    }

    /** The build a Zulu version string names, 25.38.105 for {@code Zulu25.38+105-CA}; null for any other vendor's. */
    private static @Nullable String zuluBuild(@Nullable String vendorVersion) {
        if (vendorVersion == null) return null;
        var m = ZULU.matcher(vendorVersion.strip());
        return m.lookingAt() ? m.group(1) + "." + m.group(2) : null;
    }

    /**
     * The newest plain JRE of {@code major} in a catalog payload. JavaFX (fx) and CRaC builds are skipped,
     * and musl builds unless this runtime is one, as install.sh skips them. The newest rather than the
     * first: the catalog lists the builds it flags latest ahead of newer ones it does not.
     */
    public static @Nullable Release pickLatest(String payload, int major, boolean musl) {
        Release best = null;
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
            var build = distro(joined(o, "distro_version"));
            var archive = name != null ? name : url.substring(url.lastIndexOf('/') + 1);
            var candidate = new Release(version, url, sha == null ? null : sha.toLowerCase(Locale.ROOT), archive,
                    str(o, "release_type"), build);
            if (best == null || compareBuilds(Vendor.ZULU, version, build, Vendor.ZULU, best.version(), best.build()) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The newest Temurin JRE of {@code major} for {@code os} and {@code arch} in an Adoptium payload. Its version is
     * the OpenJDK version the Java reports (25.0.5.1 for {@code jdk-25.0.5.1+2}), and it carries no build: only Zulu's
     * build number orders two releases of one Java version.
     */
    public static @Nullable Release pickTemurin(String payload, int major, String os, String arch) {
        Release best = null;
        for (JsonElement el : JsonParser.parseString(payload).getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            var binary = obj(o, "binary");
            var pkg = binary == null ? null : obj(binary, "package");
            var v = obj(o, "version");
            if (binary == null || pkg == null || v == null || !"jre".equals(str(binary, "image_type"))
                    || !"hotspot".equals(str(binary, "jvm_impl")) || !os.equals(str(binary, "os"))
                    || !arch.equals(str(binary, "architecture"))) {
                continue;
            }
            var version = openjdkVersion(v);
            var url = str(pkg, "link");
            var name = str(pkg, "name");
            if (version == null || url == null || name == null || major(version) != major) continue;
            var sha = str(pkg, "checksum");
            var candidate = new Release(version, url, sha == null ? null : sha.toLowerCase(Locale.ROOT), name, null,
                    null, Vendor.TEMURIN, temurinNotes(str(o, "release_name")));
            if (best == null || compareVersions(version, best.version()) > 0) best = candidate;
        }
        return best;
    }

    // major.minor.security, then patch where Adoptium gives one: the version java -version reports.
    private static @Nullable String openjdkVersion(JsonObject v) {
        var major = num(v, "major");
        var minor = num(v, "minor");
        var security = num(v, "security");
        if (major == null || minor == null || security == null) return null;
        var patch = num(v, "patch");
        return major + "." + minor + "." + security + (patch == null || patch == 0 ? "" : "." + patch);
    }

    private static @Nullable String temurinNotes(@Nullable String releaseName) {
        if (releaseName == null || !TEMURIN_RELEASE.matcher(releaseName).matches()) return null;
        return HttpUrl.get(ADOPTIUM_NOTES).newBuilder().addQueryParameter("version", releaseName).build().toString();
    }

    private static @Nullable JsonObject obj(JsonObject o, String key) {
        var v = o.get(key);
        return v == null || !v.isJsonObject() ? null : v.getAsJsonObject();
    }

    private static @Nullable Integer num(JsonObject o, String key) {
        var v = o.get(key);
        return v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() ? null : v.getAsInt();
    }

    /**
     * A catalog {@code release_type} in plain words, in Azul's own terms: a PSU carries the CPU's security fixes
     * plus the quarter's other bug fixes, a CPU only the security fixes. Null for none, and for one Azul has not
     * defined (LU) or a code this build does not know, so the panel and the notification show the link alone.
     */
    public static @Nullable String releaseTypeInWords(@Nullable String releaseType) {
        if (releaseType == null) return null;
        return switch (releaseType.strip().toUpperCase(Locale.ROOT)) {
            case "PSU" -> "a security and bug-fix release";
            case "CPU" -> "a security-fix release";
            default -> null;
        };
    }

    // distro_version's fourth part, 0 in every catalog entry (2026-11), is one Zulu's own version string leaves out:
    // [25,38,107,0] runs as Zulu25.38+107-CA.
    private static @Nullable String distro(@Nullable String joined) {
        if (joined == null) return null;
        var p = joined.split("\\.");
        return p.length == 4 && "0".equals(p[3]) ? joined.substring(0, joined.lastIndexOf('.')) : joined;
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

    /**
     * Orders two builds by Java version and, at the same Java version from one vendor, by that vendor's build: Azul
     * ships some fixes as a respin that keeps the Java version (25.38.107 over 25.38.105, both 25.0.5). Two vendors'
     * build numbers are unrelated, so across vendors, or with either build unknown, only the Java versions compare.
     */
    public static int compareBuilds(@Nullable Vendor vendor, String version, @Nullable String build,
                                    @Nullable Vendor otherVendor, String otherVersion, @Nullable String otherBuild) {
        int order = compareVersions(version, otherVersion);
        if (order != 0 || vendor == null || vendor != otherVendor || build == null || otherBuild == null) return order;
        return compareVersions(build, otherBuild);
    }

    /**
     * True when {@code candidate} is a later build than the Java {@code env} runs, by {@link #compareBuilds}:
     * {@link #stageable}'s test of a patch to the built-in Java, which the panel and the daily check ask too, so
     * none offers what another would not.
     */
    public static boolean isNewer(@Nullable Release candidate, Environment env) {
        return candidate != null && compareBuilds(candidate.vendor(), candidate.version(), candidate.build(),
                env.distribution(), env.javaVersion(), env.build()) > 0;
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
        var build = fields.get("build");
        var vendorLine = fields.get("vendor");
        var vendor = Vendor.named(vendorLine);
        if (version == null || sha == null || archive == null || !VERSION.matcher(version).matches()
                || !SHA256.matcher(sha).matches() || !ARCHIVE.matcher(archive).matches()
                || (build != null && !VERSION.matcher(build).matches())
                || (vendorLine != null && (vendor == null || !vendor.label.equals(vendorLine)))
                || !Files.isRegularFile(dir.resolve(archive))) {
            return null;
        }
        return new Staged(version, sha, archive, build, vendor);
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
     * As {@link #stageable(Environment, Answer, Staged, boolean)}, a null {@code latest} reading as the catalog
     * a request with no vendor asks being unreachable.
     */
    public static Release stageable(Environment env, @Nullable Release latest, @Nullable Staged staged,
                                    boolean downloading) {
        return stageable(env, answerOf(env, latest), staged, downloading);
    }

    /**
     * The release a download would stage, or why there is none. Each refusal is a row of the panel's
     * matrix, so the POST never accepts what the GET would not offer.
     *
     * @param offer the catalog's answer for what is asked: on the built-in Java, its vendor's newest patch of
     *              its major, or the other vendor's newest of that major for a switch; where {@link #switchable},
     *              the {@link #managed} Java the switch installs
     * @throws IllegalStateException naming why nothing can be staged
     */
    public static Release stageable(Environment env, Answer offer, @Nullable Staged staged, boolean downloading) {
        var kind = kind(env);
        switch (kind) {
            case CONTAINER -> throw new IllegalStateException(
                    "The runtime comes with the Docker image; pull a newer image to update Java.");
            case EXTERNAL -> {
                if (sourceCheckout(env.app())) {
                    throw new IllegalStateException("JClaw runs on a Java it did not install ("
                            + env.javaHome() + "), so that Java's vendor provides its updates.");
                }
                // jclaw.sh puts any Java at <home>/jre first, and a Git Bash start would get this Linux one.
                if (env.wslWindowsDrive()) {
                    throw new IllegalStateException("JClaw runs in WSL from a Windows folder, where a start from Git "
                            + "Bash would also pick up a Java at " + env.home().resolve(JRE_DIR) + ", so it stays on "
                            + "the distro's Java; install JClaw with install.sh inside the distro for a Java it keeps "
                            + "patched.");
                }
            }
            case BUILT_IN -> { }
        }
        if (downloading) throw new IllegalStateException("A newer Java is already downloading.");
        if (staged != null) {
            throw new IllegalStateException("Java " + staged.version() + " will be installed at the next restart.");
        }
        var latest = offer.release();
        if (latest == null) {
            var why = offer.why();
            throw new IllegalStateException(why != null ? why : unreachableWhy(Vendor.ZULU));
        }
        var running = running(env);
        if (kind == Kind.BUILT_IN && latest.vendor() == running && !isNewer(latest, env)) {
            throw new IllegalStateException("Java " + env.javaVersion() + " is up to date.");
        }
        // A switch to the other vendor at the same version is staged; an older one would take a fix away.
        if (kind == Kind.BUILT_IN && latest.vendor() != running && compareVersions(latest.version(), env.javaVersion()) < 0) {
            throw new IllegalStateException(latest.vendor().label + " " + latest.version() + " is older than the "
                    + running.label + " " + env.javaVersion() + " JClaw runs on, so switching to it would undo fixes.");
        }
        if (kind == Kind.EXTERNAL && undoesFixes(env, latest)) {
            // At one Java version only the builds differ, so the versions alone would name one number twice.
            throw new IllegalStateException(compareVersions(latest.version(), env.javaVersion()) == 0
                    ? latest.vendor().label + " " + latest.build() + " is older than the " + running.label + " "
                            + env.build() + " JClaw runs on, so switching to it would undo fixes."
                    : latest.vendor().label + " " + latest.version() + " is older than the Java "
                            + env.javaVersion() + " JClaw runs on, so switching to it would undo fixes.");
        }
        if (!Files.isWritable(env.home())) {
            throw new IllegalStateException(env.home() + " is not writable, so a new Java cannot be staged beside "
                    + env.home().resolve(JRE_DIR) + ".");
        }
        return latest;
    }

    private static Vendor defaultTarget(Environment env) {
        return kind(env) == Kind.BUILT_IN ? running(env) : Vendor.ZULU;
    }

    private static Answer answerOf(Environment env, @Nullable Release latest) {
        return latest != null ? new Answer(latest, null) : unreachable(defaultTarget(env));
    }

    /**
     * What a request for {@code vendor} would stage, from that vendor's catalog: on the built-in Java, the newest of
     * its major, a patch from its own vendor or a switch to the other; on a bundle install on a host Java, the
     * {@link #managed} Java of {@code vendor}.
     *
     * @param vendor null for the built-in Java's own vendor, or for Azul on a host Java
     */
    public static Answer offer(Environment env, @Nullable Vendor vendor, boolean refresh) {
        var target = vendor != null ? vendor : defaultTarget(env);
        return switchable(env) ? managed(env, target, refresh) : answer(env, target, major(env.javaVersion()), refresh);
    }

    /**
     * The switches this install offers, each judged by {@link #stageable} as its POST would be, a download already
     * running or staged aside, which the panel shows apart: on the built-in Java, to the other vendor; on a bundle
     * install on a host Java, to either; anywhere else none.
     *
     * @param refresh bypass the cache for an operator's re-check, as {@link #latest} has just done for its own
     *                catalog, which is therefore not asked again
     */
    public static List<Switch> switches(Environment env, boolean refresh) {
        boolean builtIn = kind(env) == Kind.BUILT_IN;
        if (!builtIn && !switchable(env)) return List.of();
        var targets = builtIn ? List.of(running(env).other()) : List.of(Vendor.ZULU, Vendor.TEMURIN);
        int major = builtIn ? major(env.javaVersion()) : MANAGED_MAJOR;
        var out = new ArrayList<Switch>();
        for (var vendor : targets) {
            boolean askedAlready = vendor == running(env) && major == major(env.javaVersion());
            var answer = answer(env, vendor, major, refresh && !askedAlready);
            String refusal = null;
            try {
                stageable(env, answer, null, false);
            } catch (IllegalStateException e) {
                refusal = e.getMessage();
            }
            var release = answer.release();
            out.add(new Switch(vendor, release == null ? null : release.version(), refusal));
        }
        return out;
    }

    /**
     * Start downloading what a request for {@code vendor} stages, {@link #offer}, for the next start to install,
     * returning as soon as it runs.
     *
     * @throws IllegalStateException when {@link #stageable} refuses, checked before anything is written
     */
    public static Release requestStage(@Nullable Vendor vendor) {
        var stager = stagerOverride;
        if (stager == null) {
            stager = Play.runningInTestMode() ? JavaRuntimeService::refuseInTestMode : JavaRuntimeService::download;
        }
        var env = environment();
        return requestStage(env, offer(env, vendor, false), CURRENT, stager);
    }

    // A request that outlives its test's seams must not delete or download anything.
    private static void refuseInTestMode(Release release, Path home, Download progress) throws IOException {
        throw new IOException("in test mode a Java runtime downloads only through setStagerForTest");
    }

    public static Release requestStage(Environment env, @Nullable Release latest, Download progress, Stager stager) {
        return requestStage(env, answerOf(env, latest), progress, stager);
    }

    public static Release requestStage(Environment env, Answer offer, Download progress, Stager stager) {
        var release = beginStage(env, offer, progress);
        var home = env.home();
        Thread.ofVirtual().name("java-runtime-download").start(() -> {
            try {
                runStage(release, home, progress, stager, true);
            } catch (IOException | RuntimeException _) {
                // runStage recorded it for the panel and logged it
            }
        });
        return release;
    }

    /**
     * {@link #requestStage} in the caller's thread, for the daily check: the same refusals, the same lock and
     * the same progress the panel follows, so the check and the Settings button never stage at once. A failure is
     * thrown and not kept in {@code progress}, whose {@link Download#lastError} the panel shows as its own
     * download's: the daily check records it apart.
     *
     * @throws IllegalStateException when {@link #stageable} refuses, checked before anything is written
     * @throws IOException           when the download or its check fails, which leaves nothing staged
     */
    public static Release stageNow(Environment env, @Nullable Release latest, Download progress, Stager stager)
            throws IOException {
        var release = beginStage(env, answerOf(env, latest), progress);
        runStage(release, env.home(), progress, stager, false);
        return release;
    }

    private static Release beginStage(Environment env, Answer offer, Download progress) {
        Release release;
        STAGING.lock();
        try {
            release = stageable(env, offer, staged(env.home()), progress.active());
            if (!progress.begin(release)) throw new IllegalStateException("A newer Java is already downloading.");
        } finally {
            STAGING.unlock();
        }
        EventLogger.info(CATEGORY, "Downloading " + release.vendor().label + " " + release.version()
                + " for the next start to install", release.url());
        return release;
    }

    private static void runStage(Release release, Path home, Download progress, Stager stager, boolean keepFailure)
            throws IOException {
        try {
            stager.stage(release, home, progress);
            EventLogger.info(CATEGORY, "Java " + release.version() + " is staged; the next start installs it");
        } catch (IOException | RuntimeException e) {
            if (keepFailure) progress.failed("Java " + release.version() + " could not be downloaded: " + e.getMessage());
            EventLogger.warn(CATEGORY, "Java runtime download failed: " + e.getMessage(), release.url());
            throw e;
        } finally {
            progress.end();
        }
    }

    /**
     * Download {@code release} into {@code <home>/jre.staged/} from its vendor's own hosts, verifying it against
     * the catalog's SHA-256 as it arrives, and write the manifest last. Any failure removes the whole staging folder.
     */
    public static Staged download(Release release, Path home, Download progress) throws IOException {
        var vendor = release.vendor();
        var url = HttpUrl.parse(release.url());
        if (url == null || !vendor.allowsLink(url)) {
            throw new RefusedException("refusing " + release.url() + ": Java is only downloaded over HTTPS from "
                    + vendor.linkPlace(), vendor.owner + "'s catalog links it to " + release.url() + ", and JClaw "
                    + "downloads " + vendor.label + " only from " + vendor.linkPlace() + " over HTTPS");
        }
        var sha = release.sha256();
        if (sha == null || !SHA256.matcher(sha).matches()) {
            throw new IOException(vendor.owner + "'s catalog gave no SHA-256 for " + release.archive());
        }
        if (!ARCHIVE.matcher(release.archive()).matches() || !VERSION.matcher(release.version()).matches()) {
            throw new IOException("refusing an archive named " + release.archive());
        }
        if (release.build() != null && !VERSION.matcher(release.build()).matches()) {
            throw new IOException("refusing a build numbered " + release.build());
        }
        var dir = home.resolve(STAGED_DIR);
        deleteTree(dir);
        Files.createDirectories(dir);
        try {
            var part = dir.resolve(release.archive() + ".part");
            fetch(url, part, sha, progress, vendor);
            Files.move(part, dir.resolve(release.archive()), StandardCopyOption.ATOMIC_MOVE);
            var manifest = dir.resolve(MANIFEST + ".part");
            Files.writeString(manifest, "version=" + release.version() + "\nsha256=" + sha + "\narchive="
                    + release.archive() + "\n" + (release.build() == null ? "" : "build=" + release.build() + "\n")
                    + "vendor=" + vendor.label + "\n", StandardCharsets.UTF_8);
            Files.move(manifest, dir.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(dir);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return new Staged(release.version(), sha, release.archive(), release.build(), vendor);
    }

    private static void fetch(HttpUrl url, Path out, String sha256, Download progress, Vendor vendor) throws IOException {
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).build());
        // general()'s 60 s call timeout spans the body, too short for ~60 MB below 8 Mbit/s; its read timeout still ends a stall.
        call.timeout().timeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        try (var response = call.execute()) {
            // Redirects are followed, so the host is checked again where the bytes come from.
            var landed = response.request().url();
            if (!vendor.allowsLanding(landed)) {
                throw new RefusedException("refusing a redirect to " + landed.host() + ": Java is only downloaded "
                        + "from " + vendor.landingPlaces(), "the download came from " + landed.host() + ", and JClaw "
                        + "downloads " + vendor.label + " only from " + vendor.landingPlaces());
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
                throw new RefusedException("checksum mismatch: " + vendor.owner + "'s catalog gives " + sha256
                        + ", the download is " + actual, "the download does not match the SHA-256 that "
                        + vendor.owner + "'s catalog gives for it");
            }
        }
    }

    /**
     * Remove the runtime waiting for the next start. Settings cancels through {@link JavaRuntimePatchCheck#cancel},
     * which also keeps the daily check from downloading it again.
     *
     * @return false when none is waiting
     * @throws IllegalStateException while a download runs
     */
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
        EventLogger.info(CATEGORY, "The Java runtime waiting for the next start was canceled");
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
