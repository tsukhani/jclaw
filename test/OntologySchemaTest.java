import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import memory.ontology.OntologySchema.Identifier;
import memory.ontology.OntologySchema.Match;
import memory.ontology.OntologyValidator;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.grapheval.ExtractionPipeline;
import services.grapheval.GraphEvalScorer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class OntologySchemaTest extends UnitTest {

    /** {@code conf/ontology/seed-schema.yaml} as it stood at version 2, byte for byte. */
    public static final String V2_SEED = """
            # JClaw seed ontology: the rulebook every knowledge-graph record is validated against
            # (memory.ontology.OntologyValidator). It changes only by a reviewed human edit — nothing
            # at runtime adds a type — and one seed is shared by every agent.
            #
            # Identifiers are curated slices of published vocabularies, kept with their prefixes:
            #   schema: https://schema.org/   prov: http://www.w3.org/ns/prov#
            #   skos:   http://www.w3.org/2004/02/skos/core#   dcterms: http://purl.org/dc/terms/
            # match is exact or close, after skos:exactMatch / skos:closeMatch; a type with no
            # counterpart writes standard: none and no match.
            #
            # Endpoints read "From[, From] -> To[, To]" (every From may point at every To);
            # "same" means any declared term type to itself. Keep one line per type so a diff of two
            # versions is one line per change.

            version: 2

            families:
              Term:       {meaning: "An entity or concept", must_link: "One or more Evidence", standard: none}
              Mapping:    {meaning: "Grounds a Term in a physical source: memory id, message id or file path", must_link: "Its Term; one or more Evidence", standard: none}
              Relation:   {meaning: "A typed edge between two Terms", must_link: "Both Terms; one or more Evidence", standard: none}
              Constraint: {meaning: "A valid-use rule attached to a Term", must_link: "Its Term; one or more Evidence", standard: none}
              Evidence:   {meaning: "Provenance supporting a claim", must_link: "The source it names (dcterms:source)", standard: "prov:Entity", match: close}

            # Record-to-record links; "Any" is any other record.
            references:
              - "Term -> Mapping"
              - "Mapping -> Term"
              - "Relation -> Term"
              - "Constraint -> Term"
              - "Term, Mapping, Relation, Constraint -> Evidence"
              - "Evidence -> Any"

            term_types:
              Person:       {standard: "schema:Person", match: exact, covers: "A named individual, including the operator"}
              Organization: {standard: "schema:Organization", match: exact, covers: "Company, institution, team, business unit"}
              Project:      {standard: "schema:Project", match: close, covers: "A body of work with a goal: product, codebase, course, paper"}
              System:       {standard: "schema:SoftwareApplication", match: close, covers: "Tool, service, device or server the operator uses or runs"}
              Artifact:     {standard: "schema:CreativeWork", match: close, covers: "A concrete addressable thing: file, URL, document, ticket, dataset"}
              Place:        {standard: "schema:Place", match: exact, covers: "City, address, property"}
              Event:        {standard: "schema:Event", match: exact, covers: "A specific dated occurrence: a trip, a renewal, a scheduled run, a particular deadline; never a weekday, a recurring time or a category such as 'deadlines'"}
              Topic:        {standard: "skos:Concept", match: exact, covers: "The subject a view or interest is about"}

            relations:
              works_at:      {kind: Association, standard: "schema:worksFor", match: exact, endpoints: ["Person -> Organization"]}
              works_on:      {kind: Association, standard: none, endpoints: ["Person, Organization -> Project"]}
              uses:          {kind: Association, standard: none, endpoints: ["Person, Organization, Project -> System"]}
              owns:          {kind: Association, standard: "schema:owns", match: close, endpoints: ["Person, Organization -> System, Artifact, Place"]}
              family_of:     {kind: Association, standard: "schema:relatedTo", match: exact, endpoints: ["Person -> Person"]}
              located_in:    {kind: Association, standard: "schema:location", match: close, endpoints: ["Person, Organization, Event -> Place"]}
              involves:      {kind: Association, standard: none, endpoints: ["Event -> Person, Organization, Project"]}
              holds_view_on: {kind: Association, standard: none, endpoints: ["Person -> Topic"]}
              part_of:       {kind: Composition, standard: "schema:isPartOf", match: close, endpoints: ["Organization -> Organization", "Project -> Project", "System -> System", "Artifact -> Project", "Place -> Place"]}
              kind_of:       {kind: Hierarchy, standard: "skos:broader", match: exact, endpoints: [same]}
              same_as:       {kind: Equivalence, standard: "schema:sameAs", match: close, endpoints: [same]}
              derived_from:  {kind: Derivation, standard: "prov:wasDerivedFrom", match: exact, endpoints: ["Artifact -> Artifact, Project"]}
            """;

    private static String seedText() throws IOException {
        return Files.readString(Play.applicationPath.toPath().resolve(OntologySchema.SEED_PATH));
    }

    /** Replaces the one occurrence of {@code from}, so a fixture that stops matching fails rather than passes. */
    private static String edit(String text, String from, String to) {
        var at = text.indexOf(from);
        assertTrue(at >= 0 && text.indexOf(from, at + 1) < 0, () -> "expected exactly one '" + from + "'");
        return text.replace(from, to);
    }

    private static void assertRefused(String text, String message) {
        var e = assertThrows(IllegalArgumentException.class, () -> OntologySchema.parse(text));
        assertEquals(message, e.getMessage());
    }

    private static void assertParses(String text) {
        assertDoesNotThrow(() -> OntologySchema.parse(text));
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
                                "Artifact -> Project", "Place -> Place"))),
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
        assertTrue(schema.allows("part_of", "Place", "Place"));
        assertTrue(schema.allows("kind_of", "Topic", "Topic"));
        assertTrue(schema.allows("same_as", "Place", "Place"));
        assertFalse(schema.allows("part_of", "Artifact", "Artifact"));
        assertFalse(schema.allows("part_of", "Place", "Organization"));
        assertFalse(schema.allows("part_of", "Event", "Place"));
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
                .replace("\"System -> System\", \"Artifact -> Project\", \"Place -> Place\"]", "\"System -> System\"]")
                .replace("\"schema:SoftwareApplication\"", "\"schema:Product\"");
        assertNotEquals(seed, edited);

        var lines = OntologySchema.diff(OntologySchema.parse(seed), OntologySchema.parse(edited));

        assertEquals(List.of(
                "+ term type Vehicle (schema:Vehicle, exact)",
                "~ relation part_of endpoints: [Organization -> Organization, Project -> Project, System -> System, "
                        + "Artifact -> Project, Place -> Place] -> [Organization -> Organization, Project -> Project, "
                        + "System -> System]",
                "~ term type System standard: schema:SoftwareApplication -> schema:Product"), lines);
    }

    @Test
    void theFingerprintMovesWithWhatExtractionAsksAndNothingElse() throws IOException {
        var seed = seedText();
        var fingerprint = OntologySchema.parse(seed).fingerprint();
        assertTrue(fingerprint.matches("v3@[0-9a-f]{12}"), fingerprint);
        assertEquals(fingerprint, OntologySchema.seed().fingerprint());

        var covers = seed.replace("covers: \"The subject a view or interest is about\"", "covers: \"A subject\"");
        var endpoints = seed.replace("\"Artifact -> Project\", \"Place -> Place\"]", "\"Artifact -> Project\"]");
        var version = seed.replace("version: 3", "version: 4");
        var reads = edit(seed, "\"X uses Y\"", "\"X makes use of Y\"");
        var symmetric = edit(seed, "\"X is the same thing as Y\", symmetric: true,", "\"X is the same thing as Y\",");
        var status = edit(seed, "\"X involves Y\", status: [holds, denied]}", "\"X involves Y\", status: [holds]}");
        var valid = edit(seed, "\"X involves Y\", status: [holds, denied]}",
                "\"X involves Y\", status: [holds, denied], valid: true}");
        var valence = edit(seed, "valid: true, valence: true}", "valid: true}");
        var dated = edit(seed, "dated: true, ", "");
        for (var edited : List.of(covers, endpoints, version, reads, symmetric, status, valid, valence, dated)) {
            assertNotEquals(seed, edited);
            assertNotEquals(fingerprint, OntologySchema.parse(edited).fingerprint());
        }

        var standard = seed.replace("\"schema:SoftwareApplication\"", "\"schema:Product\"");
        var comment = seed.replace("# JClaw seed ontology", "# The JClaw seed ontology");
        var family = edit(seed, "must_link: \"One or more Evidence\"", "must_link: \"At least one Evidence\"");
        var reference = edit(seed, "  - \"Evidence -> Any\"\n", "  - \"Evidence -> Any\"\n  - \"Evidence -> Term\"\n");
        var irreflexive = edit(seed, "\"X is derived from Y\", irreflexive: true,", "\"X is derived from Y\",");
        var systemTime = edit(seed, "\"When JClaw recorded the source\"", "\"When JClaw stored the source\"");
        var dates = edit(seed, "dates: {covers: \"YYYY,", "dates: {covers: \"Year YYYY,");
        var claims = edit(seed, "\"The source states it was so and has stopped\"", "\"The source says it stopped\"");
        for (var edited : List.of(standard, comment, family, reference, irreflexive, systemTime, dates, claims)) {
            assertNotEquals(seed, edited);
            assertEquals(fingerprint, OntologySchema.parse(edited).fingerprint(), "no question asks with it");
        }
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
    void diffReportsGlossSectionChangesAndRemovals() throws IOException {
        var seed = seedText();
        var edited = edit(edit(edit(seed,
                "\"When JClaw recorded the source\"", "\"When JClaw stored the source\""),
                "dates: {covers: \"YYYY,", "dates: {covers: \"Year YYYY,"),
                "ended:  {standard: \"pq:P582\", match: close,", "ended:  {standard: \"pq:P580\", match: exact,");

        var lines = OntologySchema.diff(OntologySchema.parse(seed), OntologySchema.parse(edited));

        var datesCovers = OntologySchema.seed().dates().covers();
        assertEquals(List.of(
                "~ claims status ended match: close -> exact",
                "~ claims status ended standard: pq:P582 -> pq:P580",
                "~ dates covers: " + datesCovers + " -> " + datesCovers.replaceFirst("^YYYY,", "Year YYYY,"),
                "~ system_time recordedAt covers: When JClaw recorded the source -> When JClaw stored the source"),
                lines);

        var reverse = OntologySchema.diff(OntologySchema.seed(), OntologySchema.parse(V2_SEED));
        assertTrue(reverse.contains("- dates"), reverse::toString);
        assertTrue(reverse.contains("- claims status holds"), reverse::toString);
    }

    @Test
    void aNonIntegerVersionIsRefused() throws IOException {
        var text = seedText().replace("version: 3\n", "version: one\n");
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
        var edited = seed.replace("version: 3\n", "version: 4\n")
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
                        "~ version: 3 -> 4"),
                lines);
    }

    @Test
    void theEventTypeCoversOnlyASpecificDatedOccurrence() {
        var covers = OntologySchema.seed().termTypes().get("Event").covers();
        for (var included : List.of("trip", "renewal", "scheduled run", "a particular deadline")) {
            assertTrue(covers.contains(included), included + " in: " + covers);
        }
        for (var excluded : List.of("weekday", "recurring time", "'deadlines'")) {
            assertTrue(covers.contains("never") && covers.contains(excluded), excluded + " in: " + covers);
        }
    }

    @Test
    void versionTwoDiffersFromVersionOneByExactlyTheTwoSpikeEdits() {
        var v2 = V2_SEED;
        var v1 = v2.replace("version: 2\n", "version: 1\n")
                .replace("covers: \"A specific dated occurrence: a trip, a renewal, a scheduled run, a particular "
                        + "deadline; never a weekday, a recurring time or a category such as 'deadlines'\"",
                        "covers: \"Something dated: trip, deadline, renewal, scheduled run\"")
                .replace(", \"Place -> Place\"]", "]");
        assertNotEquals(v2, v1);

        var lines = OntologySchema.diff(OntologySchema.parse(v1), OntologySchema.parse(v2));

        assertEquals(List.of(
                "~ relation part_of endpoints: [Organization -> Organization, Project -> Project, System -> System, "
                        + "Artifact -> Project] -> [Organization -> Organization, Project -> Project, System -> System, "
                        + "Artifact -> Project, Place -> Place]",
                "~ term type Event covers: Something dated: trip, deadline, renewal, scheduled run -> A specific dated "
                        + "occurrence: a trip, a renewal, a scheduled run, a particular deadline; never a weekday, a "
                        + "recurring time or a category such as 'deadlines'",
                "~ version: 1 -> 2"), lines);
    }

    @Test
    void anUnknownTopLevelKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "version: 3\n", "foo: 1\nversion: 3\n"), "ontology schema: unknown key 'foo'");
    }

    @Test
    void anUnknownFamilyKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "Term:       {", "Term:       {foo: x, "), "family Term: unknown key 'foo'");
    }

    @Test
    void anUnknownTermTypeKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "Topic:        {", "Topic:        {foo: x, "), "term type Topic: unknown key 'foo'");
    }

    @Test
    void anUnknownRelationKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "family_of:     {", "family_of:     {symetric: true, "),
                "relation family_of: unknown key 'symetric'");
    }

    @Test
    void anUnknownSystemTimeKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "system_time:\n", "system_time:\n  foo: {standard: none, covers: x}\n"),
                "system_time: unknown key 'foo'");
    }

    @Test
    void anUnknownSystemTimeEntryKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "recordedAt: {", "recordedAt: {foo: x, "),
                "system_time recordedAt: unknown key 'foo'");
    }

    @Test
    void anUnknownLineageKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "  lineage:\n", "  lineage:\n    foo: {standard: none, covers: x}\n"),
                "system_time lineage: unknown key 'foo'");
    }

    @Test
    void anUnknownLineageEntryKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "update:      {", "update:      {foo: x, "),
                "system_time lineage update: unknown key 'foo'");
    }

    @Test
    void anUnknownDatesKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "dates: {", "dates: {foo: x, "), "dates: unknown key 'foo'");
    }

    @Test
    void anUnknownClaimsKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "claims:\n", "claims:\n  foo: {standard: none, covers: x}\n"),
                "claims: unknown key 'foo'");
    }

    @Test
    void anUnknownClaimsStatusKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "  status:\n", "  status:\n    maybe: {standard: none, covers: x}\n"),
                "claims status: unknown key 'maybe'");
    }

    @Test
    void anUnknownClaimsStatusEntryKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "holds:  {", "holds:  {foo: x, "), "claims status holds: unknown key 'foo'");
    }

    @Test
    void anUnknownClaimsValidKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "valid:    {", "valid:    {foo: x, "), "claims valid: unknown key 'foo'");
    }

    @Test
    void anUnknownClaimsValenceKeyIsRefused() throws IOException {
        assertRefused(edit(seedText(), "  valence:\n", "  valence:\n    neutral: {standard: none, covers: x}\n"),
                "claims valence: unknown key 'neutral'");
    }

    @Test
    void theFirstUnknownKeyInSortedOrderIsReported() throws IOException {
        assertRefused(edit(seedText(), "Term:       {", "Term:       {zed: x, abc: y, "), "family Term: unknown key 'abc'");
    }

    @Test
    void aVersionThreeDocumentWithoutSystemTimeIsRefused() throws IOException {
        assertRefused(seedText().replaceFirst("(?ms)^system_time:\n.*?\n\n", ""), "ontology schema: missing system_time");
    }

    @Test
    void aVersionThreeDocumentWithoutDatesIsRefused() throws IOException {
        assertRefused(seedText().replaceFirst("(?m)^dates: .*\n", ""), "ontology schema: missing dates");
    }

    @Test
    void aVersionThreeDocumentWithoutClaimsIsRefused() throws IOException {
        assertRefused(seedText().replaceFirst("(?ms)^claims:\n.*?\n\n", ""), "ontology schema: missing claims");
    }

    @Test
    void aClaimsSectionWithoutOccursIsRefused() throws IOException {
        assertRefused(seedText().replaceFirst("(?m)^  occurs: .*\n", ""), "claims: missing occurs");
    }

    @Test
    void aVersionThreeRelationWithoutReadsIsRefused() throws IOException {
        assertRefused(edit(seedText(), "reads: \"X works at Y\", ", ""), "relation works_at: missing reads");
    }

    @Test
    void aVersionThreeRelationWithoutStatusIsRefused() throws IOException {
        assertRefused(edit(seedText(), "\"X works at Y\", status: [holds, ended, denied], valid: true}",
                "\"X works at Y\", valid: true}"), "relation works_at: missing status");
    }

    @Test
    void aStatusOutsideClaimsStatusIsRefused() throws IOException {
        var seed = seedText();
        assertRefused(edit(seed, "\"X is a kind of Y\", irreflexive: true, status: [holds]}",
                "\"X is a kind of Y\", irreflexive: true, status: [holds, maybe]}"),
                "relation kind_of: status maybe is not a key of claims.status");
        assertParses(edit(seed, "\"X is a kind of Y\", irreflexive: true, status: [holds]}",
                "\"X is a kind of Y\", irreflexive: true, status: [holds, denied]}"));
    }

    @Test
    void endedWithoutValidIsRefused() throws IOException {
        var seed = seedText();
        assertRefused(edit(seed, "\"X is a kind of Y\", irreflexive: true, status: [holds]}",
                "\"X is a kind of Y\", irreflexive: true, status: [holds, ended]}"),
                "relation kind_of: status ended needs valid: true");
        assertParses(edit(seed, "\"X is a kind of Y\", irreflexive: true, status: [holds]}",
                "\"X is a kind of Y\", irreflexive: true, status: [holds, ended], valid: true}"));
    }

    @Test
    void valenceOffPersonToTopicIsRefused() throws IOException {
        var seed = seedText();
        assertRefused(edit(seed, "\"X works at Y\", status: [holds, ended, denied], valid: true}",
                "\"X works at Y\", status: [holds, ended, denied], valid: true, valence: true}"),
                "relation works_at: valence needs the only endpoint to be Person -> Topic");
        assertRefused(edit(seed, "endpoints: [\"Person -> Topic\"]", "endpoints: [\"Person -> Topic\", \"Person -> Place\"]"),
                "relation holds_view_on: valence needs the only endpoint to be Person -> Topic");
        assertParses(seed);
        assertTrue(OntologySchema.parse(seed).relations().get("holds_view_on").valence());
    }

    @Test
    void readsNeedsExactlyOneWholeWordXAndY() throws IOException {
        var seed = seedText();
        assertRefused(edit(seed, "\"X is part of Y\"", "\"X is part of X\""),
                "relation part_of: reads must hold exactly one whole-word X and one whole-word Y, was 'X is part of X'");
        assertRefused(edit(seed, "\"X is part of Y\"", "\"X is part of Y and Y\""),
                "relation part_of: reads must hold exactly one whole-word X and one whole-word Y, "
                        + "was 'X is part of Y and Y'");
        var extends_ = OntologySchema.parse(edit(seed, "\"X is part of Y\"", "\"X eXtends Y\""));
        assertEquals("X eXtends Y", extends_.relations().get("part_of").reads());
    }

    @Test
    void symmetricNeedsEveryFromTypeToBeAToType() throws IOException {
        var seed = seedText();
        assertRefused(edit(seed, "\"X works at Y\", status:", "\"X works at Y\", symmetric: true, status:"),
                "relation works_at: symmetric needs every From type to be a To type, but Person is not");
        assertParses(edit(seed, "\"X is a kind of Y\", irreflexive: true,", "\"X is a kind of Y\", symmetric: true, irreflexive: true,"));
        assertTrue(OntologySchema.parse(seed).relations().get("family_of").symmetric(), "Person -> Person");
    }

    @Test
    void aNonBooleanFlagIsRefused() throws IOException {
        var seed = seedText();
        assertRefused(edit(seed, "dated: true,", "dated: \"true\","),
                "term type Event: dated must be a boolean, was 'true'");
        assertRefused(edit(seed, "\"X involves Y\", status: [holds, denied]}", "\"X involves Y\", status: [holds, denied], valid: 1}"),
                "relation involves: valid must be a boolean, was '1'");
        assertRefused(edit(seed, "\"X is derived from Y\", irreflexive: true,", "\"X is derived from Y\", irreflexive: no-thanks,"),
                "relation derived_from: irreflexive must be a boolean, was 'no-thanks'");
        assertTrue(OntologySchema.parse(seed).termTypes().get("Event").dated());
    }

    @Test
    void theVersionTwoFixtureParsesWithoutTheVersionThreeParts() {
        var v2 = OntologySchema.parse(V2_SEED);
        assertEquals(2, v2.version());
        assertNull(v2.systemTime());
        assertNull(v2.dates());
        assertNull(v2.claims());
        v2.relations().forEach((name, r) -> {
            assertNull(r.reads(), name);
            assertEquals(List.of(), r.statuses(), name);
            assertFalse(r.symmetric() || r.irreflexive() || r.valid() || r.valence(), name);
        });
        v2.termTypes().forEach((name, t) -> assertFalse(t.dated(), name));
    }

    @Test
    void theSeedStatusesAndLineagesMirrorTheValidatorsConstants() {
        var seed = OntologySchema.seed();
        assertEquals(OntologyValidator.STATUSES, List.copyOf(seed.claims().status().keySet()));
        assertEquals(OntologyValidator.LINEAGES, List.copyOf(seed.systemTime().lineage().keySet()));
    }

    @Test
    void theClaimQueriesReadTheRelationAndTheDatedFromType() {
        var seed = OntologySchema.seed();
        assertEquals(3, seed.version());
        assertEquals(List.of("holds", "denied"), seed.effectiveStatuses("located_in", "Event"));
        assertEquals(List.of("holds", "ended", "denied"), seed.effectiveStatuses("located_in", "Person"));
        assertEquals(List.of("holds", "ended"), seed.effectiveStatuses("family_of", "Person"));
        assertFalse(seed.validAllowed("located_in", "Event"));
        assertTrue(seed.validAllowed("located_in", "Person"));
        assertTrue(seed.timeable("located_in", "Event"));
        assertTrue(seed.timeable("involves", "Event"));
        assertTrue(seed.timeable("works_at", "Person"));
        assertFalse(seed.timeable("kind_of", "Topic"));
        assertFalse(seed.timeable("same_as", "Person"));
        assertTrue(seed.symmetric("family_of"));
        assertFalse(seed.symmetric("part_of"));
        var irreflexive = Set.of("family_of", "part_of", "kind_of", "same_as", "derived_from");
        seed.relations().keySet().forEach(name -> assertEquals(irreflexive.contains(name), seed.irreflexive(name), name));
        assertFalse(seed.irreflexive("works_at"));
        assertEquals(Set.of("family_of", "same_as"), seed.symmetricSet());
        assertThrows(IllegalArgumentException.class, () -> seed.symmetric("likes"));
        assertThrows(IllegalArgumentException.class, () -> seed.effectiveStatuses("located_in", "Vehicle"));
    }

    @Test
    void theSeedOwnsTheGlossesAndSymmetryTheEvalCodeStillHolds() {
        var seed = OntologySchema.seed();
        assertEquals(GraphEvalScorer.SYMMETRIC, seed.symmetricSet());
        assertEquals(ExtractionPipeline.SENTENCES.keySet(), seed.relations().keySet());
        seed.relations().forEach((name, r) -> assertEquals(ExtractionPipeline.SENTENCES.get(name), r.reads(), name));
    }

    @Test
    void versionThreeDiffersFromVersionTwoByTheClaimsEdit() {
        var lines = OntologySchema.diff(OntologySchema.parse(V2_SEED), OntologySchema.seed());
        assertEquals(56, lines.size(), lines::toString);
        assertEquals(List.of(
                "+ claims occurs (pq:P585, close)",
                "+ claims status denied (none)",
                "+ claims status ended (pq:P582, close)",
                "+ claims status holds (none)",
                "+ claims valence favorable (none)",
                "+ claims valence unfavorable (none)",
                "+ claims valid (dcterms:valid, close)",
                "+ dates (no standard)",
                "+ system_time lineage correction (none)",
                "+ system_time lineage restatement (none)",
                "+ system_time lineage update (none)",
                "+ system_time recordedAt (prov:generatedAtTime, close)",
                "+ system_time retiredAt (prov:invalidatedAtTime, close)",
                "~ family Evidence meaning: Provenance supporting a claim -> Provenance supporting a claim, and what its source states about that claim",
                "~ relation derived_from irreflexive: false -> true",
                "~ relation derived_from reads: none -> X is derived from Y",
                "~ relation derived_from status: [] -> [holds]",
                "~ relation family_of irreflexive: false -> true",
                "~ relation family_of reads: none -> X is a family member of Y",
                "~ relation family_of status: [] -> [holds, ended]",
                "~ relation family_of symmetric: false -> true",
                "~ relation family_of valid: false -> true",
                "~ relation holds_view_on reads: none -> X holds a view on Y",
                "~ relation holds_view_on status: [] -> [holds, ended]",
                "~ relation holds_view_on valence: false -> true",
                "~ relation holds_view_on valid: false -> true",
                "~ relation involves reads: none -> X involves Y",
                "~ relation involves status: [] -> [holds, denied]",
                "~ relation kind_of irreflexive: false -> true",
                "~ relation kind_of reads: none -> X is a kind of Y",
                "~ relation kind_of status: [] -> [holds]",
                "~ relation located_in reads: none -> X is located in Y",
                "~ relation located_in status: [] -> [holds, ended, denied]",
                "~ relation located_in valid: false -> true",
                "~ relation owns reads: none -> X owns Y",
                "~ relation owns status: [] -> [holds, ended, denied]",
                "~ relation owns valid: false -> true",
                "~ relation part_of irreflexive: false -> true",
                "~ relation part_of reads: none -> X is part of Y",
                "~ relation part_of status: [] -> [holds, ended, denied]",
                "~ relation part_of valid: false -> true",
                "~ relation same_as irreflexive: false -> true",
                "~ relation same_as reads: none -> X is the same thing as Y",
                "~ relation same_as status: [] -> [holds, denied]",
                "~ relation same_as symmetric: false -> true",
                "~ relation uses reads: none -> X uses Y",
                "~ relation uses status: [] -> [holds, ended, denied]",
                "~ relation uses valid: false -> true",
                "~ relation works_at reads: none -> X works at Y",
                "~ relation works_at status: [] -> [holds, ended, denied]",
                "~ relation works_at valid: false -> true",
                "~ relation works_on reads: none -> X works on Y",
                "~ relation works_on status: [] -> [holds, ended, denied]",
                "~ relation works_on valid: false -> true",
                "~ term type Event dated: false -> true",
                "~ version: 2 -> 3"), lines);
    }
}
