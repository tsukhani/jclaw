package tools;

import com.google.gson.JsonObject;

import java.util.Set;

/**
 * JCLAW-677: request parsing and schema-level validation of a single subagent
 * spawn call, extracted from {@link SubagentSpawnTool}. The parsed bundle is
 * the same shape the tool has always produced; {@code error} non-null
 * short-circuits {@link SubagentSpawnTool#execute}.
 */
record SubagentSpawnArgs(
        String error,
        String task, String label, Long requestedAgentId,
        String modelProvider, String modelId,
        String mode, String context, int timeoutSeconds, boolean asyncRequested) {

    private static final Set<String> ALLOWED_MODES =
            Set.of(SubagentSpawnTool.MODE_SESSION, SubagentSpawnTool.MODE_INLINE);

    static SubagentSpawnArgs fail(String msg) {
        return new SubagentSpawnArgs(msg, null, null, null, null, null, null, null, 0, false);
    }

    static SubagentSpawnArgs parse(JsonObject args) {
        var task = optString(args, "task");
        if (task == null || task.isBlank()) {
            return fail("Error: 'task' is required.");
        }
        var label = optString(args, SubagentSpawnTool.FIELD_LABEL);
        var requestedAgentId = optLong(args, SubagentSpawnTool.ARG_AGENT_ID);
        var modelProviderOverride = optString(args, "modelProvider");
        var modelIdOverride = optString(args, "modelId");
        var timeoutSeconds = optInt(args, SubagentSpawnTool.ARG_RUN_TIMEOUT_SECONDS,
                SubagentSpawnTool.DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) timeoutSeconds = SubagentSpawnTool.DEFAULT_TIMEOUT_SECONDS;
        // JCLAW-267: mode parameter — "session" (default) materializes a fresh
        // child Conversation; "inline" runs the child in the parent's
        // Conversation with messages tagged so the chat UI folds them.
        var requestedMode = optString(args, "mode");
        var mode = requestedMode == null || requestedMode.isBlank()
                ? SubagentSpawnTool.DEFAULT_MODE
                : requestedMode.toLowerCase();
        if (!ALLOWED_MODES.contains(mode)) {
            return fail("Error: 'mode' must be one of " + ALLOWED_MODES
                    + SubagentSpawnTool.GOT_LITERAL + requestedMode + "').");
        }
        // JCLAW-268: context parameter — "fresh" (default) is the JCLAW-265
        // behavior; "inherit" summarizes the parent's recent turns and unions
        // tool grants. Validate strictly so an LLM typo produces a clear
        // error rather than silently degrading.
        var requestedContext = optString(args, SubagentSpawnTool.ARG_CONTEXT);
        var context = requestedContext == null || requestedContext.isBlank()
                ? SubagentSpawnTool.DEFAULT_CONTEXT
                : requestedContext.toLowerCase();
        if (!SubagentSpawnTool.ALLOWED_CONTEXTS.contains(context)) {
            return fail("Error: 'context' must be one of " + SubagentSpawnTool.ALLOWED_CONTEXTS
                    + SubagentSpawnTool.GOT_LITERAL + requestedContext + "').");
        }

        // JCLAW-270: async parameter — false (default) preserves the synchronous
        // JCLAW-265 flow; true dispatches the child run to a background VT and
        // returns the run id immediately. Async + inline is rejected because
        // inline mode embeds the child's messages directly into the parent
        // transcript; returning control to the LLM before the child finishes
        // would leave a half-written nested block dangling. The completion-card
        // post-flow (announce Message into the parent conversation) is the
        // async equivalent of inline's inline-rendering — they're alternatives
        // for surfacing child output, not complements.
        var asyncRequested = optBool(args, "async");
        if (asyncRequested && SubagentSpawnTool.MODE_INLINE.equals(mode)) {
            return fail("Error: 'async' is only compatible with mode=\"session\" (inline mode embeds child messages directly into the parent transcript, which has no meaningful semantics before the child finishes).");
        }
        // JCLAW-497: async subagents ARE supported in task fires (a block-await
        // handoff collected by subagent_yield — see launchAsyncSpawnForTask),
        // superseding JCLAW-494's interim rejection.

        return new SubagentSpawnArgs(null, task, label, requestedAgentId,
                modelProviderOverride, modelIdOverride,
                mode, context, timeoutSeconds, asyncRequested);
    }

    static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    static Long optLong(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try { return el.getAsLong(); } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException _) { return null; }
    }

    static int optInt(JsonObject obj, String key, int fallback) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try { return el.getAsInt(); } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException _) { return fallback; }
    }

    static boolean optBool(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return false;
        try { return el.getAsBoolean(); } catch (UnsupportedOperationException | IllegalStateException _) { return false; }
    }
}
