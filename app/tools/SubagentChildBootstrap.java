package tools;

import agents.ToolRegistry;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.SessionCompactor;
import services.Tx;
import tools.SubagentSpawnTool.SubagentModel;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * JCLAW-677: materialize the child {@link Agent} + child {@link Conversation}
 * and reconcile the tool/MCP grants for a spawn, extracted from
 * {@link SubagentSpawnTool}. Also owns the inherit-mode parent-context summary
 * pre-step and the session model resolution.
 */
final class SubagentChildBootstrap {

    private SubagentChildBootstrap() {}

    /** Result of bootstrapping the child rows. {@code error} non-null means the
     *  caller should bail and surface the message verbatim. */
    record Bootstrap(Long childAgentId, Long childConvId, String childAgentName, String error) {
        static Bootstrap ok(Long agentId, Long convId, String agentName) {
            return new Bootstrap(agentId, convId, agentName, null);
        }
        static Bootstrap fail(String msg) { return new Bootstrap(null, null, null, msg); }
    }

    /** Result of {@link #buildInheritSummary}: either the populated summary
     *  text (or null when fresh / nothing to summarize) plus an optional
     *  failure reason that maps to a deferred SUBAGENT_ERROR event. */
    record InheritSummary(String text, String errorReason) {
        static final InheritSummary NONE = new InheritSummary(null, null);
    }

    /**
     * JCLAW-268: inherit-mode pre-step. Snapshot the parent's recent messages
     * inside a short Tx, then call the LLM synchronously to produce the
     * summary outside of any Tx so the round-trip doesn't hold a DB
     * connection. On failure we degrade to fresh and surface the reason as a
     * deferred SUBAGENT_ERROR. Fresh-mode requests return {@link
     * InheritSummary#NONE} unconditionally.
     */
    static InheritSummary buildInheritSummary(Agent parentAgent, Long parentConvId,
                                              String context) {
        if (!SubagentSpawnTool.CONTEXT_INHERIT.equals(context)) return InheritSummary.NONE;
        try {
            var text = buildParentContextSummary(parentAgent, parentConvId);
            return new InheritSummary(text, null);
        } catch (Exception e) {
            return new InheritSummary(null,
                    "Parent-context summarization failed: " + e.getMessage());
        }
    }

    /**
     * Step 1+2 wrapper. Materializes child Agent + Conversation in one short
     * Tx so both rows commit before the SubagentRun row is opened.
     */
    static Bootstrap bootstrapChildInTx(Agent parentAgent, Conversation parentConv,
                                        SubagentSpawnArgs parsed, InheritSummary summary) {
        final boolean inheritRequested = SubagentSpawnTool.CONTEXT_INHERIT.equals(parsed.context());
        final boolean applyInheritGrants = inheritRequested && summary.text() != null;
        final boolean inlineMode = SubagentSpawnTool.MODE_INLINE.equals(parsed.mode());
        return Tx.run(() -> bootstrapChild(
                parentAgent, parentConv, parsed.requestedAgentId(),
                parsed.label(), parsed.modelProvider(), parsed.modelId(),
                applyInheritGrants, summary.text(), inlineMode));
    }

    private static Bootstrap bootstrapChild(Agent parentAgent, Conversation parentConv,
                                            Long requestedAgentId,
                                            String label,
                                            String modelProviderOverride,
                                            String modelIdOverride,
                                            boolean applyInheritGrants,
                                            String parentContextSummary,
                                            boolean inlineMode) {
        var resolved = resolveChildAgent(parentAgent, requestedAgentId, label);
        if (resolved.error() != null) return Bootstrap.fail(resolved.error());
        var childAgent = resolved.agent();

        // JCLAW-495: a freshly-cloned subagent is a delegate of its parent and
        // must inherit the parent's MCP server grants. MCP grouped tools are
        // default-disabled for non-main agents (ToolRegistry#addMcpDefaultDisabled)
        // and a new subagent has no explicit grant rows, so without this it sees
        // zero MCP tools even when the parent has them enabled — independent of
        // the fresh/inherit context choice. Scoped to freshly-created children so
        // operator-configured reused (agentId) agents are untouched.
        if (requestedAgentId == null) {
            // JCLAW-500: bound the fresh clone's tool surface above by the
            // parent's restrictions before any inherit-mode widening below.
            copyParentToolRestrictions(parentAgent, childAgent);
            grantParentMcpGrants(parentAgent, childAgent);
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

        var childConv = resolveChildConversation(childAgent, parentConv,
                modelProviderOverride, modelIdOverride,
                applyInheritGrants ? parentContextSummary : null, inlineMode);

        return Bootstrap.ok(childAgent.id, childConv.id, childAgent.name);
    }

    /** {@code error} non-null short-circuits {@link #bootstrapChild}. */
    private record ResolvedChildAgent(Agent agent, String error) {
        static ResolvedChildAgent ok(Agent a) { return new ResolvedChildAgent(a, null); }
        static ResolvedChildAgent fail(String msg) { return new ResolvedChildAgent(null, msg); }
    }

    /**
     * Resolve the child {@link Agent}: either look up an existing row by
     * {@code requestedAgentId} or clone the parent into a fresh subagent
     * row. The {@code parent_agent_id} FK is only set on freshly-created
     * rows (see in-method commentary for the rationale).
     */
    private static ResolvedChildAgent resolveChildAgent(Agent parentAgent,
                                                        Long requestedAgentId, String label) {
        if (requestedAgentId != null) {
            Agent existing = Agent.findById(requestedAgentId);
            if (existing == null) {
                return ResolvedChildAgent.fail(
                        "Error: agentId %d not found.".formatted(requestedAgentId));
            }
            // JCLAW-500 (Change 3): a reused agent must not be MORE capable than
            // the spawning agent, or the spawn is a privilege escalation (a
            // confined parent naming the main / an unrestricted agent to run
            // with its tools or acp). Self-reuse and equal-or-narrower agents
            // pass.
            var escalation = capabilityEscalationError(parentAgent, existing);
            if (escalation != null) return ResolvedChildAgent.fail(escalation);
            // Don't mutate Agent.parent_agent_id on a pre-existing row. The
            // lineage of *this* run is already recorded on the SubagentRun
            // (parentAgentId + childAgentId), and stamping parent_agent_id
            // on the Agent row permanently demotes an operator-created
            // top-level agent into a subagent: it disappears from the
            // Agents page (ApiAgentsController.list filters parentAgent !=
            // null) and the operator can't recreate one with the same
            // name because Agent.name carries a global UNIQUE constraint.
            // For freshly-created child agents (else branch) the FK is
            // still set because those rows are genuinely subagents.
            return ResolvedChildAgent.ok(existing);
        }
        // Clone the parent's runtime config (provider, model, thinkingMode)
        // into a fresh row so the child is its own auditable identity.
        // JCLAW-269: child Agent ALWAYS inherits the parent's defaults; the
        // per-spawn modelProvider/modelId override (when supplied) lands on
        // the child Conversation, not on this row. Keeping the Agent row
        // clean of one-shot overrides means re-running the same child agent
        // later (via agentId) doesn't carry stale per-spawn state.
        var name = buildChildAgentName(parentAgent.name);
        Agent created;
        try {
            // Subagents are delegates of the parent — they inherit the
            // parent's on-disk workspace via AgentService.workspacePath's
            // parent-chain walk and never need their own SOUL / IDENTITY /
            // USER / BOOTSTRAP / AGENT skeleton. The createWorkspace=false
            // arg suppresses the directory + markdown stubs that the
            // operator-facing create paths still produce. Subagent tool
            // calls resolve to the parent's workspace transparently.
            created = AgentService.create(name,
                    parentAgent.modelProvider, parentAgent.modelId,
                    parentAgent.thinkingMode,
                    label != null && !label.isBlank()
                            ? label
                            : "Subagent of " + parentAgent.name,
                    /* createWorkspace */ false);
        } catch (RuntimeException e) {
            return ResolvedChildAgent.fail(
                    "Error: failed to create child agent: " + e.getMessage());
        }
        created.parentAgent = parentAgent;
        created.save();
        return ResolvedChildAgent.ok(created);
    }

    /**
     * JCLAW-500 (Change 3): bound a reused (agentId) child above by the
     * spawning agent. A subagent must not be MORE capable than its parent, or
     * naming an agentId becomes a privilege-escalation hatch (a confined custom
     * agent reusing the main or an unrestricted agent to act with its tools /
     * acp). Enforces child_capabilities ⊆ parent_capabilities:
     * <ul>
     *   <li>every tool the parent has disabled must also be disabled on the
     *       named child (so child_enabled ⊆ parent_enabled);</li>
     *   <li>the child may use the acp runtime only if the parent may.</li>
     * </ul>
     * Shell privileges (global paths / allowlist bypass) are main-only, so an
     * {@code isMain} mismatch is already caught by the tool-subset check — the
     * main agent disables nothing a restricted parent does. Self-reuse and
     * equal-or-narrower agents pass. Returns an error string on escalation, or
     * null when the named agent is within bounds.
     */
    private static String capabilityEscalationError(Agent spawningAgent, Agent named) {
        if (named.id != null && named.id.equals(spawningAgent.id)) {
            return null; // self-reuse is always within bounds
        }
        var parentDisabled = ToolRegistry.loadDisabledTools(spawningAgent);
        var childDisabled = ToolRegistry.loadDisabledTools(named);
        if (!childDisabled.containsAll(parentDisabled)) {
            return ("Error: agentId %d ('%s') is more capable than the spawning agent '%s' "
                    + "(it enables tools the spawning agent has disabled); a subagent may not "
                    + "exceed its parent's tool capabilities.")
                    .formatted(named.id, named.name, spawningAgent.name);
        }
        boolean parentAcp = spawningAgent.isMain() || spawningAgent.acpAllowed;
        boolean childAcp = named.isMain() || named.acpAllowed;
        if (childAcp && !parentAcp) {
            return ("Error: agentId %d ('%s') may use the acp runtime but the spawning agent "
                    + "'%s' may not; a subagent may not exceed its parent's capabilities.")
                    .formatted(named.id, named.name, spawningAgent.name);
        }
        return null;
    }

    /**
     * Resolve the child {@link Conversation}: inline mode reuses the
     * parent's row, session mode creates a fresh row stamped with the
     * per-spawn model override and inherited parent-context summary.
     *
     * <p>Per-spawn model override + inherited parent-context blob only apply
     * to session mode: they live on the *child* Conversation row, and in
     * inline mode that "child" is the parent itself — writing them there
     * would clobber the parent's effective model and context for the rest
     * of the parent's turns. Inline-mode children effectively run with the
     * parent's settings (same model, same prompt assembly); the model
     * override and parent-context summary parameters are no-ops in this
     * mode by design.
     */
    private static Conversation resolveChildConversation(Agent childAgent,
                                                         Conversation parentConv,
                                                         String modelProviderOverride,
                                                         String modelIdOverride,
                                                         String parentContextSummary,
                                                         boolean inlineMode) {
        // JCLAW-267: inline mode reuses the parent Conversation as the child's
        // run target — the SubagentRun row points its childConversation FK at
        // the same row as parentConversation, and AgentRunner persists the
        // child's messages back into the parent transcript stamped with the
        // SubagentRun id (via the ConversationService ThreadLocal marker).
        // No new Conversation row is created. Session mode keeps the existing
        // JCLAW-265 behavior: fresh child Conversation, separate transcript,
        // visible as its own row in the sidebar.
        if (inlineMode) {
            return parentConv;
        }
        // JCLAW-327 AC-5: the child Conversation inherits the parent's
        // channelType + peerId. Two reasons. (a) The new {@link MessageTool}
        // infers its default delivery channel + target from the calling
        // agent's active Conversation; without inheritance, a subagent
        // spawned inside a Telegram thread would default to
        // channelType="subagent" and have nowhere to push progress
        // updates. (b) The /conversations + /subagents UIs filter by
        // {@code parentConversation IS NULL}, so subagent children stay
        // hidden from the main listings regardless of channelType — no UI
        // regression. The {@code SUBAGENT_CHANNEL} constant survives as
        // an EventLogger category tag (see warn() sites below); it's no
        // longer used as a Conversation column value.
        var childConv = ConversationService.create(childAgent,
                parentConv.channelType, parentConv.peerId);
        childConv.parentConversation = parentConv;
        // JCLAW-269 / JCLAW-422: persist the resolved model on the child
        // Conversation so AgentRunner's ModelOverrideResolver picks it up for
        // this run, and so the JCLAW-28 cost dashboard's
        // COALESCE(c.modelProviderOverride, c.agent.modelProvider) attributes
        // spend to the actually-used model. The resolver tracks the model the
        // operator is ACTUALLY using (the parent conversation's override),
        // not just the agent's base — see resolveSubagentModel.
        var resolved = resolveSubagentModel(parentConv, childAgent, modelProviderOverride, modelIdOverride);
        // Only stamp an override when the resolved model DIFFERS from the child
        // agent's base — the plain inherit case keeps a null override (matches
        // prior behavior and the cost dashboard's COALESCE(override, base)).
        if (!Objects.equals(resolved.provider(), childAgent.modelProvider)
                || !Objects.equals(resolved.modelId(), childAgent.modelId)) {
            childConv.modelProviderOverride = resolved.provider();
            childConv.modelIdOverride = resolved.modelId();
        }
        // JCLAW-268: stamp the inherited parent-context summary on the child
        // Conversation. AgentRunner re-injects this into the child's system
        // prompt every turn via SessionCompactor.appendParentContextToPrompt.
        // Null in fresh mode, in the summarization-failure degradation path,
        // and when the parent had no usable history — all of which leave
        // the column null and turn the injection into a no-op.
        if (parentContextSummary != null && !parentContextSummary.isBlank()) {
            childConv.parentContext = parentContextSummary;
        }
        childConv.save();
        return childConv;
    }

    /**
     * JCLAW-422: resolve the model a session subagent runs on. Precedence:
     * <ol>
     *   <li>explicit per-spawn override ({@code modelProvider}/{@code modelId} args);</li>
     *   <li>operator-configured subagent default (Settings → Subagents:
     *       {@code subagent.modelProvider}/{@code subagent.modelId}) — pin every
     *       fan-out to e.g. a cheaper model;</li>
     *   <li>the parent conversation's EFFECTIVE model — its mid-chat override if
     *       the operator switched models, else the spawning agent's base. This is
     *       the fix: subagents track the model you're ACTUALLY using, not just the
     *       agent's base (a chat switched to Qwen used to spawn children onto the
     *       agent's stale lm-studio base).</li>
     * </ol>
     */
    static SubagentModel resolveSubagentModel(Conversation parentConv, Agent childAgent,
                                              String overrideProvider, String overrideId) {
        if (SubagentSpawnTool.notBlank(overrideProvider) && SubagentSpawnTool.notBlank(overrideId)) {
            return new SubagentModel(overrideProvider, overrideId);
        }
        var cfgProvider = ConfigService.get(SubagentSpawnTool.CFG_SUBAGENT_PROVIDER);
        var cfgModel = ConfigService.get(SubagentSpawnTool.CFG_SUBAGENT_MODEL);
        if (SubagentSpawnTool.notBlank(cfgProvider) && SubagentSpawnTool.notBlank(cfgModel)) {
            return new SubagentModel(cfgProvider, cfgModel);
        }
        var provider = parentConv != null && SubagentSpawnTool.notBlank(parentConv.modelProviderOverride)
                ? parentConv.modelProviderOverride : childAgent.modelProvider;
        var modelId = parentConv != null && SubagentSpawnTool.notBlank(parentConv.modelIdOverride)
                ? parentConv.modelIdOverride : childAgent.modelId;
        return new SubagentModel(provider, modelId);
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
            // Skip rows the parent doesn't grant, rows already enabled, or
            // stale rows referencing a removed tool — flipping a stale row
            // would be lying about the registry's current shape.
            if (!row.enabled
                    && !parentDisabled.contains(row.toolName)
                    && isStillRegistered(allRegistered, row.toolName)) {
                row.enabled = true;
                row.save();
                anyFlipped = true;
            }
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
     * JCLAW-495: grant a freshly-cloned subagent the parent's enabled MCP server
     * handles. MCP grouped tools are default-disabled for non-main agents
     * ({@link ToolRegistry#loadDisabledTools}); a delegate subagent needs the
     * same MCP surface as its parent to carry out delegated work, so write an
     * explicit enabled {@link AgentToolConfig} row for every MCP handle the
     * parent grants but the child (by default) does not. Mirrors the operator
     * opt-in write path (a single row keyed by the {@code mcp_<group>} handle).
     * Unlike {@link #unionParentToolGrants}, this <em>creates</em> rows — the
     * child has none for MCP tools (they are disabled-by-default, not by an
     * explicit row), so there is nothing to flip.
     */
    private static void grantParentMcpGrants(Agent parentAgent, Agent childAgent) {
        var parentDisabled = ToolRegistry.loadDisabledTools(parentAgent);
        var childDisabled = ToolRegistry.loadDisabledTools(childAgent);
        boolean anyGranted = false;
        for (var tool : ToolRegistry.listTools()) {
            if (tool.group() == null) continue;                 // MCP-grouped tools only
            if (parentDisabled.contains(tool.name())) continue; // parent doesn't grant it
            if (!childDisabled.contains(tool.name())) continue; // child already has it
            var row = new AgentToolConfig();
            row.agent = childAgent;
            row.toolName = tool.name();
            row.enabled = true;
            row.save();
            anyGranted = true;
        }
        if (anyGranted) {
            ToolRegistry.invalidateDisabledToolsCache(childAgent);
        }
    }

    /**
     * JCLAW-500 (Change 1): copy the parent's explicit tool DENY-rows onto a
     * freshly-cloned child so the child's capability set is bounded above by
     * the parent's. {@link AgentService#create} seeds only the standard
     * non-main defaults (browser, jclaw_api, plus MCP-default-disabled); it
     * does not carry the parent's custom restrictions, so without this a child
     * cloned from a restricted custom agent reverts to the broader non-main
     * baseline and ends up MORE capable than its parent. We copy only the
     * parent's {@code enabled=false} rows (the restrictions) and leave the
     * child's own non-main defaults intact, giving
     * {@code child_disabled = parent_disabled ∪ non-main-default}.
     *
     * <p>Ordering: this runs before {@link #grantParentMcpGrants} and
     * {@link #unionParentToolGrants}. Those only ever widen the child toward
     * the parent's ENABLED set, and {@code unionParentToolGrants} explicitly
     * skips any tool in the parent's disabled set, so the deny-rows copied
     * here survive both. Scoped to freshly-cloned children — the {@code
     * agentId}-reuse path runs an existing agent that already carries its own
     * rows.
     */
    private static void copyParentToolRestrictions(Agent parentAgent, Agent childAgent) {
        var childByName = new HashMap<String, AgentToolConfig>();
        for (var r : AgentToolConfig.findByAgent(childAgent)) childByName.put(r.toolName, r);
        boolean any = false;
        for (var pr : AgentToolConfig.findByAgent(parentAgent)) {
            if (pr.enabled) continue;                       // copy restrictions only
            var existing = childByName.get(pr.toolName);
            if (existing == null) {
                var row = new AgentToolConfig();
                row.agent = childAgent;
                row.toolName = pr.toolName;
                row.enabled = false;
                row.save();
                any = true;
            } else if (existing.enabled) {
                existing.enabled = false;
                existing.save();
                any = true;
            }
        }
        if (any) ToolRegistry.invalidateDisabledToolsCache(childAgent);
    }

    private static boolean isStillRegistered(List<ToolRegistry.Tool> registered, String toolName) {
        for (var t : registered) {
            if (t.name().equals(toolName)) return true;
        }
        return false;
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

    static Conversation resolveParentConversation(Long parentAgentId) {
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
}
