import services.ConfigService;
import services.ConversationService;
import services.SessionCompactor;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the classes that set the keys deciding when a chat compacts across play1's concurrent lanes: the token
 * trigger's two halves, the row-limit switch and the history limits the row trigger counts against (JF-154, JF-159).
 * Sibling of {@link ShellSandboxSync}: the keys are process-global Config-DB state read on every turn, so one class
 * lowering a limit would compact another class's chat that expects the shipped defaults, and one class deleting a
 * key would change what another's chat does.
 *
 * <p>{@link #acquire()} also deletes the keys, so each test starts from the shipped defaults.
 */
public final class CompactionTriggerSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static final List<String> KEYS = List.of(SessionCompactor.TRIGGER_TOKENS_KEY,
            SessionCompactor.TRIGGER_PERCENT_KEY, SessionCompactor.ON_ROW_LIMIT_KEY, ConversationService.KEY_GLOBAL_MAX,
            ConversationService.KEY_DM_LIMIT, ConversationService.KEY_GROUP_LIMIT);

    private CompactionTriggerSync() {}

    /** Lock the keys and delete them. Call first in {@code @BeforeEach}. */
    public static void acquire() {
        LOCK.lock();
        try {
            deleteKeys();
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Delete the keys and release the lock. Call in {@code @AfterEach}. Idempotent: only unlocks when the current
     * thread holds the lock.
     */
    public static void release() {
        try {
            deleteKeys();
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    private static void deleteKeys() {
        for (var key : KEYS) ConfigService.delete(key);
    }
}
