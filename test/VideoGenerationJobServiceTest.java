import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.VideoGenerationJob;
import models.VideoGenerationJob.State;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.videogen.VideoGenerationJobService;
import services.videogen.VideoGenerationService.VideoGenRequest;

/**
 * JCLAW-230 coverage for the {@link VideoGenerationJob} lifecycle against a mocked Replicate transport:
 * submit (PENDING → RUNNING with a provider id; submit failure → FAILED), one poll tick
 * (RUNNING → SUCCEEDED / → FAILED / stays RUNNING), and the timeout path. Uses MockWebServer (not a
 * test double) so the real client plus the state machine are exercised end to end. The
 * {@code VideoGenerationJobRunner} is inert in test mode, so these ticks are the only thing advancing
 * jobs here.
 */
class VideoGenerationJobServiceTest extends UnitTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        ConfigService.set("videogen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        ConfigService.set("videogen.maxJobMinutes", "30");
    }

    @AfterEach
    void tearDown() {
        server.close();
        ConfigService.delete("videogen.provider");
        ConfigService.delete("videogen.local.port");
    }

    private VideoGenRequest req() {
        return new VideoGenRequest("a dog skateboarding", null, 5, "16:9", null);
    }

    @Test
    void submitCreatesRunningJobWithProviderId() {
        server.enqueue(json("{\"id\":\"pred_1\",\"status\":\"starting\"}"));
        var job = VideoGenerationJobService.submit(7L, 9L, req());
        assertNotNull(job.id);
        assertEquals(State.RUNNING, job.state);
        assertEquals("pred_1", job.providerJobId);
        assertEquals("replicate", job.provider);
        assertEquals(Long.valueOf(7L), job.agentId);
    }

    @Test
    void submitFailureLandsJobInFailed() {
        server.enqueue(new MockResponse.Builder().code(402).body(buf("no credits")).build());
        var job = VideoGenerationJobService.submit(1L, 1L, req());
        assertEquals(State.FAILED, job.state);
        assertNotNull(job.errorMessage);
        assertNotNull(job.completedAt);
    }

    @Test
    void tickTransitionsRunningToSucceeded() {
        server.enqueue(json("{\"id\":\"pred_2\",\"status\":\"starting\"}"));     // submit
        var job = VideoGenerationJobService.submit(1L, 1L, req());
        assertEquals(State.RUNNING, job.state);

        server.enqueue(json("{\"status\":\"succeeded\",\"output\":[\"https://cdn/v.mp4\"]}")); // poll
        VideoGenerationJobService.tickOnce();

        VideoGenerationJob reloaded = VideoGenerationJob.findById(job.id);
        assertEquals(State.SUCCEEDED, reloaded.state);
        assertNotNull(reloaded.completedAt);
        assertEquals(Integer.valueOf(100), reloaded.percent, "a finished job reads 100%");
    }

    @Test
    void tickTransitionsRunningToFailed() {
        server.enqueue(json("{\"id\":\"pred_3\",\"status\":\"starting\"}"));
        var job = VideoGenerationJobService.submit(1L, 1L, req());

        server.enqueue(json("{\"status\":\"failed\",\"error\":\"safety filter\"}"));
        VideoGenerationJobService.tickOnce();

        VideoGenerationJob reloaded = VideoGenerationJob.findById(job.id);
        assertEquals(State.FAILED, reloaded.state);
        assertTrue(reloaded.errorMessage.contains("safety filter"), reloaded.errorMessage);
    }

    @Test
    void tickStaysRunningWhileProcessing() {
        server.enqueue(json("{\"id\":\"pred_4\",\"status\":\"starting\"}"));
        var job = VideoGenerationJobService.submit(1L, 1L, req());

        server.enqueue(json("{\"status\":\"processing\"}"));
        VideoGenerationJobService.tickOnce();

        VideoGenerationJob reloaded = VideoGenerationJob.findById(job.id);
        assertEquals(State.RUNNING, reloaded.state);
        assertNull(reloaded.percent, "cloud reports no percent (SV-1) — stays null");
    }

    @Test
    void tickPersistsLocalEngineProgressPercent() {
        // The local sidecar reports a real per-step percent (JCLAW-232). Drive a RUNNING job through the
        // real LocalVideoGenerationClient by pointing videogen.local.port at the mock server (poll hits
        // http://127.0.0.1:<port>/jobs/<id>); the runner must persist that percent onto the job row.
        ConfigService.set("videogen.local.port", String.valueOf(server.url("/").port()));
        var job = new VideoGenerationJob();
        job.prompt = "a comet streaking past";
        job.provider = "ltx-local";
        job.providerJobId = "j1";
        job.state = State.RUNNING;
        job.save();

        server.enqueue(json("{\"job_id\":\"j1\",\"state\":\"running\",\"percent\":42}"));
        VideoGenerationJobService.tickOnce();

        VideoGenerationJob reloaded = VideoGenerationJob.findById(job.id);
        assertEquals(State.RUNNING, reloaded.state);
        assertEquals(Integer.valueOf(42), reloaded.percent);
    }

    @Test
    void tickTimesOutLongRunningJob() {
        server.enqueue(json("{\"id\":\"pred_5\",\"status\":\"starting\"}"));
        var job = VideoGenerationJobService.submit(1L, 1L, req());

        // maxJobMinutes=0 → any RUNNING job is past the bound immediately. The enqueued "processing"
        // response is a safety net: if the timeout check failed to short-circuit, the tick would
        // consume it and the job would stay RUNNING — a clean assertion failure rather than a hang.
        ConfigService.set("videogen.maxJobMinutes", "0");
        server.enqueue(json("{\"status\":\"processing\"}"));
        VideoGenerationJobService.tickOnce();

        VideoGenerationJob reloaded = VideoGenerationJob.findById(job.id);
        assertEquals(State.FAILED, reloaded.state);
        assertTrue(reloaded.errorMessage.contains("timed out"), reloaded.errorMessage);
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json").body(buf(body)).build();
    }

    private static Buffer buf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
