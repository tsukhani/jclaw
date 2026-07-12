package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import memory.MemoryCategory;
import models.Agent;
import models.Memory;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.MemoryService;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.ApiResponses;
import utils.JpqlFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;

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
                            double importance, String createdAt,
                            String supersededAt, String supersededById) {}

    public record MemoryUpdateRequest(Double importance, String category) {}

    /**
     * GET /api/memories — list memories across all agents, newest first, narrowed
     * by optional filters: {@code q} (free-text over memory text via the MEMORY
     * Lucene scope, with a LIKE fallback when search isn't initialized),
     * {@code agent} (exact agent name), {@code category} (exact), and
     * {@code importance} (a threshold like {@code >0.8}, {@code <=0.5}, or a bare
     * number treated as {@code >=}).
     *
     * <p>{@code status} (JCLAW-557): {@code active} (the default — matches what
     * recall sees), {@code superseded} (only the JCLAW-525 supersession trail),
     * or {@code all}. Any other value falls back to active.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = MemoryDto.class))))
    @Operation(summary = "List memories across agents with a filter query (q / agent / category / importance / status), paginated with X-Total-Count")
    public static void list(String q, String agent, String category, String importance,
                            String status, String sort, String dir, Integer limit, Integer offset) {
        int effLimit = (limit != null && limit > 0) ? Math.min(limit, PagedJpqlQuery.MAX_LIMIT) : 200;
        int effOffset = (offset != null && offset >= 0) ? offset : 0;
        var agentNames = agentNamesById();

        // Resolve the filter (and any FTS hit set) ONCE, then run the count and
        // the page off the same resolution — otherwise a q= search would hit
        // Lucene twice per request. `total` drives the frontend pager via the
        // X-Total-Count header, matching Conversations/Subagents.
        var resolved = resolveQuery(q, agent, category, importance);
        long total = 0;
        List<Memory> rows = List.of();
        if (!resolved.empty()) {
            // JCLAW-722: COUNT and SELECT bind from one source (no re-bind divergence).
            var page = pagedQuery(resolved, status, sort, dir).page(effOffset, effLimit).execute();
            total = page.total();
            rows = page.rows();
        }

        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
        renderJSON(gson.toJson(rows.stream().map(m -> toDto(m, agentNames)).toList()));
    }

    /**
     * The list()/bulkDelete(filter) query core: memories matching the filter
     * set, newest first. Returns an empty list where list() used to
     * short-circuit (unknown agent name, empty FTS hit set).
     */
    private static List<Memory> selectMemories(String q, String agent, String category,
                                               String importance, String status,
                                               int limit, int offset) {
        var resolved = resolveQuery(q, agent, category, importance);
        // bulkDelete doesn't care about order — pass null sort → server default.
        return resolved.empty() ? List.of()
                : pagedQuery(resolved, status, null, null).page(offset, limit).rows();
    }

    /**
     * Filter + optional FTS hit set, resolved once. {@code empty} means the
     * filter can't match anything — an unknown agent name, or an FTS search
     * that ran but hit nothing — so callers short-circuit to zero rows/count
     * rather than issue a query that would return everything.
     */
    private record ResolvedQuery(JpqlFilter filter, List<Long> ftsIds, boolean empty) {}

    private static ResolvedQuery resolveQuery(String q, String agent, String category, String importance) {
        Long agentIdFilter = null;
        if (agent != null && !agent.isBlank()) {
            agentIdFilter = agentIdForName(agent.strip());
            if (agentIdFilter == null) return new ResolvedQuery(null, null, true);
        }
        var filter = new JpqlFilter()
                .eq("m.agent.id", agentIdFilter)
                .eq("m.category", normalizeCategory(category));
        applyImportance(filter, importance);

        var ftsResult = resolveFtsIds(filter, q);
        if (ftsResult.isPresent() && ftsResult.get().isEmpty()) return new ResolvedQuery(null, null, true);
        return new ResolvedQuery(filter, ftsResult.orElse(null), false);
    }

    /**
     * JCLAW-722: assemble the {@link PagedJpqlQuery} for a resolved filter, ordered
     * by sort/dir (server default when absent). The shared helper runs the SELECT
     * and the COUNT off the one WHERE body and parameter set, so the page and the
     * X-Total-Count total can't drift apart.
     */
    private static PagedJpqlQuery<Memory> pagedQuery(ResolvedQuery r, String status, String sort, String dir) {
        return PagedJpqlQuery.of(Memory.class, "Memory m", "m")
                .where(whereClause(r.filter(), r.ftsIds() != null, status))
                .positionalParams(r.filter().paramList())
                .namedParam("fts", r.ftsIds())
                .orderBy(orderByClause(sort, dir));
    }

    /**
     * Free-text q resolution: adds a LIKE to {@code filter} in the no-search-
     * backend path (Optional.empty() = no id constraint), or the Lucene hit ids
     * (present-but-empty = ran-but-matched-nothing, so the caller returns empty).
     */
    private static Optional<List<Long>> resolveFtsIds(JpqlFilter filter, String q) {
        if (q == null || q.isBlank()) return Optional.empty();
        if ("none".equals(MessageSearch.activeDialect())) {
            filter.like("LOWER(m.text)", "%" + q.strip().toLowerCase() + "%");
            return Optional.empty();
        }
        try {
            return Optional.of(MessageSearch.searchIds(LuceneIndexer.Scope.MEMORY, q.strip(), 500));
        } catch (IOException e) {
            EventLogger.warn("search", null, null,
                    "Memory FTS failed for q='%s': %s".formatted(q, e.getMessage()));
            return Optional.empty();
        }
    }

    /** The shared WHERE body: the filter clause, the optional FTS id
     *  constraint, and the status condition, AND-ed together. Empty string
     *  when nothing narrows. */
    private static String whereClause(JpqlFilter filter, boolean hasFts, String status) {
        var where = filter.toWhereClause();
        if (hasFts) {
            where = where.isEmpty() ? "m.id IN (:fts)" : where + " AND m.id IN (:fts)";
        }
        var statusCondition = statusCondition(status);
        if (statusCondition != null) {
            where = where.isEmpty() ? statusCondition : where + " AND " + statusCondition;
        }
        return where;
    }

    /**
     * Map the frontend sort column + direction to an ORDER BY. Columns are a
     * closed whitelist (switch) and direction resolves to the literal ASC/DESC,
     * so the concatenated JPQL carries no user input — no injection surface. An
     * absent/unknown column falls back to the recency default (newest first),
     * matching the pre-sort behaviour. A stable id tiebreak keeps paging
     * deterministic when the sort key has ties.
     */
    private static String orderByClause(String sort, String dir) {
        String col = switch (sort == null ? "" : sort) {
            case "agent" -> "m.agent.name";
            case "text" -> "m.text";
            case KEY_CATEGORY -> "m.category";
            case KEY_IMPORTANCE -> FIELD_IMPORTANCE;
            case "created" -> "m.createdAt";
            default -> null;
        };
        if (col == null) return "ORDER BY m.updatedAt DESC";
        String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
        return "ORDER BY " + col + " " + direction + ", m.id ASC";
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
        Memory memory = MemoryService.findById(memoryId);
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
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "importance must be between 0.0 and 1.0");
            }
            memory.importance = imp;
        }
        if (body.has(KEY_CATEGORY) && !body.get(KEY_CATEGORY).isJsonNull()) {
            var normalized = MemoryCategory.normalize(body.get(KEY_CATEGORY).getAsString());
            if (normalized != null) memory.category = normalized;
        }
        memory.save();
        renderJSON(gson.toJson(toDto(memory, agentNamesById())));
    }

    /**
     * DELETE /api/memories/{memoryId} — remove a memory.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Delete a memory")
    public static void delete(Long memoryId) {
        Memory memory = MemoryService.findById(memoryId);
        if (memory == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        memory.delete();
        ApiResponses.ok();
    }

    public record DeletedCountResponse(int deleted) {}

    /**
     * DELETE /api/memories — bulk removal, mirroring the Conversations page
     * contract (JCLAW-40 follow-up). Body is either {@code {"ids": [..]}}
     * (selection-driven Delete) or {@code {"filter": {q, agent, category,
     * importance, status}}} (Delete-all-matching; the same predicates as
     * {@link #list}). Rejects an empty body with 400 so an accidental bare
     * DELETE can't wipe the table. Deletions run through the entity
     * lifecycle — Memory's {@code @PostRemove} keeps the Lucene index in
     * sync — never bulk JPQL.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true)
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DeletedCountResponse.class)))
    @ChatHidden("destructive bulk memory deletion")
    @Operation(summary = "Bulk-delete memories by ids or by the list filter set")
    public static void bulkDelete() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        int deleted = 0;
        if (body.has("ids")) {
            for (var elem : body.getAsJsonArray("ids")) {
                Memory m = MemoryService.findById(elem.getAsLong());
                if (m != null) {
                    m.delete();
                    deleted++;
                }
            }
            renderJSON(gson.toJson(new DeletedCountResponse(deleted)));
            return;
        }

        if (body.has("filter")) {
            var f = body.getAsJsonObject("filter");
            String q = stringField(f, "q");
            String agent = stringField(f, "agent");
            String category = stringField(f, KEY_CATEGORY);
            String importance = stringField(f, KEY_IMPORTANCE);
            String status = stringField(f, "status");
            // Page-and-delete until the filter matches nothing: deleting
            // shrinks the result set, so offset stays 0 each round.
            List<Memory> batch;
            while (!(batch = selectMemories(q, agent, category, importance, status, 500, 0)).isEmpty()) {
                for (Memory m : batch) {
                    m.delete();
                    deleted++;
                }
            }
            renderJSON(gson.toJson(new DeletedCountResponse(deleted)));
            return;
        }

        badRequest();
    }

    private static String stringField(com.google.gson.JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        var v = obj.get(key).getAsString();
        return (v == null || v.isBlank()) ? null : v;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static MemoryDto toDto(Memory m, Map<String, String> agentNames) {
        // The agent FK is the immutable id (JCLAW-531/537); surface the current
        // human name, falling back to the raw id if the agent row is somehow gone.
        String key = String.valueOf(m.agent.id);
        String name = agentNames.getOrDefault(key, key);
        return new MemoryDto(String.valueOf(m.id), name, m.text, m.category,
                m.importance, m.createdAt == null ? null : m.createdAt.toString(),
                m.supersededAt == null ? null : m.supersededAt.toString(),
                m.supersededById == null ? null : String.valueOf(m.supersededById));
    }

    /**
     * JPQL condition for the {@code status} filter (JCLAW-557), or null for
     * {@code all}. Defaults to active-only so the table matches what recall
     * sees; the JCLAW-525 supersession trail is opt-in via
     * {@code status:superseded} or {@code status:all}.
     */
    private static String statusCondition(String status) {
        var s = status == null ? "" : status.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "all" -> null;
            case "superseded" -> "m.supersededAt IS NOT NULL";
            default -> "m.supersededAt IS NULL";
        };
    }

    /** Map of agent id (as string) to current name, for resolving the display label. */
    private static Map<String, String> agentNamesById() {
        return Agent.<Agent>findAll().stream()
                .collect(Collectors.toMap(a -> String.valueOf(a.id), a -> a.name));
    }

    /** Resolve an agent name to its immutable id, or null when unknown. */
    private static Long agentIdForName(String name) {
        Agent a = Agent.find("name = ?1", name).first();
        return a == null ? null : a.id;
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
