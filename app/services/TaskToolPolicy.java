package services;

import com.google.gson.JsonParser;
import llm.LlmTypes.ToolDef;
import models.Conversation;
import models.McpServer;
import models.TaskRun;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JCLAW-1068: per-task toolset restriction. {@link models.Task#enabledToolNames} is a
 * JSON array of tool names a fire may use; the column existed from JCLAW-294 but nothing
 * read it, so setting it restricted nothing.
 *
 * <p>The restriction narrows the {@code ToolDef} list once, in
 * {@link agents.AgentRunner#runForTask}. {@code ToolCallLoopRunner.offeredToolNames}
 * derives the JCLAW-883 dispatch guard's {@code offered} set from that same list, so the
 * schema the model sees and the set {@code ToolRegistry} will execute cannot drift apart.
 *
 * <p>JF-373: an entry may name one action of an MCP server, {@code mcp_<server>/<action>}. It
 * offers the server's handle, and {@code McpServerTool} then lists, describes and runs only the
 * actions the list names; an entry naming the bare handle keeps every action.
 */
public final class TaskToolPolicy {

    /** Audit category for a fire whose toolset was narrowed. Fits {@code EventLog.category} (50). */
    public static final String EVENT_CATEGORY = "TOOL_BLOCKED_BY_TASK_POLICY";

    /** Between an MCP server's handle and one of its actions in an entry. */
    public static final char ACTION_SEPARATOR = '/';

    /** {@code EventLog.message} is {@code length = 500}; oversized text fails the insert. */
    private static final int MESSAGE_LIMIT = 500;

    /** Registered tool names: natives like {@code web_search}, MCP handles like
     *  {@code mcp_google-workspace-mcp}, and one action of a handle. Gates the delimited form
     *  so stray prose cannot pass as a tool list. */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_./-]+");

    /**
     * A run's allow-list, and who holds it, for a refusal to name.
     *
     * @param holder whose list it is, as a refusal names it: {@code task 'email-labeler''s tool list}
     */
    public record RunToolList(Set<String> allowed, String holder) {}

    private TaskToolPolicy() {}

    /**
     * Parse the persisted allow-list.
     *
     * <p>An empty array reads as unrestricted, not as "zero tools". It is indistinguishable
     * from a cleared picker, and stranding a task with no tools because a control was
     * emptied is the worse failure. A task that genuinely needs no tools wants an agent
     * with none.
     *
     * @param json the raw {@code enabledToolNames} column value
     * @return allowed tool names, or {@code null} for unrestricted
     */
    public static @Nullable Set<String> parse(String json) {
        if (json == null || json.isBlank()) return null;
        var parsed = parseNames(json);
        if (parsed == null) {
            // Fail open, loudly. Failing closed would strand a task with zero tools over a
            // stray comma, and JCLAW-297 fixed the column's semantics as default-open.
            EventLogger.warn(EVENT_CATEGORY, null, null, truncate(
                    "Task enabledToolNames is not a JSON array of names; running unrestricted: " + json));
            return null;
        }
        return parsed.isEmpty() ? null : parsed;
    }

    /** {@link #parse} without the warning, for a reader that runs on every call of a fire the fire itself warned for. */
    private static @Nullable Set<String> parseQuietly(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        var parsed = parseNames(json);
        return parsed == null || parsed.isEmpty() ? null : parsed;
    }

    /** @return the names, empty for an empty list, or {@code null} when {@code json} is malformed */
    private static @Nullable Set<String> parseNames(String json) {
        var parsed = tryParseArray(json);
        return parsed != null ? parsed : tryParseDelimited(json);
    }

    /**
     * Whether {@code allowed} gives a run the tool named {@code toolName}: by its own name, or, for an
     * MCP server's handle, by an entry naming one of the server's actions.
     */
    public static boolean allows(Set<String> allowed, String toolName) {
        if (allowed.contains(toolName)) return true;
        if (!toolName.startsWith(McpServer.HANDLE_PREFIX)) return false;
        for (var entry : allowed) {
            if (actionOf(entry, toolName) != null) return true;
        }
        return false;
    }

    /**
     * The actions of the MCP server behind {@code handle} that {@code allowed} names. Null when every
     * action is allowed: there is no list, or it names the whole server. Empty when it names none.
     */
    public static @Nullable Set<String> mcpActions(@Nullable Set<String> allowed, String handle) {
        if (allowed == null || allowed.contains(handle)) return null;
        var actions = new LinkedHashSet<String>();
        for (var entry : allowed) {
            var action = actionOf(entry, handle);
            if (action != null) actions.add(action);
        }
        return actions;
    }

    /** The action {@code entry} names on {@code handle}, or null when it names none of that server's. */
    private static @Nullable String actionOf(String entry, String handle) {
        var cut = handle.length();
        return entry.length() > cut + 1 && entry.startsWith(handle) && entry.charAt(cut) == ACTION_SEPARATOR
                ? entry.substring(cut + 1) : null;
    }

    /**
     * The allow-list of the run a tool call serves: the one its conversation carries, which a subagent
     * inherits from a fenced task or chat, else its task's. Null when the run has none.
     *
     * <p>Read for each call rather than carried from the turn's start: an MCP server's handle is the one
     * tool definition for all its actions, so the definitions a turn was offered cannot say which actions
     * the list names.
     */
    public static @Nullable RunToolList toolListOfRun(@Nullable Long conversationId, @Nullable Long taskRunId) {
        if (conversationId == null && taskRunId == null) return null;
        return Tx.run(() -> {
            if (conversationId != null) {
                String own = Conversation.find(
                        "select c.enabledToolNames from Conversation c where c.id = ?1", conversationId).first();
                var allowed = parseQuietly(own);
                if (allowed != null) return new RunToolList(allowed, "the tool list this subagent inherited");
            }
            if (taskRunId == null) return null;
            Object[] task = TaskRun.find(
                    "select r.task.name, r.task.enabledToolNames from TaskRun r where r.id = ?1", taskRunId).first();
            if (task == null) return null;
            var allowed = parseQuietly((String) task[1]);
            return allowed == null ? null : new RunToolList(allowed, "task '" + task[0] + "''s tool list");
        });
    }

    /** @return the names, or {@code null} when {@code json} is not a JSON string array. */
    private static @Nullable Set<String> tryParseArray(String json) {
        try {
            var el = JsonParser.parseString(json);
            if (!el.isJsonArray()) return null;
            var names = new LinkedHashSet<String>();
            for (var item : el.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return null;
                var name = item.getAsString();
                if (name != null && !name.isBlank()) names.add(name.trim());
            }
            return names;
        } catch (RuntimeException _) {
            return null;
        }
    }

    /**
     * Accept the shapes already in the column. {@code TaskTool} stored whatever the model
     * wrote — live data holds {@code "a,b,c"} and a bare {@code "a"} alongside JSON arrays,
     * because nothing read or validated the field before JCLAW-1068. Honoring them matters:
     * dropping one to "unrestricted" would run a task the operator believed was fenced.
     *
     * <p>Anything carrying JSON punctuation was *meant* to be JSON, so a parse failure there
     * is malformed rather than a name list — otherwise {@code ["exec",} would be read as the
     * tool {@code "exec"} and silently fence the task down to nothing.
     *
     * @return the names, or {@code null} when this is not a plain delimited name list
     */
    private static @Nullable Set<String> tryParseDelimited(String raw) {
        if (raw.chars().anyMatch(c -> c == '[' || c == ']' || c == '{' || c == '}' || c == '"')) {
            return null;
        }
        var names = new LinkedHashSet<String>();
        for (var part : raw.split(",")) {
            var name = part.trim();
            if (name.isEmpty()) continue;
            if (!TOOL_NAME.matcher(name).matches()) return null;
            names.add(name);
        }
        return names;
    }

    /**
     * Narrow {@code defs} to {@code allowed}, emitting one audit event per fire when
     * anything is withheld.
     *
     * <p>Names in the allow-list that match no tool the agent has are reported too: the
     * operator meant to permit something and it silently is not there, which is a typo
     * signal worth surfacing rather than a condition to fail on.
     *
     * @param allowed {@code null} to leave {@code defs} untouched
     */
    public static List<ToolDef> restrict(List<ToolDef> defs, @Nullable Set<String> allowed,
                                         @Nullable String taskName, String agentName) {
        if (allowed == null || defs == null) return defs;

        var available = defs.stream().map(d -> d.function().name()).collect(Collectors.toSet());
        var kept = defs.stream().filter(d -> allows(allowed, d.function().name())).toList();
        var withheld = available.stream().filter(n -> !allows(allowed, n)).sorted().toList();
        var unmatched = allowed.stream()
                .filter(e -> available.stream().noneMatch(n -> n.equals(e) || actionOf(e, n) != null))
                .sorted().toList();
        if (withheld.isEmpty() && unmatched.isEmpty()) return kept;

        var msg = new StringBuilder("Task '%s' (agent '%s') restricted to %d of %d tools"
                .formatted(taskName, agentName, kept.size(), defs.size()));
        if (!withheld.isEmpty()) msg.append("; withheld: ").append(String.join(", ", withheld));
        if (!unmatched.isEmpty()) msg.append("; allow-list names matching no available tool: ")
                .append(String.join(", ", unmatched));
        EventLogger.info(EVENT_CATEGORY, agentName, null, truncate(msg.toString()));

        return kept;
    }

    /** The allow-list a conversation carries ({@code Conversation.enabledToolNames}), or null when it carries none. */
    public static @Nullable Set<String> allowListOf(@Nullable String allowListJson) {
        return allowListJson == null || allowListJson.isBlank() ? null : parse(allowListJson);
    }

    /**
     * Hold a subagent's turn to the allow-list its conversation inherited from a fenced task
     * ({@code Conversation.enabledToolNames}). Null or blank, the usual case, leaves
     * {@code defs} untouched; a malformed list fails open as it does for the fire itself.
     */
    public static List<ToolDef> restrictSubagent(List<ToolDef> defs, @Nullable String allowListJson, String agentName) {
        var allowed = allowListOf(allowListJson);
        if (allowed == null) return defs;
        var kept = defs.stream().filter(d -> allows(allowed, d.function().name())).toList();
        if (kept.size() < defs.size()) {
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Subagent '%s' held to its task's tool list: %d of %d tools".formatted(agentName, kept.size(), defs.size()));
        }
        return kept;
    }

    private static String truncate(String s) {
        return s.length() <= MESSAGE_LIMIT ? s : s.substring(0, MESSAGE_LIMIT - 3) + "...";
    }
}
