import play.Play;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the classes that point the global skill registry ({@code jclaw.skills.path}) at a folder of their own.
 * The key is process-global, and {@code %test.jclaw.skills.path} sets it to {@code tmp/test-skills}. Removing it after
 * a test, as these classes did, dropped that override too, so every later class read the repo's own {@code skills/}
 * folder as the registry: {@code TaskCreatorSkillTest} found {@code task-creator} already there and, but for its
 * precondition, would have deleted the shipped copy. {@link #restore()} puts back the value {@link #point} found.
 */
public final class SkillsPathSync {

    private static final String KEY = "jclaw.skills.path";
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static String original;

    private SkillsPathSync() {}

    /** Lock the key and point the registry at {@code registry}; a second call in the same hold keeps the first's original. */
    public static void point(Path registry) {
        LOCK.lock();
        if (LOCK.getHoldCount() == 1) original = Play.configuration.getProperty(KEY);
        Play.configuration.setProperty(KEY, registry.toString());
    }

    /**
     * Put back the value the first {@link #point} found and release every hold. Call in {@code @AfterEach}. Idempotent,
     * and a thread that holds nothing changes nothing.
     */
    public static void restore() {
        if (!LOCK.isHeldByCurrentThread()) return;
        try {
            if (original == null) {
                Play.configuration.remove(KEY);
            } else {
                Play.configuration.setProperty(KEY, original);
            }
        } finally {
            while (LOCK.isHeldByCurrentThread()) LOCK.unlock();
        }
    }
}
