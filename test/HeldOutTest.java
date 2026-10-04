import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.MemoryStoreFactory;
import memory.ontology.OntologySchema;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import services.grapheval.GraphCases;
import services.grapheval.HeldOut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** JCLAW-1356: sampling reads an agent's memories into a file without touching them; loading skips unlabelled rows. */
class HeldOutTest extends UnitTest {

    private String agentId;
    private String agentName;
    private final List<String> memoryIds = new ArrayList<>();
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        LuceneTestSync.closedForTest();
        agentName = "heldout-" + UUID.randomUUID().toString().substring(0, 8);
        agentId = String.valueOf(AgentService.create(agentName, "test-provider", "test-model").id);
        var store = MemoryStoreFactory.get();
        for (int i = 0; i < 5; i++) {
            var text = "The user keeps notebook " + i + " at Harborlight Analytics.";
            memoryIds.add(Tx.run(() -> store.storeDeferred(agentId, text, "fact", 0.5)));
        }
        dir = Files.createTempDirectory("heldout");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            var store = MemoryStoreFactory.get();
            for (var id : memoryIds) Tx.run(() -> store.delete(id));
            try (var files = Files.list(dir)) {
                for (var f : files.toList()) Files.delete(f);
            }
            Files.delete(dir);
        } finally {
            LuceneTestSync.release();
        }
    }

    private List<String> texts() {
        return Tx.run(() -> Memory.findByAgent(agentId)).stream().map(m -> m.text + "|" + m.updatedAt).sorted().toList();
    }

    @Test
    void samplingIsSeededReadOnlyAndUnlabelled() throws Exception {
        var before = texts();
        var a = dir.resolve("a.json");
        var b = dir.resolve("b.json");
        assertEquals(new HeldOut.Sampled(3, 5), HeldOut.sample(a, agentId, 3, 7));
        HeldOut.sample(b, agentId, 3, 7);
        assertEquals(Files.readString(a), Files.readString(b), "the same seed picks the same memories");
        assertEquals(before, texts(), "sampling touched no memory");

        var cases = JsonParser.parseString(Files.readString(a)).getAsJsonObject().getAsJsonArray("cases");
        assertEquals(3, cases.size());
        for (var c : cases) {
            var o = c.getAsJsonObject();
            assertFalse(o.get("labelled").getAsBoolean());
            assertTrue(memoryIds.contains(String.valueOf(o.get("memoryId").getAsLong())));
            assertTrue(o.getAsJsonArray("candidates").toString().contains("Harborlight Analytics"));
            LocalDate.parse(o.get("capturedAt").getAsString());
            assertEquals("unattributed", o.get("authorType").getAsString(), "storeDeferred records no author");
        }
        assertEquals(new HeldOut.Sampled(5, 5), HeldOut.sample(dir.resolve("all.json"), agentId, 50, 7));
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        var file = dir.resolve("labelled.json");
        Files.writeString(file, "{\"cases\":[]}");
        assertThrows(IllegalStateException.class, () -> HeldOut.sample(file, agentId, 3, 7));
        assertEquals("{\"cases\":[]}", Files.readString(file));
    }

    @Test
    void loadingSkipsAndCountsUnlabelledCasesAndValidatesTheRest() throws Exception {
        var file = dir.resolve("h.json");
        HeldOut.sample(file, agentId, 5, 7);
        var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var first = root.getAsJsonArray("cases").get(0).getAsJsonObject();
        first.addProperty("labelled", true);
        var operator = new JsonObject();
        operator.addProperty("id", "operator");
        operator.addProperty("mention", "The user");
        operator.addProperty("type", "Person");
        first.getAsJsonArray("entities").add(operator);
        Files.writeString(file, root.toString());

        var loaded = HeldOut.load(file, OntologySchema.seed());
        assertEquals(1, loaded.cases().size());
        assertEquals(4, loaded.unlabelled());
        assertEquals("h0", loaded.cases().getFirst().labels().id());
        assertEquals(first.get("memoryId").getAsLong(), loaded.cases().getFirst().memoryId());

        operator.addProperty("type", "Spaceship");
        Files.writeString(file, root.toString());
        var e = assertThrows(IllegalArgumentException.class, () -> HeldOut.load(file, OntologySchema.seed()));
        assertTrue(e.getMessage().startsWith("case h0: "), e.getMessage());
        assertFalse(e.getMessage().contains("notebook"), "a refusal never quotes the memory");

        operator.addProperty("type", "Person");
        var unquoted = new JsonObject();
        unquoted.addProperty("id", "ledger");
        unquoted.addProperty("mention", "Quillfeather Ledger");
        unquoted.addProperty("type", "Project");
        first.getAsJsonArray("entities").add(unquoted);
        Files.writeString(file, root.toString());
        e = assertThrows(IllegalArgumentException.class, () -> HeldOut.load(file, OntologySchema.seed()));
        assertEquals("case h0: labels break the v3 rules in evals/graph/README.md", e.getMessage());
        assertFalse(e.getMessage().contains("Quillfeather"), "a refusal never quotes a span");
    }

    @Test
    void aLabelledCaseSampledBeforeV3IsRefusedByPositionWithAResample() throws Exception {
        var file = dir.resolve("old.json");
        HeldOut.sample(file, agentId, 2, 7);
        var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        cases.get(1).getAsJsonObject().addProperty("labelled", true);
        var operator = new JsonObject();
        operator.addProperty("id", "operator");
        operator.addProperty("mention", "The user");
        operator.addProperty("type", "Person");
        cases.get(1).getAsJsonObject().getAsJsonArray("entities").add(operator);
        var labelled = cases.get(1).getAsJsonObject().deepCopy();
        var expected = "case h1: sampled before v3 (no capturedAt or authorType); move the file aside and resample";
        for (Consumer<JsonObject> breakIt : List.<Consumer<JsonObject>>of(
                o -> o.remove("capturedAt"), o -> o.remove("authorType"), o -> o.addProperty("authorType", "robot_turn"))) {
            var broken = labelled.deepCopy();
            breakIt.accept(broken);
            cases.set(1, broken);
            Files.writeString(file, root.toString());
            var e = assertThrows(IllegalArgumentException.class, () -> HeldOut.load(file, OntologySchema.seed()));
            assertEquals(expected, e.getMessage());
        }
        cases.set(1, labelled);
        Files.writeString(file, root.toString());
        assertEquals(1, HeldOut.load(file, OntologySchema.seed()).cases().size(), "the case as sampled loads");
    }

    @Test
    void theHeldOutAgentIsTheOneItsMemoriesBelongTo() throws Exception {
        Function<List<String>, HeldOut.Loaded> loaded = ids -> new HeldOut.Loaded(ids.stream()
                .map(id -> new HeldOut.HeldCase(Long.parseLong(id), new GraphCases.Case("h", List.of(), "x", List.of(),
                        List.of(), List.of()))).toList(), 0);
        assertEquals(agentName, HeldOut.agentName(loaded.apply(memoryIds)));
        assertNull(HeldOut.agentName(loaded.apply(List.of())));
        assertNull(HeldOut.agentName(loaded.apply(List.of("999999999"))), "no stored memory, no agent");

        var other = String.valueOf(AgentService.create("heldout-other-" + UUID.randomUUID().toString().substring(0, 8),
                "test-provider", "test-model").id);
        var foreign = Tx.run(() -> MemoryStoreFactory.get().storeDeferred(other, "The user keeps a ledger.", "fact", 0.5));
        memoryIds.add(foreign);
        var e = assertThrows(IllegalArgumentException.class, () -> HeldOut.agentName(loaded.apply(memoryIds)));
        assertTrue(e.getMessage().contains("more than one agent"), e.getMessage());
    }
}
