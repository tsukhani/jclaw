package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.Prompt;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.PromptGenerationService;
import services.PromptImportExportService;
import utils.ApiResponses;
import utils.JsonArgs;
import utils.Strings;

import java.util.Arrays;
import java.util.Locale;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static controllers.AgentAccess.Level.OWN_ONLY;
import static utils.GsonHolder.GSON;

/**
 * CRUD + import/export over the {@code prompt} table — the Prompts Library
 * (JCLAW-813).
 *
 * <p>A prompt has an owner ({@link Prompt#ownerAgent}), chosen once when it is created (JF-307): the
 * agent its creator names, else the creator. The operator and {@code main} may name any agent; any
 * other agent only itself. An agent other than {@code main} lists, reads, edits and deletes only
 * its own, and a prompt with no owner is the operator's, which only the operator and {@code main}
 * reach (JF-292).
 * Categories are a fixed taxonomy ({@link Prompt.Category}), so there is no
 * category CRUD — {@link #categories()} just exposes the closed list for the
 * frontend dropdown/filter; tags are the free-form axis and need no endpoint.
 */
@With(AuthCheck.class)
public class ApiPromptsController extends Controller {

    private static final Gson gson = GSON;

    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TAGS = "tags";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_OWNER_AGENT_ID = "ownerAgentId";

    /** Characters of {@code content} an agent's list row keeps; the whole prompt comes from {@link #get}. */
    public static final int CONTENT_PREVIEW_CHARS = 200;

    /** Wire shape for a prompt: the stored fields plus the category flattened to
     *  its stable value + display label. The category glyph is a frontend
     *  concern (value → Heroicon), so no icon travels on the wire.
     *
     *  @param ownerAgentId   the agent that owns it, or null for the operator's
     *  @param ownerAgentName that agent's name, or null for the operator's */
    public record PromptView(Long id, String title, String content, String tags,
                             String category, String categoryLabel,
                             @Nullable Long ownerAgentId, @Nullable String ownerAgentName,
                             @Nullable String createdAt, @Nullable String updatedAt) {
        static PromptView of(Prompt p) {
            return new PromptView(p.id, p.title, p.content, p.tags,
                    p.category.name(), p.category.label,
                    p.ownerAgent == null ? null : p.ownerAgent.id,
                    p.ownerAgent == null ? null : p.ownerAgent.name,
                    p.createdAt == null ? null : p.createdAt.toString(),
                    p.updatedAt == null ? null : p.updatedAt.toString());
        }
    }

    /** An agent's list row: the content gives way to a preview, so the list fits {@code jclaw_api}'s response cap. */
    public record PromptSummaryView(Long id, String title, String tags,
                                    String category, String categoryLabel,
                                    String contentPreview, boolean contentTruncated,
                                    @Nullable Long ownerAgentId, @Nullable String ownerAgentName,
                                    @Nullable String createdAt, @Nullable String updatedAt) {
        static PromptSummaryView of(Prompt p) {
            return new PromptSummaryView(p.id, p.title, p.tags,
                    p.category.name(), p.category.label,
                    Strings.truncate(p.content, CONTENT_PREVIEW_CHARS),
                    p.content.length() > CONTENT_PREVIEW_CHARS,
                    p.ownerAgent == null ? null : p.ownerAgent.id,
                    p.ownerAgent == null ? null : p.ownerAgent.name,
                    p.createdAt == null ? null : p.createdAt.toString(),
                    p.updatedAt == null ? null : p.updatedAt.toString());
        }
    }

    /** Wire shape for one entry in the fixed category list (value + label; the
     *  frontend maps the value to a Heroicon). */
    public record CategoryView(String value, String label) {
        static CategoryView of(Prompt.Category c) {
            return new CategoryView(c.name(), c.label);
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PromptView.class))))
    @Operation(summary = "List saved prompts, newest-edited first, with an optional title filter (case-insensitive substring); "
            + "an agent's rows carry a contentPreview in place of content, and the whole prompt comes from GET /api/prompts/{id}. "
            + "An agent other than main lists only the prompts it created")
    @AgentAccess(OWN_ONLY)
    public static void list(@Nullable String title) {
        // Null for the operator and main, who reach every prompt.
        var prompts = Prompt.findOrdered(RequestPrincipal.scopedAgentId(null)).stream();
        if (title != null && !title.isBlank()) {
            var needle = title.strip().toLowerCase(Locale.ROOT);
            prompts = prompts.filter(p -> p.title.toLowerCase(Locale.ROOT).contains(needle));
        }
        // The operator's rows stay whole: the Prompts page and chat's prompt picker search, copy and insert the content (JF-144).
        renderJSON(RequestPrincipal.isAgentOriginated()
                ? gson.toJson(prompts.map(PromptSummaryView::of).toList())
                : gson.toJson(prompts.map(PromptView::of).toList()));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptView.class)))
    @Operation(summary = "Get one saved prompt by id, its content whole")
    @AgentAccess(OWN_ONLY)
    public static void get(Long id) {
        renderJSON(gson.toJson(PromptView.of(requirePrompt(id))));
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = CategoryView.class))))
    @Operation(summary = "List the fixed prompt categories (value, label)")
    @AgentAccess(OPEN)
    public static void categories() {
        renderJSON(gson.toJson(Arrays.stream(Prompt.Category.values()).map(CategoryView::of).toList()));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptGenerationService.Generated.class)))
    @Operation(summary = "Generate a prompt (title, category, content, tags) from a description; does not save")
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the draft is written by main's model, "
            + "at the operator's cost")
    public static void generate() {
        RequestPrincipal.requireOperatorOrMain("generate a prompt");
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var description = JsonBodyReader.requiredOr400(body, "description");
        var generated = PromptGenerationService.generate(description);
        if (generated == null) {
            ApiResponses.error(503, ApiResponses.UNAVAILABLE,
                    "Prompt generation is unavailable — configure an LLM provider for the main agent first.");
        }
        renderJSON(gson.toJson(generated));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = PromptView.class)))
    @Operation(summary = "Create a prompt. ownerAgentId names the agent that owns it: any agent for the operator and "
            + "main, only itself for another agent. Without it the owner is the creator: the calling agent, "
            + "or nobody for the operator")
    @AgentAccess(value = OWN_ONLY, reason = "the new prompt is the calling agent's own (JF-292), unless main names "
            + "another agent as its owner (JF-307)")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }

        var row = new Prompt();
        row.title = JsonBodyReader.requiredOr400(body, KEY_TITLE);
        row.content = JsonBodyReader.requiredOr400(body, KEY_CONTENT);
        row.category = requireCategory(JsonBodyReader.requiredOr400(body, KEY_CATEGORY));
        row.tags = JsonBodyReader.optString(body, KEY_TAGS, true);
        row.ownerAgent = newPromptOwner(body);
        row.save();
        renderJSON(gson.toJson(PromptView.of(row)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = PromptView.class)))
    @Operation(summary = "Update a prompt by id (partial: only supplied fields change; the owner never does)")
    @AgentAccess(value = OWN_ONLY, reason = "one prompt the calling agent owns; main reaches every prompt (JF-292)")
    public static void update(Long id) {
        var row = requirePrompt(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }

        if (present(body, KEY_TITLE)) row.title = JsonBodyReader.requiredOr400(body, KEY_TITLE);
        if (present(body, KEY_CONTENT)) row.content = JsonBodyReader.requiredOr400(body, KEY_CONTENT);
        if (present(body, KEY_CATEGORY)) row.category = requireCategory(body.get(KEY_CATEGORY).getAsString());
        // tags is nullable/clearable: an explicit "tags":"" or null blanks it.
        if (body.has(KEY_TAGS)) row.tags = JsonBodyReader.optString(body, KEY_TAGS, true);
        row.save();
        renderJSON(gson.toJson(PromptView.of(row)));
    }

    @Operation(summary = "Delete a prompt by id")
    @AgentAccess(value = OWN_ONLY, reason = "one prompt the calling agent owns; main reaches every prompt (JF-292)")
    public static void delete(Long id) {
        var row = requirePrompt(id);
        row.delete();
        ApiResponses.ok("deleted", true);
    }

    @Operation(summary = "Export all prompts as a portable JSON document")
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the document holds every owner's prompts")
    public static void export() {
        RequestPrincipal.requireOperatorOrMain("export the prompt library");
        renderJSON(gson.toJson(PromptImportExportService.exportAll()));
    }

    @SuppressWarnings("java:S2259")
    @Operation(summary = "Import prompts from a JSON document (mode: merge | replace)")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "mode=replace discards the operator's whole prompt library in one call")
    public static void importPrompts() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var mode = JsonBodyReader.optString(body, "mode", true);
        if (mode == null) mode = PromptImportExportService.MODE_MERGE;
        if (!PromptImportExportService.MODE_MERGE.equals(mode)
                && !PromptImportExportService.MODE_REPLACE.equals(mode)) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "mode must be '%s' or '%s'".formatted(
                            PromptImportExportService.MODE_MERGE, PromptImportExportService.MODE_REPLACE));
        }
        // Play wraps the action in a JPA transaction, so the bulk delete/insert
        // commits atomically at action end — no explicit Tx needed here.
        int imported = PromptImportExportService.importAll(mode, body);
        ApiResponses.ok("imported", imported);
    }

    // ==================== helpers ====================

    private static boolean present(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull();
    }

    @SuppressWarnings("java:S2259")
    private static Prompt.Category requireCategory(String raw) {
        var c = Prompt.Category.fromValue(raw);
        if (c == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Unknown category '%s' (expected one of %s)".formatted(
                            raw, Arrays.stream(Prompt.Category.values()).map(Enum::name).toList()));
        }
        return c;
    }

    /** The agent creating a prompt: the calling agent, {@code main} included, and nobody for the operator. */
    private static @Nullable Agent creatingAgent() {
        RequestPrincipal.scopedAgentId(null);   // refuses an agent-originated request that names no agent
        return RequestPrincipal.callingAgent();
    }

    /**
     * The owner of a new prompt (JF-307): the agent {@code ownerAgentId} names, else the creator. The
     * operator and {@code main} may name any agent, and get a 400 for an id that names none; any
     * other agent may name only itself, and gets 403 {@code agent_scope} for anything else.
     */
    private static @Nullable Agent newPromptOwner(JsonObject body) {
        var creator = creatingAgent();
        if (!present(body, KEY_OWNER_AGENT_ID)) return creator;
        var id = JsonArgs.optLong(body, KEY_OWNER_AGENT_ID);
        Agent named = id == null ? null : Agent.findById(id);
        // Ahead of the 400, so the answer to an agent other than main never says which ids exist.
        if (!RequestPrincipal.mayReachAgentScopedRow(named)) {
            ApiResponses.error(403, ApiResponses.AGENT_SCOPE,
                    "An agent other than main owns every prompt it creates: leave ownerAgentId out, or send its own id.");
            throw ApiResponses.unreachable();
        }
        if (named == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, id == null
                    ? "ownerAgentId must be an agent's id, a number."
                    : "ownerAgentId " + id + " names no agent.");
            throw ApiResponses.unreachable();
        }
        return named;
    }

    /** The prompt, or 404; 403 {@code agent_scope} when it is another agent's or the operator's and the caller is not main. */
    @SuppressWarnings("java:S2259")
    private static Prompt requirePrompt(Long id) {
        Prompt row = Prompt.findById(id);
        if (row == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        if (!RequestPrincipal.mayReachAgentScopedRow(row.ownerAgent)) {
            ApiResponses.error(403, ApiResponses.AGENT_SCOPE, row.ownerAgent == null
                    ? "Prompt " + id + " is the operator's; an agent other than main reaches only its own prompts."
                    : "Prompt " + id + " belongs to another agent.");
            throw ApiResponses.unreachable();
        }
        return row;
    }
}
