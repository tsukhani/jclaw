import jobs.TailscaleFunnelBootJob;
import jobs.TailscaleFunnelBootJob.Listener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpFactories;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** JF-16: the boot reconcile runs async, before the server binds, so it waits for the funnelled port to answer. */
class TailscaleFunnelBootJobTest extends UnitTest {

    private static final BooleanSupplier RUNNING = () -> false;

    /** Refuses the first {@code refusals} connections, then answers as {@code /api/status} does. */
    private static OkHttpClient listener(int refusals, AtomicInteger attempts, List<String> urls) {
        Interceptor canned = chain -> {
            urls.add(chain.request().url().toString());
            if (attempts.incrementAndGet() <= refusals) {
                throw new ConnectException("Failed to connect to /127.0.0.1:9000");
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{\"status\":\"ok\"}", null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static Listener await(OkHttpClient transport, int port, Duration budget, Duration poll, BooleanSupplier stopping) {
        return HttpFactories.callWith(transport, () -> TailscaleFunnelBootJob.awaitListener(port, budget, poll, stopping));
    }

    @Test
    void anAnswerOnTheFunnelledLoopbackPortCountsAsListening() {
        var attempts = new AtomicInteger();
        var urls = new ArrayList<String>();

        assertEquals(Listener.ANSWERED, await(listener(0, attempts, urls), 9123, Duration.ofSeconds(5), Duration.ZERO, RUNNING));
        assertEquals(List.of("http://127.0.0.1:9123/api/status"), urls);
    }

    @Test
    void waitsThroughRefusedConnectionsUntilTheListenerAnswers() {
        var attempts = new AtomicInteger();

        assertEquals(Listener.ANSWERED,
                await(listener(2, attempts, new ArrayList<>()), 9000, Duration.ofSeconds(5), Duration.ZERO, RUNNING));
        assertEquals(3, attempts.get());
    }

    @Test
    void aSpentBudgetStillTriesOnceBeforeTimingOut() {
        var attempts = new AtomicInteger();

        assertEquals(Listener.TIMED_OUT,
                await(listener(Integer.MAX_VALUE, attempts, new ArrayList<>()), 9000, Duration.ZERO, Duration.ZERO, RUNNING));
        assertEquals(1, attempts.get());
    }

    @Test
    void timesOutWhenNothingAnswersWithinTheBudget() {
        var attempts = new AtomicInteger();

        assertEquals(Listener.TIMED_OUT, await(listener(Integer.MAX_VALUE, attempts, new ArrayList<>()), 9000,
                Duration.ofMillis(200), Duration.ofMillis(20), RUNNING));
        assertTrue(attempts.get() > 1, "it timed out without retrying: " + attempts.get() + " attempt(s)");
    }

    @Test
    void stopsWaitingOnceJClawIsStopping() {
        var attempts = new AtomicInteger();
        BooleanSupplier stopsAfterTheFirstTry = () -> attempts.get() >= 1;

        assertEquals(Listener.STOPPING, await(listener(Integer.MAX_VALUE, attempts, new ArrayList<>()), 9000,
                Duration.ofMinutes(5), Duration.ZERO, stopsAfterTheFirstTry));
        assertEquals(1, attempts.get());
    }

    @Test
    void aStopBeforeTheFirstTryAsksNothing() {
        var attempts = new AtomicInteger();

        assertEquals(Listener.STOPPING,
                await(listener(0, attempts, new ArrayList<>()), 9000, Duration.ofMinutes(5), Duration.ZERO, () -> true));
        assertEquals(0, attempts.get());
    }
}
