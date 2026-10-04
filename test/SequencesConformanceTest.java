import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.graph.GraphView;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.grapheval.GraphCases;
import services.grapheval.Sequences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** JCLAW-1367: the committed sequence set parses with no model, and every break of its rules is refused by name. */
class SequencesConformanceTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();

    private static final String BASE = """
            {"userMd": "Name: Avery Lin",
             "chains": [
              {"id": "c1", "tags": ["update"],
               "memories": [
                {"id": "m1", "capturedAt": "2025-01-02", "text": "Avery Lin uses Kestrel CI.",
                 "entities": [{"id": "operator", "mention": "Avery Lin", "type": "Person"},
                              {"id": "kestrel-ci", "mention": "Kestrel CI", "type": "System"}],
                 "relations": [{"from": "operator", "type": "uses", "to": "kestrel-ci", "status": "holds"}]},
                {"id": "m2", "capturedAt": "2025-02-03", "supersedes": {"m1": "restatement"},
                 "text": "Avery Lin still uses Kestrel CI.",
                 "entities": [{"id": "operator", "mention": "Avery Lin", "type": "Person"},
                              {"id": "kestrel-ci", "mention": "Kestrel CI", "type": "System"}],
                 "relations": [{"from": "operator", "type": "uses", "to": "kestrel-ci", "status": "holds"}]}],
               "probes": [
                {"from": "operator", "type": "uses", "to": "kestrel-ci", "d": "2025-01-10", "after": "m2",
                 "truth": "YES"}]}]}
            """;

    private static JsonObject base() {
        return JsonParser.parseString(BASE).getAsJsonObject();
    }

    private static JsonObject chain(JsonObject doc) {
        return doc.getAsJsonArray("chains").get(0).getAsJsonObject();
    }

    private static JsonObject memory(JsonObject doc, int i) {
        return chain(doc).getAsJsonArray("memories").get(i).getAsJsonObject();
    }

    private static JsonObject probe(JsonObject doc) {
        return chain(doc).getAsJsonArray("probes").get(0).getAsJsonObject();
    }

    private static Sequences parse(JsonObject doc) {
        return Sequences.parse(doc.toString(), SCHEMA);
    }

    /** Refused, with a message naming each of {@code names}. */
    private static void refused(Consumer<JsonObject> edit, String... names) {
        var doc = base();
        edit.accept(doc);
        var e = assertThrows(IllegalArgumentException.class, () -> parse(doc), doc::toString);
        for (var name : names) {
            assertTrue(e.getMessage().contains(name), () -> "'" + e.getMessage() + "' names no '" + name + "'");
        }
    }

    @Test
    void theCommittedSetParsesWithNoModel() throws IOException {
        var set = Sequences.load(Path.of(Play.applicationPath.getAbsolutePath(), Sequences.DEFAULT_PATH), SCHEMA);
        assertEquals("Avery Lin", set.ownerName());
        assertTrue(set.chains().size() >= 4, () -> set.chains().size() + " chains");
        assertTrue(set.fingerprint().matches("sequences@[0-9a-f]{12}"), set.fingerprint());
        var tags = new HashSet<String>();
        var memoryIds = new HashSet<String>();
        for (var chain : set.chains()) {
            tags.addAll(chain.tags());
            assertFalse(chain.probes().isEmpty(), chain.id());
            for (int i = 0; i < chain.memories().size(); i++) {
                var memory = chain.memories().get(i);
                assertTrue(memoryIds.add(memory.id()), memory.id());
                if (i > 0) {
                    assertFalse(memory.labels().capturedAt()
                            .isBefore(chain.memories().get(i - 1).labels().capturedAt()), memory.id());
                }
                int self = i;
                memory.supersedes().keySet().forEach(p -> assertTrue(chain.indexOf(p) >= 0
                        && chain.indexOf(p) < self, () -> memory.id() + " supersedes " + p));
                if (chain.tags().contains(GraphCases.GUEST_ABOUT_OWNER)) {
                    assertEquals(MemoryAuthorType.GUEST_TURN, memory.authorType(), memory.id());
                }
            }
            for (var p : chain.probes()) assertTrue(chain.indexOf(p.memoryId()) >= 0, p::toString);
        }
        assertTrue(tags.containsAll(Sequences.CHAIN_TAGS), tags::toString);
    }

    private static Sequences committed() throws IOException {
        return Sequences.load(Path.of(Play.applicationPath.getAbsolutePath(), Sequences.DEFAULT_PATH), SCHEMA);
    }

    @Test
    void theCommittedSetHasTheCompositionTheReadmeStates() throws IOException {
        var set = committed();
        var memories = set.chains().stream().flatMap(c -> c.memories().stream()).toList();
        var probes = set.chains().stream().flatMap(c -> c.probes().stream()).toList();
        assertEquals(60, set.chains().size());
        assertEquals(150, memories.size());
        assertEquals(30, set.chains().stream().filter(c -> c.memories().size() == 3).count());
        for (var lineage : List.of("update:40", "restatement:25", "correction:25")) {
            var parts = lineage.split(":");
            assertEquals(Long.parseLong(parts[1]), memories.stream().flatMap(m -> m.supersedes().values().stream())
                    .filter(l -> l.name().toLowerCase(Locale.ROOT).equals(parts[0])).count(), parts[0]);
        }
        assertEquals(240, probes.size());
        assertEquals(118, probes.stream().filter(p -> p.truth() == GraphView.Truth.YES).count());
        assertEquals(74, probes.stream().filter(p -> p.truth() == GraphView.Truth.NO).count());
        assertEquals(41, probes.stream().filter(Sequences.Probe::assumed).count());
        assertEquals(72, probes.stream().filter(p -> p.before() != null).count());
        assertEquals(6, set.chains().stream().filter(c -> c.tags().contains(GraphCases.GUEST_ABOUT_OWNER)).count());
        assertEquals(3, memories.stream().filter(m -> m.authorType() == MemoryAuthorType.CONSOLIDATION_DERIVED).count());
    }

    /** {@code lineage:<later>:<earlier>:<lineage>} for each link, in file order. */
    private static List<String> lineageRecords(Sequences set) {
        var out = new ArrayList<String>();
        for (var chain : set.chains()) {
            for (var m : chain.memories()) {
                m.supersedes().forEach((earlier, l) -> out.add(String.join(":", "lineage", m.id(), earlier,
                        l.name().toLowerCase(Locale.ROOT))));
            }
        }
        return out;
    }

    /** {@code probe:<from>:<type>:<to>:<d>:after|before:<memory>:<truth>} for each probe, in file order. */
    private static List<String> probeRecords(Sequences set) {
        return set.chains().stream().flatMap(c -> c.probes().stream()).map(p -> String.join(":", "probe", p.from(),
                p.type(), p.to(), p.d().toString(), p.after() != null ? "after" : "before", p.memoryId(),
                p.truth().name())).toList();
    }

    /** The first {@code k} records by the SHA-256 hex of {@code seed + ":" + record}. */
    private static List<String> sample(List<String> records, int seed, long k) {
        return records.stream().sorted(Comparator.comparing(r -> sha256(seed + ":" + r))).limit(k).toList();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSpotCheckIsTheSeededSampleOfTheCommittedLabels() throws IOException {
        var checks = JsonParser.parseString(Files.readString(Path.of(Play.applicationPath.getAbsolutePath(),
                "evals/graph/sequence-label-checks.json"))).getAsJsonObject();
        assertEquals("v3", checks.get("guide").getAsString());
        int seed = checks.get("seed").getAsInt();
        var records = new ArrayList<String>();
        for (var e : checks.getAsJsonArray("checks")) {
            var check = e.getAsJsonObject();
            var record = check.get("record").getAsString();
            records.add(record);
            var chain = check.get("chain").getAsString();
            assertTrue(record.contains(":" + chain), () -> record + " is not in chain " + chain);
            assertTrue(List.of("pending", "agree", "disagree").contains(check.get("verdict").getAsString()), record);
        }
        var set = committed();
        long lineages = records.stream().filter(r -> r.startsWith("lineage:")).count();
        long probes = records.stream().filter(r -> r.startsWith("probe:")).count();
        var expected = new ArrayList<>(sample(lineageRecords(set), seed, lineages));
        expected.addAll(sample(probeRecords(set), seed, probes));
        assertEquals(expected, records, "the checks are not the seed's sample of the committed labels");
    }

    @Test
    void theBaseDocumentAndItsAcceptedNeighboursParse() {
        var set = parse(base());
        assertEquals(1, set.chains().getFirst().links());

        var equal = base();
        memory(equal, 1).addProperty("capturedAt", "2025-01-02");
        assertEquals(memory(equal, 0).get("capturedAt"), memory(equal, 1).get("capturedAt"));
        parse(equal);

        var third = base();
        var m3 = memory(third, 1).deepCopy();
        m3.addProperty("id", "m3");
        var supersedes = new JsonObject();
        supersedes.addProperty("m2", "restatement");
        m3.add("supersedes", supersedes);
        chain(third).getAsJsonArray("memories").add(m3);
        assertEquals(2, parse(third).chains().getFirst().links());
    }

    @Test
    void theFingerprintIgnoresKeyOrderButNotContent() {
        var a = parse(base()).fingerprint();
        var reordered = new JsonObject();
        var doc = base();
        reordered.add("chains", doc.get("chains"));
        reordered.add("userMd", doc.get("userMd"));
        assertEquals(a, parse(reordered).fingerprint());
        var changed = base();
        probe(changed).addProperty("d", "2025-01-11");
        assertNotEquals(a, parse(changed).fingerprint());
    }

    @Test
    void anUnknownKeyIsRefusedAtEveryLevel() {
        refused(d -> d.addProperty("extra", 1), "sequences", "extra");
        refused(d -> chain(d).addProperty("extra", 1), "c1", "extra");
        refused(d -> memory(d, 1).addProperty("extra", 1), "c1", "m2", "extra");
        refused(d -> probe(d).addProperty("extra", 1), "c1", "probe #0", "extra");
        refused(d -> memory(d, 1).getAsJsonArray("entities").get(0).getAsJsonObject().addProperty("extra", 1),
                "c1", "m2", "extra");
        refused(d -> memory(d, 1).getAsJsonArray("relations").get(0).getAsJsonObject().addProperty("extra", 1),
                "c1", "m2", "extra");
        refused(d -> {
            var date = new JsonObject();
            date.addProperty("span", "Kestrel");
            date.addProperty("value", "2025-01");
            date.addProperty("extra", 1);
            var dates = new JsonArray();
            dates.add(date);
            memory(d, 1).add("dates", dates);
        }, "c1", "m2", "extra");
    }

    @Test
    void aBadLinkIsRefused() {
        refused(d -> {
            var later = new JsonObject();
            later.addProperty("m2", "update");
            memory(d, 0).add("supersedes", later);
        }, "c1", "m1", "m2");
        refused(d -> memory(d, 1).getAsJsonObject("supersedes").addProperty("zz", "update"), "c1", "m2", "zz");
        refused(d -> memory(d, 1).getAsJsonObject("supersedes").addProperty("m2", "update"), "c1", "m2", "itself");
        refused(d -> {
            var from = new JsonArray();
            from.add("m9");
            memory(d, 1).add("derivedFrom", from);
        }, "c1", "m2", "m9");
        refused(d -> memory(d, 1).getAsJsonObject("supersedes").addProperty("m1", "replace"), "c1", "m2", "m1");
    }

    @Test
    void aMissingRequiredKeyIsRefused() {
        refused(d -> memory(d, 1).remove("text"), "c1", "m2", "text");
        refused(d -> memory(d, 1).remove("capturedAt"), "c1", "m2", "capturedAt");
        refused(d -> memory(d, 1).remove("entities"), "c1", "m2", "entities");
        refused(d -> chain(d).remove("probes"), "c1", "probes");
        refused(d -> probe(d).remove("truth"), "c1", "probe #0", "truth");
        refused(d -> probe(d).remove("d"), "c1", "probe #0", "'d'");
    }

    @Test
    void aMemoryRelationTheSchemaDoesNotAllowIsRefused() {
        refused(d -> memory(d, 1).getAsJsonArray("relations").get(0).getAsJsonObject()
                .addProperty("type", "located_in"), "c1", "m2", "located_in");
    }

    @Test
    void aMemoryBeforeItsPredecessorIsRefused() {
        refused(d -> memory(d, 1).addProperty("capturedAt", "2025-01-01"), "c1", "m2", "capturedAt");
    }

    @Test
    void aBadProbeIsRefused() {
        refused(d -> probe(d).addProperty("after", "m9"), "c1", "probe #0", "m9");
        refused(d -> probe(d).addProperty("to", "nobody"), "c1", "probe #0", "nobody");
        refused(d -> probe(d).addProperty("before", "m1"), "c1", "probe #0", "exactly one");
        refused(d -> probe(d).remove("after"), "c1", "probe #0", "exactly one");
        refused(d -> probe(d).addProperty("truth", "MAYBE"), "c1", "probe #0", "MAYBE");
        refused(d -> probe(d).addProperty("type", "located_in"), "c1", "probe #0", "located_in");
    }

    @Test
    void aDerivedMemoryParsesBesideTheExplicitTurnAuthors() {
        var derived = base();
        memory(derived, 1).addProperty("authorType", "consolidation_derived");
        var from = new JsonArray();
        from.add("m1");
        memory(derived, 1).add("derivedFrom", from);
        var memory = parse(derived).chains().getFirst().memories().get(1);
        assertEquals(MemoryAuthorType.CONSOLIDATION_DERIVED, memory.authorType());
        assertEquals(List.of("m1"), memory.derivedFrom());

        var human = base();
        memory(human, 1).addProperty("authorType", "human_turn");
        assertEquals(MemoryAuthorType.HUMAN_TURN, parse(human).chains().getFirst().memories().get(1).authorType());
        var guest = base();
        memory(guest, 1).addProperty("authorType", "guest_turn");
        assertEquals(MemoryAuthorType.GUEST_TURN, parse(guest).chains().getFirst().memories().get(1).authorType());
        assertEquals(MemoryAuthorType.HUMAN_TURN, parse(base()).chains().getFirst().memories().get(1).authorType());
    }

    @Test
    void aDerivedMemoryWithNoSourceOrAnUnsupportedAuthorIsRefused() {
        refused(d -> memory(d, 1).addProperty("authorType", "consolidation_derived"), "c1", "m2", "derivedFrom");
        refused(d -> {
            memory(d, 1).addProperty("authorType", "consolidation_derived");
            memory(d, 1).add("derivedFrom", new JsonArray());
        }, "c1", "m2", "derivedFrom");
        refused(d -> memory(d, 1).addProperty("authorType", "agent_synthesized"), "c1", "m2", "agent_synthesized");
    }

    @Test
    void aBadAuthorTagOrDuplicateIsRefused() {
        refused(d -> memory(d, 1).addProperty("authorType", "bot_turn"), "c1", "m2", "bot_turn");
        refused(d -> chain(d).getAsJsonArray("tags").add("rewrite"), "c1", "rewrite");
        refused(d -> {
            var tags = new JsonArray();
            tags.add(GraphCases.GUEST_ABOUT_OWNER);
            chain(d).add("tags", tags);
        }, "c1", "m1", "guest_turn");
        refused(d -> memory(d, 1).addProperty("id", "m1"), "c1", "m1", "duplicate");
        refused(d -> d.getAsJsonArray("chains").add(chain(d).deepCopy()), "c1", "duplicate");
        refused(d -> {
            var copy = chain(d).deepCopy();
            copy.addProperty("id", "c2");
            d.getAsJsonArray("chains").add(copy);
        }, "c2", "m1", "duplicate");
    }
}
