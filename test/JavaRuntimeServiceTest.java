import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.JavaRuntimeService;
import services.JavaRuntimeService.Download;
import services.JavaRuntimeService.Environment;
import services.JavaRuntimeService.Kind;
import services.JavaRuntimeService.Release;
import utils.AppClock;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The Java runtime panel's service: which runtime this is, what the catalog offers, which requests it
 * refuses, and what a download leaves on disk. Every case builds its own {@link Environment} and
 * {@link Download}, so nothing here touches the shared state the controller test drives.
 */
class JavaRuntimeServiceTest extends UnitTest {

    private static final String ARCHIVE = "zulu25.38.107-ca-jre25.0.5-macosx_aarch64.tar.gz";
    private static final String URL = "https://cdn.azul.com/zulu/bin/" + ARCHIVE;

    @TempDir Path tmp;

    // --- Versions ---

    @Test
    void versionsOrderNumericallyPartByPart() {
        assertEquals(1, Integer.signum(JavaRuntimeService.compareVersions("25.0.4.1", "25.0.4")));
        assertEquals(-1, Integer.signum(JavaRuntimeService.compareVersions("25.0.4", "25.0.4.1")));
        assertEquals(1, Integer.signum(JavaRuntimeService.compareVersions("25.0.5", "25.0.4.1")));
        assertEquals(1, Integer.signum(JavaRuntimeService.compareVersions("25.0.10", "25.0.9")));
        assertEquals(1, Integer.signum(JavaRuntimeService.compareVersions("26.0.1", "25.0.9")));
        assertEquals(0, JavaRuntimeService.compareVersions("25", "25.0.0"));
        assertEquals(0, JavaRuntimeService.compareVersions("27-ea", "27"));
        assertEquals(25, JavaRuntimeService.major("25.0.4.1"));
        assertEquals(27, JavaRuntimeService.major("27-ea"));
        assertTrue(JavaRuntimeService.isNewer("25.0.4.1", "25.0.4"));
        assertFalse(JavaRuntimeService.isNewer("25.0.4", "25.0.4"));
        assertFalse(JavaRuntimeService.isNewer(null, "25.0.4"));
    }

    // --- The catalog ---

    @Test
    void theCatalogIsAskedWhatInstallShAsksAndWindowsAsksForTheZip() {
        assertEquals("https://api.azul.com/metadata/v1/zulu/packages/?java_version=25&os=macos&arch=aarch64"
                        + "&archive_type=tar.gz&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20",
                JavaRuntimeService.catalogUrl(25, "macos", "aarch64"));
        assertEquals("https://api.azul.com/metadata/v1/zulu/packages/?java_version=25&os=windows&arch=x64"
                        + "&archive_type=zip&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20",
                JavaRuntimeService.catalogUrl(25, "windows", "x64"));
    }

    @Test
    void theHostMapsToInstallShsOsAndArchNames() {
        assertEquals("macos", JavaRuntimeService.azulOs("Mac OS X", false));
        assertEquals("linux", JavaRuntimeService.azulOs("Linux", false));
        assertEquals("linux-musl", JavaRuntimeService.azulOs("Linux", true));
        assertEquals("windows", JavaRuntimeService.azulOs("Windows 11", false));
        assertNull(JavaRuntimeService.azulOs("FreeBSD", false));
        assertEquals("x64", JavaRuntimeService.azulArch("amd64"));
        assertEquals("x64", JavaRuntimeService.azulArch("x86_64"));
        assertEquals("aarch64", JavaRuntimeService.azulArch("aarch64"));
        assertNull(JavaRuntimeService.azulArch("x86"));
    }

    @Test
    void aMuslRuntimeIsKnownByItsReleaseFile() throws IOException {
        var musl = Files.createDirectories(tmp.resolve("musl"));
        Files.writeString(musl.resolve("release"), "JAVA_VERSION=\"25.0.4\"\nLIBC=\"musl\"\n");
        var glibc = Files.createDirectories(tmp.resolve("glibc"));
        Files.writeString(glibc.resolve("release"), "JAVA_VERSION=\"25.0.4\"\nLIBC=\"default\"\n");

        assertTrue(JavaRuntimeService.musl(musl));
        assertFalse(JavaRuntimeService.musl(glibc));
        assertFalse(JavaRuntimeService.musl(tmp.resolve("absent")));
    }

    @Test
    void theNewestPlainJreIsPickedEvenWhenTheCatalogListsAnOlderOneFirst() {
        // The order Azul serves: the builds it flags latest first, so a plain 25.0.4.1 comes after 25.0.3.
        var payload = "[" + String.join(",",
                entry("zulu25.36.205-ca-fx-jre25.0.4.1-macosx_aarch64.tar.gz", "[25,0,4,1]", "[25,36,205,0]", "A1"),
                entry("zulu25.34.17-ca-jre25.0.3-macosx_aarch64.tar.gz", "[25,0,3]", "[25,34,17,0]", "B2"),
                entry("zulu25.36.205-ca-jre25.0.4.1-macosx_aarch64.tar.gz", "[25,0,4,1]", "[25,36,205,0]", "C3"),
                entry("zulu25.36.15-ca-jre25.0.4-macosx_aarch64.tar.gz", "[25,0,4]", "[25,36,15,0]", "D4"),
                entry("zulu25.40.1-ca-crac-jre25.0.6-macosx_aarch64.tar.gz", "[25,0,6]", "[25,40,1,0]", "E5"),
                entry("zulu25.40.1-ca-jre25.0.6-linux_musl_aarch64.tar.gz", "[25,0,6]", "[25,40,1,0]", "F6"),
                entry("zulu26.30.1-ca-jre26.0.1-macosx_aarch64.tar.gz", "[26,0,1]", "[26,30,1,0]", "07")) + "]";

        var picked = JavaRuntimeService.pickLatest(payload, 25, false);
        assertEquals(new Release("25.0.4.1", "https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jre25.0.4.1-macosx_aarch64.tar.gz",
                hash("C3"), "zulu25.36.205-ca-jre25.0.4.1-macosx_aarch64.tar.gz"), picked);

        // A musl runtime takes the musl build install.sh would.
        assertEquals("zulu25.40.1-ca-jre25.0.6-linux_musl_aarch64.tar.gz",
                JavaRuntimeService.pickLatest(payload, 25, true).archive());
        assertNull(JavaRuntimeService.pickLatest("[]", 25, false));
        assertNull(JavaRuntimeService.pickLatest(payload, 24, false));
    }

    @Test
    void aBuildFromTheCatalogWithNoHashIsStillTheNewestButCarriesNone() {
        var payload = "[{\"name\":\"" + ARCHIVE + "\",\"download_url\":\"" + URL + "\",\"java_version\":[25,0,5]}]";
        assertEquals(new Release("25.0.5", URL, null, ARCHIVE), JavaRuntimeService.pickLatest(payload, 25, false));
    }

    @Test
    void theCatalogIsAskedForThisRuntimesCLibraryAndAFailedAnswerIsAnError() throws IOException {
        var glibc = javaHome("glibc", "default");
        var musl = javaHome("musl", "musl");
        var glibcName = "zulu25.38.107-ca-jre25.0.5-linux_x64.tar.gz";
        var muslName = "zulu25.38.107-ca-jre25.0.5-linux_musl_x64.tar.gz";
        var payloads = Map.of("linux", "[" + entry(glibcName, "[25,0,5]", "[25,38,107,0]", "A1") + "]",
                "linux-musl", "[" + entry(muslName, "[25,0,5]", "[25,38,107,0]", "B2") + "]");
        var asked = new ArrayList<String>();
        Interceptor catalog = chain -> {
            asked.add(chain.request().url().toString());
            return answer(chain.request(), 200, payloads.get(chain.request().url().queryParameter("os")));
        };
        var client = new OkHttpClient.Builder().addInterceptor(catalog).build();

        assertEquals(new Release("25.0.5", "https://cdn.azul.com/zulu/bin/" + glibcName, hash("A1"), glibcName),
                latestFrom(client, linux(glibc)));
        assertEquals(new Release("25.0.5", "https://cdn.azul.com/zulu/bin/" + muslName, hash("B2"), muslName),
                latestFrom(client, linux(musl)));
        var query = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=25&os=%s&arch=x64&archive_type=tar.gz"
                + "&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20";
        assertEquals(List.of(query.formatted("linux"), query.formatted("linux-musl")), asked);

        Interceptor down = chain -> answer(chain.request(), 503, "unavailable");
        var e = assertThrows(IOException.class,
                () -> latestFrom(new OkHttpClient.Builder().addInterceptor(down).build(), linux(glibc)));
        assertEquals("HTTP 503 from Azul's catalog", e.getMessage());
    }

    // --- Which runtime this is ---

    @Test
    void onlyTheJavaInsideHomeJreIsBuiltIn() throws IOException {
        var app = install();
        var builtIn = Files.createDirectories(tmp.resolve("jre/zulu25-macosx/zulu-25.jre/Contents/Home"));
        var previous = Files.createDirectories(tmp.resolve("jre.previous/zulu25/Contents/Home"));
        var system = Files.createDirectories(tmp.resolve("Library/Java/zulu-25.jdk/Contents/Home"));

        assertEquals(Kind.BUILT_IN, JavaRuntimeService.kind(env(app, builtIn, "25.0.4", false)));
        // A path through a link, or with a dot-segment, is the same runtime.
        var link = Files.createSymbolicLink(tmp.resolve("jre-link"), builtIn);
        assertEquals(Kind.BUILT_IN, JavaRuntimeService.kind(env(app, link, "25.0.4", false)));
        assertEquals(Kind.BUILT_IN, JavaRuntimeService.kind(env(app, tmp.resolve("jre/../jre/zulu25-macosx"), "25.0.4", false)));

        assertEquals(Kind.EXTERNAL, JavaRuntimeService.kind(env(app, previous, "25.0.4", false)));
        assertEquals(Kind.EXTERNAL, JavaRuntimeService.kind(env(app, system, "25.0.4", false)));
        assertEquals(Kind.CONTAINER, JavaRuntimeService.kind(env(app, builtIn, "25.0.4", true)));

        // A source checkout's Java is the developer's, even beside a jre/.
        Files.createDirectories(app.resolve("app"));
        assertEquals(Kind.EXTERNAL, JavaRuntimeService.kind(env(app, builtIn, "25.0.4", false)));
    }

    // --- What a download may do ---

    @Test
    void aDownloadIsOfferedOnlyForANewerPatchOfTheBuiltInJava() throws IOException {
        var app = install();
        var builtIn = Files.createDirectories(tmp.resolve("jre/Home"));
        var latest = release("25.0.5");
        var env = env(app, builtIn, "25.0.4", false);

        assertEquals(latest, JavaRuntimeService.stageable(env, latest, null, false));
        assertRefused("The runtime comes with the Docker image; pull a newer image to update Java.",
                env(app, builtIn, "25.0.4", true), latest, null, false);
        var system = Files.createDirectories(tmp.resolve("system"));
        assertRefused("JClaw runs on a Java it did not install (" + system + "), so that Java's vendor provides its updates.",
                env(app, system, "25.0.4", false), latest, null, false);
        assertRefused("A newer Java is already downloading.", env, latest, null, true);
        assertRefused("Java 25.0.5 will be installed at the next restart.",
                env, latest, new JavaRuntimeService.Staged("25.0.5", hash("C3"), ARCHIVE), false);
        assertRefused("Couldn't check Azul's catalog for a newer Java.", env, null, null, false);
        assertRefused("Java 25.0.5 is up to date.", env(app, builtIn, "25.0.5", false), latest, null, false);
        assertRefused("Java 25.0.4.1 is up to date.", env(app, builtIn, "25.0.4.1", false), release("25.0.4"), null, false);
    }

    @Test
    void aHomeThatIsNotWritableIsRefusedBeforeAnythingIsWritten() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root writes past any permission");
        var home = Files.createDirectories(tmp.resolve("read-only"));
        var app = Files.createDirectories(home.resolve("jclaw"));
        var env = env(app, Files.createDirectories(home.resolve("jre/Home")), "25.0.4", false);
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertRefused(home + " is not writable, so a new Java cannot be staged beside " + home.resolve("jre") + ".",
                    env, release("25.0.5"), null, false);
        } finally {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // --- The cached check ---

    @Test
    void theCheckIsCachedForAnHourAndKeepsTheLastGoodAnswerWhenAzulIsDown() {
        var lookup = new JavaRuntimeService.Lookup();
        var calls = new AtomicInteger();
        var up = new AtomicReference<>(release("25.0.5"));
        JavaRuntimeService.Catalog catalog = () -> {
            calls.incrementAndGet();
            var r = up.get();
            if (r == null) throw new IOException("Azul is down");
            return r;
        };
        var start = Instant.parse("2026-10-25T09:00:00Z");

        assertNull(at(start, () -> new JavaRuntimeService.Lookup().latest(false, () -> {
            throw new IOException("never reached Azul");
        })));
        assertEquals("25.0.5", at(start, () -> lookup.latest(false, catalog)).version());
        assertEquals("25.0.5", at(start.plusSeconds(3599), () -> lookup.latest(false, catalog)).version());
        assertEquals(1, calls.get());

        up.set(null);
        assertEquals("25.0.5", at(start.plusSeconds(3600), () -> lookup.latest(false, catalog)).version());
        assertEquals("25.0.5", at(start.plusSeconds(3601), () -> lookup.latest(true, catalog)).version());
        assertEquals(3, calls.get());

        up.set(release("25.0.6"));
        assertEquals("25.0.6", at(start.plusSeconds(3602), () -> lookup.latest(true, catalog)).version());
        assertEquals(4, calls.get());
    }

    // --- The download ---

    @Test
    void aVerifiedDownloadIsStagedWithItsManifestWrittenLast() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        // A staging a stopped JVM left half-written is replaced, not trusted.
        Files.createDirectories(home.resolve("jre.staged"));
        Files.writeString(home.resolve("jre.staged/" + ARCHIVE + ".part"), "half");
        var bytes = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
        var progress = new Download();
        progress.begin();

        var staged = stage(serving(bytes), new Release("25.0.5", URL, sha(bytes), ARCHIVE), home, progress);

        assertEquals(new JavaRuntimeService.Staged("25.0.5", sha(bytes), ARCHIVE), staged);
        assertEquals(staged, JavaRuntimeService.staged(home));
        assertEquals("version=25.0.5\nsha256=" + sha(bytes) + "\narchive=" + ARCHIVE + "\n",
                Files.readString(home.resolve("jre.staged/manifest")));
        assertEquals(List.of("manifest", ARCHIVE), listing(home.resolve("jre.staged")));
        assertEquals("a Zulu JRE", Files.readString(home.resolve("jre.staged/" + ARCHIVE)));
        assertEquals(100, progress.currentPercent());
    }

    @Test
    void theDownloadOutlastsTheClientsSixtySecondCallTimeout() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var bytes = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
        var timeout = new AtomicLong();
        Interceptor canned = chain -> {
            timeout.set(chain.call().timeout().timeoutNanos());
            return ok(chain.request(), ResponseBody.create(bytes, null));
        };
        // general()'s call timeout, which spans the whole body: ~60 MB in 60 s needs 8 Mbit/s.
        var client = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(60)).addInterceptor(canned).build();

        stage(client, new Release("25.0.5", URL, sha(bytes), ARCHIVE), home, new Download());

        assertEquals(TimeUnit.MINUTES.toNanos(30), timeout.get());
    }

    @Test
    void aDownloadThatFailsItsChecksumStagesNothing() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var bytes = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
        var wrong = sha("another".getBytes(StandardCharsets.UTF_8));

        var e = assertThrows(IOException.class,
                () -> stage(serving(bytes), new Release("25.0.5", URL, wrong, ARCHIVE), home, new Download()));
        assertEquals("checksum mismatch: Azul's catalog gives " + wrong + ", the download is " + sha(bytes), e.getMessage());
        assertFalse(Files.exists(home.resolve("jre.staged")));
        assertNull(JavaRuntimeService.staged(home));
    }

    @Test
    void anArchiveOverTheCapIsRefusedAndOneAtTheCapIsRead() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var bytes = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
        long cap = 200L * 1024 * 1024;

        var over = assertThrows(IOException.class,
                () -> stage(declaring(cap + 1, bytes), new Release("25.0.5", URL, sha(bytes), ARCHIVE), home, new Download()));
        assertEquals("refusing a " + (cap + 1) + "-byte archive, over the " + cap + "-byte cap", over.getMessage());
        assertFalse(Files.exists(home.resolve("jre.staged")));

        var at = stage(declaring(cap, bytes), new Release("25.0.5", URL, sha(bytes), ARCHIVE), home, new Download());
        assertEquals(new JavaRuntimeService.Staged("25.0.5", sha(bytes), ARCHIVE), at);
    }

    @Test
    void onlyHttpsFromAzulsCdnIsEverFetched() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var bytes = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
        var requests = new AtomicInteger();
        var client = counting(requests, bytes);

        for (var url : List.of("https://cdn.example.com/zulu/bin/" + ARCHIVE, "http://cdn.azul.com/zulu/bin/" + ARCHIVE,
                "https://cdn.azul.com.example.net/" + ARCHIVE)) {
            var e = assertThrows(IOException.class,
                    () -> stage(client, new Release("25.0.5", url, sha(bytes), ARCHIVE), home, new Download()));
            assertEquals("refusing " + url + ": Java is only downloaded over HTTPS from cdn.azul.com", e.getMessage());
        }
        assertEquals(0, requests.get());
        assertFalse(Files.exists(home.resolve("jre.staged")));

        var redirected = assertThrows(IOException.class,
                () -> stage(landingOn("https://downloads.example.org/" + ARCHIVE, bytes),
                        new Release("25.0.5", URL, sha(bytes), ARCHIVE), home, new Download()));
        assertEquals("refusing a redirect to downloads.example.org: Java is only downloaded from cdn.azul.com",
                redirected.getMessage());
        assertFalse(Files.exists(home.resolve("jre.staged")));
    }

    @Test
    void aReleaseWithNoHashOrAStrangeNameIsNeverFetched() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var bytes = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
        var requests = new AtomicInteger();
        var client = counting(requests, bytes);

        var noHash = assertThrows(IOException.class,
                () -> stage(client, new Release("25.0.5", URL, null, ARCHIVE), home, new Download()));
        assertEquals("Azul's catalog gave no SHA-256 for " + ARCHIVE, noHash.getMessage());
        var climbing = assertThrows(IOException.class,
                () -> stage(client, new Release("25.0.5", URL, sha(bytes), "../jre/x.tar.gz"), home, new Download()));
        assertEquals("refusing an archive named ../jre/x.tar.gz", climbing.getMessage());
        assertEquals(0, requests.get());
    }

    // --- What is on disk ---

    @Test
    void aStagingIsReadyOnlyWithAWholeManifestAndItsArchive() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var dir = Files.createDirectories(home.resolve("jre.staged"));
        assertNull(JavaRuntimeService.staged(home));

        Files.writeString(dir.resolve("manifest"), "version=25.0.5\nsha256=" + hash("C3") + "\narchive=" + ARCHIVE + "\n");
        assertNull(JavaRuntimeService.staged(home), "the manifest names an archive that is not there");

        Files.writeString(dir.resolve(ARCHIVE), "bytes");
        assertEquals(new JavaRuntimeService.Staged("25.0.5", hash("C3"), ARCHIVE), JavaRuntimeService.staged(home));

        Files.writeString(dir.resolve("manifest"), "version=25.0.5\nsha256=nothex\narchive=" + ARCHIVE + "\n");
        assertNull(JavaRuntimeService.staged(home));
    }

    @Test
    void aVersionOfThousandsOfPartsIsReadWithoutOverflowingTheStack() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var dir = Files.createDirectories(home.resolve("jre.staged"));
        var version = "25" + ".0".repeat(100_000);
        Files.writeString(dir.resolve(ARCHIVE), "bytes");
        Files.writeString(dir.resolve("manifest"), "version=" + version + "\nsha256=" + hash("C3") + "\narchive=" + ARCHIVE + "\n");

        assertEquals(new JavaRuntimeService.Staged(version, hash("C3"), ARCHIVE), JavaRuntimeService.staged(home));
    }

    @Test
    void theLastStartIsTheStatusFilesFirstLine() throws IOException {
        var app = install();
        assertNull(JavaRuntimeService.lastStart(app));
        Files.createDirectories(app.resolve("data"));
        Files.writeString(app.resolve("data/java-runtime.status"), "\nupgraded 25.0.4 → 25.0.5\n");
        assertEquals("upgraded 25.0.4 → 25.0.5", JavaRuntimeService.lastStart(app).line());
    }

    // --- Cancel ---

    @Test
    void cancelRemovesAReadyStagingAndRefusesWhileADownloadRuns() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        assertFalse(JavaRuntimeService.cancel(home, new Download()));

        var dir = Files.createDirectories(home.resolve("jre.staged"));
        Files.writeString(dir.resolve(ARCHIVE + ".part"), "half");
        assertFalse(JavaRuntimeService.cancel(home, new Download()), "a download with no manifest is not waiting");

        Files.writeString(dir.resolve(ARCHIVE), "bytes");
        Files.writeString(dir.resolve("manifest"), "version=25.0.5\nsha256=" + hash("C3") + "\narchive=" + ARCHIVE + "\n");
        var running = new Download();
        running.begin();
        var e = assertThrows(IllegalStateException.class, () -> JavaRuntimeService.cancel(home, running));
        assertEquals("A newer Java is still downloading; cancel it once the download has finished.", e.getMessage());
        assertTrue(Files.exists(dir.resolve("manifest")));

        assertTrue(JavaRuntimeService.cancel(home, new Download()));
        assertFalse(Files.exists(dir));
    }

    // --- Requesting a download ---

    @Test
    void aRequestedDownloadRunsOffTheCallerAndASecondIsRefusedMeanwhile() throws Exception {
        var app = install();
        var env = env(app, Files.createDirectories(tmp.resolve("jre/Home")), "25.0.4", false);
        var progress = new Download();
        var release = new CountDownLatch(1);
        var staging = new AtomicReference<Path>();

        var started = JavaRuntimeService.requestStage(env, release("25.0.5"), progress, (r, home, p) -> {
            staging.set(home);
            p.percent(40);
            awaitQuietly(release);
            throw new IOException("the network went away");
        });

        assertEquals("25.0.5", started.version());
        assertTrue(progress.active(), "the request reads as in flight before the download thread runs");
        var again = assertThrows(IllegalStateException.class,
                () -> JavaRuntimeService.requestStage(env, release("25.0.5"), progress, (r, h, p) -> { }));
        assertEquals("A newer Java is already downloading.", again.getMessage());

        release.countDown();
        waitUntil(() -> !progress.active());
        assertEquals(tmp, staging.get());
        assertNull(progress.currentPercent());
        assertEquals("Java 25.0.5 could not be downloaded: the network went away", progress.lastError());
    }

    // --- helpers ---

    private static void assertRefused(String why, Environment env, Release latest, JavaRuntimeService.Staged staged,
                                      boolean downloading) {
        var e = assertThrows(IllegalStateException.class,
                () -> JavaRuntimeService.stageable(env, latest, staged, downloading));
        assertEquals(why, e.getMessage());
    }

    private Path install() throws IOException {
        var app = tmp.resolve("jclaw");
        Files.createDirectories(app.resolve("conf"));
        return app;
    }

    private static Environment env(Path app, Path javaHome, String version, boolean container) {
        return new Environment(app, javaHome, version, "Azul Systems, Inc.", "Mac OS X", "aarch64", container);
    }

    private Path javaHome(String name, String libc) throws IOException {
        var home = Files.createDirectories(tmp.resolve(name));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.4\"\nLIBC=\"" + libc + "\"\n");
        return home;
    }

    private Environment linux(Path javaHome) {
        return new Environment(tmp.resolve("jclaw"), javaHome, "25.0.4", "Azul Systems, Inc.", "Linux", "amd64", false);
    }

    private static Release latestFrom(OkHttpClient client, Environment env) throws IOException {
        var failure = new AtomicReference<IOException>();
        var release = HttpFactories.callWith(client, () -> {
            try {
                return JavaRuntimeService.fetchLatest(env);
            } catch (IOException e) {
                failure.set(e);
                return null;
            }
        });
        if (failure.get() != null) throw failure.get();
        return release;
    }

    private static Release release(String version) {
        return new Release(version, URL, hash("C3"), ARCHIVE);
    }

    private static <T> T at(Instant instant, Supplier<T> body) {
        return AppClock.callWith(Clock.fixed(instant, ZoneOffset.UTC), body::get);
    }

    private static String entry(String name, String version, String distro, String hashSeed) {
        return "{\"availability_type\":\"CA\",\"distro_version\":" + distro + ",\"download_url\":\"https://cdn.azul.com/zulu/bin/"
                + name + "\",\"java_version\":" + version + ",\"latest\":true,\"name\":\"" + name
                + "\",\"product\":\"zulu\",\"sha256_hash\":\"" + hash(hashSeed).toUpperCase() + "\"}";
    }

    /** A 64-hex-digit hash made by repeating a seed. */
    private static String hash(String seed) {
        return seed.toLowerCase().repeat(64 / seed.length());
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JavaRuntimeService.Staged stage(OkHttpClient client, Release release, Path home, Download progress)
            throws IOException {
        var failure = new AtomicReference<IOException>();
        var staged = HttpFactories.callWith(client, () -> {
            try {
                return JavaRuntimeService.download(release, home, progress);
            } catch (IOException e) {
                failure.set(e);
                return null;
            }
        });
        if (failure.get() != null) throw failure.get();
        return staged;
    }

    private static OkHttpClient serving(byte[] body) {
        return landingOn(null, body);
    }

    private static OkHttpClient counting(AtomicInteger requests, byte[] body) {
        Interceptor canned = chain -> {
            requests.incrementAndGet();
            return ok(chain.request(), ResponseBody.create(body, null));
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    /** Answers as if the request had been redirected to {@code landed}, or as asked when it is null. */
    private static OkHttpClient landingOn(String landed, byte[] body) {
        Interceptor canned = chain -> {
            var request = landed == null ? chain.request() : chain.request().newBuilder().url(landed).build();
            return ok(request, ResponseBody.create(body, null));
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    /** A body whose Content-Length header says {@code declared} while it carries {@code body}. */
    private static OkHttpClient declaring(long declared, byte[] body) {
        Interceptor canned = chain -> ok(chain.request(),
                ResponseBody.create(new Buffer().write(body), null, declared));
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static Response ok(Request request, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("canned")
                .body(body).build();
    }

    private static Response answer(Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("canned")
                .body(ResponseBody.create(body, null)).build();
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }
}
