import channels.TelegramChannel;
import channels.TelegramFileDownloader;
import com.sun.net.httpserver.HttpServer;
import models.Agent;
import models.MessageAttachment;
import org.junit.jupiter.api.*;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link TelegramFileDownloader} (JCLAW-136). Runs against an
 * embedded {@link HttpServer} playing the role of Telegram's Bot API: first
 * request is {@code /getFile}, second is {@code /file/bot.../file_N.ext}.
 * Covers the happy path, size-limit rejection, and the {@code file_path}
 * resolution error.
 */
public class TelegramFileDownloaderTest extends UnitTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void teardown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void downloadsFileIntoStagingOnHappyPath() throws Exception {
        var agent = AgentService.create("tg-downloader-ok", "openrouter", "gpt-4.1");
        var payload = "fake-image-bytes".getBytes();

        server.createContext("/botTOKEN/getFile", exchange -> {
            var body = ("{\"ok\":true,\"result\":{\"file_id\":\"FID\",\"file_path\":\"photos/file_1.jpg\",\"file_size\":"
                    + payload.length + "}}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/file/botTOKEN/photos/file_1.jpg", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        var pending = new TelegramChannel.PendingAttachment(
                "FID", "my-photo.jpg", "image/jpeg", payload.length,
                MessageAttachment.KIND_IMAGE);
        var result = TelegramFileDownloader.download("TOKEN", pending, agent.name,
                "http://127.0.0.1:" + port + "/botTOKEN",
                "http://127.0.0.1:" + port + "/file/botTOKEN");

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
    public void rejectsOversizeFilePerReportedSize() throws Exception {
        var agent = AgentService.create("tg-downloader-size", "openrouter", "gpt-4.1");

        server.createContext("/botTOKEN/getFile", exchange -> {
            var oversize = TelegramFileDownloader.MAX_FILE_BYTES + 1;
            var body = ("{\"ok\":true,\"result\":{\"file_id\":\"BIG\",\"file_path\":\"videos/huge.mp4\",\"file_size\":"
                    + oversize + "}}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var pending = new TelegramChannel.PendingAttachment(
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
    public void surfacesDownloadFailedWhenGetFileErrors() throws Exception {
        var agent = AgentService.create("tg-downloader-err", "openrouter", "gpt-4.1");

        server.createContext("/botTOKEN/getFile", exchange -> {
            var body = "{\"ok\":false,\"error_code\":404,\"description\":\"file_id not found\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var pending = new TelegramChannel.PendingAttachment(
                "MISSING", null, null, 0L, MessageAttachment.KIND_FILE);
        var result = TelegramFileDownloader.download("TOKEN", pending, agent.name,
                "http://127.0.0.1:" + port + "/botTOKEN",
                "http://127.0.0.1:" + port + "/file/botTOKEN");

        assertTrue(result instanceof TelegramFileDownloader.DownloadFailed,
                "getFile ok=false must return DownloadFailed, got: " + result);
    }

    // Extension extraction tests moved to UtilsFilenamesTest after JCLAW-136
    // consolidated the helper into utils.Filenames.extensionOf.
}
