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
import services.graphspike.HeldOut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JCLAW-1356: sampling reads an agent's memories into a file without touching them; loading skips unlabelled rows. */
class HeldOutTest extends UnitTest {

    private String agentId;
    private final List<String> memoryIds = new ArrayList<>();
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        LuceneTestSync.closedForTest();
        agentId = String.valueOf(AgentService.create("heldout-" + UUID.randomUUID().toString().substring(0, 8),
                "test-provider", "test-model").id);
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
    }
}
