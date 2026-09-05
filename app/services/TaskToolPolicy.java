package services;

import com.google.gson.JsonParser;
import llm.LlmTypes.ToolDef;

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
 */
public final class TaskToolPolicy {

    /** Audit category for a fire whose toolset was narrowed. Fits {@code EventLog.category} (50). */
    public static final String EVENT_CATEGORY = "TOOL_BLOCKED_BY_TASK_POLICY";

    /** {@code EventLog.message} is {@code length = 500}; oversized text fails the insert. */
    private static final int MESSAGE_LIMIT = 500;

    /** Registered tool names: natives like {@code web_search}, MCP handles like
     *  {@code mcp_google-workspace-mcp}. Gates the delimited form so stray prose
     *  cannot pass as a tool list. */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

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
    public static Set<String> parse(String json) {
        if (json == null || json.isBlank()) return null;
        var parsed = tryParseArray(json);
        if (parsed == null) parsed = tryParseDelimited(json);
        if (parsed != null && parsed.isEmpty()) return null;
        if (parsed == null) {
            // Fail open, loudly. Failing closed would strand a task with zero tools over a
            // stray comma, and JCLAW-297 fixed the column's semantics as default-open.
            EventLogger.warn(EVENT_CATEGORY, null, null, truncate(
                    "Task enabledToolNames is not a JSON array of names; running unrestricted: " + json));
            return null;
        }
        return parsed;
    }

    /** @return the names, or {@code null} when {@code json} is not a JSON string array. */
    private static Set<String> tryParseArray(String json) {
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
    private static Set<String> tryParseDelimited(String raw) {
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
    public static List<ToolDef> restrict(List<ToolDef> defs, Set<String> allowed,
                                         String taskName, String agentName) {
        if (allowed == null || defs == null) return defs;

        var available = defs.stream().map(d -> d.function().name()).collect(Collectors.toSet());
        var kept = defs.stream().filter(d -> allowed.contains(d.function().name())).toList();
        var withheld = available.stream().filter(n -> !allowed.contains(n)).sorted().toList();
        var unmatched = allowed.stream().filter(n -> !available.contains(n)).sorted().toList();
        if (withheld.isEmpty() && unmatched.isEmpty()) return kept;

        var msg = new StringBuilder("Task '%s' (agent '%s') restricted to %d of %d tools"
                .formatted(taskName, agentName, kept.size(), defs.size()));
        if (!withheld.isEmpty()) msg.append("; withheld: ").append(String.join(", ", withheld));
        if (!unmatched.isEmpty()) msg.append("; allow-list names matching no available tool: ")
                .append(String.join(", ", unmatched));
        EventLogger.info(EVENT_CATEGORY, agentName, null, truncate(msg.toString()));

        return kept;
    }

    private static String truncate(String s) {
        return s.length() <= MESSAGE_LIMIT ? s : s.substring(0, MESSAGE_LIMIT - 3) + "...";
    }
}
