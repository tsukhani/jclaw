package tools;

import agents.AgentRunner;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import models.SubagentRun;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Scope: mode is implicitly {@code "session"} (the child runs in its own
 * conversation; the {@code mode} alternative branch is JCLAW-267 territory).
 * The {@code context} parameter accepts {@code "fresh"} (default) or
 * {@code "inherit"} (JCLAW-268). Fresh gives the child only its configured
 * system prompt and an empty history. Inherit additionally (a) summarizes the
 * parent's recent turns via a synchronous LLM call (hard-capped at
 * {@link SessionCompactor#PARENT_CONTEXT_MAX_CHARS} characters), stamps the
 * result on the child Conversation, and re-injects it into the child's system
 * prompt each turn via
 * {@link SessionCompactor#appendParentContextToPrompt}, and (b) unions the
 * parent's enabled tools into the child Agent's tool config so the child can
 * use the same tool surface the parent had — at-spawn snapshot, matching the
 * per-spawn child Agent lifetime JCLAW-265 established.
 *
 * <p>If the inherit-mode summarization LLM call fails or returns blank, the
 * spawn degrades silently to fresh-mode behavior and emits a
 * {@code SUBAGENT_ERROR} event capturing the reason; the child still spawns,
 * still gets the tool-union grant, and still runs. The reverse (skip the
 * spawn outright) would be a worse failure mode for resilience.
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
    static final String CONTEXT_FRESH = "fresh";
    static final String CONTEXT_INHERIT = "inherit";
    private static final Set<String> ALLOWED_CONTEXTS = Set.of(CONTEXT_FRESH, CONTEXT_INHERIT);

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
    public static final int DEFAULT_DEPTH_LIMIT = 1;
    public static final int DEFAULT_BREADTH_LIMIT = 5;
    /** Config-DB keys (seeded by {@link jobs.DefaultConfigJob}, editable from
     *  the Settings page's Subagents section). Names match the JCLAW-266 AC
     *  verbatim. */
    public static final String DEPTH_LIMIT_KEY = "subagent.maxDepth";
    public static final String BREADTH_LIMIT_KEY = "subagent.maxChildrenPerParent";

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
                `context` ("fresh" — default — gives the child an empty history; "inherit" \
                injects a summary of your recent turns into the child's system prompt and \
                grants it the union of your enabled tools and its own), \
                `runTimeoutSeconds` (wall-clock budget, default 300).""";
    }

    @Override
    public String summary() {
        return "Spawn a child subagent to run a task and return its final reply.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put("task", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Instruction for the child subagent (required)"));
        props.put("label", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional short display name for the spawn"));
        props.put("agentId", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Optional id of an existing Agent row to run as the child; "
                        + "defaults to a fresh clone of the calling agent"));
        props.put("modelProvider", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional provider override for the child"));
        props.put("modelId", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional model id override for the child"));
        props.put("context", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Context inheritance mode: \"fresh\" (default) for an empty child history, "
                        + "or \"inherit\" to summarize the parent's recent turns into the "
                        + "child's system prompt and grant the child the union of the parent's "
                        + "enabled tools and its own."));
        props.put("runTimeoutSeconds", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Wall-clock budget for the synchronous run (default 300)"));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
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
        // JCLAW-268: context parameter — "fresh" (default) is the JCLAW-265
        // behavior; "inherit" summarizes the parent's recent turns and unions
        // tool grants. Validate strictly so an LLM typo produces a clear
        // error rather than silently degrading.
        var requestedContext = optString(args, "context");
        var context = requestedContext == null || requestedContext.isBlank()
                ? DEFAULT_CONTEXT
                : requestedContext.toLowerCase();
        if (!ALLOWED_CONTEXTS.contains(context)) {
            return "Error: 'context' must be one of " + ALLOWED_CONTEXTS + " (got '" + requestedContext + "').";
        }

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

        // JCLAW-268: inherit-mode pre-step. We do two things outside the
        // bootstrap Tx so the LLM-bound summarize call doesn't hold a DB
        // connection:
        //   1. Snapshot the parent's recent messages inside a short Tx
        //      (the snapshot is detached from JPA and safe to consume).
        //   2. Call the LLM synchronously to produce the parent-context
        //      summary. On failure we degrade to fresh and emit
        //      SUBAGENT_ERROR (no run_id yet — that's deliberate; the
        //      reason field carries the failure cause).
        // Result feeds bootstrapChild as the parent-context blob to stamp
        // on the child Conversation. For fresh-mode and for failure
        // degradation, parentContextSummary stays null and bootstrap
        // skips the stamping + union grant.
        String parentContextSummary = null;
        String summaryErrorReason = null;
        boolean inheritRequested = CONTEXT_INHERIT.equals(context);
        if (inheritRequested) {
            try {
                parentContextSummary = buildParentContextSummary(parentAgent, parentConv.id);
            } catch (Exception e) {
                summaryErrorReason = "Parent-context summarization failed: " + e.getMessage();
            }
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
        final boolean applyInheritGrants = inheritRequested && parentContextSummary != null;
        final String resolvedParentContext = parentContextSummary;
        var bootstrap = Tx.run(() -> bootstrapChild(
                parentAgent, parentConv, resolvedAgentIdParam,
                resolvedLabel, resolvedModelProvider, resolvedModelId,
                applyInheritGrants, resolvedParentContext));
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
                runIdStr, DEFAULT_MODE, context);

        // JCLAW-268: surface inherit-mode summarization failure as a SUBAGENT_ERROR
        // immediately after spawn. The child still runs (degraded to fresh-mode
        // semantics for the parent-context blob; tool-union grant is also
        // skipped, see bootstrapChild). The spawn-time event makes the
        // degradation auditable; the terminal event below covers the run
        // itself once it finishes.
        if (summaryErrorReason != null) {
            EventLogger.recordSubagentError(
                    parentAgent.name, lookupAgentName(childAgentId),
                    runIdStr, DEFAULT_MODE, context, summaryErrorReason);
        }

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
                    parentAgent.name, childName, runIdStr, DEFAULT_MODE, context, "ok");
            case TIMEOUT -> EventLogger.recordSubagentTimeout(parentAgent.name, runIdStr);
            default -> EventLogger.recordSubagentError(
                    parentAgent.name, childName, runIdStr,
                    DEFAULT_MODE, context, errorReason);
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
        // Read from the runtime Config table so the Settings page can edit
        // these without a restart. ConfigService.getInt falls back to the
        // hard-coded default when the row is absent or unparseable; values
        // <= 0 are also coerced to the default so an operator typo can't
        // silently disable the cap.
        var depthLimit = readPositiveIntConfig(DEPTH_LIMIT_KEY, DEFAULT_DEPTH_LIMIT);
        var breadthLimit = readPositiveIntConfig(BREADTH_LIMIT_KEY, DEFAULT_BREADTH_LIMIT);

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

    private static int readPositiveIntConfig(String key, int fallback) {
        int n = ConfigService.getInt(key, fallback);
        return n > 0 ? n : fallback;
    }

    private static Bootstrap bootstrapChild(Agent parentAgent, Conversation parentConv,
                                            Long requestedAgentId,
                                            String label,
                                            String modelProviderOverride,
                                            String modelIdOverride,
                                            boolean applyInheritGrants,
                                            String parentContextSummary) {
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
            // JCLAW-269: child Agent ALWAYS inherits the parent's defaults; the
            // per-spawn modelProvider/modelId override (when supplied) lands on
            // the child Conversation below, not on this row. Keeping the Agent
            // row clean of one-shot overrides means re-running the same child
            // agent later (via agentId) doesn't carry stale per-spawn state.
            var name = buildChildAgentName(parentAgent.name);
            try {
                childAgent = AgentService.create(name,
                        parentAgent.modelProvider, parentAgent.modelId,
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

        // JCLAW-268: inherit-mode tool union. Snapshot the parent's enabled
        // tool set and flip every tool the parent has enabled but the child
        // currently has disabled (via AgentService.create's default-disabled
        // rows for browser / jclaw_api on non-main agents) to enabled. The
        // child's already-enabled tools stay enabled; "union" means the child
        // gets the broader of the two surfaces. Skipped in fresh mode AND in
        // the inherit-mode summarization-failure degradation path so the
        // failure-degraded spawn is also tool-conservative.
        if (applyInheritGrants) {
            unionParentToolGrants(parentAgent, childAgent);
        }

        var childConv = ConversationService.create(childAgent, SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        // JCLAW-269: persist the per-spawn override on the child Conversation so
        // AgentRunner's ModelOverrideResolver picks it up for this run, and so
        // the JCLAW-28 cost dashboard's
        // COALESCE(c.modelProviderOverride, c.agent.modelProvider) attributes
        // spend to the actually-used model. Both columns are set together or
        // neither — half-set is undefined per Conversation.java's contract.
        if (modelProviderOverride != null && !modelProviderOverride.isBlank()
                && modelIdOverride != null && !modelIdOverride.isBlank()) {
            childConv.modelProviderOverride = modelProviderOverride;
            childConv.modelIdOverride = modelIdOverride;
        }
        // JCLAW-268: stamp the inherited parent-context summary on the child
        // Conversation. AgentRunner re-injects this into the child's system
        // prompt every turn via SessionCompactor.appendParentContextToPrompt.
        // Null in fresh mode, in the summarization-failure degradation path,
        // and when the parent had no usable history — all of which leave
        // the column null and turn the injection into a no-op.
        if (applyInheritGrants && parentContextSummary != null && !parentContextSummary.isBlank()) {
            childConv.parentContext = parentContextSummary;
        }
        childConv.save();

        return Bootstrap.ok(childAgent.id, childConv.id);
    }

    /**
     * JCLAW-268: snapshot-into-child-Agent's-tool-config implementation of the
     * "union of parent's enabled tools and child's configured tools" AC.
     *
     * <p>Picks the snapshot approach over a per-Conversation overlay because
     * (a) the child Agent is already per-spawn under JCLAW-265's create-or-
     * reuse flow, so there's no risk of stale grants leaking into an
     * unrelated future spawn, and (b) the existing
     * {@link ToolRegistry#loadDisabledTools} fast path already reads from
     * {@code AgentToolConfig} on every turn — no new overlay codepath to
     * teach.
     *
     * <p>Mechanics:
     * <ol>
     *   <li>Compute the parent's effective enabled-tool set (every registered
     *       tool minus the parent's disabled set).</li>
     *   <li>Walk the child's existing {@code AgentToolConfig} rows. For any
     *       row whose {@code toolName} is in the parent's enabled set and
     *       currently has {@code enabled=false} on the child (the
     *       {@link AgentService#create} default-disables for {@code browser}
     *       and {@code jclaw_api} on non-main agents), flip it to
     *       {@code enabled=true}.</li>
     * </ol>
     *
     * <p>Tools the child doesn't have an explicit row for are already
     * enabled by default — no row needed. We never write a brand-new
     * {@code enabled=true} row for a tool that's already default-enabled;
     * that would only bloat the table.
     *
     * <p>Toolset-restriction caveat: the AC references "JCLAW-252 patterns"
     * for an additional restriction layer on top of the union. That ticket
     * does not exist in this codebase. There is no explicit allowlist /
     * deny-list mechanism beyond {@code AgentToolConfig} itself, so the
     * union IS the full grant — the child only sees tools the parent had
     * enabled OR the child default-allowed.
     */
    private static void unionParentToolGrants(Agent parentAgent, Agent childAgent) {
        var parentDisabled = ToolRegistry.loadDisabledTools(parentAgent);
        var allRegistered = ToolRegistry.listTools();
        // Parent's enabled set: every registered tool not in the parent's
        // disabled set. Avoids materializing the full registry as a set just
        // to negate the disabled set.
        var childRows = AgentToolConfig.findByAgent(childAgent);
        boolean anyFlipped = false;
        for (var row : childRows) {
            if (row.enabled) continue;
            if (parentDisabled.contains(row.toolName)) continue; // parent also has it off
            // Sanity check: tool actually exists. If a stale row references a
            // removed tool, we leave it alone — flipping it would be lying.
            boolean stillRegistered = false;
            for (var t : allRegistered) {
                if (t.name().equals(row.toolName)) { stillRegistered = true; break; }
            }
            if (!stillRegistered) continue;
            row.enabled = true;
            row.save();
            anyFlipped = true;
        }
        if (anyFlipped) {
            // Mirror the existing {@link controllers.ApiToolsController} write
            // path: cached disabled-set is invalidated after every toggle so
            // the very next AgentRunner turn for this child sees the freshly
            // flipped grants.
            ToolRegistry.invalidateDisabledToolsCache(childAgent);
        }
    }

    /**
     * JCLAW-268: synchronously build the parent-context summary. Snapshots
     * the parent's recent messages inside a Tx, then calls the LLM outside
     * any Tx so the chat round-trip doesn't hold a DB connection. Returns
     * {@code null} when there's nothing useful to summarize (no recent
     * messages, model returned blank) — the caller treats that as "skip
     * silently, no error". Any other failure (provider unconfigured, LLM
     * error, network) throws and the caller emits SUBAGENT_ERROR.
     */
    private static String buildParentContextSummary(Agent parentAgent, Long parentConvId) throws Exception {
        var snapshot = Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(parentConvId);
            return SessionCompactor.snapshotParentMessages(conv);
        });
        if (snapshot == null || snapshot.isEmpty()) return null;

        var provider = ProviderRegistry.get(parentAgent.modelProvider);
        if (provider == null) {
            throw new IllegalStateException(
                    "Parent provider '" + parentAgent.modelProvider + "' is not configured");
        }
        final var maxOutput = ConfigService.getInt("subagent.parentContextMaxTokens", 4096);
        final var modelId = parentAgent.modelId;
        SessionCompactor.Summarizer summarizer = sumMsgs -> {
            var resp = provider.chat(modelId, sumMsgs, List.of(), maxOutput, null, null);
            return SessionCompactor.firstChoiceText(resp);
        };
        return SessionCompactor.summarizeParentForSubagent(snapshot, summarizer);
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
