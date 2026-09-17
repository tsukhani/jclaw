import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.DatabaseBootCheckPlugin;

/**
 * JCLAW-1136: an unreachable database at boot must explain itself in the 3-part template.
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
}
