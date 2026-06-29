package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import memory.MemoryCategory;
import models.Memory;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.JpqlFilter;

import java.io.IOException;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Admin API for agent memories (JCLAW-40). A cross-agent view: lists every
 * agent's stored memories with importance, category, and the owning agent,
 * narrowed by a tasks-style query bar — free-text {@code q} over memory text
 * plus {@code agent} / {@code category} / {@code importance} predicates. The
 * operator can adjust importance (and category) or delete any memory by its id.
 *
 * <p>Single-operator Personal Edition, so memories are addressed by their global
 * id — there is no per-agent access boundary to enforce.
 */
@With(AuthCheck.class)
public class ApiMemoryController extends Controller {

    private static final Gson gson = INSTANCE;

    private static final String KEY_IMPORTANCE = "importance";
    private static final String KEY_CATEGORY = "category";
    private static final String FIELD_IMPORTANCE = "m.importance";

    public record MemoryDto(String id, String agentName, String text, String category,
                            double importance, String createdAt) {}

    public record MemoryUpdateRequest(Double importance, String category) {}

    /**
     * GET /api/memories — list memories across all agents, newest first, narrowed
     * by optional filters: {@code q} (free-text over memory text via the MEMORY
     * Lucene scope, with a LIKE fallback when search isn't initialized),
     * {@code agent} (exact agent name), {@code category} (exact), and
     * {@code importance} (a threshold like {@code >0.8}, {@code <=0.5}, or a bare
     * number treated as {@code >=}).
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = MemoryDto.class))))
    @Operation(summary = "List memories across agents with a filter query (q / agent / category / importance)")
    public static void list(String q, String agent, String category, String importance,
                            Integer limit, Integer offset) {
        int effLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 200;
        int effOffset = (offset != null && offset >= 0) ? offset : 0;

        var filter = new JpqlFilter()
                .eq("m.agentId", blankToNull(agent))
                .eq("m.category", normalizeCategory(category));
        applyImportance(filter, importance);

        // Free-text q: the MEMORY Lucene scope (unscoped — across all agents)
        // when the backend is initialized; a LIKE fallback otherwise (test mode,
        // where the index is closed / dialect is "none").
        List<Long> ftsIds = null;
        if (q != null && !q.isBlank()) {
            if ("none".equals(MessageSearch.activeDialect())) {
                filter.like("LOWER(m.text)", "%" + q.strip().toLowerCase() + "%");
            } else {
                try {
                    var ids = MessageSearch.searchIds(LuceneIndexer.Scope.MEMORY, q.strip(), 500);
                    if (ids.isEmpty()) {
                        renderJSON("[]");
                        return;
                    }
                    ftsIds = ids;
                } catch (IOException e) {
                    EventLogger.warn("search", null, null,
                            "Memory FTS failed for q='%s': %s".formatted(q, e.getMessage()));
                }
            }
        }

        var where = filter.toWhereClause();
        if (ftsIds != null) {
            where = where.isEmpty() ? "m.id IN (:fts)" : where + " AND m.id IN (:fts)";
        }
        String jpql = where.isEmpty()
                ? "SELECT m FROM Memory m ORDER BY m.updatedAt DESC"
                : "SELECT m FROM Memory m WHERE " + where + " ORDER BY m.updatedAt DESC";
        var jpaQ = JPA.em().createQuery(jpql, Memory.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            jpaQ.setParameter(i + 1, params.get(i));
        }
        if (ftsIds != null) jpaQ.setParameter("fts", ftsIds);
        List<Memory> rows = jpaQ.setFirstResult(effOffset).setMaxResults(effLimit).getResultList();

        renderJSON(gson.toJson(rows.stream().map(ApiMemoryController::toDto).toList()));
    }

    /**
     * PUT /api/memories/{memoryId} — adjust a memory's importance (0.0–1.0) and
     * optionally its category. Operator-driven curation.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = MemoryUpdateRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryDto.class)))
    @Operation(summary = "Adjust a memory's importance and/or category")
    public static void update(Long memoryId) {
        Memory memory = Memory.findById(memoryId);
        if (memory == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
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
        renderJSON(gson.toJson(toDto(memory)));
    }

    /**
     * DELETE /api/memories/{memoryId} — remove a memory.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Delete a memory")
    public static void delete(Long memoryId) {
        Memory memory = Memory.findById(memoryId);
        if (memory == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        memory.delete();
        renderJSON("{\"status\":\"deleted\"}");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static MemoryDto toDto(Memory m) {
        return new MemoryDto(String.valueOf(m.id), m.agentId, m.text, m.category,
                m.importance, m.createdAt == null ? null : m.createdAt.toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String normalizeCategory(String c) {
        return c == null || c.isBlank() ? null : MemoryCategory.normalize(c);
    }

    /**
     * Apply an importance threshold. Accepts a leading comparator — {@code >}
     * and {@code <} are strict, {@code >=} and {@code <=} inclusive — or a bare
     * number (treated as {@code >=}). A non-numeric value is ignored.
     */
    private static void applyImportance(JpqlFilter filter, String importance) {
        if (importance == null || importance.isBlank()) return;
        var v = importance.strip();
        try {
            if (v.startsWith(">=")) filter.gte(FIELD_IMPORTANCE, Double.parseDouble(v.substring(2).strip()));
            else if (v.startsWith(">")) filter.gt(FIELD_IMPORTANCE, Double.parseDouble(v.substring(1).strip()));
            else if (v.startsWith("<=")) filter.lte(FIELD_IMPORTANCE, Double.parseDouble(v.substring(2).strip()));
            else if (v.startsWith("<")) filter.lt(FIELD_IMPORTANCE, Double.parseDouble(v.substring(1).strip()));
            else filter.gte(FIELD_IMPORTANCE, Double.parseDouble(v));
        } catch (NumberFormatException _) {
            // ignore an unparseable importance filter
        }
    }
}
