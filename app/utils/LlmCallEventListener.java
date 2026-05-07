package utils;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OkHttp EventListener for outbound LLM calls. Two responsibilities, both
 * fired off the same {@link #connectionAcquired} hook so SSE streams pay
 * zero per-frame cost:
 *
 * <ul>
 *   <li>Records the time spent acquiring an outbound connection
 *       (dispatcher queue + DNS + connect + TLS handshake) as the
 *       {@code dispatcher_wait} segment under the {@code llm} channel
 *       in {@link LatencyStats}. Lets a future regression — or just a
 *       loadtest run that pushes past
 *       {@link okhttp3.Dispatcher#getMaxRequestsPerHost()} — surface as
 *       a distinct segment instead of hiding inside {@code ttft}.</li>
 *   <li>Logs the negotiated wire protocol ({@code h2}, {@code http/1.1})
 *       once per {@code host:port}. Replaces the standalone
 *       PROTOCOL_LOGGER that previously served the LLM clients.</li>
 * </ul>
 *
 * <p>Per-instance state ({@link #callStartNs}) means this MUST be created
 * via {@link #factory()} so each call gets its own listener.
 */
public final class LlmCallEventListener extends EventListener {

    private static final EventListener.Factory FACTORY = call -> new LlmCallEventListener();

    private static final Set<String> SEEN_HOSTS = ConcurrentHashMap.newKeySet();

    public static EventListener.Factory factory() { return FACTORY; }

    private long callStartNs;

    @Override
    public void callStart(Call call) {
        callStartNs = System.nanoTime();
    }

    @Override
    public void connectionAcquired(Call call, Connection connection) {
        if (callStartNs > 0L) {
            long deltaNs = System.nanoTime() - callStartNs;
            LatencyStats.record("llm", "dispatcher_wait", Math.max(0L, deltaNs / 1_000_000L));
        }
        var url = call.request().url();
        var key = url.host() + ":" + url.port();
        if (SEEN_HOSTS.add(key)) {
            play.Logger.info("OkHttp negotiated %s with %s",
                    connection.protocol(), key);
        }
    }
}
