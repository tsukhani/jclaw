import com.k2fsa.sherpa.onnx.LibraryUtils;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.voice.SherpaNative;
import services.voice.SherpaNative.Build;
import services.voice.SherpaNative.Source;
import services.voice.SherpaNative.Status;
import utils.HttpFactories;
import utils.PinnedDownload;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JF-188: the release bundle, built on Linux x64, carried only Linux x64's sherpa-onnx native library, so
 * voice failed to start on every other platform. A bundle now carries none, and each install fetches its own.
 */
class SherpaNativeTest extends UnitTest {

    private static final String URL = "https://example.invalid/sherpa-onnx-native-lib-linux-x64-v1.13.4.jar";
    private static final List<String> PINNED = List.of("linux-x64", "linux-aarch64", "osx-x64", "osx-aarch64", "win-x64");

    @TempDir
    Path root;

    @Test
    void theClassifierIsSherpasOwnNameForThePlatform() {
        assertEquals("osx-aarch64", SherpaNative.classifier("Mac OS X", "aarch64"));
        assertEquals("osx-x64", SherpaNative.classifier("Mac OS X", "x86_64"));
        assertEquals("osx-aarch64", SherpaNative.classifier("Darwin", "arm64"));
        assertEquals("linux-x64", SherpaNative.classifier("Linux", "amd64"));
        assertEquals("linux-aarch64", SherpaNative.classifier("Linux", "aarch64"));
        assertEquals("linux-arm", SherpaNative.classifier("Linux", "arm"));
        assertEquals("win-x64", SherpaNative.classifier("Windows 11", "amd64"));
        assertNull(SherpaNative.classifier("SunOS", "sparcv9"));
        assertNull(SherpaNative.classifier("Linux", "riscv64"));
    }

    @Test
    void theHostClassifierNamesTheNativeLibraryGradlePutOnTheClasspath() {
        var host = SherpaNative.hostClassifier();
        assertNotNull(host);
        var resource = "sherpa-onnx/native/" + host + "/" + SherpaNative.jniLibrary(host);
        assertNotNull(LibraryUtils.class.getClassLoader().getResource(resource), resource);
    }

    @Test
    void everyPinnedBuildIsItsReleaseAssetAtThePinnedVersion() {
        for (var classifier : PINNED) {
            var build = SherpaNative.build(classifier);
            assertNotNull(build, classifier);
            assertEquals("https://github.com/k2-fsa/sherpa-onnx/releases/download/v" + SherpaNative.VERSION
                    + "/sherpa-onnx-native-lib-" + classifier + "-v" + SherpaNative.VERSION + ".jar", build.url());
            assertTrue(build.sha256().matches("[0-9a-f]{64}"), build.sha256());
        }
        assertNull(SherpaNative.build("win-aarch64"));
    }

    /**
     * Gradle resolves the native-lib jar from the same release, so this host's jar must have the pinned version
     * and hash. A sherpa bump fails here until VERSION and the hashes follow it.
     */
    @Test
    void thePinnedVersionAndHashAreThoseOfTheJarGradleResolvedForThisHost() throws IOException {
        var host = SherpaNative.hostClassifier();
        var name = "sherpa-onnx-native-lib-" + host + "-" + SherpaNative.VERSION + ".jar";
        var jar = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).filter(p -> p.getFileName().toString().equals(name)).findFirst();
        assertTrue(jar.isPresent(), name + " is not on the test classpath; update SherpaNative.VERSION and its hashes");
        assertEquals(SherpaNative.build(host).sha256(), PinnedDownload.sha256(jar.get()));
    }

    @Test
    void resolutionFollowsSherpasOwnPrecedence() throws IOException {
        var none = new URLClassLoader(new URL[0], null);
        assertEquals(new Status(Source.MISSING, "linux-x64", null), SherpaNative.resolve(root, null, none, "linux-x64"));
        assertEquals(new Status(Source.UNSUPPORTED, null, null), SherpaNative.resolve(root, null, none, null));
        assertEquals(new Status(Source.UNSUPPORTED, "win-aarch64", null), SherpaNative.resolve(root, null, none, "win-aarch64"));

        var installed = SherpaNative.installDir(root, "linux-x64");
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("libsherpa-onnx-jni.so"), "jni");
        assertEquals(new Status(Source.DOWNLOADED, "linux-x64", installed), SherpaNative.resolve(root, null, none, "linux-x64"));

        // A native-lib jar on the classpath wins over a download, as LibraryUtils prefers its own resource.
        var classes = Files.createDirectories(root.resolve("classes/sherpa-onnx/native/linux-x64"));
        Files.writeString(classes.resolve("libsherpa-onnx-jni.so"), "jni");
        var bundled = new URLClassLoader(new URL[]{root.resolve("classes").toUri().toURL()}, null);
        assertEquals(new Status(Source.BUNDLED, "linux-x64", null), SherpaNative.resolve(root, null, bundled, "linux-x64"));

        // And a directory the operator named wins over both.
        assertEquals(new Status(Source.PROVIDED, "linux-x64", null), SherpaNative.resolve(root, "/opt/sherpa", bundled, "linux-x64"));
        assertEquals(new Status(Source.MISSING, "linux-x64", null), SherpaNative.resolve(root.resolve("elsewhere"), " ", none, "linux-x64"));
    }

    @Test
    void aVerifiedJarInstallsItsPlatformsLibrariesAndNothingElse() throws IOException {
        var entries = new LinkedHashMap<String, String>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0");
        entries.put("sherpa-onnx/native/linux-x64/libonnxruntime.so", "onnxruntime");
        entries.put("sherpa-onnx/native/linux-x64/libsherpa-onnx-jni.so", "jni");
        entries.put("sherpa-onnx/native/osx-x64/libsherpa-onnx-jni.dylib", "another platform");
        entries.put("sherpa-onnx/native/linux-x64/../../escape.so", "outside");
        entries.put("sherpa-onnx/native/linux-x64/nested/lib.so", "nested");
        var jar = jar(entries);

        var dir = HttpFactories.callWith(serving(200, jar), () -> download(new Build("linux-x64", URL, sha256(jar))));

        assertEquals(SherpaNative.installDir(root, "linux-x64"), dir);
        assertEquals(List.of(dir, dir.resolve("libonnxruntime.so"), dir.resolve("libsherpa-onnx-jni.so")), tree());
        assertEquals("onnxruntime", Files.readString(dir.resolve("libonnxruntime.so")));
        assertEquals("jni", Files.readString(dir.resolve("libsherpa-onnx-jni.so")));
        assertFalse(Files.exists(root.resolveSibling("escape.so")));
    }

    @Test
    void aChecksumMismatchInstallsNothing() throws IOException {
        var jar = jar(Map.of("sherpa-onnx/native/linux-x64/libsherpa-onnx-jni.so", "jni"));
        var wrong = new Build("linux-x64", URL, "0".repeat(64));

        var e = assertThrows(UncheckedIOException.class, () -> HttpFactories.callWith(serving(200, jar), () -> download(wrong)));

        assertTrue(e.getMessage().contains("checksum mismatch for " + URL), e.getMessage());
        assertEquals(List.of(), tree());
    }

    @Test
    void aJarAlreadyOnDiskIsCheckedBeforeAnythingIsExtracted(@TempDir Path elsewhere) throws IOException {
        var file = Files.write(elsewhere.resolve("native.jar"),
                jar(Map.of("sherpa-onnx/native/linux-x64/libsherpa-onnx-jni.so", "jni")));

        var e = assertThrows(IOException.class,
                () -> SherpaNative.install(new Build("linux-x64", URL, "0".repeat(64)), file, root));

        assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
        assertEquals(List.of(), tree());
    }

    @Test
    void anHttpErrorOrAJarWithoutTheJniLibraryInstallsNothing() throws IOException {
        var http = assertThrows(UncheckedIOException.class,
                () -> HttpFactories.callWith(serving(404, new byte[0]), () -> download(new Build("linux-x64", URL, "0".repeat(64)))));
        assertTrue(http.getMessage().contains("HTTP 404 fetching " + URL), http.getMessage());
        assertEquals(List.of(), tree());

        var noJni = jar(Map.of("sherpa-onnx/native/linux-x64/libonnxruntime.so", "onnxruntime"));
        var missing = assertThrows(UncheckedIOException.class,
                () -> HttpFactories.callWith(serving(200, noJni), () -> download(new Build("linux-x64", URL, sha256(noJni)))));
        assertTrue(missing.getMessage().contains("sherpa-onnx/native/linux-x64/libsherpa-onnx-jni.so not found"), missing.getMessage());
        assertEquals(List.of(), tree());
    }

    @Test
    void aLinkageErrorFromSherpaBecomesAnExceptionTheCallersHandle() {
        var cause = new UnsatisfiedLinkError("no sherpa-onnx-jni in java.library.path");

        var e = assertThrows(IllegalStateException.class, () -> SherpaNative.load(() -> {
            throw cause;
        }));

        assertEquals("sherpa-onnx native library unavailable: java.lang.UnsatisfiedLinkError: no sherpa-onnx-jni in java.library.path",
                e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void whatSherpaReturnsOrThrowsOtherwisePassesThroughUnchanged() {
        assertEquals("built", SherpaNative.load(() -> "built"));
        var failure = new IllegalArgumentException("bad model path");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> SherpaNative.load(() -> {
            throw failure;
        })));
    }

    private static final String PROBE = """
            public class Probe {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("installed")) {
                        var classifier = services.voice.SherpaNative.hostClassifier();
                        var dir = services.voice.SherpaNative.install(services.voice.SherpaNative.build(classifier),
                                java.nio.file.Path.of(args[2]), java.nio.file.Path.of(args[1]));
                        services.voice.SherpaNative.activate(dir, classifier);
                    }
                    try {
                        System.out.println("NATIVE " + com.k2fsa.sherpa.onnx.VersionInfo.getVersion());
                    } catch (UnsatisfiedLinkError e) {
                        System.out.println("UNSATISFIED " + e.getMessage());
                    }
                }
            }
            """;

    /**
     * Runs sherpa in a JVM whose classpath lacks every native-lib jar, as a bundle install's does, and prints
     * what its native code reports. {@code installed} first installs this host's jar the way a download would.
     */
    private String runProbe(Path dir, String mode) throws Exception {
        var probe = dir.resolve("Probe.java");
        Files.writeString(probe, PROBE);
        var entries = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).toList();
        var nativeJar = entries.stream().filter(e -> Path.of(e).getFileName().toString().startsWith("sherpa-onnx-native-lib-"))
                .findFirst().orElseThrow();
        var classpath = Stream.concat(Stream.of(Path.of("build/classes/java/main").toAbsolutePath().toString()),
                entries.stream().filter(e -> !Path.of(e).getFileName().toString().startsWith("sherpa-onnx-native-lib-")))
                .reduce((a, b) -> a + File.pathSeparator + b).orElseThrow();
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", classpath, probe.toString(), mode, dir.resolve("cache").toString(), nativeJar)
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe JVM exited");
        assertEquals(0, process.exitValue(), output);
        return output.lines().filter(l -> l.startsWith("NATIVE ") || l.startsWith("UNSATISFIED ")).findFirst().orElse(output);
    }

    @Test
    void withoutItsNativeJarSherpaCannotLoadTheWayItFailedOnABundleInstall(@TempDir Path dir) throws Exception {
        var outcome = runProbe(dir, "bare");

        assertTrue(outcome.startsWith("UNSATISFIED no sherpa-onnx-jni in java.library.path"), outcome);
    }

    @Test
    void installedAndActivatedTheLibrariesLoadAndSherpasNativeCodeRuns(@TempDir Path dir) throws Exception {
        assertEquals("NATIVE " + SherpaNative.VERSION, runProbe(dir, "installed"));
    }

    private Path download(Build build) {
        try {
            return SherpaNative.download(build, root);
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    private List<Path> tree() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> !p.equals(root)).sorted().toList();
        }
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

    private static byte[] jar(Map<String, String> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
