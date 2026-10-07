import memory.ontology.OntologySchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.CompetencyQuestions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JCLAW-1374: the competency-question file is strict, and every refusal names the question or states the count. */
class CompetencyQuestionsTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final List<String> TICKET = List.of(
            "{\"id\": \"q01\", \"text\": \"Where does the owner work now, and where did they work before?\","
                    + " \"types\": [\"Person\", \"Organization\"], \"relation\": \"works_at\", \"facet\": \"status\"}",
            "{\"id\": \"q02\", \"text\": \"Which systems does a project use?\","
                    + " \"types\": [\"Project\", \"System\"], \"relation\": \"uses\", \"facet\": null}",
            "{\"id\": \"q03\", \"text\": \"What does the owner dislike?\","
                    + " \"types\": [\"Person\", \"Topic\"], \"relation\": \"holds_view_on\", \"facet\": \"valence\"}",
            "{\"id\": \"q04\", \"text\": \"What does the owner plan to do?\","
                    + " \"types\": [\"Person\"], \"relation\": null, \"facet\": \"plan\"}");

    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("competency");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var files = Files.list(dir)) {
            for (var f : files.toList()) Files.delete(f);
        }
        Files.delete(dir);
    }

    private static String generic(int n) {
        return "{\"id\": \"q%02d\", \"text\": \"Which places does a project mention, number %d?\", \"types\": [\"Place\"],"
                .formatted(n, n) + " \"relation\": null, \"facet\": null}";
    }

    /** {@code head} followed by generic questions up to {@code total}, numbered after the head. */
    private static String file(List<String> head, int total) {
        var all = new ArrayList<>(head);
        for (int n = head.size() + 1; all.size() < total; n++) all.add(generic(n));
        return "{\"questions\": [" + String.join(",\n", all) + "]}";
    }

    private List<CompetencyQuestions.Question> load(String json) throws Exception {
        var file = dir.resolve("questions.json");
        Files.writeString(file, json);
        return CompetencyQuestions.load(file, SCHEMA);
    }

    private void assertRefused(String question, String id, String expected) {
        var json = file(List.of(question), 20);
        var e = assertThrows(IllegalArgumentException.class, () -> load(json));
        assertTrue(e.getMessage().startsWith(id + ": "), e.getMessage());
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void theTicketsExampleLoadsInFileOrder() throws Exception {
        var questions = load(file(TICKET, 20));
        assertEquals(20, questions.size());
        assertEquals(new CompetencyQuestions.Question("q01",
                "Where does the owner work now, and where did they work before?", List.of("Person", "Organization"),
                "works_at", "status"), questions.get(0));
        assertNull(questions.get(1).facet());
        assertEquals("valence", questions.get(2).facet());
        assertEquals(new CompetencyQuestions.Question("q04", "What does the owner plan to do?", List.of("Person"),
                null, "plan"), questions.get(3));
        assertEquals("q20", questions.get(19).id());
    }

    @Test
    void theCountIsTwentyToForty() throws Exception {
        assertEquals(20, load(file(List.of(), 20)).size());
        assertEquals(40, load(file(List.of(), 40)).size());
        for (int n : new int[] {19, 41}) {
            var json = file(List.of(), n);
            var e = assertThrows(IllegalArgumentException.class, () -> load(json));
            assertTrue(e.getMessage().contains(n + " questions"), e.getMessage());
        }
    }

    @Test
    void theNearBoundaryClaimsLoad() throws Exception {
        var questions = load(file(List.of(
                "{\"id\": \"q01\", \"text\": \"When does an event take place?\", \"types\": [\"Event\"],"
                        + " \"relation\": null, \"facet\": \"occurs\"}",
                "{\"id\": \"q02\", \"text\": \"Since when has the owner worked where they work?\","
                        + " \"types\": [\"Person\", \"Organization\"], \"relation\": \"works_at\", \"facet\": \"valid\"}"),
                20));
        assertEquals("occurs", questions.get(0).facet());
        assertEquals("valid", questions.get(1).facet());
    }

    @Test
    void aBrokenQuestionIsRefusedByItsId() {
        var dup = file(List.of(generic(1), generic(1)), 20);
        var e = assertThrows(IllegalArgumentException.class, () -> load(dup));
        assertEquals("question q01: duplicate id", e.getMessage());

        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\"], \"relation\": null,"
                + " \"facet\": null, \"answer\": \"x\"}", "question q01", "unknown key 'answer'");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Spaceship\"], \"relation\": null,"
                + " \"facet\": null}", "question q01", "undeclared type");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Organization\", \"Person\"],"
                + " \"relation\": \"works_at\", \"facet\": null}", "question q01", "does not allow");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\"], \"relation\": null,"
                + " \"facet\": \"mood\"}", "question q01", "facet 'mood'");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\", \"Organization\"],"
                + " \"relation\": null, \"facet\": null}", "question q01", "exactly one type");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\"], \"relation\": \"works_at\","
                + " \"facet\": null}", "question q01", "two types");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Project\", \"System\"],"
                + " \"relation\": \"uses\", \"facet\": \"valence\"}", "question q01", "facet 'valence'");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\", \"Organization\"],"
                + " \"relation\": \"works_at\", \"facet\": \"occurs\"}", "question q01", "facet 'occurs'");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\"], \"relation\": null,"
                + " \"facet\": \"status\"}", "question q01", "facet 'status'");
        assertRefused("{\"id\": \"q01\", \"text\": \"   \", \"types\": [\"Person\"], \"relation\": null,"
                + " \"facet\": null}", "question q01", "'text'");
        assertRefused("{\"id\": \"q01\", \"text\": \"Who?\", \"types\": [\"Person\"], \"facet\": null}",
                "question q01", "'relation' is required");
        assertRefused("{\"id\": \"Q1\", \"text\": \"Who?\", \"types\": [\"Person\"], \"relation\": null,"
                + " \"facet\": null}", "question #0", "'id'");
        assertRefused("{\"id\": \"q1a\", \"text\": \"Who?\", \"types\": [\"Person\"], \"relation\": null,"
                + " \"facet\": null}", "question #0", "'id'");
    }

    @Test
    void anUnknownRootKeyIsRefused() {
        var json = file(List.of(), 20).replaceFirst("\\{", "{\"version\": 1, ");
        var e = assertThrows(IllegalArgumentException.class, () -> load(json));
        assertTrue(e.getMessage().contains("unknown key 'version'"), e.getMessage());
    }
}
