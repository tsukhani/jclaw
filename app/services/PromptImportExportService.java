package services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import models.Prompt;

import java.util.List;

/**
 * Import/export of the Prompts Library (JCLAW-813). Kept out of the controller
 * because the merge/replace semantics and the lenient per-row coercion are real
 * logic worth unit-testing in isolation.
 *
 * <p>The export references categories by their stable enum <em>value</em>
 * (never the display label), so a document round-trips across installs even if
 * a category's label is later reworded.
 */
public final class PromptImportExportService {

    public static final String MODE_MERGE = "merge";
    public static final String MODE_REPLACE = "replace";
    private static final int EXPORT_VERSION = 1;

    private PromptImportExportService() { /* static-only */ }

    /** One prompt in the portable export document. */
    public record ExportPrompt(String title, String content, String tags, String category) {}

    /** The export document: a version tag (for forward-compat on import) + the prompts. */
    public record ExportDoc(int version, List<ExportPrompt> prompts) {}

    /** Snapshot every prompt for download. */
    public static ExportDoc exportAll() {
        var prompts = Prompt.<Prompt>findAll().stream()
                .map(p -> new ExportPrompt(p.title, p.content, p.tags, p.category.name()))
                .toList();
        return new ExportDoc(EXPORT_VERSION, prompts);
    }

    /**
     * Import prompts from a parsed document. {@link #MODE_REPLACE} wipes the
     * library first; {@link #MODE_MERGE} appends (title collisions are allowed —
     * two prompts may legitimately share a title). Rows missing title or content
     * are skipped; an unknown/blank category coerces to {@link Prompt.Category#CUSTOM}
     * so a foreign or partial export never fails the whole import. Runs inside
     * the caller's (controller) transaction.
     *
     * @return the number of prompts actually inserted
     */
    public static int importAll(String mode, JsonObject body) {
        if (MODE_REPLACE.equals(mode)) {
            Prompt.deleteAll();
        }
        if (body == null || !body.has("prompts") || !body.get("prompts").isJsonArray()) {
            return 0;
        }
        JsonArray arr = body.getAsJsonArray("prompts");
        int count = 0;
        for (var el : arr) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            var title = str(o, "title");
            var content = str(o, "content");
            if (title == null || title.isBlank() || content == null || content.isBlank()) continue;
            var row = new Prompt();
            row.title = title.trim();
            row.content = content;
            row.tags = blankToNull(str(o, "tags"));
            var cat = Prompt.Category.fromValue(str(o, "category"));
            row.category = cat != null ? cat : Prompt.Category.CUSTOM;
            row.save();
            count++;
        }
        return count;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
