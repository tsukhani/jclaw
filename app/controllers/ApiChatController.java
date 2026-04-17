package controllers;

import agents.AgentRunner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import models.Agent;
import models.Conversation;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import utils.LatencyTrace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static utils.GsonHolder.INSTANCE;

/**
 * Chat dispatch endpoints: sync send, SSE streaming, and file upload.
 * Conversation management (list, get messages, delete, title gen) lives
 * in {@link ApiConversationsController}.
 */
@With(AuthCheck.class)
public class ApiChatController extends Controller {

    private static final Gson gson = INSTANCE;

    /**
     * Shared scheduler for SSE heartbeats and safety timeouts across every streaming
     * request. Previously each {@link #streamChat} call spun up its own
     * {@link ScheduledExecutorService} and shut it down on stream completion — that
     * churned a thread (virtual or not) and a task queue per request under load.
     * One static scheduler with a small virtual-thread-backed pool is enough: the
     * heartbeat task is ~1 byte write every 30 s and the timeout task is a single
     * fire-once. Per-stream cancellation is tracked via {@link ScheduledFuture}
     * references held by the request and cancelled in the stream's whenComplete.
     */
    private static final ScheduledExecutorService STREAM_SCHEDULER =
            Executors.newScheduledThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                    r -> Thread.ofVirtual().name("sse-scheduler-", 0).unstarted(r));

    /** Validated prologue shared by send() and streamChat(). */
    private record ChatContext(Agent agent, String message, Long conversationId, String username) {}

    /**
     * Parse and validate the common fields from a chat request body.
     * Calls {@code badRequest()} / {@code notFound()} (which throw) on invalid input,
     * so the return value is always non-null when control returns to the caller.
     */
    private static ChatContext resolveChatContext(JsonObject body) {
        if (body == null || !body.has("message") || !body.has("agentId")) {
            badRequest();
        }

        var agentId = body.get("agentId").getAsLong();
        Agent agent = Agent.findById(agentId);
        if (agent == null) notFound();

        var messageText = body.get("message").getAsString();
        Long conversationId = (body.has("conversationId") && !body.get("conversationId").isJsonNull())
                ? body.get("conversationId").getAsLong() : null;

        return new ChatContext(agent, messageText, conversationId, session.get("username"));
    }

    /**
     * POST /api/chat/send — Send a message and get a synchronous response.
     */
    public static void send() {
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());

        Conversation conversation;
        if (ctx.conversationId() != null) {
            conversation = ConversationService.findById(ctx.conversationId());
            if (conversation == null) notFound();
        } else {
            conversation = ConversationService.findOrCreate(ctx.agent(), "web", ctx.username());
        }

        var result = AgentRunner.run(ctx.agent(), conversation, ctx.message());

        var resp = new HashMap<String, Object>();
        resp.put("conversationId", conversation.id);
        resp.put("response", result.response());
        resp.put("agentId", ctx.agent().id);
        resp.put("agentName", ctx.agent().name);
        renderJSON(gson.toJson(resp));
    }

    private static final int MAX_UPLOAD_FILES = 5;
    private static final long MAX_UPLOAD_FILE_BYTES = 10L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /**
     * POST /api/chat/upload — Multipart upload for chat attachments.
     */
    public static void uploadChatFiles(Long agentId, java.io.File[] files) {
        if (agentId == null) badRequest();
        Agent agent = Agent.findById(agentId);
        if (agent == null) notFound();

        if (files == null || files.length == 0) {
            error(400, "No files uploaded");
        }
        if (files.length > MAX_UPLOAD_FILES) {
            error(400, "Too many files (max " + MAX_UPLOAD_FILES + ")");
        }
        for (var f : files) {
            if (f == null || !f.exists()) error(400, "Invalid file upload");
            if (f.length() > MAX_UPLOAD_FILE_BYTES) {
                error(400, "File too large: " + f.getName()
                        + " (max " + (MAX_UPLOAD_FILE_BYTES / (1024 * 1024)) + " MB)");
            }
        }

        var batchId = "uploads/" + buildBatchId();
        java.nio.file.Path batchDir;
        try {
            batchDir = AgentService.acquireWorkspacePath(agent.name, batchId);
        } catch (SecurityException e) {
            error(400, "Invalid upload target");
            return;
        }

        var results = new ArrayList<Map<String, Object>>();
        try {
            Files.createDirectories(batchDir);
            for (var f : files) {
                var safeName = sanitizeFilename(f.getName());
                if (safeName.isEmpty()) {
                    error(400, "Invalid filename: " + f.getName());
                }
                java.nio.file.Path target;
                try {
                    target = AgentService.acquireContained(batchDir, safeName);
                } catch (SecurityException e) {
                    error(400, "Invalid filename: " + f.getName());
                    return;
                }
                Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

                var entry = new HashMap<String, Object>();
                entry.put("path", batchId + "/" + safeName);
                entry.put("name", safeName);
                entry.put("size", Files.size(target));
                results.add(entry);
            }
        } catch (java.io.IOException e) {
            EventLogger.error("chat", "Chat upload failed for agent %s: %s"
                    .formatted(agent.name, e.getMessage()));
            error(500, "Upload failed: " + e.getMessage());
        }

        var resp = new HashMap<String, Object>();
        resp.put("batchId", batchId);
        resp.put("files", results);
        renderJSON(gson.toJson(resp));
    }

    private static String buildBatchId() {
        var ts = ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(ID_ALPHABET[RANDOM.nextInt(ID_ALPHABET.length)]);
        }
        return ts + "-" + suffix;
    }

    private static String sanitizeFilename(String name) {
        if (name == null) return "";
        var base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.strip();
        while (base.startsWith(".")) base = base.substring(1);
        var cleaned = base.replaceAll("[^A-Za-z0-9._\\- ]", "_");
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }

    /**
     * POST /api/chat/stream — Send a message and stream the response as SSE.
     */
    public static void streamChat() {
        var trace = LatencyTrace.fromCurrentRequest();
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());
        trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);
        var agent = ctx.agent();
        var messageText = ctx.message();
        var conversationId = ctx.conversationId();
        var username = ctx.username();

        var res = Http.Response.current();
        res.contentType = "text/event-stream";
        res.setHeader("Cache-Control", "no-cache");
        res.setHeader("Connection", "keep-alive");
        res.setHeader("X-Accel-Buffering", "no");

        // streamDone completes when the SSE stream reaches a terminal frame
        // (onComplete/onError), a heartbeat write fails (client disconnected),
        // or the 600s safety timeout fires. We hand it to Play's await() below
        // so the invocation thread returns to the pool rather than blocking.
        var streamDone = new CompletableFuture<Void>();
        var cancelled = new AtomicBoolean(false);

        ScheduledFuture<?> heartbeatFuture = STREAM_SCHEDULER.scheduleAtFixedRate(() -> {
            if (cancelled.get() || streamDone.isDone()) return;
            try {
                res.writeChunk(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception _) {
                cancelled.set(true);
                streamDone.complete(null);
            }
        }, 30, 30, TimeUnit.SECONDS);

        var firstToken = new AtomicBoolean(true);
        var callbacks = new AgentRunner.StreamingCallbacks(
                conversation -> {
                    var initData = new java.util.HashMap<>(Map.of("type", "init", "conversationId", conversation.id));
                    // Use the agent's persisted thinking mode, gated by the model's
                    // current capability — same semantics as AgentRunner so the UI
                    // reflects what the LLM will actually receive.
                    if (agent.thinkingMode != null && !agent.thinkingMode.isBlank()) {
                        var provider = llm.ProviderRegistry.get(agent.modelProvider);
                        if (provider != null) {
                            var valid = provider.config().models().stream()
                                    .filter(m -> m.id().equals(agent.modelId))
                                    .findFirst()
                                    .filter(llm.LlmTypes.ModelInfo::supportsThinking)
                                    .map(m -> m.effectiveThinkingLevels().contains(agent.thinkingMode))
                                    .orElse(false);
                            if (valid) initData.put("thinkingMode", agent.thinkingMode);
                        }
                    }
                    writeSse(res, cancelled, streamDone, initData, false);
                },
                token -> {
                    if (firstToken.compareAndSet(true, false)) {
                        trace.mark(LatencyTrace.FIRST_TOKEN);
                        // First token includes timestamp — use Gson path
                        writeSse(res, cancelled, streamDone,
                                Map.of("type", "token", "content", token,
                                       "timestamp", java.time.Instant.now().toString()), false);
                    } else {
                        // Hot path — skip Gson, use pre-built template
                        writeSseToken(res, cancelled, streamDone, token);
                    }
                },
                reasoning -> writeSse(res, cancelled, streamDone,
                        Map.of("type", "reasoning", "content", reasoning), false),
                status -> writeSse(res, cancelled, streamDone,
                        Map.of("type", "status", "content", status), false),
                content -> {
                    writeSse(res, cancelled, streamDone,
                            Map.of("type", "complete", "content", content), true);
                    trace.mark(LatencyTrace.TERMINAL_SENT);
                },
                error -> {
                    writeSse(res, cancelled, streamDone,
                            Map.of("type", "error", "content", "An error occurred: " + error.getMessage()), true);
                    trace.mark(LatencyTrace.TERMINAL_SENT);
                    EventLogger.error("channel", agent.name, "web",
                            "SSE stream error: %s".formatted(error.getMessage()));
                }
        );
        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                cancelled, callbacks, trace);

        // Safety timeout: emit one error frame and complete normally if no
        // terminal frame arrives within 600s. Scheduled on the shared scheduler;
        // we hold its ScheduledFuture so we can cancel it (along with the heartbeat)
        // when the stream finishes. Using a manual watcher instead of
        // CompletableFuture.orTimeout avoids a race where the future completes
        // exceptionally before we write the timeout SSE frame.
        ScheduledFuture<?> timeoutFuture = STREAM_SCHEDULER.schedule(() -> {
            if (!streamDone.isDone()) {
                cancelled.set(true);
                writeSse(res, cancelled, streamDone,
                        Map.of("type", "error", "content", "Request timed out"), true);
            }
        }, 600, TimeUnit.SECONDS);

        // Cleanup runs regardless of how the future completes — success,
        // exception, or cancellation. Cancelling both ScheduledFutures ensures
        // no stray heartbeat or timeout task fires after the stream is done;
        // the shared scheduler itself is long-lived and never shut down here.
        streamDone.whenComplete((_, _) -> {
            heartbeatFuture.cancel(false);
            timeoutFuture.cancel(false);
            trace.end();
        });

        // Suspend this invocation until streamDone completes. The Play worker
        // thread returns to the pool immediately — no more blocking on a latch.
        // The no-op callback satisfies the framework contract; all real work
        // has already happened on the virtual thread via SSE chunk writes.
        await(streamDone, _ -> { });
    }

    /**
     * Write a single SSE frame to the response. On write failure, marks the
     * stream as cancelled. If {@code terminal} is true, always completes the
     * stream-done future (signalling the invocation can resume).
     */
    private static void writeSse(Http.Response res, AtomicBoolean cancelled,
                                 CompletableFuture<Void> done, Map<String, ?> data, boolean terminal) {
        try {
            res.writeChunk("data: %s\n\n".formatted(gson.toJson(data)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception _) {
            cancelled.set(true);
            if (!terminal) {
                done.complete(null);
                return;
            }
        }
        if (terminal) done.complete(null);
    }

    /**
     * Fast-path SSE write for token events. Avoids Gson serialization on the
     * hot path by using a pre-built JSON string template. Only the content
     * value needs escaping.
     */
    private static void writeSseToken(Http.Response res, AtomicBoolean cancelled,
                                      CompletableFuture<Void> done, String content) {
        try {
            var escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            res.writeChunk(("data: {\"type\":\"token\",\"content\":\"" + escaped + "\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception _) {
            cancelled.set(true);
            done.complete(null);
        }
    }
}
