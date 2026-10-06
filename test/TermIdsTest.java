import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.TermIds;

/** JCLAW-1370: Term ids derive from data alone, so the same inputs give the same id in any run. */
class TermIdsTest extends UnitTest {

    @Test
    void theIdsArePinnedLiterals() {
        assertEquals("term:f2d261870f23ae1c", TermIds.distinctive(7, "Organization", "harborlight analytics"));
        assertEquals("term:8e09872fdbfd6bf5", TermIds.identifier(7, "https://example.com/a"));
        assertEquals("term:fbf22075dd6da8a6", TermIds.shortName(7, "Person", "dana", 1));
        assertEquals("term:a6a7260b3be2a56a", TermIds.owner(7));
        assertEquals("map:adbbc83fffe5c8ca", TermIds.derived("map", "x", "y"));
    }

    @Test
    void eachPartAndTheFamilyKeepIdsApart() {
        var base = TermIds.distinctive(7, "Organization", "harborlight");
        assertNotEquals(base, TermIds.distinctive(8, "Organization", "harborlight"), "agent");
        assertNotEquals(base, TermIds.distinctive(7, "Project", "harborlight"), "type");
        assertNotEquals(base, TermIds.distinctive(7, "Organization", "harborlight analytics"), "key");
        assertNotEquals(TermIds.distinctive(7, "Artifact", "x"), TermIds.identifier(7, "x"), "family");
        assertNotEquals(TermIds.shortName(7, "Person", "dana", 1), TermIds.shortName(7, "Person", "dana", 2), "memory");
        assertNotEquals(TermIds.owner(7), TermIds.owner(8));
        assertTrue(base.matches("term:[0-9a-f]{16}"), base);
    }
}
