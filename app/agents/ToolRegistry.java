package agents;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import llm.LlmTypes.*;
import models.Agent;
import models.AgentToolConfig;
import play.cache.CacheConfig;
import play.cache.Caches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of available tools. Tools are registered at startup and made available
 * to agents. Handles tool execution with error catching.
 * <p>
 * Thread-safe: callers build a list of tools locally and publish atomically
 * via {@link #publish(List)}. No shared mutable buffer is needed.
 */
public class ToolRegistry {

    /**
     * JCLAW-170: rich tool output used by {@link #executeRich}. {@code text}
     * is always the string the LLM sees (same shape as {@link Tool#execute}).
     * {@code structuredJson} is an optional JSON payload the UI can render
     * richly (e.g. search result chips with favicons) — persisted to
     * {@code message.tool_result_structured} so re-opening a conversation
     * keeps the richer render. {@code null} means "no structured view".
     */
    public record ToolResult(String text, String structuredJson) {
        public static ToolResult text(String text) { return new ToolResult(text, null); }
    }

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
         */
        default ToolResult executeRich(String argsJson, Agent agent) {
            return ToolResult.text(execute(argsJson, agent));
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

        /** Enumerated actions this tool exposes. The admin UI renders these as a
         *  "Show actions" disclosure under each tool card; the names typically
         *  match the {@code action} field in {@link #parameters()}. Empty by
         *  default for single-action tools that would repeat their own name. */
        default java.util.List<ToolAction> actions() { return java.util.List.of(); }

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
    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, Tool>> externalGroups =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /** Resolve a tool's {@link Tool#parallelSafe()} flag by name, defaulting
     *  to {@code false} for unknown names. Used by
     *  {@link agents.AgentRunner#executeToolsParallel} to decide whether
     *  multiple calls to the same tool in one round may race or must serialize. */
    public static boolean isParallelSafe(String toolName) {
        var tool = tools.get(toolName);
        return tool != null && tool.parallelSafe();
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
     * JCLAW-170: rich-output sibling of {@link #execute}. Same validation
     * semantics (unknown tool, empty args, malformed args); on success
     * returns the tool's {@link ToolResult} so callers that want the
     * structured JSON payload (UI surfaces) get it alongside the LLM-visible
     * text. Tools that don't override {@link Tool#executeRich} fall back
     * to a text-only result.
     */
    public static ToolResult executeRich(String toolName, String argsJson, Agent agent) {
        var tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.text("Error: Unknown tool '%s'".formatted(toolName));
        }
        if (argsJson == null || argsJson.isEmpty()) {
            return ToolResult.text(
                    "Error: Tool '%s' received empty arguments. The model's response was likely truncated before the tool call completed. Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call."
                            .formatted(toolName));
        }
        try {
            JsonParser.parseString(argsJson);
        } catch (JsonSyntaxException e) {
            return ToolResult.text(
                    "Error: Tool '%s' received malformed arguments (likely truncated by the model's output token limit). Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call. Parse error: %s"
                            .formatted(toolName, e.getMessage()));
        }
        try {
            return tool.executeRich(argsJson, agent);
        } catch (Exception e) {
            return ToolResult.text("Error executing tool '%s': %s".formatted(toolName, e.getMessage()));
        }
    }

    public static String execute(String toolName, String argsJson, Agent agent) {
        var tool = tools.get(toolName);
        if (tool == null) {
            return "Error: Unknown tool '%s'".formatted(toolName);
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
            return "Error: Tool '%s' received empty arguments. The model's response was likely truncated before the tool call completed. Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call."
                    .formatted(toolName);
        }
        try {
            JsonParser.parseString(argsJson);
        } catch (JsonSyntaxException e) {
            return "Error: Tool '%s' received malformed arguments (likely truncated by the model's output token limit). Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call. Parse error: %s"
                    .formatted(toolName, e.getMessage());
        }
        try {
            return tool.execute(argsJson, agent);
        } catch (Exception e) {
            return "Error executing tool '%s': %s".formatted(toolName, e.getMessage());
        }
    }

    /** Get tool definitions filtered by agent's tool configuration. */
    public static List<ToolDef> getToolDefsForAgent(Agent agent) {
        return getToolDefsForAgent(agent, (models.Conversation) null);
    }

    /**
     * Get tool definitions filtered by agent config.
     *
     * <p>Native tools (group=null) ship every turn — they're predictable
     * workhorses with bounded count. MCP servers (group=server name) ship
     * exactly one parameterized entry each via {@link McpServerTool}; the
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
    public static List<ToolDef> getToolDefsForAgent(Agent agent, models.Conversation conv) {
        // JCLAW-281: discoveredMcpServers no longer drives filtering, but
        // the overload is kept so callers don't need to track which
        // signature to use. Just delegates with an empty set.
        return getToolDefsForAgent(agent, java.util.Set.<String>of());
    }

    /**
     * Pure variant. The {@code discoveredMcpServers} parameter is retained
     * for binary compatibility with tests but no longer used as a filter
     * input — JCLAW-281 made the server-level handle always-emitted.
     */
    public static List<ToolDef> getToolDefsForAgent(Agent agent, Set<String> discoveredMcpServers) {
        // Loadtest agent: ship zero tools so cross-provider tokens-per-second
        // benchmarks measure pure model speed. Sending a populated tools
        // array adds ~2-3 KB of prefill on every request AND tempts the
        // model to invoke a tool instead of answering the benchmark prompt,
        // either of which derails the comparison.
        if (agent != null
                && services.LoadTestRunner.LOADTEST_AGENT_NAME.equals(agent.name)) {
            return List.of();
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

    /** Get tool definitions filtered by a pre-loaded set of disabled tool names.
     *  Used by hot paths that already have the disabled set in scope. Does NOT
     *  apply the lazy MCP discovery gate — only call from contexts where every
     *  MCP tool should ship (e.g., catalog metadata for the admin UI). */
    public static List<ToolDef> getToolDefsForAgent(Set<String> disabledTools) {
        if (disabledTools.isEmpty()) return getToolDefs();
        return tools.values().stream()
                .filter(t -> !disabledTools.contains(t.name()))
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
    private static final play.cache.Cache<Long, Set<String>> DISABLED_TOOLS_CACHE = Caches.named(
            "agent-disabled-tools",
            CacheConfig.newBuilder()
                    .maximumSize(1000)
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
        return DISABLED_TOOLS_CACHE.get(agent.id, _ -> {
            var configs = AgentToolConfig.findByAgent(agent);
            var explicitState = new HashMap<String, Boolean>();
            for (var c : configs) explicitState.put(c.toolName, c.enabled);

            var disabled = new HashSet<String>();
            for (var entry : explicitState.entrySet()) {
                if (entry.getValue()) continue;
                disabled.add(entry.getKey());
            }
            // MCP tools default-disabled for non-main agents (operator opts-in
            // per server on the agent detail page). Main agent unaffected.
            if (!agent.isMain()) {
                // JCLAW-281: bridge pre-281 per-action enablement to the new
                // server-level handle. If the operator has explicitly
                // enabled any per-action row for an MCP server, treat the
                // server-level handle as enabled too — without this, the
                // LLM would lose access to the parameterized def and the
                // agent's previously-granted actions would become
                // unreachable. Once Phase 6 migrates AgentToolConfig rows
                // to server-level keys, this derivation becomes a no-op.
                var serversWithEnabledAction = new HashSet<String>();
                for (var entry : explicitState.entrySet()) {
                    if (!entry.getValue()) continue;
                    var tool = tools.get(entry.getKey());
                    if (tool != null && tool.group() != null && !tool.isServerLevel()) {
                        serversWithEnabledAction.add(tool.group());
                    }
                }
                for (var tool : tools.values()) {
                    if (tool.group() == null) continue;
                    if (explicitState.containsKey(tool.name())) continue;
                    if (tool.isServerLevel()
                            && serversWithEnabledAction.contains(tool.group())) continue;
                    disabled.add(tool.name());
                }
            }
            return Collections.unmodifiableSet(disabled);
        });
    }

    /** Invalidate the cached disabled-tools set for a specific agent. */
    public static void invalidateDisabledToolsCache(Agent agent) {
        if (agent != null && agent.id != null) {
            DISABLED_TOOLS_CACHE.invalidate(agent.id);
        }
    }

    /** Clear the entire disabled-tools cache. Used by tests and admin tooling. */
    public static void clearDisabledToolsCache() {
        DISABLED_TOOLS_CACHE.invalidateAll();
    }

    public static List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }
}
