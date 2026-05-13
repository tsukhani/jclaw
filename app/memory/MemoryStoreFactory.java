package memory;

import play.Play;
import services.EventLogger;

/**
 * Creates the appropriate MemoryStore based on application.conf settings.
 * Caches the singleton instance.
 */
public class MemoryStoreFactory {

    private MemoryStoreFactory() {}

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
        var backend = Play.configuration.getProperty("memory.backend", "jpa");

        return switch (backend) {
            case "jpa" -> {
                EventLogger.info("memory", "Initializing JPA memory store");
                yield new JpaMemoryStore();
            }
            case "neo4j" -> {
                try {
                    // Load via reflection so the class is only resolved when Neo4j driver is on classpath
                    Class.forName("org.neo4j.driver.GraphDatabase");
                    var storeClass = Class.forName("memory.Neo4jMemoryStore");
                    var store = (MemoryStore) storeClass.getDeclaredConstructor().newInstance();
                    EventLogger.info("memory", "Initializing Neo4j memory store");
                    yield store;
                } catch (ClassNotFoundException e) {
                    EventLogger.error("memory",
                            "Neo4j driver not found in classpath. Add neo4j-java-driver JAR to lib/. Falling back to JPA.");
                    yield new JpaMemoryStore();
                } catch (Exception e) {
                    EventLogger.error("memory",
                            "Failed to initialize Neo4j memory store: %s. Falling back to JPA.".formatted(e.getMessage()));
                    yield new JpaMemoryStore();
                }
            }
            default -> {
                EventLogger.error("memory", "Unknown memory backend '%s', falling back to JPA".formatted(backend));
                yield new JpaMemoryStore();
            }
        };
    }

    public static void reset() {
        instance = null;
    }
}
