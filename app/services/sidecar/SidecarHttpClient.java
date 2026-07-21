package services.sidecar;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import utils.HttpFactories;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Shared skeleton for the local inference-sidecar HTTP clients (ASR /
 * diarization / TTS), extracted from the triplicated boilerplate they carried
 * (JCLAW-828). Each concrete sidecar is one-inference-at-a-time by design
 * (HTTP 409 when busy), so calls are serialized JVM-wide through a FAIR lock:
 * concurrent conversations queue instead of surfacing a retryable busy
 * condition as a user-facing failure.
 *
 * <p>The lock is owned by each subclass (one JVM-wide lock per sidecar), not by
 * this base, on purpose: the three sidecars are independent processes, and the
 * clients are constructed per call — a single shared or per-instance lock would
 * either needlessly serialize unrelated ASR/diarize/TTS work or fail to
 * serialize at all. {@link #withSidecarLock(Supplier)} runs a body under the
 * subclass-supplied {@link #sidecarLock()}.
 *
 * <p>Subclasses derive their outbound client from {@link #defaultClient()},
 * which lifts the per-read socket timeout (readTimeout=0): a sidecar's first
 * request lazily loads its pipeline (gated download + model load) and
 * legitimately sends nothing for minutes. DELIBERATE TRADEOFF (JCLAW-626):
 * with readTimeout=0 a genuinely hung socket is bounded ONLY by the per-call
 * callTimeout each subclass sets on every request — do not "fix" the zero
 * without replacing that bound.
 */
public abstract class SidecarHttpClient {

    protected static final MediaType JSON = MediaType.get("application/json");

    protected final String baseUrlOverride;
    protected final OkHttpClient client;

    protected SidecarHttpClient(String baseUrlOverride, OkHttpClient client) {
        this.baseUrlOverride = baseUrlOverride;
        this.client = client;
    }

    /** The shared outbound client: the general pool/dispatcher with the per-read
     *  socket timeout lifted (see class Javadoc). */
    protected static OkHttpClient defaultClient() {
        return HttpFactories.general().newBuilder()
                .readTimeout(Duration.ZERO)
                .build();
    }

    /** This sidecar's JVM-wide fair lock (one per concrete client class). */
    protected abstract ReentrantLock sidecarLock();

    /** Run {@code body} holding this sidecar's fair lock. */
    protected final <T> T withSidecarLock(Supplier<T> body) {
        var lock = sidecarLock();
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** Collapse an error body to a single ≤300-char line for exception messages. */
    protected static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
