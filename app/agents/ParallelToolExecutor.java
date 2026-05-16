package agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolCall;
import models.Agent;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import static utils.GsonHolder.INSTANCE;

/**
 * Three-tier scheduler for a batch of tool calls. Extracted from
 * {@link AgentRunner} as part of JCLAW-299; the class owns the
 * {@code JCLAW-80 + JCLAW-81} dispatch policy plus the {@code JCLAW-281}
 * MCP per-action log expansion, which are coupled by the per-call
 * dispatch path.
 *
 * <h3>Scheduling model</h3>
 * <ul>
 *   <li><b>Parallel-safe tool</b> (opt-in via
 *   {@link ToolRegistry.Tool#parallelSafe()}): each call gets its own
 *   virtual thread — the pre-v0.7.13 behavior. Appropriate for stateless
 *   HTTP clients ({@code web_fetch}, {@code web_search}), pure-compute
 *   helpers ({@code date_time}), and validators ({@code checklist}).</li>
 *   <li><b>Non-parallel-safe tool</b>: calls are grouped by tool name
 *   into a single virtual thread and run sequentially in declared order.
 *   This is the JCLAW-80 fix — the LLM's declared call order is the
 *   authoritative contract for stateful tools (browser Page, shell cwd,
 *   workspace writers) because racing them gives screenshot-before-navigate
 *   class bugs.</li>
 *   <li><b>Across tool-name groups</b> (whether safe or unsafe): always
 *   parallel. Different tools touch different state, so there's no
 *   correctness reason to serialize them.</li>
 * </ul>
 *
 * <p>Single-tool batches skip the virtual-thread overhead and execute
 * inline on the caller. Results are always committed in the LLM's
 * declared order so {@code tool_call_id → tool_result} pairing matches
 * the pre-parallel history exactly. Cancellation is honored — in-flight
 * tools finish naturally (their results are discarded at commit time).
 */
public final class ParallelToolExecutor {

    private static final Gson gson = INSTANCE;

    private ParallelToolExecutor() {}

    /**
     * Pure compute: dispatch one tool call and return its result. No side
     * effects on shared state (message lists, image collector, DB). Safe
     * to call from multiple virtual threads concurrently.
     */
    static ToolRegistry.ToolResult runToolCall(ToolCall toolCall, Agent agent, Consumer<String> onStatus) {
        var rawName = toolCall.function().name();
        var rawArgs = toolCall.function().arguments();
        // JCLAW-281: when the model invokes a server-level mcp_<server> handle
        // with {tool, args}, expand the log line to the underlying action
        // form so operators see mcp_jira-confluence_create_issue <args> in
        // the events stream — same shape as the pre-281 per-action logs,
        // even though the wire-format tool name is just mcp_<server>.
        var display = expandMcpCallForLogging(rawName, rawArgs);
        var displayName = display.name();
        var displayArgs = display.args();
        if (onStatus != null) {
            onStatus.accept("Using tool: " + displayName);
        }
        EventLogger.info("tool", agent.name, null,
                "Executing tool '%s' (id: %s, args: %s)"
                        .formatted(displayName, toolCall.id(),
                                displayArgs.length() > 200
                                        ? displayArgs.substring(0, 200) + "..."
                                        : displayArgs));
        // JCLAW-170: use the rich-output path so search-style tools can emit a
        // structured JSON payload alongside the LLM-visible text. Non-rich
        // tools fall through the default and return a text-only ToolResult.
        var result = ToolRegistry.executeRich(rawName, rawArgs, agent);
        var text = result.text();
        var resultPreview = text.length() > 200
                ? text.substring(0, 200) + "... (%d chars)".formatted(text.length()) : text;
        EventLogger.info("tool", agent.name, null,
                "Tool '%s' returned: %s".formatted(displayName, resultPreview));
        return result;
    }

    private record McpCallDisplay(String name, String args) {}

    /**
     * JCLAW-281: synthesize the human-friendly display name and args for a
     * tool call. For native tools this is a pass-through. For MCP
     * server-level handles ({@code mcp_<server>}) invoked with
     * {@code {tool, args}}, returns the per-action display form
     * ({@code mcp_<server>_<action>}, inner args) so operators can scan
     * the event log without manually unpacking the parameterized envelope.
     */
    private static McpCallDisplay expandMcpCallForLogging(String rawName, String rawArgs) {
        if (rawName == null || !rawName.startsWith("mcp_")) {
            return new McpCallDisplay(rawName, rawArgs == null ? "" : rawArgs);
        }
        try {
            var parsed = JsonParser.parseString(
                    rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs);
            if (!parsed.isJsonObject()) return new McpCallDisplay(rawName, rawArgs);
            var obj = parsed.getAsJsonObject();
            if (!obj.has("tool") || obj.get("tool").isJsonNull()) {
                // Discovery call (no tool field) — display as-is.
                return new McpCallDisplay(rawName, rawArgs);
            }
            var actionName = obj.get("tool").getAsString();
            var actionArgs = obj.has("args") && obj.get("args").isJsonObject()
                    ? obj.getAsJsonObject("args").toString()
                    : "{}";
            return new McpCallDisplay(rawName + "_" + actionName, actionArgs);
        } catch (RuntimeException _) {
            return new McpCallDisplay(rawName, rawArgs == null ? "" : rawArgs);
        }
    }

    /**
     * Execute a batch of tool calls under the three-tier scheduling model
     * documented at the class level. Results are committed to
     * {@code currentMessages} and persisted to the conversation in the
     * LLM's declared order; {@code imageCollector} (when non-null)
     * accumulates extracted image URLs for the synthesis-side download
     * suffix; {@code onToolCall} (when non-null) fires once per completed
     * call so the SSE chat UI can render a per-call row post-persist.
     */
    @SuppressWarnings("java:S107") // every parameter is required to schedule and surface tool results
    static void executeToolsParallel(List<ToolCall> toolCalls,
                                      Agent agent, Long conversationId,
                                      List<ChatMessage> currentMessages,
                                      Consumer<String> onStatus,
                                      Consumer<AgentRunner.ToolCallEvent> onToolCall,
                                      List<String> imageCollector,
                                      AtomicBoolean isCancelled) {
        int n = toolCalls.size();
        if (n == 0) return;

        ToolRegistry.ToolResult[] results = new ToolRegistry.ToolResult[n];

        if (n == 1) {
            if (isCancelled == null || !isCancelled.get()) {
                results[0] = runToolCall(toolCalls.getFirst(), agent, onStatus);
            }
        } else {
            // Partition calls into work units:
            //   - parallel-safe tools → one work unit per CALL (each races freely)
            //   - non-parallel-safe tools → one work unit per tool-NAME group
            //     (calls within it run sequentially in declared order)
            // LinkedHashMap preserves first-occurrence order so the unsafe
            // groups, like the safe singletons, see their declared positions.
            var unsafeGroups = new LinkedHashMap<String, List<Integer>>();
            var safeCalls = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                var name = toolCalls.get(i).function().name();
                if (ToolRegistry.isParallelSafe(name)) {
                    safeCalls.add(i);
                } else {
                    unsafeGroups.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
                }
            }

            int workUnits = safeCalls.size() + unsafeGroups.size();
            var latch = new CountDownLatch(workUnits);

            // One virtual thread per parallel-safe call — full concurrency.
            for (int idx : safeCalls) {
                final int i = idx;
                Thread.ofVirtual().name("agent-tool-parallel").start(() -> {
                    try {
                        if (isCancelled != null && isCancelled.get()) return;
                        var tc = toolCalls.get(i);
                        try {
                            results[i] = runToolCall(tc, agent, onStatus);
                        } catch (Exception e) {
                            EventLogger.error("tool", agent.name, null,
                                    "Tool '%s' threw: %s"
                                            .formatted(tc.function().name(), e.getMessage()));
                            results[i] = ToolRegistry.ToolResult.text("Error executing tool: " + e.getMessage());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // One virtual thread per non-parallel-safe tool-name group —
            // calls within execute sequentially in declared order.
            for (var group : unsafeGroups.values()) {
                Thread.ofVirtual().name("agent-tool-serial").start(() -> {
                    try {
                        for (int idx : group) {
                            if (isCancelled != null && isCancelled.get()) break;
                            var tc = toolCalls.get(idx);
                            try {
                                results[idx] = runToolCall(tc, agent, onStatus);
                            } catch (Exception e) {
                                EventLogger.error("tool", agent.name, null,
                                        "Tool '%s' threw: %s"
                                                .formatted(tc.function().name(), e.getMessage()));
                                results[idx] = ToolRegistry.ToolResult.text("Error executing tool: " + e.getMessage());
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // Commit phase: append to message history and persist to DB in
        // original order, preserving LLM tool_result ordering invariants.
        for (int i = 0; i < n; i++) {
            var result = results[i];
            if (result == null) continue; // skipped due to cancellation
            var tc = toolCalls.get(i);
            var text = result.text();
            var structured = result.structuredJson();
            currentMessages.add(ChatMessage.toolResult(tc.id(), tc.function().name(), text));
            if (imageCollector != null) {
                MessageDeduplicator.extractImageUrls(text, imageCollector);
            }
            final String r = text;
            final String s = structured;
            Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                ConversationService.appendAssistantMessage(conv, null, gson.toJson(tc));
                ConversationService.appendToolResult(conv, tc.id(), r, s);
            });
            // JCLAW-170: surface the completed call to the SSE stream so the
            // chat UI can render a per-call row with the structured result
            // payload (search-result chips, favicons). Fired post-persist so
            // a reload mid-turn would still see the same row.
            if (onToolCall != null) {
                onToolCall.accept(new AgentRunner.ToolCallEvent(
                        tc.id(),
                        tc.function().name(),
                        ToolRegistry.iconFor(tc.function().name()),
                        tc.function().arguments(),
                        text,
                        structured));
            }
        }
    }
}
