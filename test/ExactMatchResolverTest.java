import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.ExactMatchResolver;
import services.grapheval.ExactMatchResolver.Mention;

import java.util.List;

/**
 * JCLAW-1356, JCLAW-1358: the baseline resolver merges only same-type, same-normalized-surface mentions, and the
 * declared owner's name as a Person joins the operator.
 */
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
                new Mention("5", "the user", "Person", true)), null);
        assertEquals(List.of(List.of("1", "2"), List.of("3"), List.of("4", "5")), clusters);
    }

    @Test
    void everyOperatorMentionJoinsOneClusterWhateverItsSurface() {
        var clusters = ExactMatchResolver.resolve(List.of(new Mention("1", "The user", "Person", true),
                new Mention("2", "the user", "Person", true), new Mention("3", "The User", "Person", false)), null);
        assertEquals(List.of(List.of("1", "2"), List.of("3")), clusters);
    }

    @Test
    void theOwnersNameAsAPersonJoinsTheOperatorCluster() {
        var mentions = List.of(new Mention("1", "The user", "Person", true),
                new Mention("2", "Avery Lin", "Person", false), new Mention("3", "Avery Lin's", "Person", false),
                new Mention("4", "Avery Lin", "Organization", false), new Mention("5", "Jonah Pell", "Person", false));
        assertEquals(List.of(List.of("1", "2", "3"), List.of("4"), List.of("5")),
                ExactMatchResolver.resolve(mentions, "Avery Lin"));
        assertEquals(List.of(List.of("1"), List.of("2", "3"), List.of("4"), List.of("5")),
                ExactMatchResolver.resolve(mentions, null), "with no owner name the name is an ordinary Person");
    }
}
