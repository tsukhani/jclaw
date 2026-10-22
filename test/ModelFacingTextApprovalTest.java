import agents.SystemPromptAssembler;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import tools.JClawApiTool;
import utils.AppClock;
import utils.GsonHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Set;

/**
 * Approval tests for the text the model reads (JF-17): the assembled system prompt, the tool definitions and the
 * {@code jclaw_api} discover surface, each compared with a checked-in file in {@code test/approved/}. A change to any
 * of them fails here and writes {@code <name>.received.txt} beside the approved file; read the diff, and if the model
 * should now read exactly that, move the received file over the approved one. Kept to these three, because a snapshot
 * approved without reading is noise.
 */
class ModelFacingTextApprovalTest extends UnitTest {

    private static final Path APPROVED = Path.of("test", "approved");
    private static final String AGENT = "approval-text-agent";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-22T09:00:00Z"), ZoneOffset.UTC);

    private Agent agent;

    @BeforeEach
    void setup() {
        ToolRegistrySync.canonicalForTest();
        var stale = Agent.findByName(AGENT);
        if (stale != null) AgentService.delete(stale);
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
    }

    @AfterEach
    void teardown() {
        try {
            var created = Agent.findByName(AGENT);
            if (created != null) AgentService.delete(created);
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void theSystemPromptIsApproved() throws IOException {
        var prompt = AppClock.callWith(FIXED, () -> SystemPromptAssembler.assemble(agent, null, Set.of(), "web").systemPrompt());
        // The environment block names the machine and the run; everything else is the text itself.
        assertApproved("system-prompt", prompt
                .replaceAll("- Agent ID: \\d+", "- Agent ID: <id>")
                .replaceAll("- JClaw version: .*", "- JClaw version: <version>")
                .replaceAll("- Platform: .*", "- Platform: <platform>")
                .replaceAll("- Runtime: Java \\d+", "- Runtime: Java <feature>"));
    }

    @Test
    void theToolDefinitionsAreApproved() throws IOException {
        var defs = GsonHolder.GSON.toJsonTree(ToolRegistry.getToolDefsForAgent(agent));
        assertApproved("tool-definitions", GsonHolder.GSON.newBuilder().setPrettyPrinting().create().toJson(sortedKeys(defs)) + "\n");
    }

    /**
     * Schemas built with {@code Map.of} and {@code Set.of} iterate in an order the JDK salts per JVM, so object keys and
     * lists of plain values ({@code enum}, {@code required}, where order means nothing) are sorted; the tool list is not.
     */
    private static JsonElement sortedKeys(JsonElement json) {
        if (json instanceof JsonObject object) {
            var sorted = new JsonObject();
            object.keySet().stream().sorted().forEach(key -> sorted.add(key, sortedKeys(object.get(key))));
            return sorted;
        }
        if (json instanceof JsonArray array) {
            var elements = array.asList().stream().map(ModelFacingTextApprovalTest::sortedKeys).toList();
            if (elements.stream().allMatch(JsonElement::isJsonPrimitive)) {
                elements = elements.stream().sorted(Comparator.comparing(JsonElement::getAsString)).toList();
            }
            var copy = new JsonArray();
            elements.forEach(copy::add);
            return copy;
        }
        return json;
    }

    @Test
    void theJclawApiDiscoverSurfaceIsApproved() throws IOException {
        assertApproved("jclaw-api-discover", new JClawApiTool().execute("{\"action\":\"discover\"}", agent));
    }

    private static void assertApproved(String name, String received) throws IOException {
        var approvedFile = APPROVED.resolve(name + ".approved.txt");
        var receivedFile = APPROVED.resolve(name + ".received.txt");
        if (Files.exists(approvedFile) && Files.readString(approvedFile).equals(received)) {
            Files.deleteIfExists(receivedFile);
            return;
        }
        Files.createDirectories(APPROVED);
        Files.writeString(receivedFile, received);
        fail((Files.exists(approvedFile) ? "The model-facing text changed" : "No approved text yet")
                + ": compare " + receivedFile + " with " + approvedFile
                + ", and if the model should now read exactly that, move the received file over the approved one");
    }
}
