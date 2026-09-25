import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LoadTestHarness;
import services.LoadTestRunner;
import utils.HttpFactories;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How LoadTestRunner classifies a turn. /api/chat/stream answers a failed turn with HTTP 200 and an
 * error frame, so only the complete frame means success; a canned transport stands in for the
 * endpoint so each run sees exactly the frames under test.
 */
class LoadTestRunnerTurnCountingTest extends UnitTest {

    private static final String INIT = "data: {\"type\":\"init\",\"conversationId\":7}\n\n";
    private static final String COMPLETED = INIT
            + "data: {\"type\":\"token\",\"content\":\"hi\"}\n\n"
            + "data: {\"type\":\"complete\",\"content\":\"hi\"}\n\n";
    private static final String FAILED = INIT
            + "data: {\"type\":\"error\",\"content\":\"The provider refused the request.\"}\n\n";

    // The runner rebinds the shared __loadtest__ agent, as the other loadtest classes do.
    @BeforeEach
    void lock() {
        LoadTestHarnessSync.acquire();
    }

    @AfterEach
    void unlock() {
        LoadTestHarnessSync.release();
    }

    private static LoadTestRunner.Result run(String sse, int concurrency, int turns, boolean stopOnError,
                                             AtomicInteger requests) {
        var transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body(ResponseBody.create(sse, MediaType.get("text/event-stream")))
                    .build();
        }).build();
        var req = new LoadTestRunner.Request(concurrency, turns, false, new LoadTestHarness.Scenario(1, 200, 5),
                true, false, "loadtest-counting-canned", "m", null, List.of(), null, stopOnError);
        return HttpFactories.callWith(transport, () -> {
            try {
                return LoadTestRunner.run(req);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aTurnEndingInAnErrorFrameCountsAsAnError() {
        var r = run(FAILED, 2, 3, false, new AtomicInteger());

        assertEquals(6, r.totalRequests(), r::toString);
        assertEquals(0, r.successCount(), r::toString);
        assertEquals(6, r.errorCount(), r::toString);
        assertFalse(r.stoppedOnError(), r::toString);
        assertNotNull(r.turnBuckets());
        assertTrue(r.turnBuckets().stream().allMatch(b -> b.count() == 0),
                () -> "failed turns must stay out of the per-turn buckets: " + r.turnBuckets());
    }

    @Test
    void aTurnEndingInACompleteFrameCountsAsASuccess() {
        var r = run(COMPLETED, 2, 3, false, new AtomicInteger());

        assertEquals(6, r.totalRequests(), r::toString);
        assertEquals(6, r.successCount(), r::toString);
        assertEquals(0, r.errorCount(), r::toString);
        assertNotNull(r.turnBuckets());
        assertTrue(r.turnBuckets().stream().allMatch(b -> b.count() == 2), () -> r.turnBuckets().toString());
    }

    @Test
    void stopOnErrorStartsNoTurnAfterTheFirstFailure() {
        var requests = new AtomicInteger();
        var r = run(FAILED, 1, 5, true, requests);

        assertTrue(r.stoppedOnError(), r::toString);
        assertEquals(1, r.totalRequests(), r::toString);
        assertEquals(1, r.errorCount(), r::toString);
        assertEquals(2, requests.get(), "the warmup and one measured turn, nothing after the failure");
    }
}
