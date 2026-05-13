import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.WhisperModel;
import services.transcription.WhisperModelManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link WhisperModelManager} download semantics in isolation:
 * the HEAD-then-GET dance, SHA256 verification against the
 * {@code X-Linked-Etag} HF header, and concurrent-call coalescing.
 *
 * <p>Uses {@code mockwebserver3} (already on the classpath for OkHttp 5
 * SSE tests) so the manager makes real socket connections to a server we
 * control — that exercises the OkHttp path end-to-end rather than mocking
 * the client out.
 */
class WhisperModelManagerTest extends UnitTest {

    private MockWebServer server;
    private Path tempRoot;
    private static final WhisperModel MODEL = WhisperModel.BASE_EN;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tempRoot = Files.createTempDirectory("whisper-model-test-");
        WhisperModelManager.resetForTest();
        WhisperModelManager.setRootForTest(tempRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        WhisperModelManager.resetForTest();
        server.close();
        deleteRecursive(tempRoot);
    }

    @Test
    void download_succeeds_whenSha256MatchesHfEtag() throws Exception {
        var body = "fake-ggml-model-bytes-payload-of-arbitrary-length".getBytes();
        var sha256 = sha256Hex(body);

        enqueueHeadResponse(sha256, body.length);
        enqueueGetResponse(body);

        var url = server.url("/" + MODEL.filename()).toString();
        var path = WhisperModelManager.doDownload(MODEL, url, null);

        assertEquals(WhisperModelManager.localPath(MODEL), path,
                "download writes to the manager's resolved local path");
        assertTrue(Files.isRegularFile(path), "file should exist after successful download");
        assertArrayEquals(body, Files.readAllBytes(path),
                "file contents should match exactly what the mock server sent");
        var status = WhisperModelManager.status(MODEL);
        assertEquals(WhisperModelManager.State.AVAILABLE, status.state(),
                "status should land on AVAILABLE after a successful download");
    }

    @Test
    void download_throwsAndDeletesPartial_onSha256Mismatch() throws Exception {
        var body = "the-real-bytes-the-server-streams-to-us".getBytes();
        var lyingHash = sha256Hex("a-completely-different-payload-than-what-was-sent".getBytes());

        enqueueHeadResponse(lyingHash, body.length);
        enqueueGetResponse(body);

        var url = server.url("/" + MODEL.filename()).toString();
        var io = assertThrows(IOException.class,
                () -> WhisperModelManager.doDownload(MODEL, url, null),
                "hash mismatch must throw");
        assertTrue(io.getMessage().contains("SHA256 mismatch"),
                "error message should name the SHA256 mismatch: " + io.getMessage());
        assertFalse(Files.exists(WhisperModelManager.localPath(MODEL)),
                "final file must not exist on hash failure");
        assertFalse(Files.exists(tempRoot.resolve(MODEL.filename() + ".part")),
                "partial file must be cleaned up on hash failure");
    }

    @Test
    void status_dropsStaleAvailableCache_whenFileIsRemoved() throws Exception {
        // Simulate a successful prior download by seeding the cache and the
        // file together — same shape doDownload would leave behind.
        var bytes = "previously-downloaded-model-bytes".getBytes();
        var path = WhisperModelManager.localPath(MODEL);
        Files.createDirectories(tempRoot);
        Files.write(path, bytes);

        var seeded = WhisperModelManager.status(MODEL);
        assertEquals(WhisperModelManager.State.AVAILABLE, seeded.state(),
                "baseline: cache reports AVAILABLE while file exists");

        // Operator action: rm the file from the data directory.
        Files.delete(path);

        var afterDelete = WhisperModelManager.status(MODEL);
        assertEquals(WhisperModelManager.State.ABSENT, afterDelete.state(),
                "status() must reconcile against filesystem and report ABSENT");

        // Subsequent polls also see ABSENT — the stale AVAILABLE entry was
        // evicted, not just shadowed.
        var followup = WhisperModelManager.status(MODEL);
        assertEquals(WhisperModelManager.State.ABSENT, followup.state(),
                "stale cache entry must be evicted, not just shadowed");
    }

    @Test
    void status_promotesToAvailable_whenFileAppearsOutOfBand() throws Exception {
        // Operator copies a model file from another machine into the data
        // directory while the JVM is running. No download was ever issued, so
        // the cache has no entry for this model — but status() should still
        // pick up the file and report AVAILABLE without needing a restart.
        var bytes = "manually-placed-model-bytes-of-arbitrary-length".getBytes();
        var path = WhisperModelManager.localPath(MODEL);
        Files.createDirectories(tempRoot);

        var beforePlace = WhisperModelManager.status(MODEL);
        assertEquals(WhisperModelManager.State.ABSENT, beforePlace.state(),
                "baseline: no file, no cache → ABSENT");

        Files.write(path, bytes);

        var afterPlace = WhisperModelManager.status(MODEL);
        assertEquals(WhisperModelManager.State.AVAILABLE, afterPlace.state(),
                "out-of-band file placement must be picked up without a restart");
        assertEquals(bytes.length, afterPlace.totalBytes(),
                "totalBytes should reflect the actual file size");
    }

    @Test
    void ensureAvailable_coalescesConcurrentCallers_toSingleDownload() throws Exception {
        // Two callers arrive at ensureAvailable concurrently with an
        // in-flight future already seeded; both must receive that same
        // future instance rather than each kicking off a fresh download.
        // The test threads compare references and exit — they never call
        // f.get(), so the seeded sentinel intentionally stays incomplete.
        // No file is written under tempRoot, so availableLocally(model)
        // returns false on every call and the inFlight.computeIfAbsent
        // path is the only one exercised.
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var hits = new AtomicInteger();

        var sentinel = new CompletableFuture<Path>();
        WhisperModelManager.putInFlightForTest(MODEL.id(), sentinel);

        var t1 = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) {}
            var f = WhisperModelManager.ensureAvailable(MODEL, null);
            if (f == sentinel) hits.incrementAndGet();
        });
        var t2 = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) {}
            var f = WhisperModelManager.ensureAvailable(MODEL, null);
            if (f == sentinel) hits.incrementAndGet();
        });

        assertTrue(ready.await(2, TimeUnit.SECONDS), "both threads should reach the gate");
        go.countDown();

        t1.join(2000);
        t2.join(2000);

        assertEquals(2, hits.get(),
                "both concurrent ensureAvailable callers must receive the in-flight CompletableFuture, "
                        + "not a fresh one");
    }

    private void enqueueHeadResponse(String sha256, long size) {
        // mockwebserver3 distinguishes HEAD vs GET by the request method;
        // the response body is irrelevant for HEAD. We only need the headers.
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("X-Linked-Etag", "\"" + sha256 + "\"")
                .addHeader("X-Linked-Size", String.valueOf(size))
                .build());
    }

    private void enqueueGetResponse(byte[] body) {
        var buf = new Buffer();
        buf.write(ByteString.of(body));
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/octet-stream")
                .body(buf)
                .build());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
