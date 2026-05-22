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
 *   <li>{@link H2LuceneMessageSearchRepository} — the Personal
 *       Edition path. H2 ships {@code FullTextLucene} which hooks
 *       INSERT/UPDATE/DELETE triggers on the indexed table and
 *       keeps an Apache Lucene index in sync. JCLAW's Personal
 *       Edition operators get full-text without standing up a
 *       separate Postgres instance.</li>
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
 * {@link FullTextSearchInitJob} calls {@link #init} once at
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
     * @param query     the search string; case-insensitive,
     *                  tokenised per the dialect's analyser
     * @param limit     hard cap on result count
     * @return matching rows ordered by relevance
     */
    List<TaskRunMessage> search(String query, int limit) throws IOException;

    /**
     * Short identifier for log lines. {@code "h2"} or {@code "postgres"}.
     */
    String dialectName();
}
