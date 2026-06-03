import channels.PendingAttachment;
import channels.TelegramFileDownloader;
import com.sun.net.httpserver.HttpServer;
import models.MessageAttachment;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.*;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import utils.SsrfGuard;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link TelegramFileDownloader} (JCLAW-136). Runs against an
 * embedded {@link HttpServer} playing the role of Telegram's Bot API: first
 * request is {@code /getFile}, second is {@code /file/bot.../file_N.ext}.
 * Covers the happy path, size-limit rejection, and the {@code file_path}
 * resolution error.
 *
 * <p>JCLAW-387 (Section E): the second-step byte download now flows through an
 * SSRF-hardened client ({@link SsrfGuard#SAFE_DNS}) and an
 * {@link SsrfGuard#assertSafeScheme(java.net.URI)} pre-check. Because the embedded
 * test server binds to {@code 127.0.0.1} — a loopback literal the guard
 * deliberately blocks — the happy-path test can't drive the download against a
 * raw loopback URL anymore. It instead swaps {@code DOWNLOAD_CLIENT} for an
 * interceptor-backed client (no socket, no DNS) and uses a non-literal host so
 * {@code assertSafeScheme} passes, mirroring {@code WebFetchToolTest}. The
 * {@code getFile} metadata call still hits the loopback server directly — that
 * leg uses the unguarded {@code HttpFactories.general()} client and is
 * unchanged.
 */
class TelegramFileDownloaderTest extends UnitTest {

    private static final Field DOWNLOAD_CLIENT_FIELD;
    static {
        try {
            // Package-private, non-final test seam (mirrors WebFetchTool.CLIENT).
            DOWNLOAD_CLIENT_FIELD = TelegramFileDownloader.class.getDeclaredField("DOWNLOAD_CLIENT");
            DOWNLOAD_CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpServer server;
    private int port;
    private OkHttpClient originalDownloadClient;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
        originalDownloadClient = (OkHttpClient) DOWNLOAD_CLIENT_FIELD.get(null);
    }

    @AfterEach
    void teardown() throws Exception {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        DOWNLOAD_CLIENT_FIELD.set(null, originalDownloadClient);
    }

    /** Swap the byte-download client for one whose interceptor returns the
     *  given payload bytes without opening a socket (so SAFE_DNS never fires
     *  and the loopback constraint is sidestepped — see class Javadoc). */
    private void installStubDownloadClient(byte[] payload) throws Exception {
        Interceptor stub = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(payload, okhttp3.MediaType.parse("application/octet-stream")))
                .build();
        DOWNLOAD_CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(stub)
                .build());
    }

    @Test
    void downloadsFileIntoStagingOnHappyPath() throws Exception {
        var agent = AgentService.create("tg-downloader-ok", "openrouter", "gpt-4.1");
        var payload = "fake-image-bytes".getBytes();
        installStubDownloadClient(payload);

        server.createContext("/botTOKEN/getFile", exchange -> {
            var body = ("{\"ok\":true,\"result\":{\"file_id\":\"FID\",\"file_path\":\"photos/file_1.jpg\",\"file_size\":"
                    + payload.length + "}}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var pending = new PendingAttachment(
                "FID", "my-photo.jpg", "image/jpeg", payload.length,
                MessageAttachment.KIND_IMAGE);
        // getFile hits the loopback mock (unguarded leg); the byte download
        // uses a non-literal host so assertSafeScheme passes, and the stub
        // interceptor returns the payload without a socket.
        var result = TelegramFileDownloader.download("TOKEN", pending, agent.name,
                "http://127.0.0.1:" + port + "/botTOKEN",
                "http://api.telegram.test/file/botTOKEN");

        assertTrue(result instanceof TelegramFileDownloader.Ok,
                "happy path must return Ok, got: " + result);
        var ok = (TelegramFileDownloader.Ok) result;
        assertEquals("my-photo.jpg", ok.input().originalFilename());
        assertEquals(MessageAttachment.KIND_IMAGE, ok.input().kind());
        assertNotNull(ok.input().attachmentId());

        var stagingDir = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        Path staged = stagingDir.resolve(ok.input().attachmentId() + ".jpg");
        assertTrue(Files.exists(staged),
                "downloaded bytes must land at staging/UUID.jpg, expected: " + staged);
        assertEquals(payload.length, Files.size(staged),
                "downloaded file size must match payload");
    }

    @Test
    void rejectsOversizeFilePerReportedSize() {
        var agent = AgentService.create("tg-downloader-size", "openrouter", "gpt-4.1");

        server.createContext("/botTOKEN/getFile", exchange -> {
            var oversize = TelegramFileDownloader.MAX_FILE_BYTES + 1;
            var body = ("{\"ok\":true,\"result\":{\"file_id\":\"BIG\",\"file_path\":\"videos/huge.mp4\",\"file_size\":"
                    + oversize + "}}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var pending = new PendingAttachment(
                "BIG", "huge.mp4", "video/mp4", 0L, MessageAttachment.KIND_FILE);
        var result = TelegramFileDownloader.download("TOKEN", pending, agent.name,
                "http://127.0.0.1:" + port + "/botTOKEN",
                "http://127.0.0.1:" + port + "/file/botTOKEN");

        assertTrue(result instanceof TelegramFileDownloader.SizeExceeded,
                "oversize must reject early, got: " + result);
        var rejected = (TelegramFileDownloader.SizeExceeded) result;
        assertEquals(TelegramFileDownloader.MAX_FILE_BYTES, rejected.limit());
        assertTrue(rejected.actualBytes() > rejected.limit(),
                "reported actual size must exceed the limit");
    }

    @Test
    void surfacesDownloadFailedWhenGetFileErrors() {
        var agent = AgentService.create("tg-downloader-err", "openrouter", "gpt-4.1");

        server.createContext("/botTOKEN/getFile", exchange -> {
            var body = "{\"ok\":false,\"error_code\":404,\"description\":\"file_id not found\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var pending = new PendingAttachment(
                "MISSING", null, null, 0L, MessageAttachment.KIND_FILE);
        var result = TelegramFileDownloader.download("TOKEN", pending, agent.name,
                "http://127.0.0.1:" + port + "/botTOKEN",
                "http://127.0.0.1:" + port + "/file/botTOKEN");

        assertTrue(result instanceof TelegramFileDownloader.DownloadFailed,
                "getFile ok=false must return DownloadFailed, got: " + result);
    }

    // ── JCLAW-387 (Section E): SSRF guard on the byte-download leg ──

    @Test
    void downloadClientWiresSafeDnsAndDisablesRedirects() {
        // Asserts against the PRODUCTION client (restored in teardown), not the
        // per-test stub. SAFE_DNS is the gate; disabled redirect-following keeps
        // a 302 from bouncing the GET past that gate to a blocked target.
        var client = TelegramFileDownloader.downloadClient();
        assertSame(SsrfGuard.SAFE_DNS, client.dns(),
                "download client must wire SsrfGuard.SAFE_DNS — this is the whole point");
        assertFalse(client.followRedirects(),
                "download client must NOT auto-follow redirects");
        assertFalse(client.followSslRedirects(),
                "download client must NOT auto-follow SSL redirects");
    }

    @Test
    void rejectsDownloadAgainstLoopbackHostBeforeStaging() {
        // A getFile response whose file_base resolves to loopback (the classic
        // SSRF target) must be rejected. With the real DOWNLOAD_CLIENT restored
        // for this test (no stub installed), SAFE_DNS blocks the loopback
        // literal via assertSafeScheme before any socket opens, and no staging
        // file is left behind.
        var agent = AgentService.create("tg-downloader-ssrf", "openrouter", "gpt-4.1");
        var payload = "x".getBytes();

        server.createContext("/botTOKEN/getFile", exchange -> {
            var body = ("{\"ok\":true,\"result\":{\"file_id\":\"FID\",\"file_path\":\"photos/file_1.jpg\",\"file_size\":"
                    + payload.length + "}}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var pending = new PendingAttachment(
                "FID", "my-photo.jpg", "image/jpeg", payload.length,
                MessageAttachment.KIND_IMAGE);
        // file base points at loopback — assertSafeScheme rejects the literal IP.
        var result = TelegramFileDownloader.download("TOKEN", pending, agent.name,
                "http://127.0.0.1:" + port + "/botTOKEN",
                "http://127.0.0.1:" + port + "/file/botTOKEN");

        assertTrue(result instanceof TelegramFileDownloader.DownloadFailed,
                "loopback download target must be rejected, got: " + result);
        var failed = (TelegramFileDownloader.DownloadFailed) result;
        assertTrue(failed.reason().contains("SSRF guard"),
                "rejection must come from the SSRF guard; got: " + failed.reason());

        var stagingDir = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        assertTrue(!Files.exists(stagingDir) || isEmptyDir(stagingDir),
                "no partial staging file should survive a guard rejection");
    }

    private static boolean isEmptyDir(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    // Extension extraction tests moved to UtilsFilenamesTest after JCLAW-136
    // consolidated the helper into utils.Filenames.extensionOf.
}
