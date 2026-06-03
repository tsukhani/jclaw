package services.search;

import models.TaskRunMessage;
import play.db.DB;
import services.EventLogger;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Static facade over the dialect-specific
 * {@link MessageSearchRepository} implementations. Picks the
 * right backend at first call by reading the JDBC product name,
 * then caches the choice for the JVM lifetime — every subsequent
 * call routes through the cached reference without re-asking the
 * connection.
 *
 * <h2>Dialect detection</h2>
 * Same pattern {@code DbSchedulerSchemaInitJob} uses: open a
 * connection, read {@code DatabaseMetaData.getDatabaseProductName},
 * substring-match against "postgresql" (case-insensitive). Default
 * is H2 — the JClaw Personal Edition's bundled dialect.
 *
 * <h2>Why static facade vs DI</h2>
 * JClaw's services layer is uniformly static (matches Play 1.x's
 * controller convention). Threading a {@link MessageSearchRepository}
 * instance through call sites would require either:
 * <ul>
 *   <li>A static "current" reference set at boot — same pattern
 *       this facade collapses to,</li>
 *   <li>Or a service-locator pattern that fetches the impl on
 *       every call — slower and no more testable, since both
 *       implementations are deterministic in their own right.</li>
 * </ul>
 *
 * <p>Tests target the {@code Repository} implementations
 * directly; this facade is the production routing layer.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public final class MessageSearch {

    // Publish-once-read-many handoff: written once under init()'s
    // synchronized guard, read by every subsequent search/activeDialect
    // call. S3077 targets compound mutation on volatile non-primitives;
    // a pure reference handoff is the textbook valid use of volatile
    // (JMM happens-before through the volatile read/write is sufficient).
    @SuppressWarnings("java:S3077")
    private static volatile MessageSearchRepository repo;

    private MessageSearch() {}

    /**
     * Initialise the dialect-appropriate backend once. Idempotent —
     * re-running just re-runs {@code init()} on the cached
     * impl, which is itself idempotent (H2's
     * {@code FullTextLucene.createIndex} no-ops when the index
     * already exists; Postgres's is currently a no-op).
     *
     * <p>Called from {@link jobs.FullTextSearchInitJob} at
     * {@code @OnApplicationStart}.
     */
    public static synchronized void init() throws IOException {
        if (repo == null) {
            repo = chooseRepository();
        }
        repo.init();
    }

    /**
     * {@link MessageSearchRepository#search} via the active backend.
     * If {@link #init} hasn't run yet (test path or pre-startup
     * call), returns an empty list rather than NPE — search before
     * init means "no index ready, nothing to find" rather than a
     * bug to surface.
     */
    public static List<TaskRunMessage> search(String query, int limit) throws IOException {
        var current = repo;
        if (current == null) return List.of();
        return current.search(query, limit);
    }

    /**
     * JCLAW-304: id-only multi-scope search facade.
     * {@link MessageSearchRepository#searchIds} via the active backend,
     * with the same pre-init no-throw contract as {@link #search}.
     * Callers (controllers wiring {@code q} into list endpoints)
     * intersect the returned id set with their existing JpqlFilter
     * predicates.
     */
    public static List<Long> searchIds(LuceneIndexer.Scope scope, String query, int limit) throws IOException {
        var current = repo;
        if (current == null) return List.of();
        return current.searchIds(scope, query, limit);
    }

    /**
     * JCLAW-415: agent-scoped id search for the per-agent {@code MEMORY} scope.
     * Routes to the active backend's {@link MessageSearchRepository#searchMemoryIds}
     * (the Lucene backend filters on the indexed agent field). Same pre-init
     * no-throw contract as {@link #searchIds}.
     */
    public static List<Long> searchMemoryIds(String agentId, String query, int limit) throws IOException {
        var current = repo;
        if (current == null) return List.of();
        return current.searchMemoryIds(agentId, query, limit);
    }

    /**
     * Short name of the active backend: {@code "h2"}, {@code "postgres"},
     * or {@code "none"} when {@link #init} hasn't run.
     */
    public static String activeDialect() {
        var current = repo;
        return current != null ? current.dialectName() : "none";
    }

    /**
     * Test-only setter so unit tests can install a deterministic
     * backend reference without going through the JDBC dialect
     * detection. Package-private — see
     * {@code MessageSearchTestHooks} for the default-package
     * bridge tests use.
     */
    static void setRepositoryForTest(MessageSearchRepository override) {
        repo = override;
    }

    private static MessageSearchRepository chooseRepository() {
        // The direct Lucene-10 backend works against any JDBC dialect —
        // it owns its own filesystem index and stays in sync via
        // TaskRunMessage's JPA lifecycle hooks rather than DB triggers.
        // Postgres operators who specifically want tsvector + GIN still
        // get the Postgres-native skeleton; everyone else gets the
        // direct Lucene path.
        try {
            String productName;
            try (var conn = DB.getDataSource().getConnection()) {
                productName = conn.getMetaData().getDatabaseProductName()
                        .toLowerCase(Locale.ROOT);
            }
            // Postgres-tsvector remains the explicit opt-in for operators
            // who don't want a sibling Lucene directory. Skeleton today;
            // the column + GIN index + trigger DDL land when an operator
            // first deploys against the Postgres dialect.
            if (productName.contains("postgresql")
                    && "true".equalsIgnoreCase(System.getProperty("jclaw.search.postgres-native"))) {
                return new PostgresMessageSearchRepository();
            }
            return new DirectLuceneMessageSearchRepository();
        } catch (Exception e) {
            EventLogger.warn("search", null, null,
                    "MessageSearch: dialect detection failed (%s); defaulting to direct Lucene"
                            .formatted(e.getMessage()));
            return new DirectLuceneMessageSearchRepository();
        }
    }
}
