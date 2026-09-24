import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.browser.PlaywrightNode;
import services.browser.PlaywrightNode.Source;
import utils.HttpFactories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The Playwright driver's Node.js: which one a host uses, and fetching the official build when none is present. */
class PlaywrightNodeTest extends UnitTest {

    private Path root;

    @BeforeEach
    void makeRoot() throws IOException {
        root = Files.createTempDirectory("pw-node-test");
    }

    @AfterEach
    void removeRoot() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void theHostPlatformIsNamedExactlyAsPlaywrightsDriverJarNamesIt() {
        assertEquals("mac-arm64", PlaywrightNode.platform("Mac OS X", "aarch64"));
        assertEquals("mac", PlaywrightNode.platform("Mac OS X", "x86_64"));
        assertEquals("linux-arm64", PlaywrightNode.platform("Linux", "aarch64"));
        assertEquals("linux", PlaywrightNode.platform("Linux", "amd64"));
        assertEquals("win32_x64", PlaywrightNode.platform("Windows 11", "amd64"));
        // DriverJar maps every Windows to x64; Windows on ARM runs it under emulation.
        assertEquals("win32_x64", PlaywrightNode.platform("Windows 11", "aarch64"));
        assertNull(PlaywrightNode.platform("FreeBSD", "amd64"));
    }

    @Test
    void everyPlatformHasAnOfficialBuildOfThePinnedVersion() {
        for (var platform : List.of("mac-arm64", "mac", "linux-arm64", "linux", "win32_x64")) {
            var b = PlaywrightNode.build(platform);
            assertNotNull(b, platform);
            var prefix = "https://nodejs.org/dist/v" + PlaywrightNode.VERSION + "/node-v" + PlaywrightNode.VERSION + "-";
            assertTrue(b.url().startsWith(prefix), b.url());
            assertTrue(b.sha256().matches("[0-9a-f]{64}"), b.sha256());
            var archiveDir = b.url().substring(b.url().lastIndexOf('/') + 1).replaceFirst("\\.(tar\\.xz|zip)$", "");
            assertTrue(b.entry().startsWith(archiveDir + "/"), b.entry() + " is not inside " + archiveDir);
        }
    }

    /**
     * The download stands in for driver-bundle's node, so the two must be the same release. A Playwright
     * bump that moves driver-bundle to a new Node fails here until VERSION and the hashes follow it.
     */
    @Test
    void thePinnedVersionIsTheNodeThatDriverBundleShips() throws Exception {
        var platform = PlaywrightNode.hostPlatform();
        assertNotNull(platform, "no Playwright platform for this test host");
        var resource = "driver/" + platform + "/" + (platform.startsWith("win") ? "node.exe" : "node");
        try (var in = ClassLoader.getSystemClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " missing — driver-bundle is expected on the test classpath");
            var node = root.resolve("node");
            Files.copy(in, node);
            assertTrue(node.toFile().setExecutable(true));
            var proc = new ProcessBuilder(node.toString(), "--version").redirectErrorStream(true).start();
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "node --version timed out");
            var version = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals("v" + PlaywrightNode.VERSION, version,
                    "driver-bundle ships Node " + version + "; update PlaywrightNode.VERSION and its hashes");
        }
    }

    @Test
    void resolutionFollowsPlaywrightsOwnPrecedence() throws IOException {
        var none = emptyLoader();
        assertEquals(Source.PREINSTALLED,
                PlaywrightNode.resolve(root, Map.of("PLAYWRIGHT_DRIVER_DIR", "/opt/pw-driver"), none, "linux").source());
        assertEquals(Source.PREINSTALLED,
                PlaywrightNode.resolve(root, Map.of("PLAYWRIGHT_NODEJS_PATH", "/usr/bin/node"), none, "linux").source());
        assertEquals(Source.MISSING, PlaywrightNode.resolve(root, Map.of(), none, "linux").source());
        assertEquals(Source.UNSUPPORTED, PlaywrightNode.resolve(root, Map.of(), none, null).source());

        var node = PlaywrightNode.installedNode(root, "linux");
        Files.createDirectories(node.getParent());
        Files.writeString(node, "fake");
        var downloaded = PlaywrightNode.resolve(root, Map.of(), none, "linux");
        assertEquals(Source.DOWNLOADED, downloaded.source());
        assertEquals(node, downloaded.node());

        // driver-bundle on the classpath wins over a download, exactly as DriverJar prefers it.
        var bundleDir = Files.createTempDirectory(root, "bundle");
        Files.createDirectories(bundleDir.resolve("driver/linux"));
        Files.writeString(bundleDir.resolve("driver/linux/node"), "bundled");
        try (var bundled = new URLClassLoader(new java.net.URL[] {bundleDir.toUri().toURL()}, null)) {
            var status = PlaywrightNode.resolve(root, Map.of(), bundled, "linux");
            assertEquals(Source.BUNDLED, status.source());
            assertNull(status.node(), "a bundled driver needs no node handed to Playwright");
        }
    }

    @Test
    void aVerifiedArchiveInstallsItsNodeBinary() throws IOException {
        var entry = "node-v" + PlaywrightNode.VERSION + "-linux-x64/bin/node";
        var archive = tarXz(Map.of("node-v" + PlaywrightNode.VERSION + "-linux-x64/README.md", "readme",
                entry, "#!fake-node"));
        var build = new PlaywrightNode.Build("linux", "https://nodejs.test/node.tar.xz", sha256(archive), entry);
        var percents = new ArrayList<Integer>();

        var installed = download(serving(200, archive), build, percents);

        assertEquals(PlaywrightNode.installedNode(root, "linux"), installed);
        assertEquals("#!fake-node", Files.readString(installed));
        assertTrue(Files.getPosixFilePermissions(installed).contains(PosixFilePermission.OWNER_EXECUTE));
        assertEquals(100, percents.getLast(), "progress should finish at 100: " + percents);
        assertNoPartialFiles();
    }

    @Test
    void theWindowsZipIsReadToo() throws IOException {
        var entry = "node-v" + PlaywrightNode.VERSION + "-win-x64/node.exe";
        var archive = zip(entry, "MZ-fake");
        var build = new PlaywrightNode.Build("win32_x64", "https://nodejs.test/node.zip", sha256(archive), entry);

        var installed = download(serving(200, archive), build, new ArrayList<>());

        assertEquals(root.resolve(PlaywrightNode.VERSION + "-win32_x64").resolve("node.exe"), installed);
        assertEquals("MZ-fake", Files.readString(installed));
    }

    @Test
    void aChecksumMismatchInstallsNothing() throws IOException {
        var entry = "node-v" + PlaywrightNode.VERSION + "-linux-x64/bin/node";
        var archive = tarXz(Map.of(entry, "tampered"));
        var build = new PlaywrightNode.Build("linux", "https://nodejs.test/node.tar.xz", "0".repeat(64), entry);

        var e = assertThrows(IOException.class, () -> download(serving(200, archive), build, new ArrayList<>()));

        assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
        assertFalse(Files.exists(PlaywrightNode.installedNode(root, "linux")), "a mismatched archive must not install");
        assertNoPartialFiles();
    }

    @Test
    void anHttpErrorOrAMissingBinaryInstallsNothing() throws IOException {
        var entry = "node-v" + PlaywrightNode.VERSION + "-linux-x64/bin/node";
        var build = new PlaywrightNode.Build("linux", "https://nodejs.test/node.tar.xz", "0".repeat(64), entry);
        var http = assertThrows(IOException.class, () -> download(serving(404, new byte[0]), build, new ArrayList<>()));
        assertTrue(http.getMessage().contains("HTTP 404"), http.getMessage());

        var noBinary = tarXz(Map.of("node-v" + PlaywrightNode.VERSION + "-linux-x64/README.md", "readme"));
        var wrongContents = new PlaywrightNode.Build("linux", build.url(), sha256(noBinary), entry);
        var missing = assertThrows(IOException.class, () -> download(serving(200, noBinary), wrongContents, new ArrayList<>()));
        assertTrue(missing.getMessage().contains("not found"), missing.getMessage());

        assertFalse(Files.exists(PlaywrightNode.installedNode(root, "linux")));
        assertNoPartialFiles();
    }

    private Path download(OkHttpClient client, PlaywrightNode.Build build, List<Integer> percents) throws IOException {
        var failure = new AtomicReference<IOException>();
        var installed = HttpFactories.callWith(client, () -> {
            try {
                return PlaywrightNode.download(build, root, percents::add);
            } catch (IOException e) {
                failure.set(e);
                return null;
            }
        });
        if (failure.get() != null) throw failure.get();
        return installed;
    }

    private void assertNoPartialFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            var leftovers = walk.filter(p -> p.getFileName().toString().endsWith(".part")).toList();
            assertTrue(leftovers.isEmpty(), "partial files left behind: " + leftovers);
        }
    }

    private static URLClassLoader emptyLoader() {
        return new URLClassLoader(new java.net.URL[0], null);
    }

    private static OkHttpClient serving(int code, byte[] body) {
        Interceptor canned = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(ResponseBody.create(body, null))
                .build();
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static byte[] tarXz(Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(new XZCompressorOutputStream(bytes))) {
            for (var f : files.entrySet()) {
                var data = f.getValue().getBytes(StandardCharsets.UTF_8);
                var e = new TarArchiveEntry(f.getKey());
                e.setSize(data.length);
                tar.putArchiveEntry(e);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(String name, String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
