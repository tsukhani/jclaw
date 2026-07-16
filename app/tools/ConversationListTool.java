package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.SubagentRun;
import services.Tx;
import utils.GsonHolder;
import utils.JsonArgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JCLAW-326: paginated read tool that lists a parent agent's
 * {@link SubagentRun} rows for the {@code conversation_list} AC. The query
 * is always scoped to the calling agent's owned rows — there is no
 * cross-agent listing path on Personal Edition, mirroring the
 * parent-ownership gates on {@link SubagentYieldTool} and
 * {@link ConversationHistoryTool}.
 *
 * <p>Filters (all optional, AND-combined):
 * <ul>
 *   <li>{@code status} — one of {@code RUNNING|COMPLETED|FAILED|KILLED|TIMEOUT}.</li>
 *   <li>{@code labelGlob} — operator-friendly glob; {@code *} maps to SQL
 *       {@code %}, literal {@code %} / {@code _} are escaped so a stored
 *       label can't accidentally match wildcards.</li>
 *   <li>{@code agentId} — narrows to runs whose child agent id matches.</li>
 * </ul>
 *
 * <p>Pagination uses limit (default 20, max 100) + offset (default 0). Fetch
 * one row beyond {@code limit} to compute {@code has_more} without a second
 * count query (same shape as {@link ConversationHistoryTool}). The
 * {@code outcomePreview} field truncates the stored outcome at 200 characters
 * so a single list call can't return megabytes of child transcripts.
 */
public class ConversationListTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "conversation_list";

    private static final String PARAM_STATUS = "status";
    private static final String PARAM_LABEL_GLOB = "labelGlob";
    private static final String PARAM_AGENT_ID = "agentId";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_OFFSET = "offset";

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int OUTCOME_PREVIEW_MAX_CHARS = 200;

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "list"; }

    @Override
    public String shortDescription() {
        return "List this agent's subagent runs with optional status / label / child-agent filters.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("list",
                        "Paginate this parent agent's owned SubagentRun rows."));
    }

    @Override
    public String description() {
        return """
                List the subagent runs owned by the calling agent (rows where this agent is the \
                parent). Use this to discover active or recent children — run ids, labels, \
                status, timing, and a short preview of each run's outcome. \
                All parameters are optional: \
                `status` (RUNNING|COMPLETED|FAILED|KILLED|TIMEOUT), \
                `labelGlob` (operator-friendly glob, * matches any chars; e.g. "probe-*"), \
                `agentId` (narrow to a specific child agent id), \
                `limit` (1-100, default 20), \
                `offset` (default 0). \
                Returns: {count, has_more, runs: [{runId, childConversationId, childAgentId, \
                label, status, startedAt, endedAt, outcomePreview}]}.""";
    }

    @Override
    public String summary() {
        return "List this agent's subagent runs with optional filters.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_STATUS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Filter by status: RUNNING, COMPLETED, FAILED, KILLED, TIMEOUT (optional)."));
        props.put(PARAM_LABEL_GLOB, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Filter by label glob (* matches any sequence of characters). "
                        + "Literal % and _ in the rest of the string are escaped."));
        props.put(PARAM_AGENT_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Filter by child agent id (optional)."));
        props.put(PARAM_LIMIT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Maximum rows to return (1-" + MAX_LIMIT + ", default " + DEFAULT_LIMIT + ")."));
        props.put(PARAM_OFFSET, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION, "Row offset for pagination (default 0)."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props
        );
    }

    /** Read-only — safe to interleave with other tool calls in the same turn. */
    @Override
    public boolean parallelSafe() { return true; }

    /** Subagent-lifecycle group: shared with {@link SubagentSpawnTool} so a
     *  same-turn {@code subagent_spawn} + {@code conversation_list} pair
     *  serializes inside {@link agents.ParallelToolExecutor}. Without this,
     *  the model can emit both tool calls in one assistant message and
     *  list's query races spawn's SubagentRun INSERT commit — surfacing as
     *  {@code count: 0} for a row the same turn just created. */
    @Override
    public String serializationGroup() { return "subagent_lifecycle"; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var parsed = parseArgs(args);
        if (parsed.error() != null) return parsed.error();
        final var callingAgentId = callingAgent.id;
        return Tx.run(() -> renderListJson(callingAgentId, parsed));
    }

    private record ParsedArgs(String error, SubagentRun.Status status, String labelLike,
                              Long agentId, int limit, int offset) {
        static ParsedArgs fail(String msg) {
            return new ParsedArgs(msg, null, null, null, 0, 0);
        }
        static ParsedArgs ok(SubagentRun.Status s, String labelLike, Long agentId,
                             int limit, int offset) {
            return new ParsedArgs(null, s, labelLike, agentId, limit, offset);
        }
    }

    private static ParsedArgs parseArgs(JsonObject args) {
        SubagentRun.Status status = null;
        var statusStr = JsonArgs.optString(args, PARAM_STATUS);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = SubagentRun.Status.valueOf(statusStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                return ParsedArgs.fail("Error: 'status' must be one of "
                        + Arrays.toString(SubagentRun.Status.values())
                        + " (got '" + statusStr + "').");
            }
        }
        var labelGlob = JsonArgs.optString(args, PARAM_LABEL_GLOB);
        String labelLike = null;
        if (labelGlob != null && !labelGlob.isBlank()) {
            labelLike = globToLike(labelGlob);
        }
        Long agentId = JsonArgs.optLong(args, PARAM_AGENT_ID);
        var limit = JsonArgs.optInt(args, PARAM_LIMIT, DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        var offset = JsonArgs.optInt(args, PARAM_OFFSET, 0);
        if (offset < 0) offset = 0;
        return ParsedArgs.ok(status, labelLike, agentId, limit, offset);
    }

    /** Translate operator-friendly glob ({@code *}) to SQL LIKE pattern
     *  ({@code %}), escaping any literal {@code %} or {@code _} so a label
     *  containing those characters can't accidentally widen the match. */
    public static String globToLike(String glob) {
        var sb = new StringBuilder(glob.length() + 4);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append('%');
                case '%', '_', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Build the JSON payload. Must be called inside an active Tx. */
    private static String renderListJson(Long callingAgentId, ParsedArgs parsed) {
        var parent = (Agent) Agent.findById(callingAgentId);
        if (parent == null) {
            return "Error: calling agent " + callingAgentId + " not found.";
        }

        // Build JPQL dynamically so unused filters don't bloat the WHERE. The
        // parent FK is always in the clause — ownership lives in the query,
        // not in a post-filter gate, so no path can leak another parent's row.
        var jpql = new StringBuilder("parentAgent = ?1");
        var params = new ArrayList<Object>();
        params.add(parent);
        if (parsed.status() != null) {
            jpql.append(" AND status = ?").append(params.size() + 1);
            params.add(parsed.status());
        }
        if (parsed.labelLike() != null) {
            jpql.append(" AND label LIKE ?").append(params.size() + 1).append(" ESCAPE '\\'");
            params.add(parsed.labelLike());
        }
        if (parsed.agentId() != null) {
            jpql.append(" AND childAgent.id = ?").append(params.size() + 1);
            params.add(parsed.agentId());
        }
        jpql.append(" ORDER BY startedAt DESC");

        int fetchLimit = parsed.limit() + 1;
        List<SubagentRun> rows = SubagentRun.<SubagentRun>find(
                        jpql.toString(), params.toArray())
                .from(parsed.offset()).fetch(fetchLimit);
        boolean hasMore = rows.size() > parsed.limit();
        if (hasMore) rows = rows.subList(0, parsed.limit());

        var runsJson = new ArrayList<Map<String, Object>>(rows.size());
        for (var run : rows) {
            var row = new LinkedHashMap<String, Object>();
            row.put("runId", String.valueOf(run.id));
            row.put("childConversationId",
                    run.childConversation != null ? String.valueOf(run.childConversation.id) : null);
            row.put("childAgentId",
                    run.childAgent != null ? String.valueOf(run.childAgent.id) : null);
            row.put("label", run.label);
            row.put(PARAM_STATUS, run.status != null ? run.status.name() : null);
            row.put("startedAt", run.startedAt != null ? run.startedAt.toString() : null);
            row.put("endedAt", run.endedAt != null ? run.endedAt.toString() : null);
            row.put("outcomePreview", truncatePreview(run.outcome));
            runsJson.add(row);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("count", runsJson.size());
        payload.put("has_more", hasMore);
        payload.put("runs", runsJson);
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /** Truncate {@link SubagentRun#outcome} to {@link #OUTCOME_PREVIEW_MAX_CHARS}
     *  with a trailing ellipsis so a 10kb error stack-trace doesn't blow up
     *  the list payload. Null outcomes (RUNNING rows) stay null. */
    public static String truncatePreview(String outcome) {
        if (outcome == null) return null;
        if (outcome.length() <= OUTCOME_PREVIEW_MAX_CHARS) return outcome;
        return outcome.substring(0, OUTCOME_PREVIEW_MAX_CHARS - 3) + "...";
    }
}
