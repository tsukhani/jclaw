import services.ConfigService;
import tools.HarnessSandbox;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the two classes that run {@code exec} across play1's concurrent lanes
 * (JCLAW-1153). Sibling of {@link LoadTestHarnessSync} and {@link LuceneTestSync},
 * which solve the same shape for the mock load-test server and the Lucene index.
 *
 * <p>{@code shell.sandbox} is process-global Config-DB state read on every
 * {@code ShellExecTool.execute}, so {@link ShellExecSandboxTest} turning it on is
 * visible to {@link ShellExecToolTest}'s forty-odd concurrent exec runs. Note the
 * direction that is NOT visible: a stray sandbox-on window mostly leaves those
 * assertions passing, because they write inside the workspace the sandbox grants —
 * the flake surfaces only on the tests that reach outside it, and rarely.
 *
 * <p>{@link #acquire()} also clears the key, so each test starts from the shipped
 * {@code false} default rather than inheriting the previous test's tri-state.
 */
public final class ShellSandboxSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private ShellSandboxSync() {}

    /** Lock the flag and reset it to off. Call first in {@code @BeforeEach}. */
    public static void acquire() {
        LOCK.lock();
        try {
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "");
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Reset the flag and release the lock. Call in {@code @AfterEach} — JUnit runs it
     * even when the test or a later {@code @BeforeEach} fails. Idempotent: only unlocks
     * when the current thread holds the lock, so a test that never acquired cannot
     * unlock someone else's window.
     */
    public static void release() {
        try {
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "");
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }
}
