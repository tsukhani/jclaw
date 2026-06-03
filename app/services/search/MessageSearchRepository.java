package services.search;

import models.TaskRunMessage;

import java.io.IOException;
import java.util.List;

/**
 * Full-text search over {@link TaskRunMessage#content}. Two
 * implementations live behind this interface so the choice of
 * backend tracks the configured Hibernate dialect:
 *
 * <ul>
 *   <li>{@link DirectLuceneMessageSearchRepository} — the Personal
 *       Edition path. JClaw owns its own Lucene 10 index directly via
 *       {@link LuceneIndexer}; the JPA hooks on
 *       {@link TaskRunMessage} drive sync (not DB triggers, since
 *       H2's {@code FullTextLucene} ships an older Lucene that
 *       {@code IllegalAccessError}s against Lucene 10). JCLAW's
 *       Personal Edition operators get full-text without standing up
 *       a separate Postgres instance.</li>
 *   <li>{@link PostgresMessageSearchRepository} — for the
 *       operator-opt-in Postgres path. Uses {@code tsvector}
 *       columns with GIN indexes, the canonical Postgres FT
 *       shape. Skeleton at this point — the column + trigger DDL
 *       lands when an operator first deploys with the
 *       Postgres dialect.</li>
 * </ul>
 *
 * <p>The right implementation is selected at boot by
 * {@link MessageSearch} based on the JDBC product name.
 * {@link jobs.FullTextSearchInitJob} calls {@link #init} once at
 * startup; everything else routes through the static facade.
 *
 * <p>Why this abstraction exists at JCLAW-21 time rather than
 * waiting for the consumer (JCLAW-22 monitoring UI's PeekPanel):
 * the schema-side wiring — trigger creation, Lucene index files
 * on disk — has to be in place before any task fires write rows
 * that need indexing. Splitting that across two stories would
 * leave us either re-indexing the entire table on JCLAW-22 land
 * (slow, surprising downtime), or shipping a Tasks foundation
 * that silently can't answer search queries.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public interface MessageSearchRepository {

    /**
     * One-time initialisation. Idempotent — re-running on every boot
     * costs microseconds when the schema is already in place.
     *
     * <p>For H2 this calls {@code FullTextLucene.init} (creates the
     * {@code FT_*} system aliases if missing) and
     * {@code FullTextLucene.createIndex} (installs the
     * INSERT/UPDATE/DELETE triggers on {@code task_run_message} if
     * not already present).
     *
     * <p>For Postgres this is currently a no-op — the
     * {@code search_vector} column + GIN index + trigger DDL is
     * left to operator-side migration when the Postgres dialect is
     * first deployed.
     */
    void init() throws IOException;

    /**
     * Return matching {@link TaskRunMessage} rows, ordered by
     * relevance (highest score first), capped at {@code limit}.
     * Empty {@code query} returns an empty list — the call is
     * intentionally non-exceptional so caller code doesn't need a
     * guard for "operator typed nothing into the search box".
     *
     * <p>Preserved for the legacy {@code /api/task-runs/search}
     * endpoint that wants typed {@link TaskRunMessage} rows back
     * directly. New list-endpoint callers should prefer
     * {@link #searchIds} — taking just the ids lets the caller
     * intersect them with other JpqlFilter predicates without
     * round-tripping unused entity rows through JPA.
     *
     * @param query     the search string; case-insensitive,
     *                  tokenised per the dialect's analyser
     * @param limit     hard cap on result count
     * @return matching rows ordered by relevance
     */
    List<TaskRunMessage> search(String query, int limit) throws IOException;

    /**
     * JCLAW-304: id-only search across an arbitrary scope. Returns the
     * matching primary-key ids ordered by Lucene's relevance scoring,
     * capped at {@code limit}. Empty / null query returns an empty
     * list (same non-exceptional contract as {@link #search}).
     *
     * <p>Callers hydrate the entity rows themselves — usually by
     * intersecting the returned id set with their existing JPQL
     * equality predicates rather than fetching every match.
     *
     * @param scope     which on-disk index to query
     * @param query     the search string; tokenised per the
     *                  dialect's analyser
     * @param limit     hard cap on result count
     * @return matching ids ordered by relevance
     */
    List<Long> searchIds(LuceneIndexer.Scope scope, String query, int limit) throws IOException;

    /**
     * JCLAW-415: agent-scoped id search over the per-agent {@code MEMORY}
     * scope — returns only {@code agentId}'s matching memory ids, relevance
     * ordered and capped at {@code limit}. The Lucene backend filters on the
     * indexed agent field; the default returns empty (Memory-on-Postgres
     * searches via {@code to_tsvector} in {@code JpaMemoryStore}, not here).
     */
    default List<Long> searchMemoryIds(String agentId, String query, int limit) throws IOException {
        return List.of();
    }

    /**
     * Short identifier for log lines. {@code "h2"} or {@code "postgres"}.
     */
    String dialectName();
}
