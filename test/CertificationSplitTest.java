import memory.ontology.OntologySchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.grapheval.CertificationSplit;
import services.grapheval.Fingerprints;
import services.grapheval.GraphCases;
import services.grapheval.HeldOut;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static utils.GsonHolder.GSON;

/** JCLAW-1368: freezing, loading and verifying a certification split, and leaving its ids out of a development run. */
class CertificationSplitTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final byte[] GUIDE = "the guide".getBytes(StandardCharsets.UTF_8);
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("cert-split");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String casesJson() throws IOException {
        return Files.readString(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH));
    }

    private static CertificationSplit.Source source(String sequences) throws IOException {
        var json = casesJson();
        return CertificationSplit.Source.cases(json, GraphCases.parse(json, SCHEMA), sequences, GUIDE, SCHEMA);
    }

    private CertificationSplit freeze(String name, long seed, double share) throws IOException {
        return CertificationSplit.freeze(root, name, source("sequences@aaaaaaaaaaaa"), seed, share, null, 0.95, null,
                SCHEMA);
    }

    @Test
    void aFreezeWritesTheManifestUnderSplits() throws IOException {
        var source = source("sequences@aaaaaaaaaaaa");
        var split = freeze("cert-2026-10", 1356, 0.3);
        var file = root.resolve("splits/cert-2026-10.json");
        assertTrue(Files.exists(file));
        assertEquals(split, CertificationSplit.load(root, "cert-2026-10"));
        assertEquals(Set.of("split", "set", "seed", "ids", "hashes", "cases", "sequences", "guide", "schema",
                "startingThreshold", "relationOrder"), GSON.fromJson(Files.readString(file), Map.class).keySet());
        assertEquals("cases", split.set());
        assertEquals((int) Math.ceil(source.items().size() * 0.3), split.ids().size());
        assertEquals(split.ids(), List.copyOf(split.hashes().keySet()));
        for (var id : split.ids()) {
            assertTrue(split.hashes().get(id).matches("[0-9a-f]{12}"), split.hashes().toString());
            assertEquals(Fingerprints.hex12(source.items().get(id)), split.hashes().get(id));
        }
        assertTrue(split.cases().matches("cases@[0-9a-f]{12}"), split.cases());
        assertEquals("sequences@aaaaaaaaaaaa", split.sequences());
        assertEquals(Fingerprints.hex12("guide", GUIDE), split.guide());
        assertEquals(SCHEMA.fingerprint(), split.schema());
        assertEquals(0.95, split.startingThreshold());
        assertEquals(SCHEMA.relations().size(), split.relationOrder().size());
        var inFileOrder = new ArrayList<>(source.items().keySet());
        inFileOrder.retainAll(split.ids());
        assertEquals(inFileOrder, split.ids(), "ids keep the set's order");
    }

    @Test
    void freezingAnExistingNameIsRefusedNamingTheManifest() throws IOException {
        freeze("cert-a", 1356, 0.3);
        var e = assertThrows(IllegalArgumentException.class, () -> freeze("cert-a", 7, 0.5));
        assertEquals("split 'cert-a' already exists: splits/cert-a.json", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> freeze("../escape", 1, 0.3));
    }

    @Test
    void theSameSeedAndShareGiveTheSameIds() throws IOException {
        var a = freeze("cert-a", 1356, 0.3);
        var b = freeze("cert-b", 1356, 0.3);
        var c = freeze("cert-c", 1357, 0.3);
        assertEquals(a.ids(), b.ids());
        assertEquals(a.hashes(), b.hashes());
        assertNotEquals(a.ids(), c.ids());
    }

    @Test
    void givenIdsAreCheckedAndTheThresholdIsOnTheGrid() throws IOException {
        var source = source("sequences@aaaaaaaaaaaa");
        var ids = List.of("c014", "c003");
        var split = CertificationSplit.freeze(root, "cert-ids", source, 1, null, ids, 0.90, List.of("uses", "works_at"),
                SCHEMA);
        assertEquals(List.of("c003", "c014"), split.ids());
        assertEquals(List.of("uses", "works_at"), split.relationOrder());
        assertThrows(IllegalArgumentException.class, () -> CertificationSplit.freeze(root, "cert-x", source, 1, null,
                List.of("nope"), 0.95, null, SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> CertificationSplit.freeze(root, "cert-y", source, 1, 0.3,
                null, 0.93, null, SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> CertificationSplit.freeze(root, "cert-z", source, 1, 0.3,
                null, 0.95, List.of("knows_nothing"), SCHEMA));
    }

    @Test
    void theDefaultRelationOrderCountsGoldOutsideTheSplitMostFirstTiesInSchemaOrder() throws IOException {
        var source = source("sequences@aaaaaaaaaaaa");
        var split = freeze("cert-order", 1356, 0.3);
        var counts = new HashMap<String, Integer>();
        source.cases().forEach((id, c) -> {
            if (split.ids().contains(id)) return;
            c.relations().forEach(r -> {
                if (!r.noise() && c.holdsOrEnded(r, SCHEMA)) counts.merge(r.type(), 1, Integer::sum);
            });
        });
        var order = split.relationOrder();
        var schemaOrder = new ArrayList<>(SCHEMA.relations().keySet());
        for (int i = 1; i < order.size(); i++) {
            int prev = counts.getOrDefault(order.get(i - 1), 0);
            int here = counts.getOrDefault(order.get(i), 0);
            assertTrue(prev > here || prev == here
                    && schemaOrder.indexOf(order.get(i - 1)) < schemaOrder.indexOf(order.get(i)), order.toString());
        }
    }

    @Test
    void verifyNamesTheFirstDriftedIdOrTheSequences() throws IOException {
        var split = freeze("cert-v", 1356, 0.3);
        assertNull(split.verify(source("sequences@aaaaaaaaaaaa")));
        var json = casesJson();
        var parsed = GraphCases.parse(json, SCHEMA);
        var items = new LinkedHashMap<>(source("sequences@aaaaaaaaaaaa").items());
        var drifted = split.ids().get(1);
        var edited = items.get(drifted).deepCopy();
        edited.addProperty("text", edited.get("text").getAsString() + " Edited.");
        items.put(drifted, edited);
        var cases = new LinkedHashMap<String, GraphCases.Case>();
        parsed.forEach(c -> cases.put(c.id(), c));
        var changed = new CertificationSplit.Source("cases", items, cases, "sequences@aaaaaaaaaaaa",
                Fingerprints.hex12("guide", GUIDE), SCHEMA.fingerprint());
        assertEquals("case " + drifted + " changed since split 'cert-v' was frozen", split.verify(changed));
        var reason = split.verify(source("sequences@bbbbbbbbbbbb"));
        assertTrue(reason.startsWith("sequences changed"), reason);
    }

    @Test
    void aDevelopmentRunLeavesOutEveryFrozenSplitsIdsAndCountsThem() throws IOException {
        var a = freeze("cert-a", 1, 0.2);
        var b = freeze("cert-b", 2, 0.2);
        var all = GraphCases.parse(casesJson(), SCHEMA);
        var frozen = CertificationSplit.frozenIds(root, "cases");
        var expected = new java.util.TreeSet<>(a.ids());
        expected.addAll(b.ids());
        assertEquals(expected, frozen);
        var outside = CertificationSplit.outside(root, "cases", all);
        assertEquals(frozen.size(), outside.excluded());
        assertEquals(all.size() - frozen.size(), outside.kept().size());
        assertTrue(outside.kept().stream().noneMatch(c -> frozen.contains(c.id())));
        assertEquals(Set.of(), CertificationSplit.frozenIds(root, "heldout"));
        assertEquals(0, CertificationSplit.outside(root, "heldout", all).excluded());
    }

    @Test
    void aHeldOutSplitNamesMemoryIdsAndTakesTheWholeSetByDefault() throws IOException {
        var file = root.resolve("heldout.json");
        Files.writeString(file, GSON.toJson(Map.of("cases", List.of(
                Map.of("memoryId", 11, "labelled", true, "text", "The user works at Harborlight Analytics.",
                        "capturedAt", "2026-10-03", "authorType", "human_turn", "candidates", List.of("x"),
                        "entities", List.of(Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization")),
                        "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight",
                                "status", "holds"))),
                Map.of("memoryId", 12, "labelled", false, "text", "unlabelled", "entities", List.of(),
                        "relations", List.of()),
                Map.of("memoryId", 13, "labelled", true, "text", "The user uses Kestrel CI.",
                        "capturedAt", "2026-10-03", "authorType", "human_turn",
                        "entities", List.of(Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                Map.of("id", "kestrel", "mention", "Kestrel CI", "type", "System")),
                        "relations", List.of(Map.of("from", "operator", "type", "uses", "to", "kestrel",
                                "status", "holds")))))));
        var loaded = HeldOut.load(file, SCHEMA);
        var source = CertificationSplit.Source.heldout(Files.readString(file), loaded, "sequences@aaaaaaaaaaaa", GUIDE,
                SCHEMA);
        var split = CertificationSplit.freeze(root, "held-1", source, 1, null, null, 0.95, null, SCHEMA);
        assertEquals("heldout", split.set());
        assertEquals(List.of("11", "13"), split.ids());
        assertEquals(List.of("11", "13"), split.casesFrom(source).stream().map(GraphCases.Case::id).toList());
        assertNull(split.verify(source));
        assertEquals(Set.of("11", "13"), CertificationSplit.frozenIds(root, "heldout"));
    }
}
