import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.graphspike.CandidateGenerator;
import services.graphspike.GraphCases;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;
import services.graphspike.GraphCases.Relation;
import services.graphspike.GraphSpikeScorer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * JCLAW-1356: the committed v2 cases load against the seed ontology and meet every composition target, a set short
 * of any target fails naming it, and each refusal rule fires, naming the case, on an edited copy of the file.
 */
class GraphCasesConformanceTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final Pattern URL = Pattern.compile("https?://[^\\s,]+");

    private static String tracked() throws Exception {
        return Files.readString(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH));
    }

    private static List<Case> committed() throws Exception {
        return GraphCases.parse(tracked(), SCHEMA);
    }

    /** Every composition target {@code cases} misses, each named. */
    static List<String> shortfalls(List<Case> cases) {
        var out = new ArrayList<String>();
        int n = cases.size();
        if (n < 120) out.add("at least 120 cases");
        long user = cases.stream().filter(c -> c.text().startsWith("The user")).count();
        if (user * 100 < 85L * n) out.add("at least 85% begin \"The user\"");
        if (cases.stream().anyMatch(c -> !GraphCases.operatorVoice(c.text()))) {
            out.add("every other case is subjectless");
        }
        double words = cases.stream().mapToInt(c -> c.text().split("\\s+").length).average().orElse(0);
        if (words < 17 || words > 23) out.add("mean length 17-23 words");
        for (var tag : GraphCases.HARD_NEGATIVE_TAGS) {
            if (cases.stream().filter(c -> c.tags().contains(tag)).count() < 12) out.add("at least 12 cases tagged " + tag);
        }
        long hard = cases.stream().filter(c -> c.tags().stream().anyMatch(GraphCases.HARD_NEGATIVE_TAGS::contains)).count();
        if (hard * 2 < n) out.add("hard-negative cases at least 50%");
        if (cases.stream().mapToInt(GraphSpikeScorer::gold).sum() < 420) out.add("at least 420 non-noise gold records");
        var types = new HashSet<String>();
        var relations = new HashSet<String>();
        var seenIn = new HashMap<String, Integer>();
        for (var c : cases) {
            c.entities().forEach(e -> {
                types.add(e.type());
                if (!e.operator()) seenIn.merge(e.id(), 1, Integer::sum);
            });
            c.relations().forEach(r -> relations.add(r.type()));
        }
        if (!types.equals(SCHEMA.termTypes().keySet())) out.add("every term type");
        if (!relations.equals(SCHEMA.relations().keySet())) out.add("every relation");
        if (seenIn.values().stream().filter(k -> k > 1).count() < 10) out.add("entity ids recur across cases");
        return out;
    }

    @Test
    void theCommittedSetMeetsEveryCompositionTarget() throws Exception {
        assertEquals(List.of(), shortfalls(committed()));
    }

    @Test
    void aSetShortOfATargetFailsNamingIt() throws Exception {
        var cases = committed();
        assertShort(cases.subList(0, 119), "at least 120 cases");
        assertShort(map(cases, c -> c.text().startsWith("The user")
                ? withText(c, "Someone" + c.text().substring("The user".length())) : c),
                "at least 85% begin \"The user\"", "every other case is subjectless");
        assertShort(map(cases, c -> withText(c, c.text() + " This sentence pads the memory well past its usual length.")),
                "mean length 17-23 words");
        assertShort(map(cases, c -> withTags(c, c.tags().stream().filter(t -> !t.equals("role")).toList())),
                "at least 12 cases tagged role");
        assertShort(map(cases, c -> withTags(c, List.of(GraphCases.PLAIN))), "hard-negative cases at least 50%");
        assertShort(map(cases, c -> new Case(c.id(), c.tags(), c.text(), c.entities().stream()
                .map(e -> new Entity(e.id(), e.mention(), e.type(), e.aliases(), e.implicit(), true)).toList(),
                c.relations().stream().map(r -> new Relation(r.from(), r.type(), r.to(), true)).toList(),
                c.negatives())), "at least 420 non-noise gold records");
        assertShort(map(cases, c -> new Case(c.id(), c.tags(), c.text(), c.entities(),
                c.relations().stream().filter(r -> !r.type().equals("derived_from")).toList(), c.negatives())),
                "every relation");
        assertShort(map(cases, c -> new Case(c.id(), c.tags(), c.text(),
                c.entities().stream().filter(e -> !e.type().equals("Event")).toList(), c.relations(), c.negatives())),
                "every term type");
        assertShort(map(cases, c -> new Case(c.id(), c.tags(), c.text(), c.entities().stream()
                .map(e -> e.operator() ? e : new Entity(c.id() + "-" + e.id(), e.mention(), e.type(), e.aliases(),
                        e.implicit(), e.noise())).toList(), c.relations(), c.negatives())),
                "entity ids recur across cases");
    }

    @Test
    void theCommittedCasesStaySyntheticAndOperatorVoiced() throws Exception {
        var cases = committed();
        for (var c : cases) {
            assertTrue(GraphCases.operatorVoice(c.text()), c.id() + " opens neither with \"The user\" nor a subjectless verb");
            assertEquals(1, c.entities().stream().filter(Entity::operator).count(), c.id() + ": exactly one operator");
            var operator = c.entity(GraphCases.OPERATOR);
            assertEquals(!c.text().toLowerCase().contains("the user"), operator != null && operator.implicit(),
                    c.id() + ": the operator is implicit exactly when the text never says \"the user\"");
            var m = URL.matcher(c.text());
            while (m.find()) {
                var host = m.group().replaceFirst("https?://", "").split("/")[0];
                assertTrue(host.equals("example.com") || host.endsWith(".example.com"), c.id() + ": " + m.group());
            }
        }
        assertTrue(cases.stream().anyMatch(c -> CandidateGenerator.subjectless(c.text())), "some cases are subjectless");
    }

    @Test
    void aNonVerbatimMentionIsRefused() throws Exception {
        assertRefused(first -> entity(first, 1).addProperty("mention", "Harborlight Analytic Group"), "is not verbatim");
    }

    @Test
    void aNonVerbatimAliasIsRefused() throws Exception {
        assertRefused(first -> {
            var aliases = new JsonArray();
            aliases.add("the firm's pipeline");
            entity(first, 2).add("aliases", aliases);
        }, "is not verbatim");
    }

    @Test
    void anUndeclaredTypeIsRefused() throws Exception {
        assertRefused(first -> entity(first, 1).addProperty("type", "Spaceship"), "undeclared type 'Spaceship'");
    }

    @Test
    void anIdTypedDifferentlyAcrossCasesIsRefused() throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        String id = null;
        for (int i = 1; i < cases.size() && id == null; i++) {
            for (var e : cases.get(i).getAsJsonObject().getAsJsonArray("entities")) {
                if (e.getAsJsonObject().get("id").getAsString().equals("harborlight")) {
                    e.getAsJsonObject().addProperty("type", "Project");
                    id = cases.get(i).getAsJsonObject().get("id").getAsString();
                    break;
                }
            }
        }
        assertNotNull(id, "harborlight recurs");
        var e = assertThrows(IllegalArgumentException.class, () -> GraphCases.parse(root.toString(), SCHEMA));
        assertTrue(e.getMessage().startsWith("case " + id + ": "), e.getMessage());
        assertTrue(e.getMessage().contains("'harborlight' is typed Project here and Organization elsewhere"),
                e.getMessage());
    }

    @Test
    void anEndpointThatIsNoCaseEntityIsRefused() throws Exception {
        assertRefused(first -> relation(first, 0).addProperty("to", "data-engineer"),
                "endpoint 'data-engineer' is not a case entity id");
    }

    @Test
    void aRelationTheSchemaDisallowsIsRefused() throws Exception {
        assertRefused(first -> {
            var r = relation(first, 0);
            r.addProperty("from", "harborlight");
            r.addProperty("to", "operator");
        }, "the schema does not allow Organization works_at Person");
    }

    @Test
    void twoRelationsOnOneOrderedPairAreRefused() throws Exception {
        assertRefused(first -> {
            var relations = first.getAsJsonArray("relations");
            relations.add(relations.get(0).deepCopy());
        }, "two relations on 'operator' -> 'harborlight'");
    }

    @Test
    void aNegativeThatIsAMentionOrAliasIsRefused() throws Exception {
        assertRefused(first -> first.getAsJsonArray("negatives").add("the team's deployment platform"),
                "negative 'the team's deployment platform' is a labelled mention or alias");
    }

    @Test
    void anUnknownTagIsRefused() throws Exception {
        assertRefused(first -> first.getAsJsonArray("tags").add("tricky"), "unknown tag 'tricky'");
    }

    @Test
    void aDuplicateCaseIdIsRefused() throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        var id = cases.get(0).getAsJsonObject().get("id").getAsString();
        cases.get(1).getAsJsonObject().addProperty("id", id);
        var e = assertThrows(IllegalArgumentException.class, () -> GraphCases.parse(root.toString(), SCHEMA));
        assertEquals("case " + id + ": duplicate id", e.getMessage());
    }

    private static void assertShort(List<Case> cases, String... targets) {
        var missed = shortfalls(cases);
        for (var target : targets) assertTrue(missed.contains(target), target + " not reported in " + missed);
    }

    private static List<Case> map(List<Case> cases, UnaryOperator<Case> edit) {
        return cases.stream().map(edit).toList();
    }

    private static Case withText(Case c, String text) {
        return new Case(c.id(), c.tags(), text, c.entities(), c.relations(), c.negatives());
    }

    private static Case withTags(Case c, List<String> tags) {
        return new Case(c.id(), tags, c.text(), c.entities(), c.relations(), c.negatives());
    }

    private static JsonObject entity(JsonObject c, int i) {
        return c.getAsJsonArray("entities").get(i).getAsJsonObject();
    }

    private static JsonObject relation(JsonObject c, int i) {
        return c.getAsJsonArray("relations").get(i).getAsJsonObject();
    }

    /** Edits the first committed case and expects a refusal naming it. */
    private static void assertRefused(Consumer<JsonObject> edit, String expected) throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        var first = root.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals("c001", first.get("id").getAsString(), "the edits assume the first committed case");
        edit.accept(first);
        var e = assertThrows(IllegalArgumentException.class, () -> GraphCases.parse(root.toString(), SCHEMA));
        assertTrue(e.getMessage().startsWith("case c001: "), e.getMessage());
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
