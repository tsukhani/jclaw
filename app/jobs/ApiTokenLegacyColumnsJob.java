package jobs;

import mcp.McpGrantKeyMigration;
import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Relax the NOT NULL on {@code api_token}'s three abandoned columns (JCLAW-1266).
 *
 * <p>{@code display_prefix}, {@code name} and {@code scope} belonged to the operator-token API
 * that shipped with JCLAW-282 and was dropped after the mcp-server pivot. The fields left
 * {@link models.ApiToken}; the columns stayed in every database created before that, still
 * NOT NULL and with no default.
 *
 * <p>Nothing noticed for as long as a row was inserted once and reused forever. JCLAW-1266 stopped
 * persisting the internal token's plaintext, so it is minted on every boot — and the very first
 * boot after that change dies with
 * {@code NULL not allowed for column "DISPLAY_PREFIX"} before the app finishes starting. A fresh
 * install is unaffected, because Hibernate creates the table from the current entity and those
 * columns never exist; only an upgrade breaks, which is why no test caught it.
 *
 * <p>Relaxed rather than dropped. Dropping is the tidier end state and it is not this job's call
 * to make on an operator's data during a boot it is already failing; a nullable dead column costs
 * nothing and leaves the decision reversible.
 *
 * <p><b>Priority is load-bearing.</b> {@link DefaultConfigJob} runs at {@code -100} and calls
 * {@code InternalApiTokenService.token()}, which is the mint that fails. At the default priority
 * this job would run after it and never get the chance — the boot would already be over. It
 * therefore runs ahead of everything, and {@code BootJobOrderConformanceTest} fails the build if
 * anything else claims a priority at or below this one.
 */
@OnApplicationStart(priority = -200)
@NoTransaction
public class ApiTokenLegacyColumnsJob extends Job<Void> {

    /** Dropped from {@link models.ApiToken} with the operator-token API; still in older schemas. */
    private static final String[] ABANDONED_COLUMNS = {"DISPLAY_PREFIX", "NAME", "SCOPE"};

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            relaxAbandonedColumns();
        } catch (SQLException e) {
            EventLogger.error("system", "api_token legacy-column migration failed: " + e.getMessage());
            throw new IllegalStateException("api_token legacy-column migration failed", e);
        }
    }

    /**
     * No-op on a database whose columns are already nullable or absent, so this is safe on every
     * boot and on a fresh install.
     *
     * @return how many columns this run actually altered
     */
    public static int relaxAbandonedColumns() throws SQLException {
        int altered;
        try (Connection conn = DB.getDataSource().getConnection()) {
            altered = relaxAbandonedColumns(conn);
            conn.commit();
        }
        if (altered > 0) {
            EventLogger.info("system",
                    "Relaxed NOT NULL on %d abandoned api_token column(s) so the internal token can "
                            + "be minted (JCLAW-1266)".formatted(altered));
        }
        return altered;
    }

    /**
     * The migration against a caller-supplied connection, without committing.
     *
     * <p>Split out so a test can drive it against a throwaway database carrying the pre-JCLAW-282
     * schema. Building that schema on the shared test database instead would add NOT NULL columns
     * to {@code api_token} for every class the fork is running concurrently, which is a flake
     * rather than a fixture.
     */
    // Public because Play's tests live in the default package.
    public static int relaxAbandonedColumns(Connection conn) throws SQLException {
        int altered = 0;
        for (var column : ABANDONED_COLUMNS) {
            if (McpGrantKeyMigration.relaxNotNull(conn, "API_TOKEN", column)) altered++;
        }
        return altered;
    }
}
