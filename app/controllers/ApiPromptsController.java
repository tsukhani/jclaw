package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Prompt;
import play.mvc.Controller;
import play.mvc.With;
import services.PromptImportExportService;
import utils.ApiResponses;

import java.util.Arrays;

import static utils.GsonHolder.INSTANCE;

/**
 * CRUD + import/export over the {@code prompt} table — the Prompts Library
 * (JCLAW-813).
 *
 * <p>Operator-wide (single-operator Personal Edition, no per-user scoping),
 * class-level {@code @With(AuthCheck.class)} like every other Api* controller.
 * Categories are a fixed taxonomy ({@link Prompt.Category}), so there is no
 * category CRUD — {@link #categories()} just exposes the closed list for the
 * frontend dropdown/filter; tags are the free-form axis and need no endpoint.
 */
@With(AuthCheck.class)
public class ApiPromptsController extends Controller {

    private static final Gson gson = INSTANCE;

    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TAGS = "tags";
    private static final String KEY_CATEGORY = "category";

    /** Wire shape for a prompt: the stored fields plus the category flattened to
     *  its stable value + display label. The category glyph is a frontend
     *  concern (value → Heroicon), so no icon travels on the wire. */
    public record PromptView(Long id, String title, String content, String tags,
                             String category, String categoryLabel,
                             String createdAt, String updatedAt) {
        static PromptView of(Prompt p) {
            return new PromptView(p.id, p.title, p.content, p.tags,
                    p.category.name(), p.category.label,
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
    @Operation(summary = "List all saved prompts, newest-edited first")
    public static void list() {
        renderJSON(gson.toJson(Prompt.findAllOrdered().stream().map(PromptView::of).toList()));
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = CategoryView.class))))
    @Operation(summary = "List the fixed prompt categories (value, label, emoji)")
    public static void categories() {
        renderJSON(gson.toJson(Arrays.stream(Prompt.Category.values()).map(CategoryView::of).toList()));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = PromptView.class)))
    @Operation(summary = "Create a prompt")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var row = new Prompt();
        row.title = JsonBodyReader.requiredOr400(body, KEY_TITLE);
        row.content = JsonBodyReader.requiredOr400(body, KEY_CONTENT);
        row.category = requireCategory(JsonBodyReader.requiredOr400(body, KEY_CATEGORY));
        row.tags = JsonBodyReader.optString(body, KEY_TAGS, true);
        row.save();
        renderJSON(gson.toJson(PromptView.of(row)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = PromptView.class)))
    @Operation(summary = "Update a prompt by id (partial: only supplied fields change)")
    public static void update(Long id) {
        var row = requirePrompt(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        if (present(body, KEY_TITLE)) row.title = JsonBodyReader.requiredOr400(body, KEY_TITLE);
        if (present(body, KEY_CONTENT)) row.content = JsonBodyReader.requiredOr400(body, KEY_CONTENT);
        if (present(body, KEY_CATEGORY)) row.category = requireCategory(body.get(KEY_CATEGORY).getAsString());
        // tags is nullable/clearable: an explicit "tags":"" or null blanks it.
        if (body.has(KEY_TAGS)) row.tags = JsonBodyReader.optString(body, KEY_TAGS, true);
        row.save();
        renderJSON(gson.toJson(PromptView.of(row)));
    }

    @Operation(summary = "Delete a prompt by id")
    public static void delete(Long id) {
        var row = requirePrompt(id);
        row.delete();
        ApiResponses.ok("deleted", true);
    }

    @Operation(summary = "Export all prompts as a portable JSON document")
    public static void export() {
        renderJSON(gson.toJson(PromptImportExportService.exportAll()));
    }

    @SuppressWarnings("java:S2259")
    @Operation(summary = "Import prompts from a JSON document (mode: merge | replace)")
    public static void importPrompts() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();
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

    @SuppressWarnings("java:S2259")
    private static Prompt requirePrompt(Long id) {
        Prompt row = Prompt.findById(id);
        if (row != null) return row;
        notFound();
        throw new AssertionError("notFound() did not throw");
    }
}
