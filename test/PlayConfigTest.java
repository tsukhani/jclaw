import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import utils.PlayConfig;

/**
 * JCLAW-1136: an {@code application.conf} value that would not parse fell back to its default with
 * nothing said, the gap {@code ConfigService}'s getters had for the Config DB.
 *
 * <p>Every key is unique to its test: {@code Play.configuration} and the report gate are
 * process-global, and play1 runs test classes concurrently.
 */
class PlayConfigTest extends UnitTest {

    @Test
    void anUnparseableValueFallsBackAndIsReported() {
        var intKey = "jclaw1136.playconfig.int";
        var longKey = "jclaw1136.playconfig.long";
        Play.configuration.setProperty(intKey, "12 per minute");
        Play.configuration.setProperty(longKey, "1MB");
        try {
            assertEquals(7, PlayConfig.intOr(intKey, 7));
            assertEquals(1024L, PlayConfig.longOr(longKey, 1024L));
            // The gate is already spent, which is only true if the reader went through it.
            assertNull(PlayConfig.parseFailureReport(intKey, "12 per minute", "a whole number", "7"));
            assertNull(PlayConfig.parseFailureReport(longKey, "1MB", "a whole number", "1024"));
        } finally {
            Play.configuration.remove(intKey);
            Play.configuration.remove(longKey);
        }
    }

    @Test
    void aValueBelowTheMinimumIsRejectedAndReportedWhileAValidOneIsUsed() {
        var key = "jclaw1136.playconfig.atleast";
        try {
            Play.configuration.setProperty(key, "0");
            assertEquals(60, PlayConfig.intAtLeast(key, 1, 60));
            assertNull(PlayConfig.parseFailureReport(key, "0", "a whole number of at least 1", "60"),
                    "a value below the minimum must report");

            Play.configuration.setProperty(key, "90");
            assertEquals(90, PlayConfig.intAtLeast(key, 1, 60));
        } finally {
            Play.configuration.remove(key);
        }
    }

    @Test
    void aBlankValueTakesTheDefaultWithoutAReport() {
        // An emptied line means "use the default", which is what happens.
        var key = "jclaw1136.playconfig.blank";
        Play.configuration.setProperty(key, "  ");
        try {
            assertEquals(5, PlayConfig.intOr(key, 5));
            assertNotNull(PlayConfig.parseFailureReport(key, "  ", "a whole number", "5"),
                    "the gate must still be unspent for a blank value");
        } finally {
            Play.configuration.remove(key);
        }
    }
}
