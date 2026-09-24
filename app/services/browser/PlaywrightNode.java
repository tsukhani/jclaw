package services.browser;

import okhttp3.Request;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jspecify.annotations.Nullable;
import utils.HttpFactories;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.zip.ZipInputStream;

/**
 * Where the Playwright driver's Node.js comes from. The release bundle drops Playwright's
 * driver-bundle jar (Node.js for all five platforms, ~194 MB), so a bundle install fetches the one
 * official build its host needs on first browser use. Playwright's bundled node is byte-identical
 * to the official release, so {@link #VERSION} must track driver-bundle's; PlaywrightNodeTest fails
 * when a Playwright bump changes it.
 */
public final class PlaywrightNode {

    private PlaywrightNode() {}

    public static final String VERSION = "24.21.0";

    /**
     * One official Node.js build: its archive URL, the SHA-256 that release publishes in
     * SHASUMS256.txt, and the node binary's path inside the archive.
     */
    public record Build(String platform, String url, String sha256, String entry) {}

    private static final String MAC_ARM64 = "mac-arm64";
    private static final String MAC = "mac";
    private static final String LINUX_ARM64 = "linux-arm64";
    private static final String LINUX = "linux";
    private static final String WIN32_X64 = "win32_x64";

    private static Build official(String platform, String dist, String sha256) {
        var dir = "node-v" + VERSION + "-" + dist;
        var windows = dist.startsWith("win");
        var archive = dir + (windows ? ".zip" : ".tar.xz");
        return new Build(platform, "https://nodejs.org/dist/v" + VERSION + "/" + archive, sha256,
                dir + (windows ? "/node.exe" : "/bin/node"));
    }

    // From https://nodejs.org/dist/v24.21.0/SHASUMS256.txt. Pinned here rather than fetched beside
    // the archive, so a compromised mirror cannot vouch for its own download.
    private static final Map<String, Build> BUILDS = Map.of(
            MAC_ARM64, official(MAC_ARM64, "darwin-arm64",
                    "6239d4cf92d864487ec8cd3615038f7b67e7f58b77b21cd2f09ea9fbd68065fe"),
            MAC, official(MAC, "darwin-x64",
                    "0ae5a24c24bb7d015cd816c5036b3f90f2945aa872fcf54e58da054753b3a299"),
            LINUX_ARM64, official(LINUX_ARM64, "linux-arm64",
                    "6ad1325edbdb5649c379b75a237147a666c95d4f9ae8d340fef2d1575d289ad2"),
            LINUX, official(LINUX, "linux-x64",
                    "fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6"),
            WIN32_X64, official(WIN32_X64, "win-x64",
                    "158f7685b44de51f6c0df1d153526cbcd3e1bc739a8dfc607721cef75de9e541"));

    /** The archives are 26-36 MB; the hash is only known once the last byte arrives. */
    static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;

    public enum Source { PREINSTALLED, BUNDLED, DOWNLOADED, MISSING, UNSUPPORTED }

    /** @param node the downloaded binary to hand Playwright; set only when {@code source} is DOWNLOADED */
    public record Status(Source source, @Nullable String platform, @Nullable Path node) {}

    public static @Nullable Build build(String platform) {
        return BUILDS.get(platform);
    }

    /** Playwright's own name for this host's platform — DriverJar.platformDir, mirrored exactly. */
    public static @Nullable String platform(String osName, String osArch) {
        var name = osName.toLowerCase(Locale.ROOT);
        var arm = osArch.toLowerCase(Locale.ROOT).equals("aarch64");
        if (name.contains("windows")) return WIN32_X64;
        if (name.contains("linux")) return arm ? LINUX_ARM64 : LINUX;
        if (name.contains("mac os x")) return arm ? MAC_ARM64 : MAC;
        return null;
    }

    public static @Nullable String hostPlatform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String binaryName(String platform) {
        return platform.startsWith("win") ? "node.exe" : "node";
    }

    private static Path installDir(Path cacheRoot, String platform) {
        return cacheRoot.resolve(VERSION + "-" + platform);
    }

    /** Where a downloaded node lives — versioned, so a Playwright bump never reuses a stale one. */
    public static Path installedNode(Path cacheRoot, String platform) {
        return installDir(cacheRoot, platform).resolve(binaryName(platform));
    }

    /**
     * Resolved in Playwright Java's own precedence (Driver.newInstance, then DriverJar): a driver or
     * node the operator or the image supplied, then driver-bundle on the classpath, then a download.
     */
    public static Status resolve(Path cacheRoot, Map<String, String> env, ClassLoader classLoader,
                                 @Nullable String platform) {
        if (present(env.get("PLAYWRIGHT_DRIVER_DIR")) || operatorDriverDir(System.getProperty(PlaywrightDriverDir.CLI_DIR_PROPERTY))
                || present(env.get("PLAYWRIGHT_NODEJS_PATH")) || present(System.getProperty("playwright.nodejs.path"))) {
            return new Status(Source.PREINSTALLED, platform, null);
        }
        if (platform == null) return new Status(Source.UNSUPPORTED, null, null);
        if (classLoader.getResource("driver/" + platform + "/" + binaryName(platform)) != null) {
            return new Status(Source.BUNDLED, platform, null);
        }
        var node = installedNode(cacheRoot, platform);
        if (Files.isRegularFile(node)) return new Status(Source.DOWNLOADED, platform, node);
        return new Status(BUILDS.containsKey(platform) ? Source.MISSING : Source.UNSUPPORTED, platform, null);
    }

    private static boolean present(@Nullable String v) {
        return v != null && !v.isBlank();
    }

    /** jclaw points playwright.cli.dir at its own copy of the bundled driver; only another directory is an operator's. */
    public static boolean operatorDriverDir(@Nullable String dir) {
        return dir != null && !dir.isBlank() && !PlaywrightDriverDir.isManaged(Path.of(dir));
    }

    /**
     * Download {@code build}, verify it against the pinned SHA-256, and extract its node binary to
     * {@link #installedNode}. {@code onPercent} receives 0-100 as bytes arrive. Nothing reaches the
     * final path unless every step succeeded, so a failed attempt is simply retried.
     */
    public static Path download(Build build, Path cacheRoot, IntConsumer onPercent) throws IOException {
        var dir = installDir(cacheRoot, build.platform());
        Files.createDirectories(dir);
        var archive = Files.createTempFile(cacheRoot, "node-", ".part");
        var staged = Files.createTempFile(dir, "node-", ".part");
        try {
            fetch(build, archive, onPercent);
            extract(build, archive, staged);
            if (!build.platform().startsWith("win")) {
                Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwx------"));
            }
            var target = installedNode(cacheRoot, build.platform());
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(staged);
        }
    }

    private static void fetch(Build build, Path archive, IntConsumer onPercent) throws IOException {
        var request = new Request.Builder().url(build.url()).build();
        try (var response = HttpFactories.general().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " fetching " + build.url());
            }
            var body = response.body();
            long total = body.contentLength();
            if (total > MAX_ARCHIVE_BYTES) throw new IOException("refusing a " + total + "-byte archive");
            var digest = sha256();
            try (var in = new DigestInputStream(body.byteStream(), digest);
                 OutputStream out = Files.newOutputStream(archive)) {
                var buffer = new byte[64 * 1024];
                long read = 0;
                int reported = -1;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    read += n;
                    if (read > MAX_ARCHIVE_BYTES) throw new IOException("archive exceeds " + MAX_ARCHIVE_BYTES + " bytes");
                    out.write(buffer, 0, n);
                    int pct = total > 0 ? (int) (read * 100 / total) : 0;
                    if (pct != reported) {
                        onPercent.accept(pct);
                        reported = pct;
                    }
                }
            }
            var actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(build.sha256())) {
                throw new IOException("checksum mismatch for " + build.url() + ": expected " + build.sha256()
                        + ", got " + actual);
            }
        }
    }

    // Only the one pinned entry is copied, to a path chosen here, so an archive's own entry names
    // can never direct a write.
    private static void extract(Build build, Path archive, Path staged) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive))) {
            if (build.url().endsWith(".zip")) {
                try (var zip = new ZipInputStream(raw)) {
                    for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                        if (e.getName().equals(build.entry())) {
                            Files.copy(zip, staged, StandardCopyOption.REPLACE_EXISTING);
                            return;
                        }
                    }
                }
            } else {
                try (var tar = new TarArchiveInputStream(new XZCompressorInputStream(raw))) {
                    for (var e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                        if (e.getName().equals(build.entry())) {
                            Files.copy(tar, staged, StandardCopyOption.REPLACE_EXISTING);
                            return;
                        }
                    }
                }
            }
        }
        throw new IOException(build.entry() + " not found in " + build.url());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JRE", e);
        }
    }
}
