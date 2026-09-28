import services.ConfigService;
import services.Tx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Capture-and-restore for the runtime config keys the scrape suites move.
 *
 * <p>{@link ConfigService#set} persists, so an {@code @AfterEach} that writes a hardcoded
 * default leaves the key materialised at a value the test invented rather than at what
 * the install had. Every scrape key reads a compiled-in default when absent, so restoring
 * a key that was absent means deleting it, not writing today's default back.
 *
 * <p>Guards hold a process-wide lock from their first write until {@link #restore()}.
 * These keys are global and several suites move the same two, so overlapping windows
 * would let one guard capture another's temporary value as the "prior" and restore that
 * — leaving a rung disabled in the developer's config table for good. The old hardcoded
 * restore was wrong in principle but self-healing; capture-and-restore is not.
 */
final class ScrapeConfigGuard {

    private static final ReentrantLock CONFIG_LOCK = new ReentrantLock();

    private final Map<String, String> prior = new LinkedHashMap<>();
    private final boolean committing;
    private boolean held;

    ScrapeConfigGuard() {
        this(false);
    }

    private ScrapeConfigGuard(boolean committing) {
        this.committing = committing;
    }

    /**
     * A guard whose writes and restores commit in their own transaction, for a FunctionalTest whose
     * request thread reads the keys: the test body's transaction is invisible to it, and the cache
     * set() seeds is cleared by every AuthFixture seed a concurrent class runs.
     */
    static ScrapeConfigGuard committing() {
        return new ScrapeConfigGuard(true);
    }

    void set(String key, String value) {
        acquire();
        remember(key);
        // No clearCache: set() seeds the cache so a crawl's own threads see the value before this
        // test's transaction commits, and clearing it hands them the uncommitted database instead.
        write(() -> ConfigService.set(key, value));
    }

    void delete(String key) {
        acquire();
        remember(key);
        write(() -> ConfigService.delete(key));
    }

    /** Restore every key this guard touched. Safe to call when nothing was touched. */
    void restore() {
        try {
            prior.forEach((key, value) -> {
                if (value == null) {
                    write(() -> ConfigService.delete(key));
                } else {
                    write(() -> ConfigService.set(key, value));
                }
            });
        } finally {
            prior.clear();
            if (held) {
                held = false;
                CONFIG_LOCK.unlock();
            }
        }
    }

    /** Bounded rather than blocking: a suite that deadlocked here would look like a hang,
     *  and the only way to reach the timeout is a guard that never restored. */
    private void acquire() {
        if (held) return;
        try {
            if (!CONFIG_LOCK.tryLock(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "another scrape suite still holds the config guard — one did not restore()");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the config guard", e);
        }
        held = true;
    }

    private void write(Runnable change) {
        if (!committing) {
            change.run();
            return;
        }
        var failure = new AtomicReference<Throwable>();
        // A platform thread: a fresh virtual one can wait behind a starved carrier pool (JCLAW-688).
        var writer = Thread.ofPlatform().start(() -> {
            try {
                Tx.run(change);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted writing a scrape config key", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("could not commit a scrape config key", failure.get());
        }
    }

    private void remember(String key) {
        // containsKey, not computeIfAbsent: an absent key captures as null, which
        // computeIfAbsent would decline to store and then re-capture after the write.
        if (!prior.containsKey(key)) prior.put(key, ConfigService.get(key));
    }
}
