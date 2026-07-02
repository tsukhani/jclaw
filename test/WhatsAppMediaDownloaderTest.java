import channels.WhatsAppCobaltRunner;
import channels.WhatsAppCobaltSession;
import channels.WhatsAppInboundMessage;
import channels.WhatsAppMediaDownloader;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // ── downloadAll dispatch (JCLAW-446/450) ──
    //
    // The Cloud-API branch of downloadAll calls downloadOne with the REAL Graph
    // API_BASE (no apiBase seam at the dispatch level), so only the paths that
    // return before the metadata HTTP call — no token, blank media ids — are
    // driven through it here. The two-step network logic itself is exercised
    // above through the public downloadOne(apiBase) seam.

    @Test
    void downloadAllYieldsEmptyWhenMessageHasNoMedia() {
        var binding = cloudBinding("tok");
        assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msgWithMedia(List.of()), "wa-dl-none").isEmpty(),
                "an empty media list must short-circuit to no inputs");
        assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msgWithMedia(null), "wa-dl-none").isEmpty(),
                "a null media list must short-circuit to no inputs");
    }

    @Test
    void downloadAllCloudApiWithoutATokenStagesNothing() {
        var binding = cloudBinding(null);
        var msg = msgWithMedia(List.of(media("MEDIA-X", "image/jpeg", null)));
        services.EventLogger.clear();
        assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msg, "wa-dl-notoken").isEmpty(),
                "a binding with no access token must skip every part (no way to authenticate)");
        binding.accessToken = "   ";
        assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msg, "wa-dl-notoken").isEmpty(),
                "a blank token is as unusable as a missing one");
        services.EventLogger.flush();
        // The guard's log line distinguishes "guard fired before any HTTP"
        // from "guard deleted and a live graph.facebook.com call failed" —
        // downloadAll returns empty either way, so the result alone can't.
        assertEquals(2L, models.EventLog.count("category = ?1 AND message LIKE ?2",
                        "channel", "%binding has no access token%"),
                "each guarded call must log the skip, proving no wire attempt was made");
    }

    @Test
    void downloadAllCloudApiSkipsPartsWithoutAMediaId() {
        var binding = cloudBinding("tok");
        // 10 parts (over the 8-file cap) that all lack a media id: each is skipped
        // before any Graph call, and the result is empty rather than a throw.
        var parts = new ArrayList<WhatsAppInboundMessage.PendingMedia>();
        for (int i = 0; i < 10; i++) {
            parts.add(media(i % 2 == 0 ? "" : null, "image/jpeg", null));
        }
        assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msgWithMedia(parts), "wa-dl-blank").isEmpty());
    }

    // ── resolveCdnUrl (step 1) failure mapping ──

    @Test
    void metadataWithoutAUsableUrlYieldsNull() throws Exception {
        var agent = AgentService.create("wa-dl-nourl", "openrouter", "gpt-4.1");
        installCdnClient(req -> { throw new AssertionError("no CDN request without a CDN url"); });
        var apiBase = graph.url("/v21.0/").toString();

        graph.enqueue(new MockResponse.Builder().code(200).body("{\"id\":\"MEDIA-4\"}").build());
        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-4", "image/jpeg", null), agent.name, apiBase),
                "metadata without a url key must abort the part");

        graph.enqueue(new MockResponse.Builder().code(200).body("{\"url\":null}").build());
        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-5", "image/jpeg", null), agent.name, apiBase),
                "a JSON-null url must abort the part");

        graph.enqueue(new MockResponse.Builder().code(200).body("{\"url\":\"   \"}").build());
        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-6", "image/jpeg", null), agent.name, apiBase),
                "a blank url must abort the part");
        assertTrue(seen.isEmpty(), "the CDN client must never fire for an unusable url");
    }

    @Test
    void malformedMetadataJsonYieldsNull() throws Exception {
        var agent = AgentService.create("wa-dl-badjson", "openrouter", "gpt-4.1");
        installCdnClient(req -> { throw new AssertionError("no CDN request on a metadata parse error"); });
        graph.enqueue(new MockResponse.Builder().code(200).body("not-json{").build());

        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-7", "image/jpeg", null), agent.name,
                        graph.url("/v21.0/").toString()),
                "a Gson parse failure must be caught and mapped to a skipped part, not thrown");
        assertTrue(seen.isEmpty());
    }

    @Test
    void metadataConnectionFailureYieldsNull() throws Exception {
        var agent = AgentService.create("wa-dl-conn", "openrouter", "gpt-4.1");
        installCdnClient(req -> { throw new AssertionError("no CDN request when the Graph host is down"); });
        var dead = new MockWebServer();
        dead.start();
        var deadBase = dead.url("/v21.0/").toString();
        dead.close(); // nothing listening → connect fails fast

        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-8", "image/jpeg", null), agent.name, deadBase),
                "a connection failure on the metadata leg must be caught, not thrown");
        assertTrue(seen.isEmpty());
    }

    // ── streamCdnToStaging (step 2) failure mapping ──

    @Test
    void cdnHttpErrorYieldsNullAndLeavesNoStagedFile() throws Exception {
        var agent = AgentService.create("wa-dl-cdn500", "openrouter", "gpt-4.1");
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://mmg.whatsapp.net/d/gone.enc\"}")
                .build());
        installCdnClient(req -> cdnResponse(req, 500, "cdn exploded".getBytes()));

        assertNull(WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-9", "image/jpeg", null), agent.name,
                graph.url("/v21.0/").toString()));
        assertEquals(1, seen.size(), "the CDN leg was attempted exactly once");
        assertStagingEmpty(agent.name);
    }

    @Test
    void cdnIoFailureYieldsNullAndLeavesNoStagedFile() throws Exception {
        var agent = AgentService.create("wa-dl-cdnio", "openrouter", "gpt-4.1");
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://mmg.whatsapp.net/d/flaky.enc\"}")
                .build());
        installFailingCdnClient("simulated mid-stream I/O failure");

        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-10", "image/jpeg", null), agent.name,
                        graph.url("/v21.0/").toString()),
                "an I/O failure while streaming must be caught and mapped to a skipped part");
        assertStagingEmpty(agent.name);
    }

    @Test
    void oversizeCdnPayloadIsDroppedAndCleaned() throws Exception {
        var agent = AgentService.create("wa-dl-oversize", "openrouter", "gpt-4.1");
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://mmg.whatsapp.net/d/huge.enc\"}")
                .build());
        // One byte over the 20 MiB Cloud-API inbound cap.
        var big = new byte[20 * 1024 * 1024 + 1];
        installCdnClient(req -> cdnOk(req, big, "video/mp4"));

        assertNull(WhatsAppMediaDownloader.downloadOne(
                        "tok", media("MEDIA-BIG", "video/mp4", null), agent.name,
                        graph.url("/v21.0/").toString()),
                "a payload over the 20 MiB cap must be dropped");
        assertStagingEmpty(agent.name);
    }

    // ── Input shaping ──

    @Test
    void mimeParametersAreStrippedFromTheStagedInput() throws Exception {
        var agent = AgentService.create("wa-dl-mimeparam", "openrouter", "gpt-4.1");
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://mmg.whatsapp.net/d/img.enc\"}")
                .build());
        installCdnClient(req -> cdnOk(req, "img".getBytes(), "image/jpeg"));

        var input = WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-P", "image/jpeg; charset=utf-8", null), agent.name,
                graph.url("/v21.0/").toString());

        assertNotNull(input);
        assertEquals("image/jpeg", input.mimeType(), "MIME parameters must be stripped");
        assertEquals(models.MessageAttachment.KIND_IMAGE, input.kind(),
                "the kind derives from the stripped MIME");
    }

    @Test
    void missingMimeFallsBackToOctetStream() throws Exception {
        var agent = AgentService.create("wa-dl-nomime", "openrouter", "gpt-4.1");
        graph.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"url\":\"https://mmg.whatsapp.net/d/blob.enc\"}")
                .build());
        installCdnClient(req -> cdnOk(req, "blob".getBytes(), "application/octet-stream"));

        var input = WhatsAppMediaDownloader.downloadOne(
                "tok", media("MEDIA-NM", null, null), agent.name,
                graph.url("/v21.0/").toString());

        assertNotNull(input);
        assertEquals("application/octet-stream", input.mimeType(),
                "no declared MIME → generic binary type (finalize re-sniffs from disk)");
        assertEquals(models.MessageAttachment.KIND_FILE, input.kind());
    }

    @Test
    void blankMediaIdYieldsNullWithoutAnyRequest() throws Exception {
        installCdnClient(req -> { throw new AssertionError("no request for a blank media id"); });
        assertNull(WhatsAppMediaDownloader.downloadOne(
                "tok", media("  ", "image/jpeg", null), "wa-dl-blank-one",
                graph.url("/v21.0/").toString()));
        assertNull(WhatsAppMediaDownloader.downloadOne(
                "tok", media(null, "image/jpeg", null), "wa-dl-blank-one",
                graph.url("/v21.0/").toString()));
        assertEquals(0, graph.getRequestCount(), "the Graph metadata leg must never fire");
        assertTrue(seen.isEmpty());
    }

    // ── WhatsApp-Web (Cobalt) branch ──

    @Test
    void cobaltDownloadWithoutALiveSessionYieldsEmpty() throws Exception {
        var binding = new WhatsAppBinding();
        binding.transport = WhatsAppTransport.WHATSAPP_WEB;
        binding.id = 9_450_201L; // synthetic key owned by this test only
        var msg = msgWithMedia(List.of(media("COBALT-MSG-1", "image/jpeg", null)));

        // No session registered for the binding: every part is skipped.
        assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msg, "wa-dl-cobalt").isEmpty(),
                "no live session → media skipped (message still delivers without it)");

        // A registered but never-connected session has no Cobalt handle: same skip.
        var handlesField = WhatsAppCobaltRunner.class.getDeclaredField("HANDLES");
        handlesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var handles = (Map<Long, WhatsAppCobaltSession>) handlesField.get(null);
        handles.put(binding.id, new WhatsAppCobaltSession(binding.id));
        try {
            assertTrue(WhatsAppMediaDownloader.downloadAll(binding, msg, "wa-dl-cobalt").isEmpty(),
                    "a session without a Cobalt handle must degrade the same way");
        } finally {
            handles.remove(binding.id);
        }
    }

    // ── helpers (dispatch tests) ──

    private static WhatsAppBinding cloudBinding(String token) {
        var binding = new WhatsAppBinding();
        binding.transport = WhatsAppTransport.CLOUD_API;
        binding.accessToken = token;
        return binding;
    }

    private static WhatsAppInboundMessage msgWithMedia(List<WhatsAppInboundMessage.PendingMedia> parts) {
        return new WhatsAppInboundMessage(
                "mid-" + System.nanoTime(), "447900000001", "447900000001",
                WhatsAppInboundMessage.CHAT_DIRECT, "PNID", WhatsAppInboundMessage.MessageType.IMAGE,
                null, null, null, parts, true, null, null);
    }

    private static Response cdnResponse(Request req, int code, byte[] body) {
        return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(code).message("HTTP " + code)
                .body(ResponseBody.create(body, MediaType.parse("text/plain")))
                .build();
    }

    /** Swap in a CDN client whose interceptor fails with an {@link IOException},
     *  as a real socket would mid-stream ({@code Interceptor.intercept} may throw
     *  checked IO, unlike the {@code Function}-based responder installer). */
    private void installFailingCdnClient(String message) throws Exception {
        Interceptor stub = chain -> {
            seen.add(chain.request());
            throw new IOException(message);
        };
        DOWNLOAD_CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(stub)
                .followRedirects(false)
                .followSslRedirects(false)
                .build());
    }

    private static void assertStagingEmpty(String agentName) throws IOException {
        var staging = AgentService.acquireWorkspacePath(agentName, "attachments/staging");
        if (!Files.exists(staging)) return;
        try (var files = Files.list(staging)) {
            assertTrue(files.findAny().isEmpty(),
                    "no staged file may survive a failed download");
        }
    }
}
