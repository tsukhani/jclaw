import memory.PgVectorProvisioner;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.test.UnitTest;

/**
 * JCLAW-528: pgvector provisioning. What is testable on the H2 test database
 * is the <b>guard</b> (a non-Postgres dialect must be a clean no-op — no DDL
 * attempted, "not ready" reported so callers keep vectors on the Lucene HNSW
 * backend) and the <b>DDL contract</b> (idempotent statements, embedding
 * column sized to the configured dimensions, HNSW index for cosine distance).
 * Executing the DDL needs live Postgres with the pgvector extension available
 * and is validated there. The fallback routing a {@code false} return
 * triggers — {@code JpaMemoryStore(false, true)} recall via full-text
 * search — is pinned by {@code JpaMemoryStorePgDialectTest}.
 */
class PgVectorProvisionerTest extends UnitTest {

    @Test
    void ensureProvisionedIsANoOpOnNonPostgres() throws Exception {
        assertFalse(PgVectorProvisioner.ensureProvisioned(),
                "on H2 the step must report not-ready without attempting DDL");

        // And it really was a no-op: no embedding column materialised on the
        // memory table (H2 upper-cases unquoted identifiers in its metadata).
        try (var conn = DB.getDataSource().getConnection();
             var cols = conn.getMetaData().getColumns(null, null, "MEMORY", "EMBEDDING")) {
            assertFalse(cols.next(), "no DDL may leak onto a non-Postgres database");
        }
    }

    @Test
    void ddlProvisionsExtensionColumnAndCosineHnswIdempotently() {
        var ddl = PgVectorProvisioner.ddlStatements(768);

        assertEquals(3, ddl.size());
        assertEquals("CREATE EXTENSION IF NOT EXISTS vector", ddl.get(0));
        assertTrue(ddl.get(1).contains("ALTER TABLE memory ADD COLUMN IF NOT EXISTS embedding vector(768)"),
                "the embedding column must carry the configured dimensions");
        assertTrue(ddl.get(2).contains("CREATE INDEX IF NOT EXISTS"),
                "the index step must be re-runnable");
        assertTrue(ddl.get(2).contains("USING hnsw (embedding vector_cosine_ops)"),
                "the AC pins an HNSW index for cosine distance");
    }

    // --- JCLAW-1005: a dimension change must not leave the leg enabled-and-wrong ---

    @Test
    void aFirstProvisionIsUsableBecauseThereIsNoColumnYet() {
        assertTrue(PgVectorProvisioner.dimensionUsable(1536, PgVectorProvisioner.COLUMN_ABSENT),
                "the normal first-provision case must not be blocked");
    }

    @Test
    void aMatchingExistingColumnIsUsable() {
        assertTrue(PgVectorProvisioner.dimensionUsable(768, 768));
    }

    @Test
    void aChangedDimensionRefusesTheVectorLeg() {
        // "ALTER TABLE ... ADD COLUMN IF NOT EXISTS" is a no-op once the column exists, so the
        // column keeps its ORIGINAL width forever. Enabling the leg anyway would fuse
        // new-model query vectors against old-model stored vectors — semantically wrong
        // neighbours — and semanticNeighbors backs both capture dedup and the forget matcher.
        assertFalse(PgVectorProvisioner.dimensionUsable(768, 1536),
                "a deployment provisioned at 1536 that switches to a 768-dim model must refuse");
        assertFalse(PgVectorProvisioner.dimensionUsable(1536, 768),
                "the mismatch is symmetric — widening is no safer than narrowing");
    }
}
