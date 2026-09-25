package memory;

import play.db.DB;
import services.EventLogger;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * JCLAW-528: guarded pgvector provisioning. Before this step existed,
 * {@link JpaMemoryStore} wrote embeddings via raw SQL assuming the
 * {@code vector} extension, the {@code memory.embedding} column, and an index
 * already existed — flipping {@code memory.jpa.vector.enabled} on Postgres was
 * a silent no-op until an operator ran the DDL by hand.
 *
 * <p>{@link #ensureProvisioned()} runs the three-statement DDL — extension,
 * embedding column sized to {@code memory.jpa.vector.dimensions}, HNSW index
 * for cosine distance — against a short-lived raw connection. Every statement
 * is {@code IF NOT EXISTS}, so re-running on every boot (the
 * {@link jobs.PgVectorSchemaInitJob} call) and again on store construction
 * costs a few metadata lookups.
 *
 * <p>PostgreSQL-only by live-connection sniff: on H2 (always the case in
 * dev/test) the step is a no-op returning {@code false} — vector search routes
 * to the Lucene HNSW backend there (JCLAW-555), so there is nothing to
 * provision. {@code false} also comes back when the DDL fails (pgvector not
 * installed on the server, missing {@code CREATE EXTENSION} privilege): the
 * error is logged loudly and {@link JpaMemoryStore} falls back to full-text
 * search rather than failing silently on every embedding write.
 */
public final class PgVectorProvisioner {

    private static final String EVENT_CATEGORY_MEMORY = "memory";

    private PgVectorProvisioner() {}

    /**
     * Ensure the pgvector schema exists on a PostgreSQL database.
     *
     * @return {@code true} when the vector extension, embedding column, and
     *         HNSW index are in place; {@code false} on a non-Postgres dialect
     *         (nothing to provision — the Lucene HNSW backend serves vectors)
     *         or when provisioning failed (logged; callers must fall back to
     *         full-text search).
     */
    public static boolean ensureProvisioned() {
        int dimensions;
        try (Connection conn = DB.getDataSource().getConnection()) {
            if (!conn.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT).contains("postgresql")) {
                return false;
            }
            dimensions = configuredDimensions();

            // JCLAW-1005: "ADD COLUMN IF NOT EXISTS embedding vector(N)" is a no-op once the
            // column exists, so it keeps its ORIGINAL dimension forever — changing the model
            // or memory.jpa.vector.dimensions never resizes it. Reporting the leg live anyway
            // would fuse new-model query vectors against old-model stored vectors, and
            // semanticNeighbors (capture dedup AND the forget matcher) would match the wrong
            // rows, with the Settings panel showing success throughout. Degrading to
            // keyword-only LOUDLY is strictly better than that.
            int observed = observedDimension(conn);
            if (!dimensionUsable(dimensions, observed)) {
                EventLogger.error(EVENT_CATEGORY_MEMORY,
                        ("pgvector dimension mismatch: memory.embedding is vector(%d) but the "
                         + "configured embedding model needs %d. Vector memory is DISABLED and "
                         + "recall has degraded to full-text search — the stored vectors were "
                         + "written by a different model and cannot be compared with the new "
                         + "one. Migrate the column and re-embed, or restore the previous model.")
                                .formatted(observed, dimensions));
                return false;
            }

            for (String ddl : ddlStatements(dimensions)) {
                try (Statement s = conn.createStatement()) {
                    s.execute(ddl);
                }
            }
            // Hikari hands out connections with autoCommit=false and PostgreSQL
            // DDL is transactional (JCLAW-453) — without an explicit commit the
            // whole step rolls back when the connection closes. The flip side is
            // free atomicity: a failure part-way through commits nothing.
            conn.commit();
        } catch (Exception e) {
            EventLogger.error(EVENT_CATEGORY_MEMORY,
                    ("pgvector provisioning failed: %s — vector memory is unavailable, " +
                     "recall falls back to full-text search. Install the pgvector extension " +
                     "on the server (or grant CREATE EXTENSION to the JClaw role) and restart.")
                            .formatted(e.getMessage()));
            return false;
        }
        EventLogger.info(EVENT_CATEGORY_MEMORY,
                "pgvector provisioned: vector extension, memory.embedding vector(%d), HNSW cosine index"
                        .formatted(dimensions));
        return true;
    }

    /**
     * The provisioning DDL, in execution order. Test-visible (the AC pins the
     * configured dimensions and the cosine-distance HNSW index; the statements
     * themselves can only execute on live Postgres). {@code dimensions} is a
     * parsed {@code int}, so interpolating it into DDL — which cannot take bind
     * parameters — is injection-safe.
     */
    public static List<String> ddlStatements(int dimensions) {
        return List.of(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "ALTER TABLE memory ADD COLUMN IF NOT EXISTS embedding vector(%d)".formatted(dimensions),
                "CREATE INDEX IF NOT EXISTS idx_memory_embedding_hnsw " +
                        "ON memory USING hnsw (embedding vector_cosine_ops)");
    }

    private static int configuredDimensions() {
        return MemoryVectorSettings.dimensions();
    }

    /** No {@code memory.embedding} column yet — the normal first-provision case. */
    public static final int COLUMN_ABSENT = -1;

    /**
     * Whether the vector leg may be enabled, given the configured dimension and the one the
     * existing column actually has (JCLAW-1005).
     *
     * <p>Test-visible and deliberately pure. pgvector's own rejection of a wrong-width vector
     * can only be observed against live Postgres, and nothing in CI will raise it — but this
     * decision is what determines whether {@code resolveVectorEnabled} reports the leg live,
     * and that is the part that must never be wrong.
     */
    public static boolean dimensionUsable(int configured, int observed) {
        return observed == COLUMN_ABSENT || observed == configured;
    }

    /**
     * The dimension of the existing {@code memory.embedding} column, or {@link #COLUMN_ABSENT}
     * when there is none. pgvector stores the declared width in {@code atttypmod}, and an
     * unparameterised {@code vector} column reports -1 there — which reads as "absent" here,
     * correctly: an unconstrained column accepts any width, so there is nothing to mismatch.
     */
    private static int observedDimension(Connection conn) {
        var sql = """
                SELECT a.atttypmod FROM pg_attribute a
                  JOIN pg_class c ON a.attrelid = c.oid
                 WHERE c.relname = 'memory' AND a.attname = 'embedding' AND NOT a.attisdropped
                """;
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : COLUMN_ABSENT;
        } catch (Exception e) {
            // Cannot read the catalog — treat as absent and let the DDL decide. Failing closed
            // here would disable a working deployment over a permissions quirk.
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "Could not read the memory.embedding column dimension: %s".formatted(e.getMessage()));
            return COLUMN_ABSENT;
        }
    }
}
