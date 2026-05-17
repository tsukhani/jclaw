package services.search;

import models.TaskRunMessage;
import services.EventLogger;

import java.util.List;

/**
 * Postgres tsvector-backed implementation of
 * {@link MessageSearchRepository}. Activated when the JDBC product
 * name reports "PostgreSQL" — for JClaw's operator-opt-in Postgres
 * deployment path.
 *
 * <h3>Status: skeleton</h3>
 * The runtime path is wired so that selecting this impl at boot
 * doesn't crash the application, but {@link #init} and
 * {@link #search} are documented placeholders. The schema-side
 * setup ({@code search_vector tsvector} column + GIN index +
 * BEFORE INSERT/UPDATE trigger to populate {@code search_vector}
 * from {@code content}) lands when an operator first deploys
 * against Postgres — that's the natural trigger to validate the
 * migration end-to-end on the target dialect.
 *
 * <h3>Reference shape</h3>
 * The planned implementation:
 * <pre>
 *   ALTER TABLE task_run_message ADD COLUMN search_vector tsvector;
 *   CREATE INDEX idx_trm_search_vector ON task_run_message USING GIN (search_vector);
 *   CREATE TRIGGER trm_search_vector_update
 *     BEFORE INSERT OR UPDATE ON task_run_message
 *     FOR EACH ROW EXECUTE FUNCTION
 *     tsvector_update_trigger(search_vector, 'pg_catalog.english', content);
 * </pre>
 * <p>And the query:
 * <pre>
 *   SELECT id FROM task_run_message
 *   WHERE search_vector @@ plainto_tsquery('english', ?)
 *   ORDER BY ts_rank(search_vector, plainto_tsquery('english', ?)) DESC
 *   LIMIT ?
 * </pre>
 *
 * <p>Throwing {@link UnsupportedOperationException} from
 * {@link #search} is the deliberate choice over returning an
 * empty list — operators who reach this path before the migration
 * exists should see the failure loudly rather than silently get
 * "no results" from a search that's actually broken.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public final class PostgresMessageSearchRepository implements MessageSearchRepository {

    @Override
    public void init() {
        // No-op until the migration story lands. Boot logs the
        // selection so operators see the skeleton was hit, without
        // throwing — running JClaw against Postgres for non-search
        // workflows should keep working.
        EventLogger.info("search", null, null,
                "Postgres search backend selected — skeleton, full-text disabled. "
                + "Migration to add search_vector + GIN index pending operator deployment.");
    }

    @Override
    public List<TaskRunMessage> search(String query, int limit) {
        throw new UnsupportedOperationException(
                "Postgres full-text search is not yet implemented. "
                + "The migration to add search_vector + GIN index has not been deployed. "
                + "See PostgresMessageSearchRepository javadoc for the planned shape.");
    }

    @Override
    public String dialectName() {
        return "postgres";
    }
}
