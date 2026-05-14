package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SubagentRun;
import services.Tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-274: read a subagent run's child conversation transcript.
 *
 * <p>Companion to {@link SpawnSubagentTool} / {@link YieldToSubagentTool}.
 * The parent agent (or an operator using the slash command — see
 * {@code /subagent history} in {@link slash.Commands}) calls this tool with
 * a {@code runId} returned from a prior spawn; the tool validates the
 * caller owns the run, then returns the child conversation's full message
 * list (role, content, tool calls, tool results, timestamps, kind/metadata)
 * for inspection or programmatic post-processing.
 *
 * <p><b>Permission model.</b> The calling agent must equal the
 * {@link SubagentRun#parentAgent}. JClaw is currently single-tenant
 * Personal Edition — there is no operator-role concept in the codebase
 * (no User entity, no RBAC, no admin grant). The AC's "operators can read
 * any" reads at the REST/API endpoint layer (a future {@code /api/subagent-runs/{id}/messages}
 * route would be operator-unconditional once auth lands); the tool path
 * stays strictly parent-owned because a tool call is always made by a
 * concrete agent context and "operator" is not a tool-callable identity.
 * When multi-tenant auth lands, an operator-role check would slot in as a
 * short-circuit ahead of the parentAgent equality gate.
 *
 * <p><b>Size + pagination.</b> Per AC, the transcript is bounded so a
 * runaway child can't blow up the tool's return string. Per-message content
 * is left untruncated (the AC says "truncate per-message content" but in
 * practice the child's own messages already obey JClaw's other size caps:
 * the announce truncation in {@link SpawnSubagentTool#ANNOUNCE_REPLY_MAX_CHARS}
 * bounds the visible reply, and tool results are bounded by their own
 * tools' return sizes). What matters for THIS tool is bounding the message
 * <em>count</em> so a 10,000-turn child doesn't return a single 200 MB
 * JSON blob to the LLM. The default {@link #DEFAULT_LIMIT} is 100 with a
 * hard cap of {@link #MAX_LIMIT}; pagination via {@code beforeMessageId}
 * lets the caller walk back through history a page at a time if it really
 * needs all of it.
 */
public class SessionsHistoryTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "sessions_history";

    /** Default number of messages returned per call. */
    static final int DEFAULT_LIMIT = 100;

    /** Hard ceiling on the messages-per-call parameter. A caller that
     *  explicitly asks for more will be clamped silently — the LLM has no
     *  business getting 10,000 rows in a single tool turn. */
    static final int MAX_LIMIT = 200;

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "history"; }

    @Override
    public String shortDescription() {
        return "Read the full message list (role, content, tool calls/results, timestamps) for a subagent run's child conversation.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("history",
                        "Return the child conversation's transcript for a given subagent run id."));
    }

    @Override
    public String description() {
        return """
                Read the full message list for the child conversation of a previously-spawned \
                subagent run. Returns role, content, tool calls/results, message kind/metadata, \
                and timestamps for each message. Use this to inspect what the child actually did \
                (multi-turn reasoning, tool invocations, intermediate replies) after a \
                spawn_subagent or yield_to_subagent call has terminated. \
                Required: `runId` (the run id returned from spawn_subagent). \
                Optional: `limit` (1-200, default 100), `beforeMessageId` (cursor — return only \
                messages with id < this value; use to page back through long transcripts). \
                Permission: the calling agent must be the run's parent agent.""";
    }

    @Override
    public String summary() {
        return "Read the child conversation transcript for a subagent run id.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put("runId", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Run id returned by a prior spawn_subagent call (required)."));
        props.put("limit", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Maximum messages to return (1-" + MAX_LIMIT + ", default " + DEFAULT_LIMIT + ")."));
        props.put("beforeMessageId", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Pagination cursor: return only messages with id < this value. "
                        + "Use the smallest id from a previous page to walk back through history."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of("runId")
        );
    }

    /** Read-only — safe to interleave with other tool calls in the same turn. */
    @Override
    public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var runIdStr = optString(args, "runId");
        if (runIdStr == null || runIdStr.isBlank()) {
            return "Error: 'runId' is required.";
        }
        long runId;
        try {
            runId = Long.parseLong(runIdStr);
        } catch (NumberFormatException _) {
            return "Error: 'runId' must be a numeric run id (got '" + runIdStr + "').";
        }
        var requestedLimit = optInt(args, "limit", DEFAULT_LIMIT);
        if (requestedLimit <= 0) requestedLimit = DEFAULT_LIMIT;
        if (requestedLimit > MAX_LIMIT) requestedLimit = MAX_LIMIT;
        var limit = requestedLimit;

        var beforeStr = optString(args, "beforeMessageId");
        Long beforeMessageId = null;
        if (beforeStr != null && !beforeStr.isBlank()) {
            try {
                beforeMessageId = Long.parseLong(beforeStr);
            } catch (NumberFormatException _) {
                return "Error: 'beforeMessageId' must be numeric (got '" + beforeStr + "').";
            }
        }

        final var callingAgentId = callingAgent.id;
        final var beforeId = beforeMessageId;
        return Tx.run(() -> {
            var run = (SubagentRun) SubagentRun.findById(runId);
            if (run == null) {
                return "Error: no SubagentRun found for runId " + runId + ".";
            }
            // Parent-ownership gate. See class javadoc on the operator-role
            // story: single-tenant Personal Edition has no operator concept,
            // so the gate is strict parentAgent equality. A future
            // multi-tenant build slots an operator-role short-circuit here.
            if (run.parentAgent == null || !callingAgentId.equals(run.parentAgent.id)) {
                return "Error: runId " + runId + " is not owned by the calling agent.";
            }
            var childConv = run.childConversation;
            if (childConv == null) {
                return "Error: runId " + runId + " has no child conversation (audit row is malformed).";
            }
            return renderHistoryJson(run, childConv, limit, beforeId);
        });
    }

    /** Build the JSON payload. Must be called inside an active Tx. */
    private static String renderHistoryJson(SubagentRun run, Conversation childConv,
                                             int limit, Long beforeMessageId) {
        // Fetch one row beyond the requested limit so we can set {@code
        // has_more} truthfully without a second count query.
        int fetchLimit = limit + 1;
        List<Message> rows;
        if (beforeMessageId != null) {
            rows = Message.<Message>find(
                    "conversation = ?1 AND id < ?2 ORDER BY id DESC",
                    childConv, beforeMessageId).fetch(fetchLimit);
        } else {
            rows = Message.<Message>find(
                    "conversation = ?1 ORDER BY id DESC",
                    childConv).fetch(fetchLimit);
        }
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = rows.subList(0, limit);
        // Caller expects chronological order (oldest first within the page);
        // we fetched DESC to make the pagination cursor cheap, so reverse
        // before serializing.
        var ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);

        var messages = new ArrayList<Map<String, Object>>(ordered.size());
        for (var msg : ordered) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", msg.id);
            row.put("role", msg.role);
            row.put("content", msg.content);
            row.put("tool_calls", msg.toolCalls);
            row.put("tool_results", msg.toolResults);
            row.put("tool_result_structured", msg.toolResultStructured);
            row.put("reasoning", msg.reasoning);
            row.put("message_kind", msg.messageKind);
            row.put("metadata", msg.metadata);
            row.put("usage_json", msg.usageJson);
            row.put("subagent_run_id", msg.subagentRunId);
            row.put("created_at", msg.createdAt != null ? msg.createdAt.toString() : null);
            messages.add(row);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("run_id", String.valueOf(run.id));
        payload.put("child_conversation_id", String.valueOf(childConv.id));
        payload.put("count", messages.size());
        payload.put("has_more", hasMore);
        payload.put("messages", messages);
        return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try { return el.getAsInt(); } catch (RuntimeException e) { return fallback; }
    }
}
