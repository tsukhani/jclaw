import okhttp3.OkHttpClient;
import tools.WebScrapeTool;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Swaps {@code WebScrapeTool.CLIENT} for one test and restores it, holding a process-wide lock
 * between: test classes run concurrently, and one suite's restore would hand another's crawl the
 * SSRF-guarded client. Its holders are the only callers of {@code RobotsCache.resetForTest()}, so
 * reset that cache only while holding it. Take it before {@link ScrapeConfigGuard}, which these
 * suites acquire only in test bodies, so the two locks are always taken in one order; call
 * {@link #restore()} from a {@code finally}, or a throwing teardown line leaks the lock.
 */
final class ScrapeClientSwap {

    // Fair: a waiter that kept losing to barging would time out rather than wait its turn.
    private static final ReentrantLock CLIENT_LOCK = new ReentrantLock(true);
    private static final Field CLIENT;

    static {
        try {
            CLIENT = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private OkHttpClient original;
    private boolean held;

    void install(OkHttpClient client) throws IllegalAccessException {
        acquire();
        if (original == null) original = (OkHttpClient) CLIENT.get(null);
        CLIENT.set(null, client);
    }

    /** Put the original back. Safe to call when nothing was installed. */
    void restore() throws IllegalAccessException {
        try {
            if (original != null) CLIENT.set(null, original);
        } finally {
            original = null;
            if (held) {
                held = false;
                CLIENT_LOCK.unlock();
            }
        }
    }

    /** Bounded rather than blocking, as {@link ScrapeConfigGuard} is: the only way to reach
     *  the timeout is a suite that never restored. */
    private void acquire() {
        if (held) return;
        try {
            if (!CLIENT_LOCK.tryLock(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "another scrape suite still holds WebScrapeTool.CLIENT — one did not restore()");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for WebScrapeTool.CLIENT", e);
        }
        held = true;
    }
}
