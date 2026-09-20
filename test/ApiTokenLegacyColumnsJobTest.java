import jobs.ApiTokenLegacyColumnsJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The upgrade path JCLAW-1266 broke, and the migration that repairs it.
 *
 * <p>JCLAW-1266 stopped persisting the internal token's plaintext, so it is minted on every boot
 * instead of once ever. On an installation created before JCLAW-282's operator-token API was
 * dropped, {@code api_token} still carries {@code display_prefix}, {@code name} and {@code scope}
 * as NOT NULL with no default — columns {@code ApiToken} no longer declares. The first boot after
 * the change died with {@code NULL not allowed for column "DISPLAY_PREFIX"}.
 *
 * <p><b>Why the suite could not see it.</b> The test database is create-drop in memory, so
 * Hibernate builds {@code api_token} from the current entity and those columns simply do not
 * exist. 590 green classes could not have caught it; only an upgrade could. So this test builds
 * the old schema itself.
 *
 * <p>On a throwaway database rather than the shared one: adding NOT NULL columns to
 * {@code api_token} would break every class the fork is running beside this one.
 */
class ApiTokenLegacyColumnsJobTest extends UnitTest {

    /** The pre-JCLAW-282 shape: NOT NULL, no default — which is what makes the insert fail. */
    private static void createLegacySchema(Statement s) throws SQLException {
        s.execute("CREATE TABLE api_token (id BIGINT PRIMARY KEY, owner_username VARCHAR(255) NOT NULL, "
                + "secret_hash VARCHAR(255) NOT NULL, created_at TIMESTAMP, last_used_at TIMESTAMP, "
                + "expires_at TIMESTAMP, revoked_at TIMESTAMP, "
                + "display_prefix VARCHAR(64) DEFAULT 'x' NOT NULL, "
                + "name VARCHAR(255) DEFAULT 'x' NOT NULL, "
                + "scope VARCHAR(64) DEFAULT 'x' NOT NULL)");
        // Added with a default so the ALTER is legal on a populated table, then dropped, leaving
        // the live shape: NOT NULL with nothing to fall back on.
        for (var column : new String[] {"display_prefix", "name", "scope"}) {
            s.execute("ALTER TABLE api_token ALTER COLUMN " + column + " DROP DEFAULT");
        }
    }

    /** The insert JCLAW-1266's mint issues — the entity's columns only. */
    private static void insertAsTheEntityWould(Statement s) throws SQLException {
        s.execute("INSERT INTO api_token (id, owner_username, secret_hash, created_at) "
                + "VALUES (1, 'system', 'hash', CURRENT_TIMESTAMP)");
    }

    @Test
    void theMigrationMakesAPreJclaw282SchemaMintable() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:legacy-api-token-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")) {
            try (Statement s = conn.createStatement()) {
                createLegacySchema(s);
            }

            // RED without the migration: this is the production failure, reproduced.
            try (Statement s = conn.createStatement()) {
                insertAsTheEntityWould(s);
                fail("expected the insert to fail on the legacy schema — if it succeeds the fixture "
                        + "no longer reproduces the bug and this test proves nothing");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().toUpperCase().contains("DISPLAY_PREFIX"),
                        "expected the NOT NULL violation this migration exists for, got: " + expected.getMessage());
            }

            assertEquals(3, ApiTokenLegacyColumnsJob.relaxAbandonedColumns(conn),
                    "all three abandoned columns should have been relaxed");

            // GREEN after it: the same insert now succeeds.
            try (Statement s = conn.createStatement()) {
                insertAsTheEntityWould(s);
            }
        }
    }

    @Test
    void theMigrationIsANoOpOnAFreshInstall() throws Exception {
        // Hibernate builds api_token from the current entity, so the abandoned columns are absent
        // and there is nothing to relax. Running every boot must stay free.
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:fresh-api-token-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE api_token (id BIGINT PRIMARY KEY, owner_username VARCHAR(255) NOT NULL, "
                        + "secret_hash VARCHAR(255) NOT NULL, created_at TIMESTAMP)");
            }
            assertEquals(0, ApiTokenLegacyColumnsJob.relaxAbandonedColumns(conn));
            assertEquals(0, ApiTokenLegacyColumnsJob.relaxAbandonedColumns(conn),
                    "a second run must also be a no-op — this job runs on every boot");
        }
    }
}
