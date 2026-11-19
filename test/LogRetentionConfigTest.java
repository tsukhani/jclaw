import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import play.Play;
import play.test.UnitTest;
import utils.LogFootprint;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Retention policy of the shipped log4j2 configs (JCLAW-1057).
 *
 * <p>Guards a defect that was silent for 70 days: the configs declared
 * {@code <DefaultRolloverStrategy max="30"/>}, which reads as "keep 30 archives" but
 * bounds the {@code %i} counter — and the filePatterns were date-only, so there was no
 * counter to bound and nothing was ever pruned. Archives accumulated one per day until
 * the directory reached 97 MB.
 *
 * <p>{@code %i} has since been added, which is what makes {@code max} meaningful again —
 * so these tests pin both halves apart: retention comes from {@code Delete}, and
 * {@code max} is only the index cap.
 *
 * <p>Testing configuration rather than behavior is deliberate: whether log4j2 honors a
 * {@code Delete} action is log4j2's contract to keep, but whether JClaw still *asks* for
 * one is ours, and that is the half that regressed. Mirrors
 * {@code EvalSuiteConformanceTest}, which fails the build on a malformed dataset for the
 * same reason.
 */
class LogRetentionConfigTest extends UnitTest {

    private Element rollingFile(String configName) throws Exception {
        var file = new File(Play.applicationPath, "conf/" + configName);
        assertTrue(file.isFile(), "missing config: " + file);
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        var nodes = doc.getElementsByTagName("RollingFile");
        assertEquals(1, nodes.getLength(), configName + " should declare exactly one RollingFile");
        return (Element) nodes.item(0);
    }

    private Element deleteAction(Element rollingFile, String configName) {
        var strategies = rollingFile.getElementsByTagName("DefaultRolloverStrategy");
        assertEquals(1, strategies.getLength(), configName + " should declare one rollover strategy");
        var strategy = (Element) strategies.item(0);
        var deletes = strategy.getElementsByTagName("Delete");
        assertEquals(1, deletes.getLength(),
                configName + " must declare a Delete action or archives accumulate without bound");
        return (Element) deletes.item(0);
    }

    private void assertRetains(String configName, String ownArchive, String foreignArchive)
            throws Exception {
        var rolling = rollingFile(configName);
        var delete = deleteAction(rolling, configName);

        // A size policy can fire twice in one day. A date-only filePattern hands both
        // rollovers the same archive name and the second overwrites the first, losing
        // that morning's logs silently — %i is what keeps them distinct.
        if (rolling.getElementsByTagName("SizeBasedTriggeringPolicy").getLength() > 0) {
            var pattern = rolling.getAttribute("filePattern");
            assertTrue(pattern.contains("%i"),
                    configName + " rolls over on size, so its filePattern needs %i or a "
                            + "second rollover in one day overwrites the first: " + pattern);
        }

        var ages = delete.getElementsByTagName("IfLastModified");
        assertEquals(1, ages.getLength(), configName + " should bound retention by age");
        assertEquals("P30D", ((Element) ages.item(0)).getAttribute("age"),
                configName + " should keep 30 days of archives");

        var names = delete.getElementsByTagName("IfFileName");
        assertEquals(1, names.getLength(), configName + " should scope Delete to its own files");
        var regex = Pattern.compile(((Element) names.item(0)).getAttribute("regex"));

        assertTrue(regex.matcher(ownArchive).matches(),
                configName + " must prune its own archives; " + regex + " missed " + ownArchive);
        // The hazard this scoping exists for: a bare application-*.log.gz would also match
        // the test appender's files, so one config would prune another's history.
        assertFalse(regex.matcher(foreignArchive).matches(),
                configName + " must not prune another appender's archives; " + regex
                        + " matched " + foreignArchive);
    }

    /**
     * The Maintenance panel tells the operator how many days of logs are kept, reading
     * {@link LogFootprint#RETENTION_DAYS}. If the appender's window is edited without it,
     * the panel promises a retention the configuration does not implement.
     */
    @Test
    void thePanelsRetentionClaimMatchesTheAppenders() throws Exception {
        var delete = deleteAction(rollingFile("log4j2-prod.xml"), "log4j2-prod.xml");
        var age = ((Element) delete.getElementsByTagName("IfLastModified").item(0))
                .getAttribute("age");
        assertEquals("P" + LogFootprint.RETENTION_DAYS + "D", age,
                "LogFootprint.RETENTION_DAYS and the appender's IfLastModified must agree");
    }

    @Test
    void devConfigPrunesOnlyItsOwnArchives() throws Exception {
        assertRetains("log4j2.xml", "application-2026-06-06.log.gz",
                "application-test-2026-06-06.log.gz");
    }

    @Test
    void prodConfigPrunesOnlyItsOwnArchives() throws Exception {
        assertRetains("log4j2-prod.xml", "application-2026-06-06.log.gz",
                "application-test-2026-06-06.log.gz");
    }

    @Test
    void testConfigPrunesOnlyItsOwnArchives() throws Exception {
        assertRetains("log4j2-test.xml", "application-test-2026-06-06.log.gz",
                "application-2026-06-06.log.gz");
    }

    /**
     * {@code max} is the {@code %i} cap, not a retention window — confusing the two is
     * the original defect. Its default is 7, which at 50MB an archive would start
     * discarding a busy day's logs, so it has to be set deliberately once {@code %i} is
     * in the pattern.
     */
    @Test
    void theIndexCapLeavesRoomForABusyDay() throws Exception {
        var strategy = (Element) rollingFile("log4j2-prod.xml")
                .getElementsByTagName("DefaultRolloverStrategy").item(0);
        var max = strategy.getAttribute("max");
        assertFalse(max.isEmpty(),
                "max defaults to 7, so a %i pattern without it discards the 8th archive "
                        + "of any day");
        assertTrue(Integer.parseInt(max) >= 50,
                "the index cap should not bite before the age-based Delete does; got " + max);
    }

    /**
     * Archives written before {@code %i} was added carry no index. They must stay
     * prunable, or the pre-existing backlog is retained forever while new files rotate
     * correctly — the worst of both.
     */
    @Test
    void retentionSurvivesAnIndexedArchiveName() throws Exception {
        var delete = deleteAction(rollingFile("log4j2.xml"), "log4j2.xml");
        var regex = Pattern.compile(((Element) delete.getElementsByTagName("IfFileName").item(0))
                .getAttribute("regex"));
        assertTrue(regex.matcher("application-2026-06-06-2.log.gz").matches(),
                "an indexed archive must be prunable: " + regex);
        assertTrue(regex.matcher("application-2026-06-06.log.gz").matches(),
                "a pre-%i archive must stay prunable: " + regex);
    }
}
