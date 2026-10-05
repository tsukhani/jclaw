import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the classes that depend on {@code FactoryProcess.runnerForTest}: it is
 * process-global, so {@link ApiFactoryControllerTest}'s fake would otherwise capture
 * {@link FactoryProcessTest}'s real runs across play1's concurrent lanes.
 */
public final class FactoryRunnerSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private FactoryRunnerSync() {}

    /** Call first in {@code @BeforeEach}. */
    public static void acquire() {
        LOCK.lock();
    }

    /** Call in {@code @AfterEach}. Idempotent: only unlocks a lock this thread holds. */
    public static void release() {
        if (LOCK.isHeldByCurrentThread()) LOCK.unlock();
    }
}
