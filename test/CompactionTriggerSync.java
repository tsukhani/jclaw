import services.ConfigService;
import services.SessionCompactor;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the classes that set {@code chat.compaction.triggerTokens} across play1's concurrent lanes. Sibling of
 * {@link ShellSandboxSync}: the key is process-global Config-DB state read on every turn, so one class setting the
 * recommended 150,000 would compact another class's chat that expects the shipped default, off, and one class
 * deleting it would leave another's chat uncompacted.
 *
 * <p>{@link #acquire()} also deletes the key, so each test starts from the shipped default.
 */
public final class CompactionTriggerSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private CompactionTriggerSync() {}

    /** Lock the key and delete it. Call first in {@code @BeforeEach}. */
    public static void acquire() {
        LOCK.lock();
        try {
            ConfigService.delete(SessionCompactor.TRIGGER_TOKENS_KEY);
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Delete the key and release the lock. Call in {@code @AfterEach}. Idempotent: only unlocks when the current
     * thread holds the lock.
     */
    public static void release() {
        try {
            ConfigService.delete(SessionCompactor.TRIGGER_TOKENS_KEY);
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }
}
