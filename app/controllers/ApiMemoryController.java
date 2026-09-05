package controllers;

import agents.SystemPromptAssembler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import memory.CoreMemoryCapMigration;
import memory.JpaMemoryStore;
import memory.MemoryCategory;
import memory.MemoryKeyBackfillService;
import memory.MemoryReembedService;
import memory.MemoryVectorSettings;
import models.Agent;
import models.Memory;
import play.mvc.Controller;
import play.mvc.With;
import play.mvc.results.Result;
import services.ConfigService;
import services.EventLogger;
import services.MemoryService;
import services.evals.MemoryEvalGenerator;
import services.evals.MemoryEvalPaths;
import services.evals.MemoryEvalScorer;
import services.evals.MemoryEvalSuite;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.ApiResponses;
import utils.JpqlFilter;
import utils.JsonArgs;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static utils.GsonHolder.GSON;

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

    private static final Gson gson = GSON;

    private static final String KEY_IMPORTANCE = "importance";
    private static final String KEY_CATEGORY = "category";
    private static final String FIELD_IMPORTANCE = "m.importance";
    private static final String NO_SUCH_AGENT = "No agent with id ";

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
            // JCLAW-969: was a hard 500 with no signal to the caller. X-Total-Count is a COUNT
            // over this id set, so it could never exceed 500 — and because orderByClause
            // re-sorts by updatedAt while the surviving ids were chosen by Lucene relevance,
            // the truncation was invisible in the UI rather than merely undocumented.
            int cap = ConfigService.getInt("memory.search.maxHits", 5000);
            var ids = MessageSearch.searchIds(LuceneIndexer.Scope.MEMORY, q.strip(), cap);
            if (ids.size() >= cap) {
                EventLogger.warn("search", null, null,
                        ("Memory search for q='%s' hit the %d-hit cap; results beyond it are "
                                + "unreachable. Raise memory.search.maxHits.").formatted(q, cap));
            }
            return Optional.of(ids);
        } catch (IOException e) {
            EventLogger.warn("search", null, null,
                    "Memory FTS failed for q='%s': %s".formatted(q, e.getMessage()));
            // Fail CLOSED. Optional.empty() means "no id constraint" — returning it here
            // would drop the caller's q entirely, so a failed search reads as an unfiltered
            // one: list renders the whole corpus, and bulkDelete(filter{q}) deletes it.
            return Optional.of(List.of());
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
     * matching the pre-sort behavior. A stable id tiebreak keeps paging
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
            throw ApiResponses.unreachable();
        }
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        if (body.has(KEY_IMPORTANCE) && !body.get(KEY_IMPORTANCE).isJsonNull()) {
            double imp = body.get(KEY_IMPORTANCE).getAsDouble();
            // JCLAW-970: the body parser is lenient, so NaN arrives as a number and passes both
            // range comparisons. Reject rather than let the @PreUpdate backstop silently reset it.
            if (!Double.isFinite(imp) || imp < 0.0 || imp > 1.0) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "importance must be between 0.0 and 1.0");
            }
            memory.importance = imp;
        }
        if (body.has(KEY_CATEGORY) && !body.get(KEY_CATEGORY).isJsonNull()) {
            var normalized = MemoryCategory.normalize(body.get(KEY_CATEGORY).getAsString());
            // JCLAW-927: rejected rather than coerced. Capture coerces because the
            // alternative is discarding a memory over a label, but this is an operator
            // typing a category deliberately — silently storing something else would hide
            // the mistake behind a 200.
            if (normalized != null && MemoryCategory.from(normalized).isEmpty()) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                        "category must be one of " + MemoryCategory.labels());
            }
            if (normalized != null) memory.category = normalized;
        }
        memory.save();
        renderJSON(gson.toJson(toDto(memory, agentNamesById())));
    }

    /** Cluster structure only: sizes, never texts — a stats call must not leak the corpus. */
    public record EvalClusterView(String clusterBy, double clusterThreshold,
                                 List<Integer> distinctFactsPerCluster) {}

    /** Summary of a generated suite. Never returns the cases: they are personal data. */
    public record EvalGenerateView(String suiteId, String fingerprint, int cases, String path) {}

    /**
     * POST /api/memories/evals/generate — build a recall eval suite from an agent's own
     * memories (JCLAW-529).
     *
     * <p>Writes through {@link MemoryEvalPaths}, which refuses any destination outside the
     * git-ignored local directory. The response deliberately carries counts and a
     * fingerprint but not the cases: a generated case is personal data by construction,
     * and there is no reason for it to travel anywhere it does not have to.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EvalGenerateView.class)))
    @Operation(summary = "Generate a memory-recall eval suite from the corpus")
    public static void evalGenerate() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var agent = requireEvalAgent(body);
        var suiteId = JsonArgs.optString(body, "suiteId", "recall");
        int sampleSize = JsonArgs.optInt(body, "sampleSize", 25);

        var mode = JsonArgs.optString(body, "mode", "single");
        // Semantic by default: lexical gold groups share their signal with any ranker
        // that penalises token overlap, which decides the comparison in advance.
        // See MemoryEvalGenerator#generateCoverage.
        // Temporal reads clusterThreshold as a span in DAYS, not a cosine, so it needs its
        // own default — 0.62 days is a 15-hour window and would return an empty suite that
        // looked like a corpus problem (JCLAW-943).
        boolean temporal = "temporal".equals(mode);
        var clusterBy = JsonArgs.optString(body, "clusterBy", temporal ? "temporal" : "semantic");
        var clustering = new MemoryEvalGenerator.Clustering(
                clusterBy,
                JsonArgs.optDouble(body, "clusterThreshold", temporal ? 7.0 : 0.62),
                JsonArgs.optInt(body, "minFacts", 3),
                JsonArgs.optInt(body, "maxFacts", 8));
        if (JsonArgs.optBool(body, "dryRun")) {
            renderJSON(gson.toJson(new EvalClusterView(clustering.by(), clustering.threshold(),
                    MemoryEvalGenerator.clusterSizes(agent, clustering))));
        }

        var writer = MemoryEvalGenerator.writerFor(agent);
        if (writer == null) {
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "Agent '%s' has no usable provider for question generation".formatted(agent.name));
        }
        // "coverage" builds broad questions needing several distinct facts — the only
        // mode that can measure how well a block covers a question, since a single-fact
        // question scores three paraphrases exactly as it scores three different facts.
        // "bridge" asks through a relation stored in a different memory than the answer,
        // which "single" cannot express: it writes each question from the gold text alone.
        // "temporal" reaches a stretch of the corpus through a time reference, and
        // "multihop" chains two clusters through a shared entity so both count toward
        // coverage (JCLAW-943). Neither is reachable from the three above, which all write
        // their question from memory text and so can only ever ask about subject matter.
        var suite = switch (mode) {
            case "coverage" -> MemoryEvalGenerator.generateCoverage(agent, suiteId, sampleSize, clustering, writer);
            case "bridge" -> MemoryEvalGenerator.generateBridge(agent, suiteId, sampleSize, writer);
            case "temporal" -> MemoryEvalGenerator.generateTemporal(agent, suiteId, sampleSize, clustering, writer);
            case "multihop" -> MemoryEvalGenerator.generateMultiHop(agent, suiteId, sampleSize, clustering, writer);
            default -> MemoryEvalGenerator.generate(agent, suiteId, sampleSize, writer);
        };
        try {
            MemoryEvalPaths.ensureLocalDir();
            var file = MemoryEvalPaths.suiteFile(suiteId);
            Files.writeString(file, gson.toJson(suite));
            renderJSON(gson.toJson(new EvalGenerateView(suite.id(), suite.fingerprint(),
                    suite.cases().size(), MemoryEvalPaths.LOCAL_DIR + "/" + suiteId + ".json")));
        } catch (Result r) {
            throw r;
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, e.getMessage());
        } catch (IOException e) {
            ApiResponses.error(500, ApiResponses.IO_ERROR, "Could not write suite: " + e.getMessage());
        }
    }

    /**
     * POST /api/memories/evals/run — score a generated suite against live recall.
     *
     * <p>Each case is retrieved through {@link SystemPromptAssembler#recall}, the same
     * pipeline the system prompt uses, so a score describes production rather than a
     * reimplementation of it. Candidates are read in scored order rather than only the
     * selected ones, so recall at 10 measures retrieval instead of the recall limit.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryEvalScorer.Report.class)))
    @Operation(summary = "Score a memory-recall eval suite against live recall")
    public static void evalRun() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var agent = requireEvalAgent(body);
        var suiteId = JsonArgs.optString(body, "suiteId", "recall");
        MemoryEvalSuite suite;
        try {
            suite = gson.fromJson(
                    Files.readString(MemoryEvalPaths.suiteFile(suiteId)), MemoryEvalSuite.class);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, e.getMessage());
            throw ApiResponses.unreachable();
        } catch (IOException _) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "No generated suite '%s' — generate one first".formatted(suiteId));
            throw ApiResponses.unreachable();
        }

        // Which ranking to score. "selected" is what the model actually sees — the
        // ranking truncated to the recall budget. "candidates" scores retrieval before
        // that cut, which is the right scope for asking whether the store can find a
        // memory at all, independently of how many the budget admits.
        var scope = JsonArgs.optString(body, "scope", "selected");
        boolean selectedOnly = !"candidates".equals(scope);

        var retrievals = new ArrayList<List<Long>>(suite.cases().size());
        for (var c : suite.cases()) {
            var result = SystemPromptAssembler.recall(String.valueOf(agent.id), c.query(), Set.of());
            retrievals.add(selectedOnly
                    ? result.selected().stream().map(x -> Long.parseLong(x.id())).toList()
                    : result.candidates().stream().map(x -> Long.parseLong(x.entry().id())).toList());
        }
        renderJSON(gson.toJson(MemoryEvalScorer.score(suite, retrievals)));
    }

    /**
     * POST /api/memories/backfill-keys — bring an existing corpus up to the JCLAW-529
     * storage contract: promote identity facts to core, then generate a retrieval key for
     * every un-keyed memory and re-embed it.
     *
     * <p>Returns immediately; poll the same path with GET. The keying pass is one model
     * call per memory, so a corpus of any size outlives a request.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryKeyBackfillService.Status.class)))
    @Operation(summary = "Backfill core promotion and retrieval keys over an existing corpus")
    public static void backfillKeysStart() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var agent = requireEvalAgent(body);
        if (!MemoryKeyBackfillService.start(agent)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "A backfill is already running");
        }
        renderJSON(gson.toJson(MemoryKeyBackfillService.status()));
    }

    /** GET /api/memories/backfill-keys — progress of the run started above. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryKeyBackfillService.Status.class)))
    @Operation(summary = "Status of the retrieval-key backfill")
    public static void backfillKeysStatus() {
        renderJSON(gson.toJson(MemoryKeyBackfillService.status()));
    }

    /** 400/404 unless the body names an agent that exists. */
    private static Agent requireEvalAgent(JsonObject body) {
        var agentId = JsonArgs.optString(body, "agentId", "");
        if (agentId.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId is required");
        }
        Agent agent = Agent.findById(Long.valueOf(agentId));
        if (agent == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, NO_SUCH_AGENT + agentId);
        }
        return agent;
    }

    /** One scored candidate as recall saw it (JCLAW-937). */
    public record RecallCandidateView(long id, String text, String category, double importance,
                                      double relevance, double decay, double score, boolean selected) {}

    /** A recall, the settings that shaped it, and every candidate it considered. */
    public record RecallView(String agentId, String query, int limit,
                             double relevanceWeight, double importanceWeight,
                             String vectorBackend, int selectedTokens,
                             List<Long> selectedIds,
                             List<RecallCandidateView> candidates) {}

    /**
     * POST /api/memories/recall — what recall would inject for a query, and why (JCLAW-937).
     *
     * <p>Recall is assembled per turn straight into the system prompt, and nothing else
     * exposes it: {@code prompt-breakdown} passes a null user message, so the block never
     * appears there. Every recall change to date has been verified against fixtures or an
     * offline sweep rather than the live pipeline.
     *
     * <p>Runs {@link SystemPromptAssembler#recall}, the same method the prompt uses, so
     * this cannot report on a pipeline that is no longer the real one. It returns the
     * candidates the limit cut as well as the selected ones, which is what lets a
     * memory-quality eval (JCLAW-529) compute recall at several k from a single call.
     *
     * <p>Read-only in the strict sense: no {@code lastAccessedAt} stamp, so inspecting
     * recall cannot move the decay anchor it is inspecting or let a repeated eval measure
     * its own earlier passes.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RecallView.class)))
    @Operation(summary = "Inspect what memory recall returns for a query")
    public static void recall() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var query = JsonArgs.optString(body, "query", "");
        if (query.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "query is required");
        }
        var agentId = JsonArgs.optString(body, "agentId", "");
        if (agentId.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId is required");
        }
        if (Agent.findById(Long.valueOf(agentId)) == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, NO_SUCH_AGENT + agentId);
        }

        var result = SystemPromptAssembler.recall(agentId, query, Set.of());
        var candidates = result.candidates().stream()
                .map(c -> new RecallCandidateView(Long.parseLong(c.entry().id()), c.entry().text(),
                        c.entry().category(), c.entry().importance(), c.entry().relevance(),
                        c.decay(), c.score(), c.selected()))
                .toList();
        renderJSON(gson.toJson(new RecallView(agentId, query, result.limit(),
                result.relevanceWeight(), result.importanceWeight(), vectorBackendLabel(),
                result.selectedTokens(),
                result.selected().stream().map(e -> Long.parseLong(e.id())).toList(),
                candidates)));
    }

    /** Which vector leg served this recall, so a result is attributable to a backend. */
    private static String vectorBackendLabel() {
        if (!MemoryVectorSettings.enabled()) return "keyword-only";
        return JpaMemoryStore.isPostgresDialect() ? "pgvector" : "lucene-hnsw";
    }

    /**
     * GET /api/agents/{agentId}/core-migration — this agent's core count against the cap.
     * Polled by the agent editor's Memory card; safe to call at any cadence.
     *
     * <p>Per agent rather than per instance because the cap is: the core block is
     * assembled per agent, so a total across the instance describes a state no migration
     * can act on (JCLAW-981).
     */
    @ApiResponse(responseCode = "200")
    @Operation(summary = "One agent's core-memory usage against the cap")
    public static void coreMigrationStatus(Long agentId) {
        requireAgentById(agentId);
        renderJSON(gson.toJson(CoreMemoryCapMigration.status(String.valueOf(agentId))));
    }

    /**
     * POST /api/agents/{agentId}/core-migration — recategorize this agent's core memories
     * past the cap, using its own model to choose the new bucket (JCLAW-981).
     *
     * <p>Operator-triggered rather than automatic: the memory tool can refuse a core write
     * past the cap but cannot make the agent ask before filing something elsewhere, so
     * bringing an over-cap agent back in line is a deliberate action, not a side effect
     * of a boot.
     *
     * <p>Returns 409 with the reason when it cannot start — this agent is not over the
     * cap, or another agent's migration holds the single-flight slot.
     */
    @ApiResponse(responseCode = "202")
    @Operation(summary = "Recategorise one agent's core memories past the cap")
    public static void coreMigrationStart(Long agentId) {
        requireAgentById(agentId);
        var refusal = CoreMemoryCapMigration.start(String.valueOf(agentId));
        if (refusal != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, refusal);
        }
        renderJSON(gson.toJson(CoreMemoryCapMigration.status(String.valueOf(agentId))));
    }

    /** 404s unless {@code agentId} names an existing agent. */
    private static void requireAgentById(Long agentId) {
        if (agentId == null || Agent.<Agent>findById(agentId) == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, NO_SUCH_AGENT + agentId);
        }
    }

    /**
     * GET /api/memories/reembed — whether a rebuild is in flight and how far along.
     * Polled by the Settings panel and the chat notice; safe to call at any cadence.
     */
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Re-embed progress")
    public static void reembedStatus() {
        renderJSON(gson.toJson(MemoryReembedService.status()));
    }

    /**
     * POST /api/memories/reembed — rebuild every memory's embedding against the model
     * currently configured (JCLAW-933).
     *
     * <p>Returns 409 with the reason when it cannot start: vector memory disabled, a
     * rebuild already in flight, or a configured dimension the index cannot store. That
     * last one is a refusal rather than a warning because the rebuild wipes the index
     * before writing, so starting it with an unusable model would leave nothing behind.
     */
    @ApiResponse(responseCode = "202")
    @Operation(summary = "Start re-embedding stored memories")
    public static void reembedStart() {
        var refusal = MemoryReembedService.start();
        if (refusal != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, refusal);
        }
        renderJSON(gson.toJson(MemoryReembedService.status()));
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
            throw ApiResponses.unreachable();
        }
        memory.deleteWithLineage();
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
                    m.deleteWithLineage();
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
                    m.deleteWithLineage();
                    deleted++;
                }
            }
            renderJSON(gson.toJson(new DeletedCountResponse(deleted)));
            return;
        }

        badRequest();
    }

    private static String stringField(JsonObject obj, String key) {
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
        var s = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
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
