import channels.SlackFileDownloader;
import channels.SlackPendingFile;
import models.MessageAttachment;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import utils.SsrfGuard;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Unit tests for {@link SlackFileDownloader} (JCLAW-344). Slack's
 * {@code url_private_download} is a direct byte URL (no getFile indirection), so
 * the download is a single GET with a Bearer token. As with
 * {@code TelegramFileDownloaderTest}, the SSRF-hardened {@code DOWNLOAD_CLIENT} is
 * swapped for an interceptor client that returns canned responses without a
 * socket — so {@link SsrfGuard#SAFE_DNS} never fires and a non-literal Slack host
 * (e.g. {@code files.slack.com}) makes {@link SsrfGuard#assertSafeScheme} pass.
 * Covers the happy path, the {@code slack_audio} MIME remap, size + host + HTML
 * rejections, and the CDN redirect (auth dropped cross-origin).
 */
class SlackFileDownloaderTest extends UnitTest {

    private static final Field DOWNLOAD_CLIENT_FIELD;
    static {
        try {
            // Package-private, non-final test seam (mirrors WebFetchTool.CLIENT).
            DOWNLOAD_CLIENT_FIELD = SlackFileDownloader.class.getDeclaredField("DOWNLOAD_CLIENT");
            DOWNLOAD_CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private OkHttpClient original;
    private final List<Request> seen = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        seen.clear();
        original = (OkHttpClient) DOWNLOAD_CLIENT_FIELD.get(null);
    }

    @AfterEach
    void teardown() throws Exception {
        DOWNLOAD_CLIENT_FIELD.set(null, original);
    }

    /** Swap DOWNLOAD_CLIENT for an interceptor client that records each request and
     *  returns the responder's canned response — no socket, so SAFE_DNS never
     *  fires. followRedirects(false) so a 302 reaches the downloader's manual hop. */
    private void install(Function<Request, Response> responder) throws Exception {
        Interceptor stub = chain -> {
            seen.add(chain.request());
            return responder.apply(chain.request());
        };
        DOWNLOAD_CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(stub)
                .followRedirects(false)
                .followSslRedirects(false)
                .build());
    }

    private static Response ok200(Request req, byte[] body, String contentType) {
        return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create(body, MediaType.parse(contentType)))
                .build();
    }

    private static SlackPendingFile file(String url, String name, String mime, long size, String subtype) {
        return new SlackPendingFile("F1", url, name, mime, size, subtype);
    }

    @Test
    void downloadsImageIntoStagingOnHappyPath() throws Exception {
        var agent = AgentService.create("sk-dl-ok", "openrouter", "gpt-4.1");
        var payload = "fake-png-bytes".getBytes();
        install(req -> ok200(req, payload, "image/png"));

        var result = SlackFileDownloader.download("xoxb-t",
                file("https://files.slack.com/files-pri/T1/F1/photo.png", "photo.png", "image/png", payload.length, null),
                agent.name);

        assertTrue(result instanceof SlackFileDownloader.Ok, "happy path → Ok, got: " + result);
        var ok = (SlackFileDownloader.Ok) result;
        assertEquals("photo.png", ok.input().originalFilename());
        assertEquals(MessageAttachment.KIND_IMAGE, ok.input().kind());
        assertEquals("image/png", ok.input().mimeType());
        assertEquals("Bearer xoxb-t", seen.get(0).header("Authorization"),
                "bot token attached on the same-host download");
        var staging = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        Path staged = staging.resolve(ok.input().attachmentId() + ".png");
        assertTrue(Files.exists(staged), "bytes must land at staging/UUID.png: " + staged);
        assertEquals(payload.length, Files.size(staged));
    }

    @Test
    void slackAudioVideoMimeRemappedToAudio() throws Exception {
        var agent = AgentService.create("sk-dl-audio", "openrouter", "gpt-4.1");
        var payload = "voice".getBytes();
        // Slack serves voice clips as a video/* container; the downloader uses the
        // actual response Content-Type and remaps video/* → audio/* for slack_audio.
        install(req -> ok200(req, payload, "video/mp4"));

        var result = SlackFileDownloader.download("xoxb-t",
                file("https://files.slack.com/files-pri/T1/F2/clip.mp4", "clip.mp4", "video/mp4", payload.length, "slack_audio"),
                agent.name);

        assertTrue(result instanceof SlackFileDownloader.Ok, "→ Ok, got: " + result);
        var ok = (SlackFileDownloader.Ok) result;
        assertEquals("audio/mp4", ok.input().mimeType(), "slack_audio video/* must remap to audio/*");
        assertEquals(MessageAttachment.KIND_AUDIO, ok.input().kind());
    }

    @Test
    void rejectsOversizeFromReportedSize() throws Exception {
        var agent = AgentService.create("sk-dl-size", "openrouter", "gpt-4.1");
        install(req -> { throw new AssertionError("must reject before any request"); });

        var result = SlackFileDownloader.download("xoxb-t",
                file("https://files.slack.com/files-pri/T1/F3/big.bin", "big.bin", "application/octet-stream",
                        SlackFileDownloader.MAX_FILE_BYTES + 1, null),
                agent.name);

        assertTrue(result instanceof SlackFileDownloader.SizeExceeded, "oversize → SizeExceeded, got: " + result);
        assertTrue(seen.isEmpty(), "must reject from reported size without a request");
    }

    @Test
    void refusesNonSlackHost() throws Exception {
        var agent = AgentService.create("sk-dl-host", "openrouter", "gpt-4.1");
        install(req -> { throw new AssertionError("must refuse before any request"); });

        var result = SlackFileDownloader.download("xoxb-t",
                file("https://evil.example.com/files/photo.png", "photo.png", "image/png", 10, null),
                agent.name);

        assertTrue(result instanceof SlackFileDownloader.DownloadFailed, "non-Slack host → DownloadFailed, got: " + result);
        assertTrue(seen.isEmpty(), "must refuse the host before attaching the token / opening a socket");
    }

    @Test
    void rejectsHtmlLoginPage() throws Exception {
        var agent = AgentService.create("sk-dl-html", "openrouter", "gpt-4.1");
        // files:read missing → Slack serves an HTML login page instead of bytes.
        install(req -> ok200(req, "<!doctype html><html>login</html>".getBytes(), "text/html; charset=utf-8"));

        var result = SlackFileDownloader.download("xoxb-t",
                file("https://files.slack.com/files-pri/T1/F4/x.png", "x.png", "image/png", 20, null),
                agent.name);

        assertTrue(result instanceof SlackFileDownloader.DownloadFailed, "HTML page → DownloadFailed, got: " + result);
    }

    @Test
    void followsSlackCdnRedirectOnceDroppingAuth() throws Exception {
        var agent = AgentService.create("sk-dl-redir", "openrouter", "gpt-4.1");
        var payload = "cdn-bytes".getBytes();
        install(req -> {
            if (req.url().host().equals("files.slack.com")) {
                return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(302).message("Found")
                        .header("Location", "https://files.slack-edge.com/cdn/photo.png")
                        .body(ResponseBody.create("".getBytes(), MediaType.parse("text/plain")))
                        .build();
            }
            return ok200(req, payload, "image/png");
        });

        var result = SlackFileDownloader.download("xoxb-t",
                file("https://files.slack.com/files-pri/T1/F5/photo.png", "photo.png", "image/png", payload.length, null),
                agent.name);

        assertTrue(result instanceof SlackFileDownloader.Ok, "CDN redirect → Ok, got: " + result);
        assertEquals(2, seen.size(), "exactly one redirect hop");
        assertEquals("Bearer xoxb-t", seen.get(0).header("Authorization"), "auth on the first (slack.com) leg");
        assertNull(seen.get(1).header("Authorization"), "auth dropped on the cross-origin CDN leg");
    }

    @Test
    void downloadClientWiresSafeDnsAndDisablesRedirects() {
        // The production client (restored in teardown) must carry the SSRF DNS gate
        // and not auto-follow redirects (we hop manually past the host allowlist).
        var client = SlackFileDownloader.downloadClient();
        assertSame(SsrfGuard.SAFE_DNS, client.dns(), "download client must use SAFE_DNS");
        assertFalse(client.followRedirects(), "auto-redirect must be disabled");
    }
}
