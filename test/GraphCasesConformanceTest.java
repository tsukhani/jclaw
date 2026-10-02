import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.graphspike.GraphCases;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.function.Consumer;

/**
 * JCLAW-1344: the tracked graph-extraction cases load against the seed ontology and cover what the spec asks of
 * them, and each refusal rule fires, naming the case, on an edited copy of the tracked file.
 */
class GraphCasesConformanceTest extends UnitTest {

    private static String tracked() throws Exception {
        return Files.readString(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH));
    }

    @Test
    void theTrackedCasesLoadAndCoverEveryTypeAndRelation() throws Exception {
        var schema = OntologySchema.seed();
        var cases = GraphCases.parse(tracked(), schema);

        assertTrue(cases.size() >= 40, "at least 40 cases, got " + cases.size());
        var types = new HashSet<String>();
        var relations = new HashSet<String>();
        cases.forEach(c -> {
            c.entities().forEach(e -> types.add(e.type()));
            c.relations().forEach(r -> relations.add(r.type()));
        });
        assertEquals(schema.termTypes().keySet(), types);
        assertEquals(schema.relations().keySet(), relations);
        assertTrue(cases.stream().filter(c -> c.entities().isEmpty()).count() >= 8, "at least 8 no-entity cases");
        assertTrue(cases.stream().filter(c -> c.id().startsWith("distractor-")).count() >= 8,
                "at least 8 distractor cases");
    }

    @Test
    void theTrackedCasesStaySynthetic() throws Exception {
        for (var c : GraphCases.parse(tracked(), OntologySchema.seed())) {
            assertFalse(c.text().toLowerCase().contains("the user"), c.id() + " says 'the user'");
            for (var word : c.text().split("\\s+")) {
                if (word.startsWith("http")) assertTrue(word.startsWith("https://wiki.example.com/"), c.id() + ": " + word);
            }
        }
    }

    @Test
    void aNonVerbatimMentionIsRefused() throws Exception {
        assertRefused(first -> first.getAsJsonArray("entities").get(0).getAsJsonObject()
                .addProperty("mention", "Dan Reyes"), "is not verbatim");
    }

    @Test
    void anUndeclaredTypeIsRefused() throws Exception {
        assertRefused(first -> first.getAsJsonArray("entities").get(0).getAsJsonObject()
                .addProperty("type", "Spaceship"), "undeclared type 'Spaceship'");
    }

    @Test
    void aRelationTheSchemaDisallowsIsRefused() throws Exception {
        // Person works_at Organization is allowed; Organization works_at Person is not.
        assertRefused(first -> {
            var relation = first.getAsJsonArray("relations").get(0).getAsJsonObject();
            relation.addProperty("from", "Harborlight Analytics");
            relation.addProperty("to", "Dana Reyes");
        }, "the schema does not allow Organization works_at Person");
    }

    @Test
    void anEndpointThatIsNoLabelledEntityIsRefused() throws Exception {
        assertRefused(first -> first.getAsJsonArray("relations").get(0).getAsJsonObject()
                .addProperty("to", "data engineer"), "endpoint 'data engineer' is not a labelled entity");
    }

    @Test
    void aDuplicateIdIsRefused() throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        var id = cases.get(0).getAsJsonObject().get("id").getAsString();
        cases.get(1).getAsJsonObject().addProperty("id", id);
        var e = assertThrows(IllegalArgumentException.class,
                () -> GraphCases.parse(root.toString(), OntologySchema.seed()));
        assertEquals("case " + id + ": duplicate id", e.getMessage());
    }

    /** Edits the first tracked case and expects a refusal naming it. */
    private static void assertRefused(Consumer<JsonObject> edit, String expected) throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        var first = root.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals("rel-works-at", first.get("id").getAsString(), "the edits assume the first tracked case");
        edit.accept(first);
        var e = assertThrows(IllegalArgumentException.class,
                () -> GraphCases.parse(root.toString(), OntologySchema.seed()));
        assertTrue(e.getMessage().startsWith("case rel-works-at: "), e.getMessage());
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
