import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator;
import services.grapheval.GraphCases;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Entity;
import services.grapheval.GraphCases.Relation;
import services.grapheval.GraphEvalScorer;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * JCLAW-1356, JCLAW-1358: the committed v2 cases load against the seed ontology and meet every composition target, a
 * set short of any target fails naming it, and each refusal rule fires, naming the case, on an edited copy of the file.
 */
class GraphCasesConformanceTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final Pattern URL = Pattern.compile("https?://[^\\s,]+");
    private static final String OWNER = "Avery Lin";

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
        if (user * 100 < 12L * n || user * 100 > 17L * n) out.add("12-17% begin \"The user\"");
        long owner = cases.stream().filter(c -> c.text().startsWith(OWNER)).count();
        if (owner * 100 < 60L * n) out.add("at least 60% begin \"" + OWNER + "\"");
        if (cases.stream().anyMatch(c -> !GraphCases.operatorVoice(c.text(), OWNER) && !guest(c))) {
            out.add("every case is owner-voiced or guest");
        }
        var guests = cases.stream().filter(GraphCasesConformanceTest::guest).toList();
        if (guests.size() < 6 || guests.stream().noneMatch(c -> c.text().startsWith("A guest"))) {
            out.add("at least 6 guest cases, one opening \"A guest\"");
        }
        double words = cases.stream().mapToInt(c -> c.text().split("\\s+").length).average().orElse(0);
        if (words < 17 || words > 23) out.add("mean length 17-23 words");
        for (var tag : GraphCases.HARD_NEGATIVE_TAGS) {
            if (cases.stream().filter(c -> c.tags().contains(tag)).count() < 12) out.add("at least 12 cases tagged " + tag);
        }
        long hard = cases.stream().filter(c -> c.tags().stream().anyMatch(GraphCases.HARD_NEGATIVE_TAGS::contains)).count();
        if (hard * 2 < n) out.add("hard-negative cases at least 50%");
        if (cases.stream().mapToInt(c -> GraphEvalScorer.gold(c, SCHEMA)).sum() < 420) out.add("at least 420 non-noise gold records");
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

    private static boolean guest(Case c) {
        return c.tags().contains(GraphCases.GUEST);
    }

    /** What is wrong with a guest case's operator labels, or null when nothing is. */
    static @Nullable String guestViolation(Case c) {
        boolean aboutOwner = c.tags().contains(GraphCases.GUEST_ABOUT_OWNER);
        if (!aboutOwner && c.entity(GraphCases.OPERATOR) != null) return c.id() + ": a guest case has no operator";
        if (c.relations().stream().anyMatch(r -> r.reaches(GraphCases.OPERATOR)
                && !(aboutOwner && r.status().equals(GraphCases.UNASSERTED)))) {
            return c.id() + ": a guest case relates nothing to the operator";
        }
        return CandidateGenerator.subjectless(c.text()) ? c.id() + ": a guest case is not subjectless" : null;
    }

    @Test
    void theCommittedSetMeetsEveryCompositionTarget() throws Exception {
        assertEquals(List.of(), shortfalls(committed()));
    }

    @Test
    void aSetShortOfATargetFailsNamingIt() throws Exception {
        var cases = committed();
        assertShort(cases.subList(0, 119), "at least 120 cases");
        assertShort(map(cases, c -> c.text().startsWith("The user") || c.text().startsWith(OWNER)
                ? withText(c, "Someone" + c.text().substring(c.text().startsWith(OWNER) ? OWNER.length() : "The user".length()))
                : c), "12-17% begin \"The user\"", "at least 60% begin \"" + OWNER + "\"",
                "every case is owner-voiced or guest");
        assertShort(cases.stream().filter(c -> !guest(c)).toList(), "at least 6 guest cases, one opening \"A guest\"");
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
    void theUserBandFailsJustOutsideItAndPassesAtItsEdges() throws Exception {
        var cases = committed();
        assertEquals(140, cases.size(), "the band edges below assume 140 cases");
        assertShort(withUserOpeners(cases, 16), "12-17% begin \"The user\"");
        assertFalse(shortfalls(withUserOpeners(cases, 17)).contains("12-17% begin \"The user\""));
        assertFalse(shortfalls(withUserOpeners(cases, 23)).contains("12-17% begin \"The user\""));
        assertShort(withUserOpeners(cases, 24), "12-17% begin \"The user\"");
    }

    @Test
    void theOwnerShareFailsJustBelowSixtyPercentAndPassesAtIt() throws Exception {
        var cases = committed();
        assertEquals(140, cases.size(), "84 owner openers is exactly 60% of 140");
        var target = "at least 60% begin \"" + OWNER + "\"";
        assertFalse(shortfalls(withUserOpeners(cases, 31)).contains(target));
        assertShort(withUserOpeners(cases, 32), target);
    }

    @Test
    void theGuestFloorFailsAtFiveAndPassesAtSix() throws Exception {
        var cases = committed();
        var target = "at least 6 guest cases, one opening \"A guest\"";
        assertFalse(shortfalls(withGuests(cases, 6)).contains(target));
        assertShort(withGuests(cases, 5), target);
    }

    /** {@code cases} keeping only {@code k} guest cases, the one opening "A guest" among them. */
    private static List<Case> withGuests(List<Case> cases, int k) {
        var keep = cases.stream().filter(GraphCasesConformanceTest::guest)
                .sorted(Comparator.comparing(c -> !c.text().startsWith("A guest"))).limit(k).toList();
        return cases.stream().filter(c -> !guest(c) || keep.contains(c)).toList();
    }

    /** {@code cases} with exactly the first {@code k} owner-voiced, named cases opening "The user", the rest the owner. */
    private static List<Case> withUserOpeners(List<Case> cases, int k) {
        var out = new ArrayList<Case>();
        int given = 0;
        for (var c : cases) {
            var t = c.text();
            var rest = t.startsWith("The user") ? t.substring("The user".length())
                    : t.startsWith(OWNER) ? t.substring(OWNER.length()) : null;
            out.add(rest == null ? c : withText(c, (given++ < k ? "The user" : OWNER) + rest));
        }
        return out;
    }

    @Test
    void theCommittedCasesStaySyntheticAndOperatorVoiced() throws Exception {
        assertEquals(OWNER, GraphCases.ownerName(tracked()));
        var cases = committed();
        for (var c : cases) {
            var m = URL.matcher(c.text());
            while (m.find()) {
                var host = m.group().replaceFirst("https?://", "").split("/")[0];
                assertTrue(host.equals("example.com") || host.endsWith(".example.com"), c.id() + ": " + m.group());
            }
            if (guest(c)) {
                assertNull(guestViolation(c));
                continue;
            }
            assertTrue(GraphCases.operatorVoice(c.text(), OWNER),
                    c.id() + " opens neither with \"The user\", \"" + OWNER + "\" nor a subjectless verb");
            assertEquals(1, c.entities().stream().filter(Entity::operator).count(), c.id() + ": exactly one operator");
            boolean user = c.text().toLowerCase().contains("the user");
            boolean named = c.text().contains(OWNER);
            assertFalse(user && named, c.id() + ": says both \"" + OWNER + "\" and \"the user\"");
            var operator = c.entity(GraphCases.OPERATOR);
            assertEquals(!user && !named, operator != null && operator.implicit(),
                    c.id() + ": the operator is implicit exactly when the text names neither the owner nor the user");
        }
        assertTrue(cases.stream().anyMatch(c -> CandidateGenerator.subjectless(c.text())), "some cases are subjectless");
    }

    @Test
    void aSetWithoutAUsableUserMdDeclaresNoOwner() throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        root.addProperty("userMd", "Name: <your name>");
        assertNull(GraphCases.ownerName(root.toString()));
        root.remove("userMd");
        assertNull(GraphCases.ownerName(root.toString()));
    }

    @Test
    void aGuestCaseRelatedToTheOperatorIsRefused() throws Exception {
        var guests = committed().stream().filter(GraphCasesConformanceTest::guest)
                .filter(c -> !c.relations().isEmpty()).toList();
        var related = guests.get(0);
        var person = related.entities().getFirst();
        var withOperator = new Case(related.id(), related.tags(), related.text(), related.entities(),
                List.of(Relation.of(GraphCases.OPERATOR, "family_of", person.id())), related.negatives());
        assertEquals(related.id() + ": a guest case relates nothing to the operator", guestViolation(withOperator));
        var entities = new ArrayList<>(related.entities());
        entities.add(Entity.implicitOperator());
        assertEquals(related.id() + ": a guest case has no operator", guestViolation(new Case(related.id(),
                related.tags(), related.text(), entities, related.relations(), related.negatives())));
        var sibling = guests.get(1);
        assertTrue(sibling.relations().stream().noneMatch(r -> r.from().equals(GraphCases.OPERATOR)
                || r.to().equals(GraphCases.OPERATOR)), sibling.id());
        assertNull(guestViolation(sibling));
    }

    @Test
    void aNamedOperatorMentionThatIsNotTheDeclaredOwnerIsRefused() throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        JsonObject named = null;
        for (var c : cases) {
            if (c.getAsJsonObject().get("text").getAsString().startsWith(OWNER + " ")) {
                named = c.getAsJsonObject();
                break;
            }
        }
        assertNotNull(named, "a case names the owner");
        var id = named.get("id").getAsString();
        named.addProperty("text", "Avery" + named.get("text").getAsString().substring(OWNER.length()));
        entity(named, 0).addProperty("mention", "Avery");
        var e = assertThrows(IllegalArgumentException.class, () -> GraphCases.parse(root.toString(), SCHEMA));
        assertTrue(e.getMessage().startsWith("case " + id + ": "), e.getMessage());
        assertTrue(e.getMessage().contains("'Avery'"), e.getMessage());

        named.addProperty("text", "The user" + named.get("text").getAsString().substring("Avery".length()));
        entity(named, 0).addProperty("mention", "The user");
        assertEquals(140, GraphCases.parse(root.toString(), SCHEMA).size());
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

    /** Edits the case {@code id} of the committed set and parses the result. */
    private static List<Case> parseEdited(String id, Consumer<JsonObject> edit) throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        for (var c : root.getAsJsonArray("cases")) {
            if (c.getAsJsonObject().get("id").getAsString().equals(id)) edit.accept(c.getAsJsonObject());
        }
        return GraphCases.parse(root.toString(), SCHEMA);
    }

    private static Case parsed(String id, Consumer<JsonObject> edit) throws Exception {
        return parseEdited(id, edit).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertRootRefused(Consumer<JsonObject> edit, String expected) throws Exception {
        var root = JsonParser.parseString(tracked()).getAsJsonObject();
        edit.accept(root);
        var e = assertThrows(IllegalArgumentException.class, () -> GraphCases.parse(root.toString(), SCHEMA));
        assertEquals(expected, e.getMessage());
    }

    @Test
    void anUnknownKeyAtAnyLevelIsRefusedNamingTheCaseAndTheKey() throws Exception {
        assertRootRefused(root -> root.addProperty("version", 3), "graph cases: unknown key 'version'");
        assertRootRefused(root -> root.remove("capturedAt"), "graph cases: the root needs 'capturedAt'");
        assertRefused(first -> first.addProperty("source", "x"), "unknown key 'source'");
        assertRefused(first -> entity(first, 1).addProperty("role", "x"), "unknown key 'role'");
        assertRefused(first -> relation(first, 0).addProperty("since", "2019"), "unknown key 'since'");
        assertEquals(LocalDate.parse("2026-03-01"), parsed("c001", first -> first.addProperty("capturedAt", "2026-03-01"))
                .capturedAt(), "a case's capturedAt overrides the root's");
    }

    @Test
    void aRelationWithoutAStatusIsRefused() throws Exception {
        assertRefused(first -> relation(first, 0).remove("status"), "has no 'status'");
        assertEquals(GraphCases.ENDED, parsed("c001", first -> relation(first, 0).addProperty("status", "ended"))
                .relations().getFirst().status());
    }

    @Test
    void aValidTheFormatDoesNotAllowIsRefused() throws Exception {
        assertRefused(first -> {
            relation(first, 0).addProperty("status", "unasserted");
            relation(first, 0).addProperty("valid", "2019");
        }, "takes no 'valid'");
        assertEquals("2019/..", parsed("c001", first -> relation(first, 0).addProperty("valid", "2019/.."))
                .relations().getFirst().valid());
        var e = assertThrows(IllegalArgumentException.class,
                () -> parseEdited("c011", c -> relation(c, 0).addProperty("valid", "2019/..")));
        assertTrue(e.getMessage().endsWith("takes no 'valid'"), "a holds from an Event: " + e.getMessage());
    }

    @Test
    void aDenialsValidIsExactlyTheNeverForm() throws Exception {
        assertRefused(first -> {
            relation(first, 0).addProperty("status", "denied");
            relation(first, 0).addProperty("valid", "../2026-02-14");
        }, "a denial's 'valid' is exactly '../2026-02-15'");
        assertEquals("../2026-02-15", parsed("c001", first -> {
            relation(first, 0).addProperty("status", "denied");
            relation(first, 0).addProperty("valid", "../2026-02-15");
        }).relations().getFirst().valid());
    }

    @Test
    void aValenceOffHoldsViewOnIsRefused() throws Exception {
        assertRefused(first -> relation(first, 0).addProperty("valence", "favorable"), "takes no 'valence'");
        assertEquals("unfavorable", parsed("c005", c -> relation(c, 0).addProperty("valence", "unfavorable"))
                .relations().getFirst().valence(), "a valence the lexicon would not give still parses");
    }

    @Test
    void noiseOnADenialOrAnUnassertedIsRefused() throws Exception {
        for (var status : List.of("denied", "unasserted")) {
            assertRefused(first -> {
                relation(first, 0).addProperty("status", status);
                relation(first, 0).addProperty("noise", true);
            }, "'noise' only marks a holds or ended relation, not " + status);
        }
        assertTrue(parsed("c001", first -> relation(first, 0).addProperty("noise", true)).relations().getFirst().noise());
    }

    @Test
    void occursOnANonEventIsRefused() throws Exception {
        assertRefused(first -> entity(first, 1).addProperty("occurs", "2026"), "only an Event occurs");
        assertEquals("2027-10", parsed("c011", c -> entity(c, 2).addProperty("occurs", "2027-10"))
                .entity("quillon").occurs(), "an occurs the text does not support still parses");
        var e = assertThrows(IllegalArgumentException.class,
                () -> parseEdited("c011", c -> entity(c, 2).addProperty("occurs", "2027/..")));
        assertTrue(e.getMessage().endsWith("'occurs' is a date or a closed interval"), e.getMessage());
        assertEquals("2027-10/2027-11", parsed("c011", c -> entity(c, 2).addProperty("occurs", "2027-10/2027-11"))
                .entity("quillon").occurs(), "a closed interval of two dates parses");
    }

    @Test
    void aSecondDenialOnOnePairIsRefusedButADenialBesideAPositiveParses() throws Exception {
        assertRefused(first -> {
            var denial = relation(first, 0).deepCopy();
            denial.addProperty("status", "denied");
            first.getAsJsonArray("relations").add(denial);
            first.getAsJsonArray("relations").add(denial.deepCopy());
        }, "two denied relations on 'operator' -> 'harborlight'");
        var c = parsed("c001", first -> {
            var denial = relation(first, 0).deepCopy();
            denial.addProperty("status", "denied");
            first.getAsJsonArray("relations").add(denial);
        });
        assertEquals(3, c.relations().size());
    }

    @Test
    void aDateOutsideTheEdtfSubsetOrNotVerbatimIsRefused() throws Exception {
        assertRefused(first -> first.add("dates", JsonParser.parseString(
                "[{\"span\":\"every release\",\"value\":\"June 2019\"}]")), "is outside the EDTF subset");
        assertRefused(first -> first.add("dates", JsonParser.parseString(
                "[{\"span\":\"2019\",\"value\":\"2019\"}]")), "date span '2019' is not verbatim in the text");
        var c = parsed("c001", first -> first.add("dates", JsonParser.parseString(
                "[{\"span\":\"every release\",\"value\":\"2019-21\"}]")));
        assertEquals("2019-21", c.dates().getFirst().value(), "a value the normalizer would not produce still parses");
        assertNull(parsed("c001", first -> first.add("dates", JsonParser.parseString(
                "[{\"span\":\"every release\",\"value\":null}]"))).dates().getFirst().value());
    }

    @Test
    void theFiveNewTagsParseAndHardNegativesKeepTheirSix() throws Exception {
        var tags = parsed("c001", first -> List.of("ended", "negated", "unasserted", "dated")
                .forEach(t -> first.getAsJsonArray("tags").add(t))).tags();
        assertTrue(tags.containsAll(List.of("ended", "negated", "unasserted", "dated")), tags.toString());
        assertTrue(parsed("c132", c -> c.getAsJsonArray("tags").add(GraphCases.GUEST_ABOUT_OWNER)).tags()
                .contains(GraphCases.GUEST_ABOUT_OWNER));
        assertRefused(first -> first.getAsJsonArray("tags").add(GraphCases.GUEST_ABOUT_OWNER),
                "'guest-about-owner' is always tagged beside 'guest'");
        assertEquals(6, GraphCases.HARD_NEGATIVE_TAGS.size());
        assertTrue(GraphCases.HARD_NEGATIVE_TAGS.stream().noneMatch(List.of("ended", "negated", "unasserted", "dated",
                GraphCases.GUEST_ABOUT_OWNER)::contains));
    }

    @Test
    void aGuestAboutOwnerCaseMayNameTheOperatorButAssertsNothingOfIt() throws Exception {
        Consumer<JsonObject> aboutOwner = c -> {
            c.getAsJsonArray("tags").add(GraphCases.GUEST_ABOUT_OWNER);
            c.getAsJsonArray("entities").add(JsonParser.parseString(
                    "{\"id\":\"operator\",\"type\":\"Person\",\"implicit\":true}"));
            c.getAsJsonArray("relations").add(JsonParser.parseString(
                    "{\"from\":\"operator\",\"type\":\"family_of\",\"to\":\"jonah-pell\",\"status\":\"unasserted\"}"));
        };
        var c = parsed("c132", aboutOwner);
        assertNull(guestViolation(c), "a guest-about-owner case may name the operator");
        var e = assertThrows(IllegalArgumentException.class, () -> parseEdited("c132", aboutOwner.andThen(o -> relation(o,
                o.getAsJsonArray("relations").size() - 1).addProperty("status", "holds"))));
        assertTrue(e.getMessage().contains("must be unasserted"), e.getMessage());
        var plain = new Case(c.id(), List.of(GraphCases.GUEST), c.text(), c.entities(), c.relations(), c.negatives());
        assertEquals(c.id() + ": a guest case has no operator", guestViolation(plain), "a plain guest case still may not");
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
