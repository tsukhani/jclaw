package memory;

import services.EventLogger;

/**
 * Creates the appropriate MemoryStore based on application.conf settings.
 * Caches the singleton instance.
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
        // Single Postgres/H2-backed store (pgvector for vectors). The former
        // pluggable Neo4j backend was dropped — vector similarity and graph/
        // ontology now live in Postgres.
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
