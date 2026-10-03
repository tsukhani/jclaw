import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.graphspike.ExactMatchResolver;
import services.graphspike.ExactMatchResolver.Mention;

import java.util.List;

/** JCLAW-1356: the baseline resolver merges only same-type, same-normalized-surface mentions. */
class ExactMatchResolverTest extends UnitTest {

    @Test
    void normalizingFoldsCaseStripsTheAndPossessiveAndCollapsesSpace() {
        assertEquals("kestrel ci", ExactMatchResolver.normalize("  The   Kestrel CI's "));
        assertEquals("harborlight", ExactMatchResolver.normalize("Harborlight’s"));
        assertEquals("theremin", ExactMatchResolver.normalize("Theremin"), "only a whole leading word");
    }

    @Test
    void sameTypeAndSurfaceMergeAndADifferentTypeDoesNot() {
        var clusters = ExactMatchResolver.resolve(List.of(
                new Mention("1", "Kestrel CI", "System", false),
                new Mention("2", "the kestrel ci", "System", false),
                new Mention("3", "Kestrel CI", "Project", false),
                new Mention("4", "The user", "Person", true),
                new Mention("5", "the user", "Person", true)));
        assertEquals(List.of(List.of("1", "2"), List.of("3"), List.of("4", "5")), clusters);
    }

    @Test
    void everyOperatorMentionJoinsOneClusterWhateverItsSurface() {
        var clusters = ExactMatchResolver.resolve(List.of(new Mention("1", "The user", "Person", true),
                new Mention("2", "the user", "Person", true), new Mention("3", "The User", "Person", false)));
        assertEquals(List.of(List.of("1", "2"), List.of("3")), clusters);
    }
}
