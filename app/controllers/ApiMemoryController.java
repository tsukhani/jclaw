package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import memory.MemoryCategory;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import play.mvc.Controller;
import play.mvc.With;

import static utils.GsonHolder.INSTANCE;

/**
 * Admin API for agent memories (JCLAW-40). Lists an agent's stored memories with
 * their importance and category so the operator can see what's been captured,
 * manually adjust importance (and category), and delete entries.
 *
 * <p>Memories are keyed by agent <em>name</em> in the store; every action
 * resolves the agent by id and scopes the row to that agent's name, so one
 * agent's id can never read or mutate another agent's memory.
 */
@With(AuthCheck.class)
public class ApiMemoryController extends Controller {

    private static final Gson gson = INSTANCE;

    private static final String KEY_IMPORTANCE = "importance";
    private static final String KEY_CATEGORY = "category";

    public record MemoryDto(String id, String text, String category,
                            double importance, String createdAt) {}

    public record MemoryUpdateRequest(Double importance, String category) {}

    /**
     * GET /api/agents/{id}/memories — list an agent's memories, newest first.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = MemoryDto.class))))
    @Operation(summary = "List an agent's stored memories with importance and category")
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var result = MemoryStoreFactory.get().list(agent.name).stream()
                .map(e -> new MemoryDto(e.id(), e.text(), e.category(),
                        e.importance(),
                        e.createdAt() == null ? null : e.createdAt().toString()))
                .toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * PUT /api/agents/{id}/memories/{memoryId} — adjust a memory's importance
     * (0.0–1.0) and optionally its category. Operator-driven curation.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = MemoryUpdateRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryDto.class)))
    @Operation(summary = "Adjust a memory's importance and/or category")
    public static void updateForAgent(Long id, Long memoryId) {
        Memory memory = scopedMemory(id, memoryId);

        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw new AssertionError("unreachable: badRequest() throws");
        }
        if (body.has(KEY_IMPORTANCE) && !body.get(KEY_IMPORTANCE).isJsonNull()) {
            double imp = body.get(KEY_IMPORTANCE).getAsDouble();
            if (imp < 0.0 || imp > 1.0) {
                error(400, "importance must be between 0.0 and 1.0");
            }
            memory.importance = imp;
        }
        if (body.has(KEY_CATEGORY) && !body.get(KEY_CATEGORY).isJsonNull()) {
            var normalized = MemoryCategory.normalize(body.get(KEY_CATEGORY).getAsString());
            if (normalized != null) memory.category = normalized;
        }
        memory.save();

        renderJSON(gson.toJson(new MemoryDto(String.valueOf(memory.id), memory.text,
                memory.category, memory.importance,
                memory.createdAt == null ? null : memory.createdAt.toString())));
    }

    /**
     * DELETE /api/agents/{id}/memories/{memoryId} — remove a memory.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Delete a memory")
    public static void deleteForAgent(Long id, Long memoryId) {
        Memory memory = scopedMemory(id, memoryId);
        memory.delete();
        renderJSON("{\"status\":\"deleted\"}");
    }

    /**
     * Resolve {@code memoryId} and confirm it belongs to the agent identified by
     * {@code id}. 404s (never 403) on a missing agent, missing memory, or a
     * cross-agent id mismatch — a not-yours row is indistinguishable from a
     * not-existent one.
     */
    private static Memory scopedMemory(Long id, Long memoryId) {
        Agent agent = Agent.findById(id);
        if (agent == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        Memory memory = Memory.findById(memoryId);
        if (memory == null || !agent.name.equals(memory.agentId)) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        return memory;
    }
}
