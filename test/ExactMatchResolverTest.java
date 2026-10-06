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
    void theCanonicalKeyFoldsCaseStripsADeterminerPossessiveAndPunctuation() {
        assertEquals("kestrel ci", ExactMatchResolver.normalize("  The   Kestrel CI's "));
        assertEquals("harborlight", ExactMatchResolver.normalize("Harborlight’s"));
        assertEquals("theremin", ExactMatchResolver.normalize("Theremin"), "only a whole leading word");
        assertEquals("larkspur inn", ExactMatchResolver.normalize("The Larkspur Inn"));
        assertEquals("larkspur inn", ExactMatchResolver.normalize("Larkspur Inn's"), "the possessive before punctuation");
        assertEquals("orchard", ExactMatchResolver.normalize("An Orchard"));
        assertEquals("team", ExactMatchResolver.normalize("a team"));
        assertEquals("wifi router", ExactMatchResolver.normalize("Wi-Fi  router!"));
        assertEquals("oat milks", ExactMatchResolver.normalize("oat milks"), "no singular outside a Topic");
    }

    @Test
    void aTopicsLastTokenIsReducedToItsSingular() {
        assertEquals("oat milk", ExactMatchResolver.normalize("oat milks", "Topic"));
        assertEquals("berry", ExactMatchResolver.normalize("berries", "Topic"));
        assertEquals("glass", ExactMatchResolver.normalize("glasses", "Topic"));
        assertEquals("box", ExactMatchResolver.normalize("boxes", "Topic"));
        assertEquals("match", ExactMatchResolver.normalize("matches", "Topic"));
        assertEquals("dish", ExactMatchResolver.normalize("dishes", "Topic"));
        assertEquals("house", ExactMatchResolver.normalize("houses", "Topic"));
        assertEquals("bus", ExactMatchResolver.normalize("bus", "Topic"));
        assertEquals("analysis", ExactMatchResolver.normalize("analysis", "Topic"));
        assertEquals("glass", ExactMatchResolver.normalize("glass", "Topic"));
        assertEquals("cats dog", ExactMatchResolver.normalize("cats dogs", "Topic"), "the last token only");
        assertEquals("oat milks", ExactMatchResolver.normalize("oat milks", "Organization"));
    }

    @Test
    void anIdentifierKeyIsTheIdentifierTrimmedWithEmailAndUrlSchemeAndHostWithoutCase() {
        assertTrue(ExactMatchResolver.isIdentifier(" https://Example.com/a "));
        assertTrue(ExactMatchResolver.isIdentifier("~/notes/plan.md"));
        assertTrue(ExactMatchResolver.isIdentifier("JCLAW-1370"));
        assertTrue(ExactMatchResolver.isIdentifier("Dana@Example.com"));
        assertFalse(ExactMatchResolver.isIdentifier("see https://example.com"), "wholly, not containing");
        assertFalse(ExactMatchResolver.isIdentifier("Harborlight Analytics"));
        assertEquals("https://example.com/A?q=B", ExactMatchResolver.identifierKey(" https://Example.COM/A?q=B "));
        assertEquals("dana@example.com", ExactMatchResolver.identifierKey("Dana@Example.com"));
        assertEquals("JCLAW-1370", ExactMatchResolver.identifierKey("JCLAW-1370"));
        assertEquals("~/Notes/plan.md", ExactMatchResolver.identifierKey("~/Notes/plan.md"));
        assertTrue(ExactMatchResolver.isIdentifier("/node_modules/@Types/X.d.ts"));
        assertEquals("/node_modules/@Types/X.d.ts", ExactMatchResolver.identifierKey("/node_modules/@Types/X.d.ts"),
                "a path that also reads as an email keeps its case");
    }

    @Test
    void edgePunctuationIsTrimmedBeforeTheDeterminerAndPossessive() {
        assertEquals("larkspur inn", ExactMatchResolver.normalize("Larkspur Inn's."));
        assertEquals("inn", ExactMatchResolver.normalize("\"The Inn\""));
        assertEquals("larkspur inn", ExactMatchResolver.normalize("(the Larkspur Inn’s)"));
        assertEquals("", ExactMatchResolver.normalize("..."));
    }

    @Test
    void identifiersClusterByTheirKeyWhateverTheTypeAndTopicsBySingular() {
        var clusters = ExactMatchResolver.resolve(List.of(
                new Mention("1", "https://Example.com/a", "Artifact", false),
                new Mention("2", "https://example.com/a", "System", false),
                new Mention("3", "oat milk", "Topic", false),
                new Mention("4", "Oat milks", "Topic", false)), null);
        assertEquals(List.of(List.of("1", "2"), List.of("3", "4")), clusters);
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
