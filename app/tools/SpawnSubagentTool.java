package tools;

import agents.AgentRunner;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JCLAW-265: synchronous spawn of a child agent as a subagent.
 *
 * <p>The parent calls this tool with a {@code task} (the instruction the child
 * should carry out). The tool materializes a fresh child {@link Agent} and a
 * fresh child {@link Conversation} both wired to the parent via the JCLAW-264
 * self-FKs, records a {@link SubagentRun} row (RUNNING + startedAt), invokes
 * {@link AgentRunner#run} synchronously, then updates the audit row with the
 * terminal status, outcome, and ended_at. {@link EventLogger#recordSubagentSpawn}
 * fires immediately after the audit row is persisted (so the {@code run_id} in
 * the payload is real); {@link EventLogger#recordSubagentComplete} or
 * {@link EventLogger#recordSubagentError} fires on terminal outcomes.
 *
 * <p>Scope (initial; see JCLAW-257 epic): mode is implicitly {@code "session"}
 * (the child runs in its own conversation) and context is implicitly
 * {@code "fresh"} (no parent history is copied in). The {@code mode} /
 * {@code context} alternative branches are JCLAW-267 / JCLAW-268.
 *
 * <p>The parent's tool registration provides the child's tool surface: the
 * child Agent row is cloned from the parent so it inherits the same provider,
 * model, and tool-config policies as the parent (operator-toggleable on the
 * child's own agent row going forward).
 *
 * <p><b>Timeout enforcement (best-effort).</b> The synchronous child run is
 * dispatched to a virtual thread and we await up to {@code runTimeoutSeconds}.
 * On {@link TimeoutException} we mark the SubagentRun {@code TIMEOUT} and emit
 * {@code SUBAGENT_TIMEOUT}; the child VT is interrupted but
 * {@code AgentRunner.run} has no first-class cancel hook today, so any
 * in-flight LLM HTTP call or tool execution may continue to completion in the
 * background. Tight cancellation semantics are JCLAW-270 (async path) territory.
 */
public class SpawnSubagentTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "spawn_subagent";

    static final String DEFAULT_MODE = "session";
    static final String DEFAULT_CONTEXT = "fresh";

    /** Channel value stamped on subagent conversations. Not a real transport —
     *  {@link models.ChannelType#fromValue} returns null, so dispatchers no-op
     *  and the row exists purely as the child's audit-trail container. */
    public static final String SUBAGENT_CHANNEL = "subagent";

    /** Default wall-clock budget for a synchronous spawn, per AC. */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * JCLAW-266: recursion caps. Defaults match Personal Edition posture —
     * single level of delegation (top-level Agent spawns one tier of
     * subagents, no grandchildren) and a small fan-out so one parent can't
     * saturate the executor with concurrent children.
     *
     * <p>Depth = count of {@link Agent#parentAgent} links walking from the
     * spawning agent back to a root. {@code depthLimit=1} means
     * "only top-level agents (parentAgent == null) can spawn"; a child
     * would itself be at depth 1, hit the limit, and be refused.
     *
     * <p>Breadth = count of {@link SubagentRun} rows whose
     * {@code parentAgent} is the spawning agent and {@code status = RUNNING}.
     * Per direct parent — not the whole subtree.
     */
    static final int DEFAULT_DEPTH_LIMIT = 1;
    static final int DEFAULT_BREADTH_LIMIT = 5;
    static final String DEPTH_LIMIT_KEY = "subagents.depth.limit";
    static final String BREADTH_LIMIT_KEY = "subagents.breadth.limit";

    /** Soft cap on how far we walk the parent chain when computing depth,
     *  so a cycle (shouldn't happen, but defense-in-depth) can't spin
     *  forever. Far above any plausible depth limit. */
    private static final int MAX_DEPTH_WALK = 64;

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "users"; }

    @Override
    public String shortDescription() {
        return "Delegate a task to a child subagent that runs in its own conversation and returns its final reply.";
    }

    @Override
    public List<agents.ToolAction> actions() {
        return List.of(
                new agents.ToolAction("spawn",
                        "Run a task on a child subagent synchronously and return its final reply"));
    }

    @Override
    public String description() {
        return """
                Spawn a child subagent to carry out a task on your behalf. The child runs \
                in its own fresh conversation and you receive its final assistant reply. \
                Use this when a task is well-scoped and benefits from an isolated context \
                (long research, separate tool surface, exploratory work). \
                Required: `task` (the instruction the child should execute). \
                Optional: `label` (short display name), `agentId` (numeric id of an existing \
                agent to run as the child; defaults to a fresh clone of the current agent), \
                `modelProvider` and `modelId` (override the child's model), \
                `runTimeoutSeconds` (wall-clock budget, default 300).""";
    }

    @Override
    public String summary() {
        return "Spawn a child subagent to run a task and return its final reply.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        "task", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Instruction for the child subagent (required)"),
                        "label", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Optional short display name for the spawn"),
                        "agentId", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "Optional id of an existing Agent row to run as the child; "
                                        + "defaults to a fresh clone of the calling agent"),
                        "modelProvider", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Optional provider override for the child"),
                        "modelId", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Optional model id override for the child"),
                        "runTimeoutSeconds", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "Wall-clock budget for the synchronous run (default 300)")
                ),
                SchemaKeys.REQUIRED, List.of("task")
        );
    }

    /** Touches DB rows that other tool invocations might race; safer to keep
     *  serial within a single parent turn. */
    @Override
    public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent parentAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();

        var task = optString(args, "task");
        if (task == null || task.isBlank()) {
            return "Error: 'task' is required.";
        }
        var label = optString(args, "label");
        var requestedAgentId = optLong(args, "agentId");
        var modelProviderOverride = optString(args, "modelProvider");
        var modelIdOverride = optString(args, "modelId");
        var timeoutSeconds = optInt(args, "runTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        // JCLAW-266: enforce recursion caps before touching the DB. Both checks
        // run inside a Tx so the ancestor walk and the RUNNING-row count see a
        // consistent snapshot. On refusal we emit SUBAGENT_LIMIT_EXCEEDED and
        // return a plain-text error — no SubagentRun row, no child Agent, no
        // child Conversation gets written.
        var refusal = Tx.run(() -> enforceRecursionLimits(parentAgent));
        if (refusal != null) {
            EventLogger.recordSubagentLimitExceeded(parentAgent.name, refusal);
            return "Subagent spawn refused: " + refusal;
        }

        // Resolve the parent conversation. AgentRunner does not pass it into
        // tools — by convention the most-recent conversation for this agent
        // is the one the tool call is being made from. Subagent spawning is
        // first introduced here so there's no incumbent pattern to match;
        // the lookup is wrapped in a Tx to dodge detached-entity issues on
        // VT-dispatched tool calls.
        var parentAgentId = parentAgent.id;
        var parentConv = Tx.run(() -> resolveParentConversation(parentAgentId));
        if (parentConv == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'."
                    .formatted(parentAgent.name);
        }

        // Step 1+2: materialize child Agent + Conversation in one short Tx so
        // both rows commit before we open SubagentRun. AgentService.create
        // already runs its own internal sub-work (workspace seed, default
        // tool-config rows) and tolerates being inside an outer Tx.run via
        // Tx's "inside-tx → reuse" branch.
        final String resolvedLabel = label;
        final String resolvedModelProvider = modelProviderOverride;
        final String resolvedModelId = modelIdOverride;
        final Long resolvedAgentIdParam = requestedAgentId;
        var bootstrap = Tx.run(() -> bootstrapChild(
                parentAgent, parentConv, resolvedAgentIdParam,
                resolvedLabel, resolvedModelProvider, resolvedModelId));
        if (bootstrap.error() != null) {
            return bootstrap.error();
        }
        var childAgentId = bootstrap.childAgentId();
        var childConvId = bootstrap.childConvId();

        // Step 3: insert the SubagentRun audit row (RUNNING + startedAt) in
        // its own short Tx so the row commits and is visible from any thread
        // we hand the run to.
        final var parentConvIdFinal = parentConv.id;
        var runId = Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = Agent.findById(parentAgentId);
            run.childAgent = Agent.findById(childAgentId);
            run.parentConversation = Conversation.findById(parentConvIdFinal);
            run.childConversation = Conversation.findById(childConvId);
            // status defaults to RUNNING, startedAt populated by @PrePersist.
            run.save();
            return run.id;
        });

        var runIdStr = String.valueOf(runId);
        EventLogger.recordSubagentSpawn(
                parentAgent.name, lookupAgentName(childAgentId),
                runIdStr, DEFAULT_MODE, DEFAULT_CONTEXT);

        // Step 4: synchronous AgentRunner.run on a VT so we can enforce the
        // wall-clock budget via Future.get(timeout). The child Agent +
        // Conversation are re-fetched inside the VT so they're managed in a
        // fresh persistence context.
        var future = CompletableFuture.supplyAsync(
                () -> {
                    var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                    var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                    if (childAgent == null || childConv == null) {
                        throw new IllegalStateException(
                                "Subagent rows vanished before AgentRunner.run");
                    }
                    return AgentRunner.run(childAgent, childConv, task);
                },
                runnable -> Thread.ofVirtual().name("subagent-" + runId).start(runnable));

        String reply;
        SubagentRun.Status terminalStatus;
        String terminalOutcome;
        String errorReason = null;

        try {
            var result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            reply = result == null ? "" : result.response();
            terminalStatus = SubagentRun.Status.COMPLETED;
            terminalOutcome = reply;
        } catch (TimeoutException te) {
            future.cancel(true); // best-effort interrupt; AgentRunner has no cancel hook yet
            terminalStatus = SubagentRun.Status.TIMEOUT;
            errorReason = "Subagent run exceeded %d-second budget".formatted(timeoutSeconds);
            terminalOutcome = errorReason;
            reply = "";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            terminalStatus = SubagentRun.Status.FAILED;
            errorReason = "Parent thread interrupted while awaiting subagent";
            terminalOutcome = errorReason;
            reply = "";
        } catch (ExecutionException ee) {
            var cause = ee.getCause() != null ? ee.getCause() : ee;
            terminalStatus = SubagentRun.Status.FAILED;
            errorReason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            terminalOutcome = errorReason;
            reply = "";
        }

        // Step 5: write the terminal SubagentRun update in its own short Tx.
        final var finalStatus = terminalStatus;
        final var finalOutcome = terminalOutcome;
        Tx.run(() -> {
            var fresh = (SubagentRun) SubagentRun.findById(runId);
            if (fresh != null) {
                fresh.status = finalStatus;
                fresh.endedAt = Instant.now();
                fresh.outcome = finalOutcome;
                fresh.save();
            }
        });

        // Step 6: emit the terminal lifecycle event.
        var childName = lookupAgentName(childAgentId);
        switch (terminalStatus) {
            case COMPLETED -> EventLogger.recordSubagentComplete(
                    parentAgent.name, childName, runIdStr, DEFAULT_MODE, DEFAULT_CONTEXT, "ok");
            case TIMEOUT -> EventLogger.recordSubagentTimeout(parentAgent.name, runIdStr);
            default -> EventLogger.recordSubagentError(
                    parentAgent.name, childName, runIdStr,
                    DEFAULT_MODE, DEFAULT_CONTEXT, errorReason);
        }

        // Tool return — the LLM will see this JSON string.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("run_id", runIdStr);
        payload.put("conversation_id", String.valueOf(childConvId));
        payload.put("reply", reply);
        payload.put("status", terminalStatus.name());
        return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    // ----- internals -----

    /** Result of bootstrapping the child rows. {@code error} non-null means the
     *  caller should bail and surface the message verbatim. */
    private record Bootstrap(Long childAgentId, Long childConvId, String error) {
        static Bootstrap ok(Long agentId, Long convId) { return new Bootstrap(agentId, convId, null); }
        static Bootstrap fail(String msg) { return new Bootstrap(null, null, msg); }
    }

    /**
     * Returns a refusal reason string when the spawn would violate either
     * recursion cap, or {@code null} when the spawn may proceed. Must be
     * called inside a {@link Tx#run} block: it touches the persisted
     * {@code parentAgent} chain and queries {@link SubagentRun}.
     *
     * <p>Race note: two concurrent spawns from the same parent could both
     * see "N-1 RUNNING" and both proceed, transiently exceeding the breadth
     * cap by one. Personal Edition rarely runs into this (the parent LLM
     * issues tool calls sequentially within a turn); accepted as an
     * advisory limit for now. A {@code SELECT ... FOR UPDATE} on the parent
     * Agent row would close the window if/when it matters.
     */
    static String enforceRecursionLimits(Agent parentAgent) {
        var depthLimit = positiveIntOrDefault(DEPTH_LIMIT_KEY, DEFAULT_DEPTH_LIMIT);
        var breadthLimit = positiveIntOrDefault(BREADTH_LIMIT_KEY, DEFAULT_BREADTH_LIMIT);

        var currentDepth = depthOf(parentAgent);
        // currentDepth is the spawning agent's depth. Spawning would create
        // a child at depth currentDepth+1; refuse when currentDepth+1 exceeds
        // the limit, i.e. currentDepth >= limit.
        if (currentDepth >= depthLimit) {
            return "depth limit %d exceeded (current depth: %d)."
                    .formatted(depthLimit, currentDepth);
        }

        // Re-fetch the parent inside this tx for a managed reference. The
        // count query takes the managed instance to keep Hibernate happy.
        var managed = (Agent) Agent.findById(parentAgent.id);
        if (managed == null) {
            // The spawning agent vanished between the controller and here.
            // Surface a clear refusal rather than spinning into bootstrap.
            return "parent agent %d not found.".formatted(parentAgent.id);
        }
        long running = SubagentRun.count(
                "parentAgent = ?1 AND status = ?2",
                managed, SubagentRun.Status.RUNNING);
        if (running >= breadthLimit) {
            return "breadth limit %d exceeded (running children: %d).".formatted(
                    breadthLimit, running);
        }
        return null;
    }

    /** Count of parentAgent links from {@code start} back to a root.
     *  Capped by {@link #MAX_DEPTH_WALK} as a cycle guard. */
    private static int depthOf(Agent start) {
        int depth = 0;
        var cursor = start != null ? start.parentAgent : null;
        while (cursor != null && depth < MAX_DEPTH_WALK) {
            depth++;
            cursor = cursor.parentAgent;
        }
        return depth;
    }

    private static int positiveIntOrDefault(String key, int fallback) {
        var raw = play.Play.configuration != null
                ? play.Play.configuration.getProperty(key) : null;
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    private static Bootstrap bootstrapChild(Agent parentAgent, Conversation parentConv,
                                            Long requestedAgentId,
                                            String label,
                                            String modelProviderOverride,
                                            String modelIdOverride) {
        Agent childAgent;
        if (requestedAgentId != null) {
            childAgent = Agent.findById(requestedAgentId);
            if (childAgent == null) {
                return Bootstrap.fail(
                        "Error: agentId %d not found.".formatted(requestedAgentId));
            }
            // Stamp the parent FK even for a pre-existing agent so the lineage
            // is recorded; if a child was previously spawned by a different
            // parent the most-recent parent wins (best-effort hierarchy).
            childAgent.parentAgent = parentAgent;
            childAgent.save();
        } else {
            // Clone the parent's runtime config (provider, model, thinkingMode)
            // into a fresh row so the child is its own auditable identity.
            var provider = modelProviderOverride != null && !modelProviderOverride.isBlank()
                    ? modelProviderOverride : parentAgent.modelProvider;
            var modelId = modelIdOverride != null && !modelIdOverride.isBlank()
                    ? modelIdOverride : parentAgent.modelId;
            var name = buildChildAgentName(parentAgent.name);
            try {
                childAgent = AgentService.create(name, provider, modelId,
                        parentAgent.thinkingMode,
                        label != null && !label.isBlank()
                                ? label
                                : "Subagent of " + parentAgent.name);
            } catch (RuntimeException e) {
                return Bootstrap.fail("Error: failed to create child agent: " + e.getMessage());
            }
            childAgent.parentAgent = parentAgent;
            childAgent.save();
        }

        var childConv = ConversationService.create(childAgent, SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();

        return Bootstrap.ok(childAgent.id, childConv.id);
    }

    /** Unique child agent name: parent + short token. Agent.name has a unique
     *  constraint; we keep the parent prefix so the workspace folder is easy to
     *  locate when debugging. */
    private static String buildChildAgentName(String parentName) {
        var suffix = Long.toString(System.nanoTime(), 36);
        return parentName + "-sub-" + suffix;
    }

    private static Conversation resolveParentConversation(Long parentAgentId) {
        var parent = (Agent) Agent.findById(parentAgentId);
        if (parent == null) return null;
        // Pick the most recently-updated conversation that ISN'T a subagent
        // child of someone else (a subagent calling spawn nests under its own
        // parent conversation, which is itself an existing Conversation row —
        // no special-case needed; channelType="subagent" simply means "I'm a
        // child of someone" and we still want that as the parent for a nested
        // spawn).
        return Conversation.find("agent = ?1 ORDER BY updatedAt DESC", parent).first();
    }

    private static String lookupAgentName(Long id) {
        if (id == null) return null;
        return Tx.run(() -> {
            var a = (Agent) Agent.findById(id);
            return a != null ? a.name : null;
        });
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    private static Long optLong(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try { return el.getAsLong(); } catch (RuntimeException e) { return null; }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try { return el.getAsInt(); } catch (RuntimeException e) { return fallback; }
    }
}
