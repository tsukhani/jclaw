import services.decision.JevApi;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;
import utils.CircuitBreakers;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the test classes that send JEV requests across play1's concurrent lanes (JCLAW-1302).
 * Sibling of {@link LuceneTestSync} and {@link ShellSandboxSync}, for the same reason: every JEV call
 * runs under the one process-global {@code decision:jev} breaker, so one class's deliberate 503s would
 * open it for another class's run, which then sends nothing.
 *
 * <p>The Ollama decision provider's {@code decision:ollama} breaker rides the same lock (JCLAW-1336), and so
 * does Laya's {@code decision:laya} (JCLAW-1447).
 *
 * <p>{@link #acquire()} also drops all three breakers, so each test starts from fresh, closed ones.
 */
public final class JevBreakerTestSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private JevBreakerTestSync() {}

    /** Lock the breaker and drop it. Call first in {@code @BeforeEach}. */
    public static void acquire() {
        LOCK.lock();
        CircuitBreakers.remove(JevApi.BREAKER_NAME);
        CircuitBreakers.remove(OllamaDecision.BREAKER_NAME);
        CircuitBreakers.remove(LayaDecision.BREAKER_NAME);
    }

    /**
     * Drop the breaker and release the lock. Call in {@code @AfterEach} or a {@code finally}. Idempotent:
     * only unlocks when the current thread holds the lock.
     */
    public static void release() {
        try {
            CircuitBreakers.remove(JevApi.BREAKER_NAME);
            CircuitBreakers.remove(OllamaDecision.BREAKER_NAME);
            CircuitBreakers.remove(LayaDecision.BREAKER_NAME);
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }
}
