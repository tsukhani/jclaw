import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import memory.ontology.OntologySchema.Identifier;
import memory.ontology.OntologySchema.Match;
import memory.ontology.OntologyValidator;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class OntologySchemaTest extends UnitTest {

    private static String seedText() throws IOException {
        return Files.readString(Play.applicationPath.toPath().resolve(OntologySchema.SEED_PATH));
    }

    @Test
    void theSeedDeclaresExactlyTheFamiliesOfTheSealedInterface() {
        var permits = Arrays.stream(OntologyRecord.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
        var schema = OntologySchema.seed();
        assertEquals(Set.of("Term", "Mapping", "Relation", "Constraint", "Evidence"), permits);
        assertEquals(permits, schema.families().keySet());
        assertEquals(new Identifier("prov:Entity", Match.CLOSE), schema.families().get("Evidence").identifier());
        assertEquals(new Identifier("none", null), schema.families().get("Term").identifier());
    }

    @Test
    void theSeedReferencesMirrorTheValidatorsConstant() {
        assertEquals(OntologyValidator.STRUCTURAL_REFERENCES, OntologySchema.seed().references());
    }

    @Test
    void theSeedDeclaresTheTicketsEightTermTypes() {
        var expected = Map.of(
                "Person", new Identifier("schema:Person", Match.EXACT),
                "Organization", new Identifier("schema:Organization", Match.EXACT),
                "Project", new Identifier("schema:Project", Match.CLOSE),
                "System", new Identifier("schema:SoftwareApplication", Match.CLOSE),
                "Artifact", new Identifier("schema:CreativeWork", Match.CLOSE),
                "Place", new Identifier("schema:Place", Match.EXACT),
                "Event", new Identifier("schema:Event", Match.EXACT),
                "Topic", new Identifier("skos:Concept", Match.EXACT));
        var termTypes = OntologySchema.seed().termTypes();
        assertEquals(8, termTypes.size());
        assertEquals(expected.keySet(), termTypes.keySet());
        expected.forEach((name, id) -> assertEquals(id, termTypes.get(name).identifier(), name));
    }

    @Test
    void theSeedDeclaresTheTicketsTwelveRelationsWithTheirEndpoints() {
        record Expected(String kind, Identifier id, List<String> endpoints) {}
        var none = new Identifier("none", null);
        var expected = Map.ofEntries(
                Map.entry("works_at", new Expected("Association",
                        new Identifier("schema:worksFor", Match.EXACT), List.of("Person -> Organization"))),
                Map.entry("works_on", new Expected("Association", none, List.of("Person, Organization -> Project"))),
                Map.entry("uses", new Expected("Association", none,
                        List.of("Person, Organization, Project -> System"))),
                Map.entry("owns", new Expected("Association", new Identifier("schema:owns", Match.CLOSE),
                        List.of("Person, Organization -> System, Artifact, Place"))),
                Map.entry("family_of", new Expected("Association",
                        new Identifier("schema:relatedTo", Match.EXACT), List.of("Person -> Person"))),
                Map.entry("located_in", new Expected("Association", new Identifier("schema:location", Match.CLOSE),
                        List.of("Person, Organization, Event -> Place"))),
                Map.entry("involves", new Expected("Association", none,
                        List.of("Event -> Person, Organization, Project"))),
                Map.entry("holds_view_on", new Expected("Association", none, List.of("Person -> Topic"))),
                Map.entry("part_of", new Expected("Composition", new Identifier("schema:isPartOf", Match.CLOSE),
                        List.of("Organization -> Organization", "Project -> Project", "System -> System",
                                "Artifact -> Project"))),
                Map.entry("kind_of", new Expected("Hierarchy", new Identifier("skos:broader", Match.EXACT),
                        List.of("same"))),
                Map.entry("same_as", new Expected("Equivalence", new Identifier("schema:sameAs", Match.CLOSE),
                        List.of("same"))),
                Map.entry("derived_from", new Expected("Derivation",
                        new Identifier("prov:wasDerivedFrom", Match.EXACT), List.of("Artifact -> Artifact, Project"))));
        var relations = OntologySchema.seed().relations();
        assertEquals(12, relations.size());
        assertEquals(expected.keySet(), relations.keySet());
        expected.forEach((name, e) -> {
            var actual = relations.get(name);
            assertEquals(e.kind(), actual.kind(), name);
            assertEquals(e.id(), actual.identifier(), name);
            assertEquals(e.endpoints(), actual.endpoints(), name);
        });
    }

    @Test
    void allowsReadsTheCrossProductAndSameType() {
        var schema = OntologySchema.seed();
        assertTrue(schema.allows("works_on", "Organization", "Project"));
        assertTrue(schema.allows("owns", "Organization", "Place"));
        assertTrue(schema.allows("part_of", "Artifact", "Project"));
        assertTrue(schema.allows("kind_of", "Topic", "Topic"));
        assertTrue(schema.allows("same_as", "Place", "Place"));
        assertFalse(schema.allows("part_of", "Artifact", "Artifact"));
        assertFalse(schema.allows("works_at", "Person", "Project"));
        assertFalse(schema.allows("kind_of", "Topic", "Person"));
        assertFalse(schema.allows("kind_of", "Vehicle", "Vehicle"));
        assertFalse(schema.allows("likes", "Person", "Person"));
    }

    @Test
    void anEndpointNamingAnUnknownTypeIsRefused() throws IOException {
        var text = seedText().replace("\"Person -> Topic\"", "\"Person -> Vehicle\"");
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertTrue(e.getMessage().contains("holds_view_on") && e.getMessage().contains("Vehicle"), e.getMessage());
    }

    @Test
    void aMissingStandardIsRefused() throws IOException {
        var text = seedText().replace("Place:        {standard: \"schema:Place\", match: exact, ",
                "Place:        {");
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertTrue(e.getMessage().contains("term type Place") && e.getMessage().contains("standard"), e.getMessage());
    }

    @Test
    void aBadMatchIsRefused() throws IOException {
        var text = seedText().replace("\"skos:broader\", match: exact", "\"skos:broader\", match: broad");
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertTrue(e.getMessage().contains("relation kind_of") && e.getMessage().contains("broad"), e.getMessage());
    }

    @Test
    void aStandardWithoutMatchAndNoneWithMatchAreRefused() throws IOException {
        var noMatch = seedText().replace("\"schema:Event\", match: exact,", "\"schema:Event\",");
        var e1 = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(noMatch));
        assertTrue(e1.getMessage().contains("term type Event"), e1.getMessage());

        var noneWithMatch = seedText().replace("uses:          {kind: Association, standard: none,",
                "uses:          {kind: Association, standard: none, match: close,");
        var e2 = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(noneWithMatch));
        assertTrue(e2.getMessage().contains("relation uses"), e2.getMessage());
    }

    @Test
    void unparsableYamlAndDuplicateKeysAreRefused() throws IOException {
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse("version: [1\nfamilies: {"));
        assertTrue(e.getMessage().contains("not valid YAML"), e.getMessage());

        var duplicated = seedText().replace("relations:\n", "relations:\n  uses: {kind: Association, standard: none, "
                + "endpoints: [\"Person -> System\"]}\n");
        assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(duplicated));
    }

    @Test
    void aReferenceToAnUnknownFamilyIsRefused() throws IOException {
        var text = seedText().replace("\"Constraint -> Term\"", "\"Commitment -> Term\"");
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertTrue(e.getMessage().contains("Commitment"), e.getMessage());
    }

    @Test
    void diffNamesEachChangeOnOneSortedLine() throws IOException {
        var seed = seedText();
        var edited = seed
                .replace("term_types:\n", "term_types:\n  Vehicle: {standard: \"schema:Vehicle\", match: exact, "
                        + "covers: \"Car, bike, boat\"}\n")
                .replace("\"System -> System\", \"Artifact -> Project\"]", "\"System -> System\"]")
                .replace("\"schema:SoftwareApplication\"", "\"schema:Product\"");
        assertNotEquals(seed, edited);

        var lines = OntologySchema.diff(OntologySchema.parse(seed), OntologySchema.parse(edited));

        assertEquals(List.of(
                "+ term type Vehicle (schema:Vehicle, exact)",
                "~ relation part_of endpoints: [Organization -> Organization, Project -> Project, System -> System, "
                        + "Artifact -> Project] -> [Organization -> Organization, Project -> Project, System -> System]",
                "~ term type System standard: schema:SoftwareApplication -> schema:Product"), lines);
    }

    @Test
    void diffOfASchemaWithItselfIsEmpty() {
        var seed = OntologySchema.seed();
        assertEquals(List.of(), OntologySchema.diff(seed, seed));
        assertEquals(List.of(), OntologySchema.diff(seed, OntologySchema.seed()));
    }

    @Test
    void diffReportsRemovalsReferencesAndMatchChanges() throws IOException {
        var seed = seedText();
        var edited = seed
                .replaceAll("(?m)^  owns: .*\\n", "")
                .replace("  - \"Evidence -> Any\"\n", "")
                .replace("\"schema:sameAs\", match: close", "\"schema:sameAs\", match: exact");

        var lines = OntologySchema.diff(OntologySchema.parse(seed), OntologySchema.parse(edited));

        assertEquals(List.of(
                "- reference Evidence -> Any",
                "- relation owns",
                "~ relation same_as match: close -> exact"), lines);
    }

    @Test
    void aNonIntegerVersionIsRefused() throws IOException {
        var text = seedText().replace("version: 1\n", "version: one\n");
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertTrue(e.getMessage().contains("version"), e.getMessage());
    }

    @Test
    void aRelationWithNoEndpointsIsRefused() throws IOException {
        var text = seedText().replace("endpoints: [\"Person -> Topic\"]", "endpoints: []");
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertTrue(e.getMessage().contains("relation holds_view_on"), e.getMessage());
    }

    @Test
    void aFamilyMissingMustLinkOrMeaningIsRefused() throws IOException {
        var noMustLink = seedText().replace(" must_link: \"One or more Evidence\",", "");
        var e1 = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(noMustLink));
        assertTrue(e1.getMessage().contains("family Term") && e1.getMessage().contains("must_link"), e1.getMessage());

        var noMeaning = seedText().replace("meaning: \"An entity or concept\", ", "");
        var e2 = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(noMeaning));
        assertTrue(e2.getMessage().contains("family Term") && e2.getMessage().contains("meaning"), e2.getMessage());
    }

    @Test
    void diffReportsVersionFamilyReferenceCoversAndKindChanges() throws IOException {
        var seed = seedText();
        var edited = seed.replace("version: 1\n", "version: 2\n")
                .replace("must_link: \"One or more Evidence\"", "must_link: \"At least one Evidence\"")
                .replace("  - \"Evidence -> Any\"\n", "  - \"Evidence -> Any\"\n  - \"Evidence -> Term\"\n")
                .replace("covers: \"City, address, property\"", "covers: \"City, address, property, region\"")
                .replace("{kind: Equivalence,", "{kind: Alias,");

        var lines = OntologySchema.diff(OntologySchema.parse(seed), OntologySchema.parse(edited));

        assertEquals(
                List.of(
                        "+ reference Evidence -> Term",
                        "~ family Term must_link: One or more Evidence -> At least one Evidence",
                        "~ relation same_as kind: Equivalence -> Alias",
                        "~ term type Place covers: City, address, property -> City, address, property, region",
                        "~ version: 1 -> 2"),
                lines);
    }
}
