import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.DatabaseBootCheckPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JCLAW-1136: an unreachable database at boot must explain itself in the 3-part template.
 * JF-266: the connection the check opens stays open until released, so the boot opens the
 * database once.
 *
 * <p>{@code DbSchedulerSchemaInitJob} already rendered one, but as an {@code @OnApplicationStart}
 * job it never ran for this failure: {@code DBPlugin} throws on its test connection first and
 * aborts {@code Play.start} before any job. These pin the check the plugin makes ahead of it.
 */
class DatabaseBootCheckPluginTest extends UnitTest {

    private static final String H2 = "org.h2.Driver";

    @Test
    void aRefusedConnectionRendersTheThreePartDatabaseMessage() {
        // Port 1 on loopback has no listener, so the H2 client is refused immediately.
        var message = DatabaseBootCheckPlugin.failureMessage("",
                "jdbc:h2:tcp://127.0.0.1:1/jclaw1136-bootcheck", H2, null, null, Play.classloader);

        assertNotNull(message, "a refused connection must produce the startup message");
        assertEquals(3, message.split("\n\n").length, "three parts: " + message);
        assertTrue(message.startsWith("What broke: The database refused the connection"), message);
        assertTrue(message.contains("db.url"), "the remedy names the connection settings: " + message);
    }

    @Test
    void aDatabaseThatAcceptsTheConnectionProducesNoMessage() {
        assertNull(DatabaseBootCheckPlugin.failureMessage("", "jdbc:h2:mem:jclaw1136-bootcheck-ok",
                H2, null, null, Play.classloader));
    }

    @Test
    void aConfigurationThisCheckCannotJudgeIsLeftForDBPluginToReport() {
        // Stopping boot on a guess would replace DBPlugin's specific error with a wrong one.
        var loader = Play.classloader;
        assertNull(DatabaseBootCheckPlugin.failureMessage("jndi:java:comp/env/jdbc/jclaw",
                "jdbc:h2:tcp://127.0.0.1:1/x", H2, null, null, loader), "a JNDI datasource");
        assertNull(DatabaseBootCheckPlugin.failureMessage("", null, H2, null, null, loader), "no URL");
        assertNull(DatabaseBootCheckPlugin.failureMessage("", "jdbc:h2:tcp://127.0.0.1:1/x",
                "no.such.Driver", null, null, loader), "a driver that cannot be loaded");
        assertNull(DatabaseBootCheckPlugin.failureMessage("", "jdbc:postgresql://127.0.0.1:1/x",
                H2, null, null, loader), "a driver that does not accept the URL");
    }

    @Test
    void aRefusedConnectionHoldsNothing() {
        var probe = DatabaseBootCheckPlugin.probe("", "jdbc:h2:tcp://127.0.0.1:1/jf266-refused",
                H2, null, null, Play.classloader);

        assertNull(probe.connection());
        assertNotNull(probe.message());
    }

    @Test
    void aConfigurationThisCheckCannotJudgeHoldsNothingAndSaysNothing() {
        var probe = DatabaseBootCheckPlugin.probe("", "jdbc:postgresql://127.0.0.1:1/x",
                H2, null, null, Play.classloader);

        assertNull(probe.connection());
        assertNull(probe.message());
    }

    @Test
    void aFileDatabaseIsHeldAndAnInMemoryOneIsNot() {
        // DBPlugin opens a mem: database as user sa; one this check created as "" would refuse it.
        assertTrue(DatabaseBootCheckPlugin.holds("jdbc:h2:retry:./data/jclaw;MODE=MYSQL;AUTO_SERVER=TRUE"));
        assertTrue(DatabaseBootCheckPlugin.holds("jdbc:h2:file:./data/jclaw"));
        assertFalse(DatabaseBootCheckPlugin.holds("jdbc:h2:mem:play;MODE=MYSQL;LOCK_MODE=0"));
        assertFalse(DatabaseBootCheckPlugin.holds(null));
    }

    /**
     * An in-memory H2 database lives exactly as long as its last connection, which makes it a
     * faithful stand-in for the file database that closed between the boot's three opens.
     */
    @Test
    void theHeldConnectionKeepsTheDatabaseOpenForLaterConnectionsUntilReleased() throws SQLException {
        var url = "jdbc:h2:mem:jf266-held";
        var probe = DatabaseBootCheckPlugin.probe("", url, H2, null, null, Play.classloader);
        var held = probe.connection();
        assertNotNull(held, "an accepting database yields the open connection");
        assertNull(probe.message());
        DatabaseBootCheckPlugin.hold(held);
        try (var statement = held.createStatement()) {
            statement.execute("CREATE TABLE jf266 (x INT)");
        }

        assertEquals(1, tableCount(url), "a later connection joins the database the held connection keeps open");

        DatabaseBootCheckPlugin.release();
        assertTrue(held.isClosed(), "release closes the held connection");
        assertEquals(0, tableCount(url), "once released, the database closed and a new connection starts afresh");

        DatabaseBootCheckPlugin.release();
        assertTrue(held.isClosed(), "a second release has nothing left to close");
    }

    private static int tableCount(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'JF266'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
