import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.videogen.BflVideoGenerationClient;
import services.videogen.VideoGenerationException;
import services.videogen.VideoGenerationRouter;
import services.videogen.VideoGenerationService.State;
import services.videogen.VideoGenerationService.VideoGenRequest;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The Black Forest Labs video client against a canned transport: submit posts a FLUX 3 text-to-video
 * task and hands back its polling URL as the job id, and poll maps BFL's task statuses onto
 * {@link State}.
 */
class BflVideoGenerationClientTest extends UnitTest {

    private static final String POLLING_URL = "http://127.0.0.1:1/v1/get_result?id=task-1";

    private final Deque<String> answers = new ArrayDeque<>();
    private final List<Request> sent = new ArrayList<>();
    private final Deque<Integer> codes = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        answers.clear();
        sent.clear();
        codes.clear();
        // Any base works, since the canned transport answers every host; fill it only when unset so
        // this class never re-points ImageGenerationClientTest, which writes it for its own server.
        var base = ConfigService.get("provider.bfl.baseUrl");
        if (base == null || base.isBlank()) ConfigService.set("provider.bfl.baseUrl", "http://127.0.0.1:1/v1");
        ConfigService.set("provider.bfl.apiKey", "test-key");
        ConfigService.set("videogen.bfl.model", "");
    }

    private OkHttpClient canned() {
        Interceptor interceptor = chain -> {
            sent.add(chain.request());
            var code = codes.isEmpty() ? 200 : codes.removeFirst();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(ResponseBody.create(answers.removeFirst(), null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static JsonObject bodyOf(Request request) throws IOException {
        var buffer = new Buffer();
        request.body().writeTo(buffer);
        return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
    }

    @Test
    void submitPostsATextToVideoTaskAndReturnsItsPollingUrl() throws IOException {
        answers.add("{\"id\":\"task-1\",\"polling_url\":\"" + POLLING_URL + "\"}");

        var id = new BflVideoGenerationClient(canned())
                .submit(new VideoGenRequest("a fox in the snow", null, 8, "9:16", 24));

        assertEquals(POLLING_URL, id);
        var request = sent.getFirst();
        assertTrue(request.url().encodedPath().endsWith("/flux-3-video"), request.url().toString());
        assertEquals("test-key", request.header("x-key"));
        var body = bodyOf(request);
        assertEquals("t2v", body.get("mode").getAsString());
        assertEquals("a fox in the snow", body.get("prompt").getAsString());
        assertEquals(8, body.get("duration").getAsInt());
        assertEquals("9:16", body.get("aspect_ratio").getAsString());
        // FLUX 3 refuses unknown fields, and it has no fps parameter.
        assertFalse(body.has("fps"), body.toString());
    }

    @Test
    void submitClampsTheDurationAndLeavesAnUnsupportedAspectToAuto() throws IOException {
        answers.add("{\"id\":\"a\",\"polling_url\":\"" + POLLING_URL + "\"}");
        answers.add("{\"id\":\"b\",\"polling_url\":\"" + POLLING_URL + "\"}");
        var client = new BflVideoGenerationClient(canned());

        client.submit(new VideoGenRequest("short", null, 2, "5:4", null));
        client.submit(new VideoGenRequest("long", null, 60, null, null));

        var shortBody = bodyOf(sent.get(0));
        assertEquals(5, shortBody.get("duration").getAsInt());
        assertFalse(shortBody.has("aspect_ratio"), shortBody.toString());
        assertEquals(20, bodyOf(sent.get(1)).get("duration").getAsInt());
    }

    @Test
    void submitUsesTheConfiguredModel() {
        ConfigService.set("videogen.bfl.model", "flux-3-video-fast");
        answers.add("{\"id\":\"task-1\",\"polling_url\":\"" + POLLING_URL + "\"}");

        new BflVideoGenerationClient(canned()).submit(new VideoGenRequest("a fox", null, null, null, null));

        assertTrue(sent.getFirst().url().encodedPath().endsWith("/flux-3-video-fast"), sent.getFirst().url().toString());
    }

    @Test
    void anUnaskedLengthIsTheShortestClipNotBflsAuto() throws IOException {
        // BFL bills per second, and auto may choose up to 20 s for a clip nobody asked to be long.
        answers.add("{\"id\":\"task-1\",\"polling_url\":\"" + POLLING_URL + "\"}");

        new BflVideoGenerationClient(canned()).submit(new VideoGenRequest("a fox", null, null, null, null));

        var body = bodyOf(sent.getFirst());
        assertEquals(5, body.get("duration").getAsInt());
        assertFalse(body.has("resolution"), "an unsent resolution is BFL's HD default: " + body);
    }

    @Test
    void submitSurfacesAnHttpError() {
        codes.add(402);
        answers.add("insufficient credits");
        var client = new BflVideoGenerationClient(canned());
        var request = new VideoGenRequest("a fox", null, null, null, null);

        var ex = assertThrows(VideoGenerationException.class, () -> client.submit(request));
        assertTrue(ex.getMessage().contains("submit failed: HTTP 402"), ex.getMessage());
    }

    @Test
    void pollReadyReturnsTheSampleUrl() {
        answers.add("{\"id\":\"task-1\",\"status\":\"Ready\",\"result\":{\"sample\":\"http://127.0.0.1:1/clip.mp4\"}}");

        var r = new BflVideoGenerationClient(canned()).poll(POLLING_URL);

        assertEquals(State.SUCCEEDED, r.state());
        assertEquals("http://127.0.0.1:1/clip.mp4", r.resultUrl());
        assertEquals(POLLING_URL, sent.getFirst().url().toString());
        assertEquals("test-key", sent.getFirst().header("x-key"));
    }

    @Test
    void pollWhileGeneratingIsRunning() {
        answers.add("{\"id\":\"task-1\",\"status\":\"Generating\",\"progress\":0.4}");

        var r = new BflVideoGenerationClient(canned()).poll(POLLING_URL);

        assertEquals(State.RUNNING, r.state());
        assertNull(r.resultUrl());
    }

    @Test
    void pollModeratedIsAFailedGenerationNotAThrow() {
        answers.add("{\"id\":\"task-1\",\"status\":\"Content Moderated\"}");

        var r = new BflVideoGenerationClient(canned()).poll(POLLING_URL);

        assertEquals(State.FAILED, r.state());
        assertTrue(r.error().contains("Content Moderated"), r.error());
    }

    @Test
    void pollReadyWithoutASampleIsFailed() {
        answers.add("{\"id\":\"task-1\",\"status\":\"Ready\",\"result\":{}}");

        var r = new BflVideoGenerationClient(canned()).poll(POLLING_URL);

        assertEquals(State.FAILED, r.state());
    }

    @Test
    void theRouterSelectsBflAndReportsItsDefaultModel() {
        assertInstanceOf(BflVideoGenerationClient.class, VideoGenerationRouter.serviceFor("bfl").orElseThrow());
        assertEquals("flux-3-video", VideoGenerationRouter.effectiveModel("bfl"));

        ConfigService.set("videogen.bfl.model", "flux-3-video-fast");
        assertEquals("flux-3-video-fast", VideoGenerationRouter.effectiveModel("bfl"));
    }
}
