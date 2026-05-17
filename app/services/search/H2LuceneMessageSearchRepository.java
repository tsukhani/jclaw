package services.search;

import models.TaskRunMessage;
import org.h2.fulltext.FullTextLucene;
import play.db.DB;
import services.EventLogger;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 Lucene-backed implementation of {@link MessageSearchRepository}.
 * Uses the {@code org.h2.fulltext.FullTextLucene} family of stored
 * procedures shipped with H2 — same surface as the higher-level
 * {@code CALL FTL_INIT()} / {@code CALL FTL_SEARCH_DATA(...)} SQL
 * aliases, but invoked directly via Java so no SQL parsing is in
 * the hot path.
 *
 * <h3>How H2's FullTextLucene works (operator FYI)</h3>
 * <ol>
 *   <li>{@link FullTextLucene#init} creates the {@code FT_INDEXES}
 *       housekeeping table and the {@code FT_*} stored-procedure
 *       aliases in the {@code FT} schema.</li>
 *   <li>{@link FullTextLucene#createIndex} installs INSERT, UPDATE,
 *       and DELETE triggers on the named table so the Lucene index
 *       stays in sync without application-side bookkeeping. The
 *       Lucene index files live under
 *       {@code <db-path>.lucene.<schema>.<table>/}.</li>
 *   <li>{@link FullTextLucene#searchData} runs a Lucene query and
 *       returns matches as a {@code ResultSet} with columns
 *       {@code SCHEMA, TABLE, COLUMNS, KEYS, SCORE} — {@code KEYS}
 *       is a {@code VARCHAR ARRAY} holding the primary-key values
 *       of the matched row.</li>
 * </ol>
 *
 * <p>The triggers handle existing rows at index-creation time via
 * a one-shot {@code SELECT * FROM table} sweep
 * ({@code FullTextLucene.indexExistingRows}), so adding the index
 * mid-life of a database doesn't require any operator-side
 * re-indexing.
 */
public final class H2LuceneMessageSearchRepository implements MessageSearchRepository {

    /** {@inheritDoc} */
    @Override
    public void init() throws Exception {
        try (var conn = DB.datasource.getConnection()) {
            FullTextLucene.init(conn);
            // FullTextLucene.createIndex is NOT idempotent at the
            // FTL.INDEXES metadata level — re-running it tries to
            // INSERT a duplicate (SCHEMA, TABLE) row and throws
            // 23505 unique-constraint. Pre-check via the FTL.INDEXES
            // table that init() just installed; only call
            // createIndex when no row exists.
            if (!indexAlreadyRegistered(conn, "PUBLIC", "TASK_RUN_MESSAGE")) {
                FullTextLucene.createIndex(conn, "PUBLIC", "TASK_RUN_MESSAGE", "CONTENT");
                EventLogger.info("search", null, null,
                        "H2 Lucene full-text index created on task_run_message.content");
            }
        }
    }

    private static boolean indexAlreadyRegistered(java.sql.Connection conn,
                                                   String schema, String table) throws java.sql.SQLException {
        // FTL.INDEXES quotes TABLE as a column name (it's a SQL keyword) —
        // hence the embedded double-quotes in the SQL string.
        try (var ps = conn.prepareStatement(
                "SELECT 1 FROM FTL.INDEXES WHERE SCHEMA = ? AND \"TABLE\" = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskRunMessage> search(String query, int limit) throws Exception {
        if (query == null || query.isBlank()) return List.of();

        // Phase 1: ask Lucene for the matching primary keys in
        // relevance order. searchData returns rows of
        // (SCHEMA, TABLE, COLUMNS, KEYS, SCORE) — KEYS is a SQL
        // ARRAY containing the primary-key values for the row.
        var ids = new ArrayList<Long>(limit);
        try (var pooled = DB.datasource.getConnection()) {
            // Hikari wraps connections in HikariProxyConnection. H2's
            // FullTextLucene.search performs a direct cast to
            // org.h2.jdbc.JdbcConnection internally and ClassCastException
            // through the proxy. Unwrap to get the real JdbcConnection —
            // lifecycle still belongs to the pooled wrapper above, which
            // returns the underlying connection to the pool when closed.
            var conn = pooled.isWrapperFor(org.h2.jdbc.JdbcConnection.class)
                    ? pooled.unwrap(org.h2.jdbc.JdbcConnection.class)
                    : pooled;
            try (var rs = FullTextLucene.searchData(conn, query, limit, 0)) {
                while (rs.next()) {
                    Array keys = rs.getArray("KEYS");
                    if (keys == null) continue;
                    Object[] elems = (Object[]) keys.getArray();
                    if (elems == null || elems.length == 0) continue;
                    // task_run_message has a single-column primary key (id BIGINT)
                    // so KEYS[0] is the row's id. Parse defensively in case
                    // H2 returns it as String or Number.
                    var raw = elems[0];
                    if (raw instanceof Number n) {
                        ids.add(n.longValue());
                    } else if (raw != null) {
                        try {
                            ids.add(Long.parseLong(raw.toString()));
                        } catch (NumberFormatException _) {
                            // Unexpected — skip the row and continue. Better
                            // to return partial results than fail the entire
                            // search because one row's KEYS shape was odd.
                        }
                    }
                }
            }
        }
        if (ids.isEmpty()) return List.of();

        // Phase 2: bulk-fetch the rows from JPA, preserving Lucene's
        // relevance ordering. JPA's IN clause is bounded by the
        // ${limit} passed in (default monitoring-UI page size is 50,
        // well within the per-DB IN-list ceilings).
        var rows = services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = play.db.jpa.JPA.em()
                    .createQuery("SELECT m FROM TaskRunMessage m WHERE m.id IN :ids")
                    .setParameter("ids", ids)
                    .getResultList();
            var typed = new ArrayList<TaskRunMessage>(raw.size());
            for (var r : raw) typed.add((TaskRunMessage) r);
            return typed;
        });

        // Re-order rows to match Lucene's relevance ordering: JPA's
        // IN-query result order is unspecified, but Lucene's was. A
        // by-id map lets us walk ids in the original order without
        // an N² scan.
        var byId = new java.util.HashMap<Long, TaskRunMessage>(rows.size() * 2);
        for (var row : rows) byId.put(row.id, row);
        var ordered = new ArrayList<TaskRunMessage>(ids.size());
        for (var id : ids) {
            var row = byId.get(id);
            if (row != null) ordered.add(row);
        }
        return ordered;
    }

    /** {@inheritDoc} */
    @Override
    public String dialectName() {
        return "h2";
    }
}
