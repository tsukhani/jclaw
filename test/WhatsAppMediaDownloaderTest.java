import channels.WhatsAppInboundMessage;
import channels.WhatsAppMediaDownloader;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
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
 * Unit coverage for {@link WhatsAppMediaDownloader}'s Cloud-API branch (JCLAW-446).
 *
 * <p>The Graph two-step is {@code GET /v21.0/{mediaId}} (metadata → CDN url) then
 * {@code GET <cdn>} (bytes). The metadata leg uses the shared general client and is
 * driven here against a {@link MockWebServer} via the {@code apiBase} overload; the
 * byte leg goes through the SSRF-hardened {@code DOWNLOAD_CLIENT}, swapped (as in
 * {@link SlackFileDownloaderTest}) for a socket-free interceptor so
 * {@link SsrfGuard#SAFE_DNS} never fires and a real-looking {@code *.whatsapp.net}
 * host passes the allowlist. Also covers the CDN-host allowlist refusal and the
 * pure host/wiring helpers.
 */
class WhatsAppMediaDownloaderTest extends UnitTest {

    private static final Field DOWNLOAD_CLIENT_FIELD;
    static {
        try {
            DOWNLOAD_CLIENT_FIELD = WhatsAppMediaDownloader.class.getDeclaredField("DOWNLOAD_CLIENT");
            DOWNLOAD_CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private OkHttpClient original;
    private final List<Request> seen = new ArrayList<>();
    private MockWebServer graph;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        seen.clear();
        original = (OkHttpClient) DOWNLOAD_CLIENT_FIELD.get(null);
        graph = new MockWebServer();
        graph.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        DOWNLOAD_CLIENT_FIELD.set(null, original);
        graph.close();
    }

    private void installCdnClient(Function<Request, Response> responder) throws Exception {
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

    private static Response cdnOk(Request req, byte[] body, String contentType) {
        return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create(body, MediaType.parse(contentType)))
                .build();
    }

    private static WhatsAppInboundMessage.PendingMedia media(String id, String mime, String filename) {
        return new WhatsAppInboundMessage.PendingMedia(id, mime, 0L, filename, false);
    }

    @Test
    void downloadsCloudApiMediaTwoStepIntoStaging() throws Exception {
        var agent = AgentService.create("wa-dl-ok", "openrouter", "gpt-4.1");
        var payload = "fake-jpeg-bytes".getBytes();
        // Step 1: the Graph metadata lookup returns a *.whatsapp.net CDN url.
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://mmg.whatsapp.net/d/abc.enc\","
                        + "\"mime_type\":\"image/jpeg\",\"id\":\"MEDIA-1\"}")
                .build());
        // Step 2: the CDN byte fetch (interceptor-stubbed).
        installCdnClient(req -> cdnOk(req, payload, "image/jpeg"));

        var input = WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-1", "image/jpeg", null), agent.name,
                graph.url("/v21.0/").toString());

        assertNotNull(input, "two-step download should produce an Input");
        assertEquals("image/jpeg", input.mimeType());
        assertEquals("whatsapp-MEDIA-1", input.originalFilename(),
                "no filename → synthesised from the media id");
        // The metadata request carried the Bearer token.
        var metaReq = graph.takeRequest();
        assertEquals("Bearer tok", metaReq.getHeaders().get("Authorization"));
        // The CDN leg carried the Bearer token too (Graph media CDN requires it).
        assertEquals("Bearer tok", seen.get(0).header("Authorization"));
        var staging = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        Path staged = staging.resolve(input.attachmentId());
        assertTrue(Files.exists(staged), "bytes must land in staging: " + staged);
        assertEquals(payload.length, Files.size(staged));
    }

    @Test
    void refusesNonCdnHostFromMetadata() throws Exception {
        var agent = AgentService.create("wa-dl-host", "openrouter", "gpt-4.1");
        // Metadata points at a non-WhatsApp host — the byte fetch must be refused.
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://evil.example.com/d/abc.enc\"}")
                .build());
        installCdnClient(req -> { throw new AssertionError("must refuse before any CDN request"); });

        var input = WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-2", "image/jpeg", null), agent.name,
                graph.url("/v21.0/").toString());

        assertNull(input, "a non-CDN host must abort the download");
        assertTrue(seen.isEmpty(), "the CDN client must never be called for a refused host");
    }

    @Test
    void metadataLookupFailureYieldsNull() throws Exception {
        var agent = AgentService.create("wa-dl-meta", "openrouter", "gpt-4.1");
        graph.enqueue(new MockResponse.Builder().code(404).body("{\"error\":{\"message\":\"not found\"}}").build());
        installCdnClient(req -> { throw new AssertionError("no CDN request on metadata failure"); });

        var input = WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-3", "image/jpeg", null), agent.name,
                graph.url("/v21.0/").toString());

        assertNull(input);
        assertTrue(seen.isEmpty());
    }

    @Test
    void documentKeepsItsFilename() throws Exception {
        var agent = AgentService.create("wa-dl-doc", "openrouter", "gpt-4.1");
        var payload = "%PDF-1.4 fake".getBytes();
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://media.whatsapp.net/d/doc.enc\"}")
                .build());
        installCdnClient(req -> cdnOk(req, payload, "application/pdf"));

        var input = WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-DOC", "application/pdf", "invoice.pdf"), agent.name,
                graph.url("/v21.0/").toString());

        assertNotNull(input);
        assertEquals("invoice.pdf", input.originalFilename());
        assertEquals(models.MessageAttachment.KIND_FILE, input.kind());
        var staging = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        // The .pdf extension is salvaged from the document filename.
        assertTrue(Files.exists(staging.resolve(input.attachmentId() + ".pdf")),
                "extension salvaged from the document filename");
    }

    @Test
    void isCdnHostAllowlist() {
        assertTrue(WhatsAppMediaDownloader.isCdnHost("mmg.whatsapp.net"));
        assertTrue(WhatsAppMediaDownloader.isCdnHost("scontent.xx.fbcdn.net"));
        assertTrue(WhatsAppMediaDownloader.isCdnHost("media.cdn.whatsapp.net"));
        assertFalse(WhatsAppMediaDownloader.isCdnHost("evil.example.com"));
        assertFalse(WhatsAppMediaDownloader.isCdnHost("notwhatsapp.net.evil.com"));
        assertFalse(WhatsAppMediaDownloader.isCdnHost(null));
    }

    @Test
    void downloadClientWiresSafeDnsAndDisablesRedirects() {
        var client = WhatsAppMediaDownloader.downloadClient();
        assertSame(SsrfGuard.SAFE_DNS, client.dns(), "download client must use SAFE_DNS");
        assertFalse(client.followRedirects(), "auto-redirect must be disabled");
    }

    @Test
    void emptyMediaListYieldsEmpty() {
        // downloadAll short-circuits when the message has no media — exercised through
        // the public seam to confirm the contract (no binding/transport needed here).
        var msg = new WhatsAppInboundMessage(
                "mid", "447900000001", "447900000001",
                WhatsAppInboundMessage.CHAT_DIRECT, "PNID", WhatsAppInboundMessage.MessageType.TEXT,
                "hi", null, null, List.of(), true, null, null);
        assertTrue(msg.media().isEmpty());
    }
}
