import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.browser.BrowserSetup;
import services.browser.PlaywrightNode;
import services.browser.ScriptNode;
import services.browser.ScriptNode.Host;
import services.browser.ScriptNode.Ready;
import services.browser.ScriptNode.Refused;
import services.browser.ScriptNode.Source;
import utils.HttpFactories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** The Node.js scripts run on: where it is found, its download without Chromium, and what its start arguments refuse. */
class ScriptNodeTest extends UnitTest {

    private static final String URL = "https://nodejs.test/node.tar.xz";
    private static final String ENTRY = "node-v" + PlaywrightNode.VERSION + "-linux-x64/bin/node";
    private static final String STEP = "Downloading Node.js " + PlaywrightNode.VERSION + " for scripts";

    private static Path nodeDir;
    private static Path node;

    private Path root;
    private byte[] archive;
    private PlaywrightNode.Build build;
    private BrowserSetup.Progress progress;

    @BeforeAll
    static void findANodeOnDisk() throws IOException {
        var platform = PlaywrightNode.hostPlatform();
        var resource = platform == null ? null : "driver/" + platform + "/" + (platform.startsWith("win") ? "node.exe" : "node");
        try (var in = resource == null ? null : ClassLoader.getSystemClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                nodeDir = Files.createTempDirectory("script-node-bin");
                node = nodeDir.resolve("node");
                Files.copy(in, node);
                assertTrue(node.toFile().setExecutable(true));
                return;
            }
        }
        var explicit = System.getenv(ScriptNode.NODEJS_PATH_ENV);
        if (explicit != null && Files.isRegularFile(Path.of(explicit))) node = Path.of(explicit);
    }

    @AfterAll
    static void removeNode() throws IOException {
        if (nodeDir != null) deleteTree(nodeDir);
    }

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("script-node-test");
        archive = PlaywrightNodeTest.tarXz(Map.of(ENTRY, "#!downloaded-node"));
        build = new PlaywrightNode.Build("linux", URL, PlaywrightNodeTest.sha256(archive), ENTRY);
        progress = new BrowserSetup.Progress();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteTree(root);
    }

    // ── Where Node comes from ────────────────────────────────────────────────

    @Test
    void theOperatorsNodejsPathComesFirst() throws IOException {
        var own = write(root.resolve("own/node"));
        managedNode("k");
        downloadedNode();

        assertEquals(new Ready(own, Source.NODEJS_PATH),
                resolveOffline(host(Map.of(ScriptNode.NODEJS_PATH_ENV, own.toString()), "k")));
    }

    @Test
    void aNodejsPathThatIsNotAFileIsRefusedAndNothingIsDownloaded() throws IOException {
        var missing = root.resolve("missing/node");
        var requests = new ArrayList<String>();

        var resolution = HttpFactories.callWith(serving(200, requests, () -> {}),
                () -> ScriptNode.resolve(host(Map.of(ScriptNode.NODEJS_PATH_ENV, missing.toString()), null), progress));

        assertEquals(new Refused("PLAYWRIGHT_NODEJS_PATH names " + missing + ", which is not a file"), resolution);
        assertEquals(List.of(), requests);
        assertFalse(Files.exists(PlaywrightNode.installedNode(cacheRoot(), "linux")));
    }

    @Test
    void aBlankNodejsPathCountsAsUnset() throws IOException {
        var managed = managedNode("k");
        assertEquals(new Ready(managed, Source.MANAGED_DRIVER),
                resolveOffline(host(Map.of(ScriptNode.NODEJS_PATH_ENV, "  "), "k")));
    }

    @Test
    void theManagedDriverFolderComesBeforeTheDownload() throws IOException {
        var managed = managedNode("k");
        downloadedNode();

        var ready = resolveOffline(host(Map.of(), "k"));

        assertEquals(new Ready(managed, Source.MANAGED_DRIVER), ready);
        assertEquals(root.resolve("driver/k"), ((Ready) ready).folder());
    }

    @Test
    void aManagedFolderWithNoNodeFallsThroughToTheDownload() throws IOException {
        var managed = managedNode("k");
        Files.delete(managed);
        var downloaded = downloadedNode();

        assertEquals(new Ready(downloaded, Source.DOWNLOADED), resolveOffline(host(Map.of(), "k")));
    }

    @Test
    void aNodeDownloadedEarlierIsUsedWithoutARequest() throws IOException {
        var downloaded = downloadedNode();

        assertEquals(new Ready(downloaded, Source.DOWNLOADED), resolveOffline(host(Map.of(), null)));
        assertFalse(BrowserSetup.snapshot(progress).active(), "nothing was downloaded, so nothing shows in flight");
    }

    @Test
    void aPlatformWithNoNodeBuildIsRefused() {
        var noBuild = new Host(Map.of(), root.resolve("driver"), null, cacheRoot(), null);
        assertEquals(new Refused("Scripts run on Node.js, which has no build for this platform (%s %s)"
                        .formatted(System.getProperty("os.name"), System.getProperty("os.arch"))),
                resolveOffline(noBuild));
    }

    // ── The download ─────────────────────────────────────────────────────────

    @Test
    void withNoneOnDiskThePinnedBuildIsDownloadedWithoutStartingTheBrowsersSetup() throws IOException {
        var requests = new ArrayList<String>();
        var during = new AtomicReference<BrowserSetup.Snapshot>();
        var browserSetupDuring = new AtomicReference<Boolean>();

        var resolution = HttpFactories.callWith(serving(200, requests, () -> {
            during.set(BrowserSetup.snapshot(progress));
            browserSetupDuring.set(progress.active());
        }), () -> ScriptNode.resolve(host(Map.of(), null), progress));

        var installed = PlaywrightNode.installedNode(cacheRoot(), "linux");
        assertEquals(new Ready(installed, Source.DOWNLOADED), resolution);
        assertEquals("#!downloaded-node", Files.readString(installed));
        assertEquals(List.of(URL), requests, "the Node archive is the only download");
        assertTrue(during.get().active(), "Settings shows the download in flight");
        assertEquals(STEP, during.get().step());
        assertEquals(0, during.get().percent());
        assertEquals(false, browserSetupDuring.get(), "the browser's setup, which installs Chromium, never began");
        var after = BrowserSetup.snapshot(progress);
        assertFalse(after.active());
        assertNull(after.step());
        assertNull(after.error());
    }

    @Test
    void aChecksumMismatchIsRefusedNamingTheDownloadAndInstallsNothing() throws IOException {
        var tampered = new PlaywrightNode.Build("linux", URL, "0".repeat(64), ENTRY);
        var reason = "checksum mismatch for " + URL + ": expected " + "0".repeat(64) + ", got "
                + PlaywrightNodeTest.sha256(archive);

        var resolution = HttpFactories.callWith(serving(200, new ArrayList<>(), () -> {}),
                () -> ScriptNode.resolve(new Host(Map.of(), root.resolve("driver"), null, cacheRoot(), tampered), progress));

        assertEquals(new Refused("Node.js " + PlaywrightNode.VERSION + " for scripts could not be downloaded ("
                + reason + "); retry, or download it from Settings > Browser"), resolution);
        assertFalse(Files.exists(PlaywrightNode.installedNode(cacheRoot(), "linux")));
        var settings = BrowserSetup.snapshot(progress);
        assertFalse(settings.active());
        assertEquals("Node.js for scripts could not be downloaded: " + reason, settings.error());
    }

    @Test
    void aFailedDownloadIsRefusedNamingItAndTheNextAttemptClearsTheFailure() throws IOException {
        var failed = HttpFactories.callWith(serving(503, new ArrayList<>(), () -> {}),
                () -> ScriptNode.resolve(host(Map.of(), null), progress));

        assertEquals(new Refused("Node.js " + PlaywrightNode.VERSION + " for scripts could not be downloaded (HTTP 503 fetching "
                + URL + "); retry, or download it from Settings > Browser"), failed);
        assertEquals("Node.js for scripts could not be downloaded: HTTP 503 fetching " + URL,
                BrowserSetup.snapshot(progress).error());

        var retried = HttpFactories.callWith(serving(200, new ArrayList<>(), () -> {}),
                () -> ScriptNode.resolve(host(Map.of(), null), progress));

        assertEquals(new Ready(PlaywrightNode.installedNode(cacheRoot(), "linux"), Source.DOWNLOADED), retried);
        assertNull(BrowserSetup.snapshot(progress).error());
    }

    @Test
    void aBrowserSetupThatBeginsDuringAScriptsDownloadIsStillInFlightAfterIt() {
        HttpFactories.callWith(serving(200, new ArrayList<>(), progress::begin),
                () -> ScriptNode.resolve(host(Map.of(), null), progress));

        assertTrue(progress.active());
        assertTrue(BrowserSetup.snapshot(progress).active(), "the browser's setup still runs, so Settings still shows it");
        progress.end();
        assertFalse(BrowserSetup.snapshot(progress).active());
    }

    @Test
    void theBrowsersOwnDownloadLeavesItsSetupInFlightForChromium() {
        progress.begin();

        var node = HttpFactories.callWith(serving(200, new ArrayList<>(), () -> {}), () -> {
            try {
                return BrowserSetup.downloadNode(build, cacheRoot(), progress, "Downloading the browser driver");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(PlaywrightNode.installedNode(cacheRoot(), "linux"), node);
        var settings = BrowserSetup.snapshot(progress);
        assertTrue(settings.active());
        assertEquals("Downloading the browser driver", settings.step());
        assertEquals(100, settings.percent());
    }

    @Test
    void twoScriptsThatFindNodeMissingShareOneDownload() throws Exception {
        var requested = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstRequests = Collections.synchronizedList(new ArrayList<String>());
        var secondRequests = Collections.synchronizedList(new ArrayList<String>());
        var first = new AtomicReference<ScriptNode.Resolution>();
        var second = new AtomicReference<ScriptNode.Resolution>();

        var a = new Thread(() -> first.set(HttpFactories.callWith(serving(200, firstRequests, () -> {
            requested.countDown();
            awaitQuietly(release);
        }), () -> ScriptNode.resolve(host(Map.of(), null), progress))));
        a.start();
        assertTrue(requested.await(30, TimeUnit.SECONDS), "the first download never started");
        var b = new Thread(() -> second.set(HttpFactories.callWith(serving(200, secondRequests, () -> {}),
                () -> ScriptNode.resolve(host(Map.of(), null), progress))));
        b.start();
        awaitWaiting(b);
        release.countDown();
        a.join(30_000);
        b.join(30_000);

        var installed = new Ready(PlaywrightNode.installedNode(cacheRoot(), "linux"), Source.DOWNLOADED);
        assertEquals(installed, first.get());
        assertEquals(installed, second.get());
        assertEquals(List.of(URL), firstRequests);
        assertEquals(List.of(), secondRequests, "the second script waited for the first download and used it");
    }

    // ── How Node starts ──────────────────────────────────────────────────────

    @Test
    void theStartArgumentsTurnPermissionsOnWithNoAllowFlagAndTheHeapCap() {
        var argv = ScriptNode.command(Path.of("/opt/node/bin/node"), 96);

        assertEquals(List.of("/opt/node/bin/node", "--permission", "--max-old-space-size=96", "--eval"), argv.subList(0, 4));
        assertEquals(5, argv.size(), "the loader is the last argument: " + argv);
        assertEquals(List.of(), argv.stream().filter(a -> a.startsWith("--allow")).toList());
    }

    @Test
    void theHeapCapIsTheConfiguredOne() {
        var anyNode = Path.of("/opt/node/bin/node");
        try {
            ConfigService.delete(ScriptNode.HEAP_KEY);
            assertEquals("--max-old-space-size=256", ScriptNode.command(anyNode).get(2));
            ConfigService.set(ScriptNode.HEAP_KEY, "96");
            assertEquals("--max-old-space-size=96", ScriptNode.command(anyNode).get(2));
            ConfigService.set(ScriptNode.HEAP_KEY, "16");
            assertEquals("--max-old-space-size=16", ScriptNode.command(anyNode).get(2));
            // A row written past the write check reads as the default, never as a heap Node cannot start in.
            ConfigService.set(ScriptNode.HEAP_KEY, "15");
            assertEquals("--max-old-space-size=256", ScriptNode.command(anyNode).get(2));
        } finally {
            ConfigService.delete(ScriptNode.HEAP_KEY);
        }
    }

    @Test
    void theScriptIsOneJsonLine() {
        assertEquals("\"a\\nb \\\"c\\\" </x> \\u2028\"\n",
                new String(ScriptNode.stdin("a\nb \"c\" </x> \u2028"), StandardCharsets.UTF_8));
    }

    @Test
    void aScriptCannotReadOrWriteAFileOrStartAProcessOrAWorker() throws Exception {
        var secret = Files.writeString(root.resolve("secret.txt"), "s3cret");
        var written = root.resolve("written.txt");
        var script = String.join("\n",
                "const probe = (name, f) => { try { f(); console.log(name + ' allowed'); }",
                "  catch (e) { console.log(name + ' ' + e.code + ' ' + e.permission); } };",
                "probe('read', () => require('node:fs').readFileSync(%s, 'utf8'));".formatted(js(secret)),
                "probe('write', () => require('node:fs').writeFileSync(%s, 'x'));".formatted(js(written)),
                "probe('spawn', () => require('node:child_process').execFileSync(%s, ['--version']));".formatted(js(requireNode())),
                "probe('worker', () => new (require('node:worker_threads').Worker)('0', { eval: true }).unref());");
        var argv = ScriptNode.command(requireNode(), 256);

        var confined = run(argv, ScriptNode.stdin(script));

        assertEquals(new Run(0, """
                read ERR_ACCESS_DENIED FileSystemRead
                write ERR_ACCESS_DENIED FileSystemWrite
                spawn ERR_ACCESS_DENIED ChildProcess
                worker ERR_ACCESS_DENIED WorkerThreads
                """), confined.withoutErr());
        assertFalse(Files.exists(written));

        // The same run without --permission does all four, so the refusals above are the flag's.
        var open = run(argv.stream().filter(a -> !a.equals("--permission")).toList(), ScriptNode.stdin(script));
        assertEquals(new Run(0, "read allowed\nwrite allowed\nspawn allowed\nworker allowed\n"), open.withoutErr());
        assertEquals("x", Files.readString(written));
    }

    @Test
    void theScriptArrivesOnStdinNeverInTheArgumentsAndTheRestOfStdinIsItsOwn() throws Exception {
        var script = String.join("\n",
                "const marker = 'marker-5d1e';",
                "console.log([...process.argv, ...process.execArgv].some(a => a.includes(marker)));",
                "require('node:readline').createInterface({ input: process.stdin })",
                "  .once('line', l => { console.log('next: ' + l); process.stdin.destroy(); });");
        var argv = ScriptNode.command(requireNode(), 256);
        var input = new ByteArrayOutputStream();
        input.write(ScriptNode.stdin(script));
        input.write("a result line\n".getBytes(StandardCharsets.UTF_8));

        var ran = run(argv, input.toByteArray());

        assertEquals(new Run(0, "false\nnext: a result line\n"), ran.withoutErr());
        assertEquals(List.of(), argv.stream().filter(a -> a.contains("marker-5d1e")).toList());
    }

    @Test
    void theHeapCapEndsAScriptThatOutgrowsIt() throws Exception {
        var script = "const a = []; for (let i = 0; i < 6; i++) a.push(new Array(1e6).fill(1)); console.log(a.length);";

        assertEquals(new Run(0, "6\n"), run(ScriptNode.command(requireNode(), 256), ScriptNode.stdin(script)).withoutErr());

        var capped = run(ScriptNode.command(requireNode(), 32), ScriptNode.stdin(script));
        assertEquals(134, capped.exit(), "Node aborts on SIGABRT: " + capped.err());
        assertEquals("", capped.out());
        assertTrue(capped.err().lines().anyMatch(
                        l -> l.equals("FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory")),
                capped.err());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record Run(int exit, String out, String err) {
        Run(int exit, String out) {
            this(exit, out, "");
        }

        Run withoutErr() {
            return new Run(exit, out);
        }
    }

    private static Path requireNode() {
        Assumptions.assumeTrue(node != null,
                "no Node.js on disk: driver-bundle is not on the classpath and PLAYWRIGHT_NODEJS_PATH names no file");
        return node;
    }

    private Run run(List<String> argv, byte[] stdin) throws Exception {
        var err = Files.createTempFile(root, "stderr", ".txt");
        var proc = new ProcessBuilder(argv).redirectError(err.toFile()).start();
        try {
            try (var in = proc.getOutputStream()) {
                in.write(stdin);
            }
            var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "node did not exit");
            return new Run(proc.exitValue(), out, Files.readString(err));
        } finally {
            proc.destroyForcibly();
        }
    }

    private static String js(Path p) {
        return utils.GsonHolder.GSON.toJson(p.toString());
    }

    private Host host(Map<String, String> env, String driverKey) {
        return new Host(env, root.resolve("driver"), driverKey, cacheRoot(), build);
    }

    private Path cacheRoot() {
        return root.resolve("node-cache");
    }

    /** Resolve where any request would fail the test, so a found Node is proven to need no download. */
    private ScriptNode.Resolution resolveOffline(Host host) {
        var requests = new ArrayList<String>();
        var resolution = HttpFactories.callWith(serving(200, requests, () -> {}), () -> ScriptNode.resolve(host, progress));
        assertEquals(List.of(), requests);
        return resolution;
    }

    private Path managedNode(String key) throws IOException {
        var dir = Files.createDirectories(root.resolve("driver").resolve(key));
        Files.createFile(dir.resolve(".jclaw-driver"));
        return write(dir.resolve("node"));
    }

    private Path downloadedNode() throws IOException {
        return write(PlaywrightNode.installedNode(cacheRoot(), "linux"));
    }

    private static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "#!node");
    }

    private OkHttpClient serving(int code, List<String> requests, Runnable during) {
        Interceptor canned = chain -> {
            requests.add(chain.request().url().toString());
            during.run();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(ResponseBody.create(code == 200 ? archive : new byte[0], null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitWaiting(Thread t) throws InterruptedException {
        for (int i = 0; i < 3000 && t.getState() != Thread.State.WAITING; i++) Thread.sleep(10);
        assertEquals(Thread.State.WAITING, t.getState(), "the second script should wait on the first download");
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
