import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.caption.VlmModel;
import services.caption.VlmModelManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-213 download semantics for {@link VlmModelManager} in isolation: streamed GET → SHA256
 * verification against the model's <b>pinned</b> manifest hash, partial-file cleanup on mismatch,
 * multi-file availability/status reconciliation, and concurrent-call coalescing. Uses
 * {@code mockwebserver3} (already on the classpath) so the manager makes real socket calls to a
 * server we control.
 */
class VlmModelManagerTest extends UnitTest {

    private MockWebServer server;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tempRoot = Files.createTempDirectory("vlm-model-test-");
        VlmModelManager.resetForTest();
        VlmModelManager.setRootForTest(tempRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        VlmModelManager.resetForTest();
        server.close();
        deleteRecursive(tempRoot);
    }

    @Test
    void downloadFile_succeeds_whenSha256MatchesPinnedHash() throws Exception {
        var body = "fake-onnx-graph-bytes-of-arbitrary-length".getBytes();
        var sha256 = sha256Hex(body);
        enqueueGet(body);

        var url = server.url("/encoder.onnx").toString();
        var dest = tempRoot.resolve("vit-gpt2").resolve("encoder.onnx");
        var path = VlmModelManager.doDownloadFile("vit-gpt2", url, sha256, dest, null);

        assertEquals(dest, path);
        assertTrue(Files.isRegularFile(dest), "file should exist after a verified download");
        assertArrayEquals(body, Files.readAllBytes(dest), "contents must match what the server sent");
    }

    @Test
    void downloadFile_throwsAndDeletesPartial_onSha256Mismatch() throws Exception {
        var body = "the-real-bytes-streamed".getBytes();
        var lyingHash = sha256Hex("a-different-payload".getBytes());
        enqueueGet(body);

        var url = server.url("/encoder.onnx").toString();
        var dest = tempRoot.resolve("vit-gpt2").resolve("encoder.onnx");
        var io = assertThrows(IOException.class,
                () -> VlmModelManager.doDownloadFile("vit-gpt2", url, lyingHash, dest, null),
                "a pinned-hash mismatch must throw");
        assertTrue(io.getMessage().contains("SHA256 mismatch"), io.getMessage());
        assertFalse(Files.exists(dest), "final file must not exist on hash failure");
        assertFalse(Files.exists(dest.resolveSibling("encoder.onnx.part")),
                "partial file must be cleaned up on hash failure");
    }

    @Test
    void status_isAvailableOnlyWhenAllManifestFilesPresent() throws Exception {
        var dir = VlmModelManager.localDir(VlmModel.VIT_GPT2);
        Files.createDirectories(dir);
        assertEquals(VlmModelManager.State.ABSENT, VlmModelManager.status(VlmModel.VIT_GPT2).state(),
                "no files → ABSENT");

        // Write all manifest files → AVAILABLE; remove one → back to ABSENT.
        for (var f : VlmModel.VIT_GPT2.files()) {
            Files.write(dir.resolve(f.localName()), "x".getBytes());
        }
        assertEquals(VlmModelManager.State.AVAILABLE, VlmModelManager.status(VlmModel.VIT_GPT2).state(),
                "all manifest files present → AVAILABLE");

        Files.delete(dir.resolve(VlmModel.VIT_GPT2.files().getFirst().localName()));
        assertEquals(VlmModelManager.State.ABSENT, VlmModelManager.status(VlmModel.VIT_GPT2).state(),
                "a missing file must reconcile back to ABSENT (stale AVAILABLE evicted)");
    }

    @Test
    void delete_removesAllManifestFilesAndDir() throws Exception {
        var dir = VlmModelManager.localDir(VlmModel.VIT_GPT2);
        Files.createDirectories(dir);
        for (var f : VlmModel.VIT_GPT2.files()) {
            Files.write(dir.resolve(f.localName()), "x".getBytes());
        }
        assertTrue(VlmModelManager.availableLocally(VlmModel.VIT_GPT2), "precondition: downloaded");

        assertTrue(VlmModelManager.delete(VlmModel.VIT_GPT2), "delete reports removal");
        assertFalse(Files.exists(dir), "model dir removed");
        assertFalse(VlmModelManager.availableLocally(VlmModel.VIT_GPT2), "files gone after delete");
        assertEquals(VlmModelManager.State.ABSENT, VlmModelManager.status(VlmModel.VIT_GPT2).state(),
                "status reconciles to ABSENT after delete");
    }

    @Test
    void delete_isNoOpWhenAbsent() throws Exception {
        assertFalse(VlmModelManager.delete(VlmModel.VIT_GPT2),
                "deleting a never-downloaded model returns false (no-op)");
    }

    @Test
    void ensureAvailable_coalescesConcurrentCallers_toSingleDownload() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var hits = new AtomicInteger();
        var sentinel = new CompletableFuture<Path>();
        VlmModelManager.putInFlightForTest(VlmModel.VIT_GPT2.id(), sentinel);

        Runnable caller = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException _) { }
            if (VlmModelManager.ensureAvailable(VlmModel.VIT_GPT2, null) == sentinel) hits.incrementAndGet();
        };
        var t1 = Thread.ofVirtual().start(caller);
        var t2 = Thread.ofVirtual().start(caller);

        assertTrue(ready.await(2, TimeUnit.SECONDS), "both threads should reach the gate");
        go.countDown();
        t1.join(2000);
        t2.join(2000);
        assertEquals(2, hits.get(),
                "both concurrent callers must receive the in-flight future, not a fresh download");
    }

    private void enqueueGet(byte[] body) {
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
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException _) { } });
        }
    }
}
