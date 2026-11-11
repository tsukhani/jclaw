package tools;

import agents.AgentRunner;
import agents.DangerousActionGate;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.LoadTestRunner;
import services.SubagentRegistry;
import services.Tx;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JF-164: hands a task to another of the operator's agents and waits for its reply. The callee runs as itself, with
 * its own tools, model and prompt, in a fresh conversation of its own, through {@link SubagentSpawnTool}'s single-spawn
 * pipeline: the run row, the breadth cap, kill and the trust and task-fence inheritance are that pipeline's. No tool
 * comparison bounds the callee, so the tool is offered only on a turn the owner reads alone
 * ({@code ToolRegistry.OPERATOR_TURN_ONLY}).
 *
 * <p>Only an agent hands work on: a subagent is never offered the tool. A caller is therefore always a top-level
 * agent, at depth 0, so {@code subagent.maxDepth} never refuses a hand-off. A chain may visit an agent again; what
 * bounds it is the hand-off budget, {@link #MAX_HAND_OFFS_KEY}, which every hand-off under one turn nobody handed
 * work to draws from.
 */
public class AgentSpawnTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "agent_spawn";

    static final String ARG_AGENT = "agent";
    static final String ARG_TASK = "task";
    static final String NOT_AN_OWNER_TURN =
            "Error: agent_spawn is available only on a turn the owner reads alone; no agent was asked.";
    static final String HAND_OFF_REFUSED = "Agent hand-off refused: ";
    static final String NOT_FOR_A_SUBAGENT = "Error: a subagent cannot hand work to another agent. Return what you "
            + "have to the agent that spawned you.";

    /** Config-DB key (seeded by {@link jobs.DefaultConfigJob}, editable from Settings): the hand-off budget. */
    public static final String MAX_HAND_OFFS_KEY = "subagent.maxHandOffs";
    public static final int DEFAULT_MAX_HAND_OFFS = 10;

    private static final Set<String> INTERNAL_AGENTS = Set.of(LoadTestRunner.LOADTEST_AGENT_NAME,
            LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME, Agent.EVALTEST_AGENT_NAME);

    private static final String DESCRIPTION = """
            Hand a task to another agent on this instance and wait for its reply. The agent works in its own \
            conversation and sees only the task you write, so include everything it needs. Its reply is that \
            agent's own output: check it before relying on it. A file it saves stays in its own workspace: link it \
            as files_url followed by the file's path, using the files_url in the result. To run a helper copy of \
            yourself, use subagent_spawn instead.

            Agents you can hand work to:
            """;

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "users"; }

    @Override
    public String summary() {
        return "Hand a task to another agent on this instance and get its reply.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("spawn", "Hand a task to another agent and return its reply"));
    }

    @Override
    public String description() {
        return DESCRIPTION + "(listed per agent)";
    }

    @Override
    public String description(@Nullable Agent caller) {
        // McpDiscoveryTest builds defs for no agent; such a list excludes no caller.
        var reachable = reachableFrom(caller);
        if (reachable.isEmpty()) return DESCRIPTION + "none";
        var lines = new ArrayList<String>();
        for (var agent : reachable) {
            lines.add("- " + agent.name() + ": " + (agent.purpose() != null ? agent.purpose() : "(no purpose given)"));
        }
        return DESCRIPTION + String.join("\n", lines);
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(ARG_AGENT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "The exact name of the agent to hand the task to, as listed above"));
        props.put(ARG_TASK, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Everything the agent needs to do the task; it sees nothing else"));
        props.put(SubagentSpawnTool.FIELD_LABEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional short display name for the hand-off"));
        props.put(SubagentSpawnTool.ARG_RUN_TIMEOUT_SECONDS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Idle budget: seconds of inactivity (no LLM round/tool call) before the run times out; "
                        + "active work resets it, so it need not cover total runtime (default "
                        + SubagentSpawnTool.defaultRunTimeoutSeconds() + ")"));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of(ARG_AGENT, ARG_TASK));
    }

    @Override
    public boolean parallelSafe() { return false; }

    /** Shared with {@code subagent_spawn} and {@code subagent_yield}: see {@link SubagentSpawnTool#serializationGroup}. */
    @Override
    public String serializationGroup() { return "subagent_lifecycle"; }

    /** An agent {@code caller} may hand work to: its name and the operator's description of it, if any. */
    public record Reachable(Long id, String name, @Nullable String purpose) {}

    /**
     * The agents {@code caller} may hand work to, sorted by name ignoring case: every enabled top-level agent but
     * {@code main}, the agents JClaw runs for itself and the caller. The description lists them and
     * {@link #execute} refuses any other; {@link #execute} matches a name exactly.
     */
    public static List<Reachable> reachableFrom(@Nullable Agent caller) {
        return Tx.run(() -> {
            List<Agent> candidates = Agent.find("enabled = true AND parentAgent IS NULL").fetch();
            var out = new ArrayList<Reachable>();
            for (var agent : candidates) {
                if (reachable(agent, caller)) out.add(new Reachable(agent.id, agent.name, agent.description));
            }
            out.sort(Comparator.comparing(Reachable::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        });
    }

    private static boolean reachable(Agent agent, @Nullable Agent caller) {
        return agent.enabled && agent.parentAgent == null && !agent.isMain()
                && !INTERNAL_AGENTS.contains(agent.name) && (caller == null || !agent.id.equals(caller.id));
    }

    @Override
    public String execute(String argsJson, Agent caller) {
        // Not offered on such a turn either; this is the net for a site that offers it anyway. A fire has no
        // conversation, so its task decides.
        var turnConversationId = ToolContext.conversationId();
        var offered = turnConversationId != null ? DangerousActionGate.ownerReadsAlone(turnConversationId)
                : AgentRunner.fireOffersOwnerTools(ToolContext.taskRunId());
        if (!offered) return NOT_AN_OWNER_TURN;
        // Also withheld from a subagent's tools; this is the net for a call that arrives anyway.
        if (caller.parentAgent != null) return NOT_FOR_A_SUBAGENT;

        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return "Error: arguments are not valid JSON: " + e.getMessage();
        }
        var name = SubagentSpawnArgs.optString(args, ARG_AGENT);
        if (name == null || name.isBlank()) return "Error: 'agent' is required.";
        var task = SubagentSpawnArgs.optString(args, ARG_TASK);
        if (task == null || task.isBlank()) return "Error: 'task' is required.";

        var callee = Tx.run(() -> resolveCallee(name.strip(), caller));
        var target = callee.target();
        if (target == null) return callee.error();
        var calleeId = target.id();
        var calleeName = target.name();

        var handOffs = handOffCounter();
        int budget = SubagentLimits.readPositiveIntConfig(MAX_HAND_OFFS_KEY, DEFAULT_MAX_HAND_OFFS);
        int soFar = handOffs.get();
        if (soFar >= budget) {
            var reason = "hand-off limit %d reached (hand-offs so far: %d).".formatted(budget, soFar);
            EventLogger.recordSubagentLimitExceeded(caller.name, reason);
            return HAND_OFF_REFUSED + reason + " Reply with what you have.";
        }
        handOffs.incrementAndGet();

        var parsed = SubagentSpawnArgs.handOff(task, SubagentSpawnArgs.optString(args, SubagentSpawnTool.FIELD_LABEL),
                calleeId, SubagentSpawnArgs.timeoutSeconds(args));
        var extras = new LinkedHashMap<String, Object>();
        extras.put("agent", calleeName);
        extras.put("agent_id", calleeId);
        extras.put("files_url", "/api/agents/" + calleeId + "/files/");
        // An empty argument object: the hand-off always runs natively.
        return SubagentSpawnTool.spawnOne(new JsonObject(), caller, parsed, HAND_OFF_REFUSED, extras, handOffs);
    }

    /**
     * The counter this hand-off draws from: the one its caller's own run carries when the caller was handed work,
     * else the calling turn's. A call outside any turn (no trace bound) counts on its own.
     */
    private static AtomicInteger handOffCounter() {
        var tree = SubagentRegistry.currentHandOffs();
        if (tree != null) return tree;
        var turn = LatencyTrace.current();
        return turn != null ? turn.handOffs() : new AtomicInteger();
    }

    /** {@code target} null refuses the hand-off with {@code error}. */
    private record Callee(@Nullable Reachable target, String error) {
        static Callee fail(String error) { return new Callee(null, error); }
    }

    /** The reach checks in the order their refusals are written: main, internal, self, unknown or clone, disabled. */
    private static Callee resolveCallee(String name, Agent caller) {
        if (Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            return Callee.fail("Error: agent_spawn cannot hand work to the main agent.");
        }
        if (INTERNAL_AGENTS.contains(name)) {
            return Callee.fail("Error: agent_spawn cannot hand work to '%s', an agent JClaw runs for itself."
                    .formatted(name));
        }
        if (name.equals(caller.name)) {
            return Callee.fail("Error: '%s' is the calling agent. To run a helper copy of yourself, use subagent_spawn."
                    .formatted(name));
        }
        var unknown = ("Error: no agent named '%s' can take this task. Choose one of the agents listed in "
                + "this tool's description.").formatted(name);
        var agent = Agent.findByName(name);
        if (agent == null || agent.parentAgent != null) return Callee.fail(unknown);
        if (!agent.enabled) return Callee.fail("Error: agent '%s' is disabled.".formatted(name));
        // The checks above only name the refusal; the rule is the one the description lists by.
        if (!reachable(agent, caller)) return Callee.fail(unknown);
        return new Callee(new Reachable(agent.id, agent.name, agent.description), "");
    }
}
