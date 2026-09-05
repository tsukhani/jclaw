package memory;

import services.EventLogger;

/**
 * Supplies the process-wide {@link MemoryStore} singleton. There is no selection to
 * make: one implementation, whose vector backend is chosen inside the store by JDBC
 * dialect rather than by any setting read here.
 */
public class MemoryStoreFactory {

    private MemoryStoreFactory() {}

    private static final String EVENT_CATEGORY_MEMORY = "memory";

    private static volatile MemoryStore instance;

    public static MemoryStore get() {
        if (instance == null) {
            synchronized (MemoryStoreFactory.class) {
                if (instance == null) {
                    instance = create();
                }
            }
        }
        return instance;
    }

    private static MemoryStore create() {
        EventLogger.info(EVENT_CATEGORY_MEMORY, "Initializing JPA memory store");
        return new JpaMemoryStore();
    }

    public static void reset() {
        instance = null;
    }

    /**
     * Test-only: pin a specific store instance, bypassing config-driven creation
     * (or clear back to lazy creation with {@link #reset()}). Lets a test drive the
     * pipeline through a vector-enabled or spy store without flipping process-global
     * config; parallels {@link JpaMemoryStore#setEmbedderForTest}. Callers that touch
     * the Lucene index serialize on {@code LuceneTestSync}, so this shared static is
     * never raced by a sibling memory test.
     */
    public static void setForTest(MemoryStore store) {
        instance = store;
    }
}
