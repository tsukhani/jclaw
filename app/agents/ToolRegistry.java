package agents;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import llm.LlmTypes.ToolDef;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import services.LoadTestRunner;
import services.Tx;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available tools. Tools are registered at startup and made available
 * to agents. Handles tool execution with error catching.
 * <p>
 * Thread-safe: callers build a list of tools locally and publish atomically
 * via {@link #publish(List)}. No shared mutable buffer is needed.
 */
public class ToolRegistry {

    /**
     * JCLAW-170: rich tool output used by {@link #executeRich}.
     *
     * @param text           the string the LLM sees (same shape as
     *                       {@link Tool#execute})
     * @param structuredJson optional JSON payload the UI can render richly
     *                       (e.g. search-result chips with favicons) —
     *                       persisted to {@code message.tool_result_structured}
     *                       so re-opening a conversation keeps the richer
     *                       render. {@code null} means "no structured view".
     */
    public record ToolResult(String text, String structuredJson, List<GeneratedAttachment> attachments,
                             VideoJobRef videoJob, Outcome outcome) {

        /**
         * Whether the registry actually handed this call to a tool (JCLAW-883).
         *
         * <p>Without it, "the tool ran and reported a problem" and "the tool never
         * ran" are both just a {@code text} beginning with "Error:", separable only
         * by matching on that prose. The eval capture needs them apart — a model
         * that invents three tool names and executes nothing has not called three
         * tools — and so does anything else that later wants to count real work.
         */
        public enum Outcome {
            /** Handed to the tool. Its {@code text} is the tool's own output, success or failure. */
            DISPATCHED,
            /** No tool of that name is registered — typically a name the model invented. */
            UNKNOWN_TOOL,
            /** Registered, but not among the tools this turn offered the model. */
            NOT_ENABLED,
            /**
             * Registered and offered, but the arguments were absent or unparseable, so the
             * registry rejected the call before any tool ran — almost always a call truncated
             * by the model's output-token limit. Distinct from {@code DISPATCHED} because the
             * tool never executed: counting a truncated call as work inflates {@code toolsCalled}
             * and spends a {@link ToolResultVerifier} check on a call that never happened.
             */
            INVALID_ARGS
        }

        /** Back-compat 2-arg form — most tools produce no inline attachment. */
        public ToolResult(String text, String structuredJson) {
            this(text, structuredJson, List.of(), null, Outcome.DISPATCHED);
        }
        public ToolResult {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            outcome = outcome == null ? Outcome.DISPATCHED : outcome;
        }

        /** 4-arg form: a dispatched result. Refusals use {@link #refused}. */
        public ToolResult(String text, String structuredJson, List<GeneratedAttachment> attachments, VideoJobRef videoJob) {
            this(text, structuredJson, attachments, videoJob, Outcome.DISPATCHED);
        }

        public static ToolResult text(String text) { return new ToolResult(text, null, List.of(), null); }

        /** A call the registry declined to dispatch, and why. */
        public static ToolResult refused(String text, Outcome outcome) {
            return new ToolResult(text, null, List.of(), null, outcome);
        }

        /** True when a tool actually ran — the signal {@code toolsCalled} is built from. */
        public boolean dispatched() { return outcome == Outcome.DISPATCHED; }
        /** JCLAW-228: a tool ({@code generate_image}) that produced an image to inline on the
         *  assistant turn. The commit path ({@link ParallelToolExecutor}) attaches it to
         *  the assistant message via {@link AgentExecutionSink}; the model still sees {@code text}. */
        public static ToolResult withImage(String text, String structuredJson, GeneratedAttachment image) {
            return new ToolResult(text, structuredJson, image == null ? List.of() : List.of(image), null);
        }
        /** JCLAW-562: a tool that produced several inline attachments (the diarize_audio
         *  extract action's per-speaker voice clips). All land on the one assistant turn
         *  that carried the tool call. */
        public static ToolResult withAttachments(String text, String structuredJson, List<GeneratedAttachment> attachments) {
            return new ToolResult(text, structuredJson, attachments, null);
        }
        /** JCLAW-235: a tool ({@code generate_video}) that submitted an async job. The commit path
         *  creates a placeholder MessageAttachment linked to the job on the assistant turn (JCLAW-234);
         *  the model sees {@code text} (a "generating, appears when ready" confirmation). */
        public static ToolResult withVideoJob(String text, Long jobId, String generationMetadata) {
            return new ToolResult(text, null, List.of(), new VideoJobRef(jobId, generationMetadata));
        }
    }

    /** JCLAW-235: reference to a submitted video-generation job, carried on a {@link ToolResult} so the
     *  tool-call commit path can create a placeholder attachment linked to it on the assistant turn. */
    public record VideoJobRef(Long jobId, String generationMetadata) {}

    public interface Tool {
        String name();
        String description();
        Map<String, Object> parameters();
        String execute(String argsJson, Agent agent);

        /**
         * JCLAW-170: rich-output variant. Defaults to wrapping {@link #execute} in a
         * text-only {@link ToolResult}; tools that want the UI to render a richer
         * view (clickable result chips, favicons, previews) override this and
         * supply a structured JSON payload alongside the LLM-visible text.
         *
         * @param argsJson the JSON arguments string the model sent
         * @param agent    the executing agent
         * @return the rich tool result
         */
        default ToolResult executeRich(String argsJson, Agent agent) {
            return ToolResult.text(execute(argsJson, agent));
        }

        /**
         * JCLAW-836: a check this tool can make on its OWN result that the generic
         * checks in {@link ToolResultVerifier} cannot. Return the reason the result
         * indicates failure, or empty when nothing is wrong. Costs no model call.
         *
         * <p>This lives on the tool because only the tool knows what its own fields
         * mean. {@code exec} is the worked example: it reports {@code exitCode: -1}
         * both when it killed a process on timeout AND when it deliberately left one
         * running for the user to interact with. A generic checker reading exit codes
         * would flag the second as a failure and be confidently wrong — the same
         * defect class this whole layer exists to catch, relocated into the catcher.
         *
         * <p>Called only for a DISPATCHED result that already passed the generic
         * checks, so implementations do not re-check for blank text or the
         * {@code "Error…"} prose convention. They should return empty rather than
         * throw on anything unexpected; a throw is caught and logged upstream, but it
         * costs the turn its verification count.
         *
         * @param result the result this tool just produced
         * @return why the result indicates failure, or empty if it does not
         */
        default Optional<String> postConditionFailure(ToolResult result) {
            return Optional.empty();
        }

        /** Short one-line summary for the system prompt tool catalog. Defaults to
         *  the full description, but tools with multi-sentence descriptions should
         *  override this to prevent the LLM from misreading action names or internal
         *  details as top-level tool names. */
        default String summary() { return description(); }

        /** Taxonomy bucket used to group tools in the system-prompt Tool Catalog.
         *  Must be one of {@code "System"}, {@code "Files"}, {@code "Web"},
         *  {@code "Utilities"} — matching the {@code CANONICAL_CATEGORY_ORDER}
         *  list exposed by {@link ToolCatalog}. Defaults to {@code "Utilities"}
         *  so a tool that forgets to override still renders somewhere sensible. */
        default String category() { return "Utilities"; }

        /** Semantic icon key consumed by the admin UI's SVG dictionary (e.g.
         *  {@code "terminal"}, {@code "folder"}, {@code "globe"}). The backend
         *  does not know what pixels render from this — it only emits the key and
         *  the frontend resolves it to an SVG {@code <path>}. Defaults to
         *  {@code "wrench"}, the generic fallback icon. */
        default String icon() { return "wrench"; }

        /** User-facing blurb rendered in the admin UI tool cards. Richer than
         *  the function-calling {@link #description()} that goes to the LLM —
         *  describes the tool's purpose for a human browsing the /tools page.
         *  Defaults to {@link #summary()}. */
        default String shortDescription() { return summary(); }

        /** Enumerated actions this tool exposes. The admin UI renders these as
         *  the "Functions" disclosure under each tool card. Convention: every
         *  tool declares at least one — multi-action tools mirror the
         *  {@code action} enum in {@link #parameters()} exactly, and
         *  single-action tools describe their one function with a short verb
         *  name (see {@code exec}, {@code web_search}). The empty default
         *  exists only so a forgotten override fails visibly as "Functions 0"
         *  rather than breaking registration. */
        default List<ToolAction> actions() { return List.of(); }

        /** Runtime config key (in {@code ConfigService}) that must be truthy for
         *  this tool to be usable. The admin UI gates the "Enable" toggle on
         *  this. {@code null} means no runtime dependency. */
        default String requiresConfig() { return null; }

        /** Safe to invoke concurrently from multiple virtual threads on behalf
         *  of the SAME agent in a SINGLE round?
         *
         *  <p>The default is {@code false} — under {@link agents.AgentRunner}'s
         *  JCLAW-80 scheduler, multiple calls to a non-parallel-safe tool in
         *  one round run sequentially in the LLM's declared order on a single
         *  virtual thread. This is the conservative position that matches
         *  OpenClaw, JavaClaw, and most production agent frameworks.
         *
         *  <p>A tool should override this to {@code true} only when it holds
         *  <em>no shared state</em> — no workspace file I/O, no long-lived
         *  handles (browser Page, shell process), no non-idempotent DB writes.
         *  Parallel-safe tools get one virtual thread per call, so calls race
         *  freely. Stateless HTTP clients ({@code web_fetch}, {@code
         *  web_search}), pure-compute helpers ({@code date_time}), and
         *  validating-only tools ({@code checklist}) are the typical shape.
         *
         *  <p>Getting this wrong is a correctness bug, not a performance
         *  tradeoff: the screenshot-before-navigate class of race
         *  (JCLAW-80). When in doubt, leave it {@code false}. */
        default boolean parallelSafe() { return false; }

        /** JCLAW-382: does this tool perform a sensitive or irreversible
         *  action (a shell command, a spend, sending on the user's behalf)
         *  that warrants an interactive approve/deny gate before it runs?
         *
         *  <p>The default is {@code false} — the overwhelming majority of
         *  tools are reversible reads or scoped writes that need no gate.
         *  When a tool returns {@code true} AND the running agent is bound to
         *  a channel that supports interactive approval (today: Telegram),
         *  {@link DangerousActionGate} raises an approve/deny prompt and
         *  blocks the dispatch on the user's decision. On every other channel
         *  — and for every non-dangerous tool — the gate is a no-op, so this
         *  flag never changes behavior outside the Telegram-bound path.
         *
         *  <p>Native {@code exec} ({@code ShellExecTool}) is the canonical
         *  always-dangerous tool. JCLAW-388 extends the gate to MCP: a
         *  server-level handle ({@code mcp.McpServerTool}) and its per-action
         *  adapters ({@code mcp.McpToolAdapter}) resolve this flag from their
         *  {@link models.McpServer#requiresApproval} column, so an operator can
         *  opt a whole MCP server into the same approve/deny prompt. The flag
         *  defaults off per server, so unflagged servers stay un-gated. */
        default boolean dangerous() { return false; }

        /** JCLAW-844: per-call danger classification. Most tools are uniformly
         *  dangerous (or not) regardless of arguments, so this defaults to the
         *  argument-blind {@link #dangerous()}. A tool whose danger depends on the
         *  specific call overrides it to inspect {@code argsJson} — {@code jclaw_api}
         *  is a mutation (and thus dangerous) only for POST/PUT/PATCH/DELETE, and a
         *  plain read otherwise. The gate passes the same wire-format args the tool
         *  will receive, so the classification matches the call that would run. */
        default boolean dangerous(String argsJson) { return dangerous(); }

        /** Serialization-group key for the parallel tool dispatcher. Tools
         *  returning the same non-null key are placed in the same serial
         *  queue and execute in LLM-declared order on a single virtual
         *  thread — even when their {@link #name() name}s differ.
         *
         *  <p>The default mirrors the pre-JCLAW-291 behavior: parallel-safe
         *  tools are not grouped at all (each call gets its own VT), while
         *  non-parallel-safe tools group by their own name (multiple calls
         *  to the same tool serialize, calls to different tools parallelize).
         *
         *  <p>Override when two distinct tools share state that races at the
         *  DB or in-memory layer. The {@code subagent_spawn} + {@code
         *  subagent_yield} pair is the motivating case: yield reads the
         *  SubagentRun row that spawn just inserted, so they must serialize
         *  even when the LLM emits both in one assistant message. Both
         *  return the same {@code "subagent_lifecycle"} key from this
         *  method, forcing them onto one serial queue. */
        default String serializationGroup() {
            return parallelSafe() ? null : name();
        }

        /** Optional grouping key for the {@code /tools} admin page. Tools
         *  sharing the same {@code group} render as a single card with
         *  their {@link #actions()} folded together. Used by
         *  {@code McpToolAdapter} (returns the server name) so the 72
         *  tools advertised by one MCP server display as one card titled
         *  with the server name, instead of 72 separate cards. Native
         *  tools default to {@code null} (one card per tool). */
        default String group() { return null; }

        /** JCLAW-281: marks a tool as the server-level handle for its
         *  {@link #group()}, i.e. the single parameterized entry that
         *  represents an MCP server in the LLM's function-calling schema.
         *  Tools with a non-null {@code group()} that return {@code false}
         *  here (the per-action MCP adapters) stay in the registry for
         *  execution lookups but are hidden from
         *  {@link #getToolDefsForAgent} — the server-level handle is the
         *  sole face the model sees. Native tools and tools without a
         *  group return {@code false} by default; their visibility is
         *  unaffected. */
        default boolean isServerLevel() { return false; }
    }

    /** JCLAW-281: by-name lookup used by server-level handles to delegate
     *  parameterized invocations to the per-action adapter that already
     *  carries the allowlist gate + audit trail. Returns {@code null} when
     *  no tool with that name is registered. */
    public static Tool lookupTool(String name) {
        return tools.get(name);
    }

    /** Native (compile-time) tools published via {@link #publish(List)} from
     *  {@link jobs.ToolRegistrationJob}. Kept separate from external groups so
     *  re-registering natives (e.g. when {@code provider.loadtest-mock.enabled}
     *  flips at runtime) doesn't blow away MCP-discovered tools. */
    // Reference is replaced atomically with an unmodifiableMap; the held map
    // is immutable so volatile-on-reference is sufficient publication.
    @SuppressWarnings("java:S3077")
    private static volatile Map<String, Tool> nativeTools = Map.of();

    /** Externally-sourced tool groups, keyed by group name (e.g. an MCP server
     *  name). Each group's tools are published atomically together via
     *  {@link #publishExternal(String, List)} and removed atomically via
     *  {@link #unpublishExternal(String)}. */
    private static final ConcurrentHashMap<String, Map<String, Tool>> externalGroups =
            new ConcurrentHashMap<>();

    /** Merged view of native + external tools. Recomputed under
     *  {@link #rebuildLock} on every change and replaces this volatile field
     *  in one assignment so readers see a consistent snapshot. The existing
     *  reader code path ({@code tools.values()}, {@code tools.get(name)}) is
     *  unchanged — this field carries the merged view under the same name. */
    private static volatile Map<String, Tool> tools = Map.of();

    private static final Object rebuildLock = new Object();

    /**
     * Atomically publish a new tool set built by the caller. Uses LinkedHashMap
     * to preserve registration order — iteration stability matters for LLM
     * prompt caching, which hashes the serialized tools array as part of the prefix.
     */
    public static void publish(List<Tool> toolList) {
        var map = new LinkedHashMap<String, Tool>();
        for (var tool : toolList) {
            map.put(tool.name(), tool);
        }
        nativeTools = Collections.unmodifiableMap(map);
        rebuildMerged();
    }

    /**
     * Publish a group of externally-sourced tools (JCLAW-31: one MCP server's
     * discovered tools). Replaces any prior tools for the same {@code group}.
     * Naming: callers SHOULD prefix tool names so they can't shadow native
     * tools — {@link mcp.McpToolAdapter} uses {@code mcp_<server>_<tool>}.
     * Last writer wins on collisions; native tools are merged first so
     * external tools can override (intentional — useful for testing).
     */
    public static void publishExternal(String group, List<Tool> toolList) {
        var map = new LinkedHashMap<String, Tool>();
        for (var tool : toolList) map.put(tool.name(), tool);
        externalGroups.put(group, Collections.unmodifiableMap(map));
        rebuildMerged();
        // The per-agent disabled-tools set folds in the "MCP defaults to disabled
        // for custom agents" policy by walking the live tool registry. When that
        // registry changes (server connects, list_changed adds tools), the cached
        // per-agent set goes stale — bust it so next read recomputes.
        clearDisabledToolsCache();
    }

    /** Remove all tools published under {@code group}. No-op if the group is
     *  unknown. Used when an MCP server disconnects. */
    public static void unpublishExternal(String group) {
        if (externalGroups.remove(group) != null) {
            rebuildMerged();
            clearDisabledToolsCache();
        }
    }

    private static void rebuildMerged() {
        synchronized (rebuildLock) {
            var merged = new LinkedHashMap<String, Tool>(nativeTools);
            for (var groupMap : externalGroups.values()) merged.putAll(groupMap);
            tools = Collections.unmodifiableMap(merged);
        }
    }

    public static List<ToolDef> getToolDefs() {
        return tools.values().stream()
                .map(t -> ToolDef.of(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /** Resolve a tool's {@link Tool#serializationGroup()} key by name. Returns
     *  {@code null} for unknown names AND for tools whose own
     *  {@code serializationGroup()} is {@code null} (the parallel-safe case);
     *  used by {@link agents.ParallelToolExecutor} to merge cross-name serial
     *  queues for the {@code subagent_spawn}/{@code subagent_yield} pair
     *  and similar future cases. For unknown tools we fall back to the tool
     *  name as the group key so a typo can't accidentally unlock parallelism. */
    public static String serializationGroupFor(String toolName) {
        var tool = tools.get(toolName);
        if (tool == null) return toolName;
        return tool.serializationGroup();
    }

    /** JCLAW-382: resolve a tool's {@link Tool#dangerous()} flag by name,
     *  defaulting to {@code false} for unknown names. Used by
     *  {@link DangerousActionGate} to decide whether a dispatch needs an
     *  interactive approve/deny gate. */
    public static boolean isDangerous(String toolName) {
        var tool = tools.get(toolName);
        return tool != null && tool.dangerous();
    }

    /** JCLAW-844: per-call danger resolution — passes {@code argsJson} to the tool
     *  so a tool whose danger depends on its arguments (today {@code jclaw_api})
     *  classifies per call. Defaults to {@code false} for unknown names, matching
     *  {@link #isDangerous(String)}. */
    public static boolean isDangerous(String toolName, String argsJson) {
        var tool = tools.get(toolName);
        return tool != null && tool.dangerous(argsJson);
    }

    /** JCLAW-170: resolve a tool's semantic icon key by name. Returns the
     *  registered tool's {@link Tool#icon} hint, or {@code "wrench"} for
     *  unknown/unregistered names. Used by the agent loop to stamp every
     *  {@code tool_call} SSE frame with the icon hint the UI renders. */
    public static String iconFor(String toolName) {
        var tool = tools.get(toolName);
        return tool != null ? tool.icon() : "wrench";
    }

    /**
     * JCLAW-883: refuse a call for a tool this turn did not offer the model.
     *
     * <p>{@code offered} is the name set from {@link #getToolDefsForAgent}, computed
     * once in the turn's prologue where a transaction exists and threaded down to
     * the dispatch point. It is NOT recomputed here: tool calls run on their own
     * virtual threads with no JPA transaction open — "no JDBC connection is held
     * during LLM HTTP calls or tool execution" — so reading per-agent config at this
     * point throws {@code No active EntityManager} and kills the dispatch.
     *
     * <p>Why the guard is needed at all: {@link #loadDisabledTools} used to be
     * consulted only when building the schema, so per-agent tool config hid a tool
     * from the model without stopping it running. The first {@code __evaltest__}
     * sweep — against an agent granted nothing — guessed {@code web_search} and it
     * executed and returned live results; three sibling guesses ({@code httpFetch},
     * {@code http_fetch}, {@code webSearch}) failed only because those names do not
     * exist. The same hole left {@code generate_image} / {@code generate_video} /
     * {@code generate_audio}, default-off precisely because they cost money and
     * time, reachable on any agent by a lucky guess.
     *
     * <p>"Only what was offered" rather than "not disabled" because it needs no
     * lookup and covers both holes with one rule. Grouped (MCP) per-action adapters
     * are unaffected: {@code McpServerTool} invokes them object-directly and never
     * resolves them through this registry, so they never reach this check. The
     * server-level {@code mcp_<group>} handle IS offered and IS checked, which is
     * correct — an MCP server switched off for an agent should not run.
     *
     * <p>{@code null} means unrestricted, for callers that are not dispatching a
     * model-chosen call (tests, internal invocation).
     */
    private static boolean notOffered(String toolName, Set<String> offered) {
        return offered != null && !offered.contains(toolName);
    }

    /** Phrased so the model stops rather than retrying, and without enumerating what it cannot see. */
    private static String notEnabledError(String toolName) {
        return ("Error: Tool '%s' is not enabled for this agent. Do not retry it — "
                + "use only the tools listed in your request.").formatted(toolName);
    }

    /**
     * The dispatch path. JCLAW-170 made it rich-output: on success it returns the tool's
     * {@link ToolResult}, so callers that want the structured JSON payload (UI surfaces)
     * get it alongside the LLM-visible text. Tools that don't override
     * {@link Tool#executeRich} fall back to a text-only result.
     *
     * <p>Dispatch is restricted to {@code offered} — the tool names this turn actually put
     * in front of the model (JCLAW-883). Pass {@code null} to skip the restriction; every
     * model-driven dispatch passes the real set.
     *
     * <p>Every rejection here carries a non-{@code DISPATCHED} {@link ToolResult.Outcome},
     * because nothing downstream can recover that distinction from the text alone.
     */
    public static ToolResult executeRich(String toolName, String argsJson, Agent agent, Set<String> offered) {
        var tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.refused("Error: Unknown tool '%s'".formatted(toolName),
                    ToolResult.Outcome.UNKNOWN_TOOL);
        }
        if (notOffered(toolName, offered)) {
            return ToolResult.refused(notEnabledError(toolName), ToolResult.Outcome.NOT_ENABLED);
        }
        // Defense-in-depth: validate the args JSON is parseable BEFORE invoking the
        // tool. When the LLM hits its output-token budget mid-tool-call, the
        // streaming accumulator emits a truncated arguments string (e.g. for
        // writeDocument: "{\"action\":\"writeDocument\",\"path\":\"foo.docx\"" — no
        // closing brace, no content field). The per-round truncation guards in
        // AgentRunner catch finish_reason="length"/"max_tokens", but some providers
        // (notably OpenRouter's Bedrock route for Anthropic models) can end a
        // stream without a clear finish_reason, letting the malformed call reach
        // the tool. Gson then throws EOFException deep inside the tool and the LLM
        // sees a cryptic "End of input at line 1 column N" error that doesn't
        // teach it to retry with smaller content. Pre-validate here and return a
        // message the LLM can actually act on.
        if (argsJson == null || argsJson.isEmpty()) {
            return ToolResult.refused(
                    "Error: Tool '%s' received empty arguments. The model's response was likely truncated before the tool call completed. Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call."
                            .formatted(toolName),
                    ToolResult.Outcome.INVALID_ARGS);
        }
        try {
            JsonParser.parseString(argsJson);
        } catch (JsonSyntaxException e) {
            return ToolResult.refused(
                    "Error: Tool '%s' received malformed arguments (likely truncated by the model's output token limit). Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call. Parse error: %s"
                            .formatted(toolName, e.getMessage()),
                    ToolResult.Outcome.INVALID_ARGS);
        }
        try {
            return tool.executeRich(argsJson, agent);
        } catch (Exception e) {
            return ToolResult.text("Error executing tool '%s': %s".formatted(toolName, e.getMessage()));
        }
    }

    /**
     * Text-only view of {@link #executeRich}. There is no production caller — the live
     * dispatch path is {@link ParallelToolExecutor} via {@code executeRich} — but tests that
     * only assert on the model-visible text read better through this. It delegates so the
     * guard chain has exactly one definition; the two variants previously duplicated it,
     * error prose included.
     *
     * <p>Equivalent to the rich path for every registered tool: non-overriders inherit
     * {@link Tool#executeRich}'s default, which wraps {@code execute}, and every tool that
     * does override it already implements {@code execute} as {@code executeRich(...).text()}.
     */
    public static String execute(String toolName, String argsJson, Agent agent) {
        return executeRich(toolName, argsJson, agent, null).text();
    }

    /** Get tool definitions filtered by agent's tool configuration. */
    public static List<ToolDef> getToolDefsForAgent(Agent agent) {
        return getToolDefsForAgent(agent, (Conversation) null);
    }

    /**
     * Get tool definitions filtered by agent config.
     *
     * <p>Native tools (group=null) ship every turn — they're predictable
     * workhorses with bounded count. MCP servers (group=server name) ship
     * exactly one parameterized entry each via {@link mcp.McpServerTool}; the
     * per-action {@code McpToolAdapter} wrappers stay in the registry for
     * the execution path but are hidden from these defs (JCLAW-281).
     *
     * <p>The {@code conv} parameter is retained for binary compatibility
     * but no longer drives a lazy gate: the parameterized handle is small
     * (one entry per server) and is always emitted when the server is
     * connected, so the model can call it (with empty args) to bootstrap
     * its action catalog. The pre-JCLAW-281 design gated per-action
     * adapters on a history scan for {@code list_mcp_tools} calls; the
     * new design folds that bootstrap into every server's own surface.
     */
    @SuppressWarnings("java:S1172") // conv retained for binary compatibility per Javadoc (JCLAW-281)
    public static List<ToolDef> getToolDefsForAgent(Agent agent, Conversation conv) {
        return getToolDefsForAgent(agent, Set.<String>of());
    }

    /**
     * Pure variant. The {@code discoveredMcpServers} parameter is retained
     * for binary compatibility with tests but no longer used as a filter
     * input — JCLAW-281 made the server-level handle always-emitted.
     */
    @SuppressWarnings("java:S1172") // discoveredMcpServers retained for binary/test compatibility per Javadoc (JCLAW-281)
    public static List<ToolDef> getToolDefsForAgent(Agent agent, Set<String> discoveredMcpServers) {
        // Loadtest agent: ship zero tools so cross-provider tokens-per-second
        // benchmarks measure pure model speed. Sending a populated tools
        // array adds ~2-3 KB of prefill on every request AND tempts the
        // model to invoke a tool instead of answering the benchmark prompt,
        // either of which derails the comparison.
        if (agent != null
                && LoadTestRunner.LOADTEST_AGENT_NAME.equals(agent.name)) {
            return List.of();
        }
        // Single-tool benchmark agent: expose ONLY loadtest_sleep (registered
        // for the duration of a tools loadtest) so a real provider exercises
        // exactly one deterministic tool round. Empty if the tool isn't
        // currently registered, so nothing leaks outside a tools run.
        if (agent != null
                && LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME.equals(agent.name)) {
            var sleep = tools.get("loadtest_sleep");
            return sleep == null ? List.of()
                    : List.of(ToolDef.of(sleep.name(), sleep.description(), sleep.parameters()));
        }
        var disabled = loadDisabledTools(agent);
        // JCLAW-281: function-calling defs emit at most one entry per MCP
        // server (the server-level handle from McpServerTool). The per-
        // action adapters stay in the registry as the execution path, but
        // hiding them here saves the schema from N-tools-per-server bloat
        // and removes the need for the legacy list_mcp_tools discovery
        // primitive — discovery is now part of the server-level handle's
        // own surface (empty args returns the action catalog).
        return tools.values().stream()
                .filter(t -> !disabled.contains(t.name()))
                .filter(t -> t.group() == null || t.isServerLevel())
                .map(t -> ToolDef.of(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * Per-agent cache of disabled-tool names. The write path for AgentToolConfig is
     * a single endpoint ({@link controllers.ApiToolsController}) which calls
     * {@link #invalidateDisabledToolsCache} after every toggle. Every other caller
     * is a read, and each streaming turn reads once, so caching here eliminates a
     * DB round-trip per turn. Keyed by agent ID; values are unmodifiable to guard
     * against callers accidentally mutating the cached set. JCLAW-203 moved from
     * a hand-rolled ConcurrentHashMap to Caches.named — Hibernate L2 doesn't
     * cover this path because the value is a derived projection (Set of disabled
     * tool names) computed across multiple AgentToolConfig rows, not a single
     * entity by ID.
     */
    private static final Cache<Long, Set<String>> DISABLED_TOOLS_CACHE = Caches.named(
            "agent-disabled-tools",
            CacheConfig.newBuilder()
                    .maximumSize(1000)
                    // JCLAW-1042 (VULN-099): size-only eviction meant an entry poisoned by a
                    // racing read could feed the JCLAW-883 execution guard for the life of the
                    // JVM. A TTL bounds that to seconds even if an ordering bug returns.
                    .expireAfterWrite(Duration.ofSeconds(30))
                    .build());

    /**
     * Load the set of disabled tool names for an agent. Cached per agent; the cache
     * is invalidated whenever an {@link models.AgentToolConfig} row is written via
     * {@link #invalidateDisabledToolsCache}, and broadly via
     * {@link #clearDisabledToolsCache} whenever the registry's grouped tools change
     * (so the per-agent default-disable for new MCP tools picks up immediately).
     *
     * <p><b>Default policy.</b> Native tools are enabled by default; explicit
     * {@link models.AgentToolConfig} rows override. <em>Grouped tools (MCP, the
     * only current source of {@link Tool#group()}) flip the default for non-main
     * agents</em>: with no config row, custom agents see them as disabled. Operators
     * opt-in by toggling them on per-(agent, MCP server) in the agent detail page,
     * which writes an explicit AgentToolConfig row with {@code enabled=true}.
     */
    public static Set<String> loadDisabledTools(Agent agent) {
        if (agent == null || agent.id == null) {
            // Unsaved agents have no configs; treat as "nothing disabled."
            return Set.of();
        }
        return DISABLED_TOOLS_CACHE.get(agent.id, _ -> computeDisabledTools(agent));
    }

    /** JCLAW-228: {@code generate_image} is default-OFF for every agent (opt-in per agent). */
    private static final String GENERATE_IMAGE_TOOL = "generate_image";
    /** JCLAW-235: {@code generate_video} is default-OFF for every agent (opt-in, like generate_image). */
    private static final String GENERATE_VIDEO_TOOL = "generate_video";
    /** JCLAW-876: {@code generate_audio} is default-OFF for every agent (opt-in) — speaking a
     *  reply costs seconds of synthesis and can trigger a sidecar model load. */
    private static final String GENERATE_AUDIO_TOOL = "generate_audio";
    /** JCLAW-911: {@code printer} is default-OFF for every agent (opt-in). The others on this
     *  list are gated on cost or latency; this one is gated on being irreversible in the
     *  physical world — paper leaves a device in someone's room and no undo exists. If
     *  spending a few cents on an image warrants opt-in, so does that. */
    private static final String PRINTER_TOOL = "printer";
    /** JCLAW-919: {@code memory} follows the {@code jclaw_api} main/non-main split rather
     *  than the blanket opt-in above. Its {@code forget} action hard-deletes rows with no
     *  undo, which is why a non-main agent has to be granted it — but main is the operator's
     *  own agent, the one being asked to remember and forget things, and it already holds
     *  broader authority than this. Gating it there would leave "forget that" unanswerable
     *  in the conversation where it is actually said. An explicit disable row still wins. */
    private static final String MEMORY_TOOL = "memory";
    /** JCLAW-941: {@code jclaw_api} can drive JClaw's own admin API, so only the main agent
     *  gets it without being granted it. Computed here rather than seeded as a disable row at
     *  agent creation (JCLAW-282): a seeded row cannot cover an agent that already existed,
     *  an agent created outside AgentService.create, or a row later deleted — this instance
     *  had two agents holding no row and therefore full access. Same reasoning JCLAW-883
     *  recorded for the eval agent. An explicit enable row still wins, so granting it to a
     *  purpose-built agent stays a single click. */
    private static final String JCLAW_API_TOOL = "jclaw_api";

    /** JCLAW-1065: reads conversation history beyond the current turn, so it sits
     *  with the other cross-turn-reach tools rather than defaulting on. */
    private static final String CONVERSATION_SEARCH_TOOL = "conversation_search";

    private static Set<String> computeDisabledTools(Agent agent) {
        var configs = AgentToolConfig.findByAgent(agent);
        var explicitState = new HashMap<String, Boolean>();
        // handle() derives an MCP row's name from the server it points at (JCLAW-983), so a
        // renamed server's grants keep matching without a row ever being rewritten.
        for (var c : configs) explicitState.put(c.handle(), c.enabled);

        var disabled = new HashSet<String>();
        for (var entry : explicitState.entrySet()) {
            if (Boolean.FALSE.equals(entry.getValue())) disabled.add(entry.getKey());
        }
        // JCLAW-883: the eval agent inverts the default — every tool is opt-in, not
        // just the costly ones below. An eval sweep runs unattended and its suites
        // deliberately provoke tool selection, so a tool nobody granted must not be
        // reachable: the first live sweep ran against `main` with the full surface
        // and created a real recurring task. Inverting here rather than seeding
        // disabled rows at provisioning also means a tool added to the registry
        // later is off for this agent too, instead of silently re-opening the hole.
        if (agent.isEvalTest()) {
            for (var tool : tools.values()) {
                if (!Boolean.TRUE.equals(explicitState.get(tool.name()))) disabled.add(tool.name());
            }
            return Collections.unmodifiableSet(disabled);
        }
        disableUnlessGranted(disabled, explicitState, OPT_IN_FOR_EVERY_AGENT);
        if (!agent.isMain()) {
            disableUnlessGranted(disabled, explicitState, OPT_IN_FOR_NON_MAIN_AGENTS);
            addMcpDefaultDisabled(disabled, explicitState);
        }
        return Collections.unmodifiableSet(disabled);
    }

    /**
     * Tools every agent has to be granted explicitly. Each constant above carries the
     * reason it is on this list — cost, latency, or an action with no undo.
     */
    private static final List<String> OPT_IN_FOR_EVERY_AGENT = List.of(
            GENERATE_IMAGE_TOOL, GENERATE_VIDEO_TOOL, GENERATE_AUDIO_TOOL, PRINTER_TOOL);

    /**
     * Tools main holds by default and every other agent has to be granted. The split is
     * trust, not cost: both reach beyond the turn they run in — one into JClaw's own admin
     * API, one into stored memory — and main is the operator's own agent.
     */
    private static final List<String> OPT_IN_FOR_NON_MAIN_AGENTS = List.of(
            MEMORY_TOOL, JCLAW_API_TOOL, CONVERSATION_SEARCH_TOOL);

    /** Disables each named tool that carries no explicit enable row — the opt-in default. */
    private static void disableUnlessGranted(Set<String> disabled,
                                             Map<String, Boolean> explicitState,
                                             List<String> optIn) {
        for (var name : optIn) {
            if (!Boolean.TRUE.equals(explicitState.get(name))) disabled.add(name);
        }
    }

    /**
     * MCP tools default-disabled for non-main agents (operator opts-in per
     * server via PUT /api/agents/:id/tool-groups/:group). MCP enablement is
     * server-level only: {@code updateGroupForAgent} writes a single
     * {@link AgentToolConfig} row for the server with an empty action, and the
     * LLM's schema only exposes the server-level handle, so there's no
     * per-action bridging to do — every grouped tool without an explicit row
     * gets added to {@code disabled}, full stop.
     */
    private static void addMcpDefaultDisabled(HashSet<String> disabled, HashMap<String, Boolean> explicitState) {
        for (var tool : tools.values()) {
            if (tool.group() != null && !explicitState.containsKey(tool.name())) {
                disabled.add(tool.name());
            }
        }
    }

    /**
     * Invalidate the cached disabled-tools set for a specific agent, immediately.
     *
     * <p>Ordering is the caller's to choose, and deliberately not forced here (JCLAW-1042).
     * Callers whose grant write shares the ambient transaction — the two
     * {@code ApiToolsController} toggles — must wrap this in {@link Tx#afterCommit} so a
     * concurrent tool call cannot recompute the set from the pre-commit rows and re-cache it.
     * Callers that already committed independently must NOT: {@code EvalCapture.calibrate}
     * writes through {@code commitInFreshTx} and {@code SubagentChildBootstrap} flips grants
     * for a child whose next turn must see them, so deferring either to an unrelated
     * transaction's commit would leave a durable write behind a stale cache.
     */
    public static void invalidateDisabledToolsCache(Agent agent) {
        if (agent != null && agent.id != null) {
            DISABLED_TOOLS_CACHE.invalidate(agent.id);
        }
    }

    /**
     * Clear the entire disabled-tools cache. Used by tests and admin tooling.
     *
     * <p>Deliberately immediate, unlike {@link #invalidateDisabledToolsCache}: this is an
     * explicit "clear it now" operation rather than an eviction ordered against a write, and
     * four test classes call it expecting the next read to recompute.
     */
    public static void clearDisabledToolsCache() {
        DISABLED_TOOLS_CACHE.invalidateAll();
    }

    public static List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }
}
