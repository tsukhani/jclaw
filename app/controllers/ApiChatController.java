package controllers;

import agents.AgentRunner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import models.Agent;
import models.Conversation;
import org.apache.tika.Tika;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Util;
import play.mvc.With;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import utils.LatencyTrace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
     *
     * <p>Pool sizing: {@code max(2, availableProcessors / 2)}. Floor of 2 covers
     * single-core test containers. The {@code / 2} ratio leaves CPU headroom for
     * the agent loop itself (LLM streaming, tool dispatch) — these scheduler
     * threads are I/O-bound and infrequent, but having one per core would starve
     * the request-path carriers under load. Virtual threads mean the "pool size"
     * is really the number of carrier threads that service scheduled dispatches;
     * the actual heartbeat/timeout tasks run on ephemeral virtual threads.
     */
    private static final AtomicReference<ScheduledExecutorService> STREAM_SCHEDULER_REF = new AtomicReference<>();

    private static ScheduledExecutorService streamScheduler() {
        var s = STREAM_SCHEDULER_REF.get();
        if (s != null && !s.isShutdown()) return s;
        var fresh = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> Thread.ofVirtual().name("sse-scheduler-", 0).unstarted(r));
        if (STREAM_SCHEDULER_REF.compareAndSet(s, fresh)) return fresh;
        fresh.shutdown();
        return STREAM_SCHEDULER_REF.get();
    }

    /**
     * Time-bounded drain of the SSE scheduler. Called from
     * {@link jobs.ShutdownJob} at application stop. In-flight streams are
     * allowed 5 s to wind down (heartbeat cancels, timeout fires, final
     * flush); anything still scheduled is dropped. Re-init on next access
     * ensures later code paths still work (e.g. {@code JobLifecycleTest}
     * calls {@code ShutdownJob.doJob()} in the middle of the suite).
     */
    @Util
    public static void shutdown() {
        var s = STREAM_SCHEDULER_REF.getAndSet(null);
        if (s != null) utils.VirtualThreads.gracefulShutdown(s, "sse-scheduler");
    }

    /** Validated prologue shared by send() and streamChat(). */
    private record ChatContext(Agent agent, String message, Long conversationId, String username,
                                java.util.List<services.AttachmentService.Input> attachments) {}

    /**
     * Parse and validate the common fields from a chat request body.
     * Calls {@code badRequest()} / {@code notFound()} (which throw) on invalid input,
     * so the return value is always non-null when control returns to the caller.
     *
     * <p>JCLAW-25: also parses the optional {@code attachments} array — each
     * entry is the per-file metadata the frontend roundtripped from a prior
     * {@code /api/chat/upload} response. Enforces the vision gate: if any
     * attachment declares {@code kind=IMAGE} and the resolved model does not
     * advertise {@code supportsVision}, responds 400 rather than quietly
     * dropping the images.
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

        var attachments = parseAttachments(body);
        if (services.AttachmentService.anyImage(attachments) && !agentSupportsVision(agent)) {
            error(400, "This model does not support images");
        }
        if (services.AttachmentService.anyAudio(attachments) && !agentSupportsAudio(agent)) {
            error(400, "This model does not support audio");
        }

        return new ChatContext(agent, messageText, conversationId, session.get("username"), attachments);
    }

    private static java.util.List<services.AttachmentService.Input> parseAttachments(JsonObject body) {
        if (body == null || !body.has("attachments") || body.get("attachments").isJsonNull()) {
            return java.util.List.of();
        }
        var arr = body.getAsJsonArray("attachments");
        var out = new java.util.ArrayList<services.AttachmentService.Input>(arr.size());
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var id = o.has("attachmentId") ? o.get("attachmentId").getAsString() : null;
            if (id == null || id.isBlank()) {
                error(400, "attachment missing attachmentId");
            }
            var originalFilename = o.has("originalFilename") ? o.get("originalFilename").getAsString() : null;
            var mimeType = o.has("mimeType") ? o.get("mimeType").getAsString() : null;
            var sizeBytes = o.has("sizeBytes") ? o.get("sizeBytes").getAsLong() : 0L;
            var kind = o.has("kind") ? o.get("kind").getAsString() : models.MessageAttachment.KIND_FILE;
            out.add(new services.AttachmentService.Input(id, originalFilename, mimeType, sizeBytes, kind));
        }
        return out;
    }

    /**
     * Resolve whether the agent's active model advertises {@code supportsVision}.
     * Mirrors the capability lookup {@link agents.AgentRunner} performs for
     * thinking-mode validation: provider registry lookup, model-id match,
     * boolean flag. Returns {@code false} when the provider or model can't
     * be resolved — better to reject an image attachment than accept it
     * against an unknown model.
     */
    private static boolean agentSupportsVision(Agent agent) {
        var provider = llm.ProviderRegistry.get(agent.modelProvider);
        if (provider == null) return false;
        return provider.config().models().stream()
                .filter(m -> m.id().equals(agent.modelId))
                .findFirst()
                .map(llm.LlmTypes.ModelInfo::supportsVision)
                .orElse(false);
    }

    /** Mirror of {@link #agentSupportsVision} for the JCLAW-131 audio gate. */
    private static boolean agentSupportsAudio(Agent agent) {
        var provider = llm.ProviderRegistry.get(agent.modelProvider);
        if (provider == null) return false;
        return provider.config().models().stream()
                .filter(m -> m.id().equals(agent.modelId))
                .findFirst()
                .map(llm.LlmTypes.ModelInfo::supportsAudio)
                .orElse(false);
    }

    /**
     * POST /api/chat/send — Send a message and get a synchronous response.
     */
    public static void send() {
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());

        // JCLAW-26: intercept slash commands before the LLM round. The
        // handler owns conversation creation (/new) or context-reset state
        // (/reset). Unknown slash-prefixed input falls through as normal text.
        var slashCmd = slash.Commands.parse(ctx.message());
        if (slashCmd.isPresent()) {
            Conversation current;
            if (slashCmd.get() == slash.Commands.Command.NEW) {
                current = null;
            } else if (ctx.conversationId() != null) {
                current = ConversationService.findById(ctx.conversationId());
                if (current == null) notFound();
            } else {
                current = ConversationService.findOrCreate(ctx.agent(), "web", ctx.username());
            }
            // JCLAW-111: thread the user's typed arguments through the
            // args-aware execute overload so /model status, /model reset,
            // and /model NAME don't silently fall through to the no-args
            // summary branch on web. Telegram got this fix in JCLAW-109 via
            // AgentRunner.processInboundForAgentStreaming; the web path
            // needs the same treatment.
            var slashResult = slash.Commands.execute(
                    slashCmd.get(), ctx.agent(), "web", ctx.username(), current,
                    slash.Commands.extractArgs(ctx.message()));
            var slashResp = new HashMap<String, Object>();
            slashResp.put("conversationId",
                    slashResult.conversation() != null ? slashResult.conversation().id : null);
            slashResp.put("response", slashResult.responseText());
            slashResp.put("agentId", ctx.agent().id);
            slashResp.put("agentName", ctx.agent().name);
            renderJSON(gson.toJson(slashResp));
        }

        Conversation conversation;
        if (ctx.conversationId() != null) {
            conversation = ConversationService.findById(ctx.conversationId());
            if (conversation == null) notFound();
        } else {
            conversation = ConversationService.findOrCreate(ctx.agent(), "web", ctx.username());
        }

        var result = AgentRunner.run(ctx.agent(), conversation, ctx.message(), ctx.attachments());

        var resp = new HashMap<String, Object>();
        resp.put("conversationId", conversation.id);
        resp.put("response", result.response());
        resp.put("agentId", ctx.agent().id);
        resp.put("agentName", ctx.agent().name);
        renderJSON(gson.toJson(resp));
    }

    private static final int MAX_UPLOAD_FILES = 5;

    /**
     * Shared Tika instance for server-side MIME sniffing. Tika is thread-safe
     * once constructed and caches its detector config internally, so one
     * process-wide instance avoids re-parsing the classpath mime-types tree
     * per upload.
     */
    private static final Tika TIKA = new Tika();

    /**
     * POST /api/chat/upload — Multipart upload for chat attachments. JCLAW-25:
     * files stage under {@code workspace/{agent.name}/attachments/staging/{uuid}.{ext}}
     * and the response returns a per-file {@code attachmentId} the client
     * roundtrips on the matching send. Finalization (move to the
     * conversation-keyed directory and {@code chat_message_attachment} row
     * insertion) happens in {@link AgentRunner} when the send lands.
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
        }

        java.nio.file.Path stagingDir;
        try {
            stagingDir = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        } catch (SecurityException e) {
            error(400, "Invalid upload target");
            return;
        }

        var results = new ArrayList<Map<String, Object>>();
        try {
            Files.createDirectories(stagingDir);
            for (var f : files) {
                var safeName = sanitizeFilename(f.getName());
                if (safeName.isEmpty()) {
                    error(400, "Invalid filename: " + f.getName());
                }
                var uuid = UUID.randomUUID().toString();
                // Tika reads file magic bytes — authoritative over browser-declared
                // Content-Type (which can be absent, wrong, or spoofed). Kind
                // classification and per-kind size caps both derive from this
                // sniffed MIME, never the declared one.
                var sniffedMime = TIKA.detect(f);
                var kind = models.MessageAttachment.kindForMime(sniffedMime);
                var cap = services.UploadLimits.forKind(kind);
                if (f.length() > cap) {
                    error(400, "%s too large: %s (max %d MB for %s)"
                            .formatted(services.UploadLimits.displayName(kind),
                                    f.getName(), cap / (1024 * 1024),
                                    services.UploadLimits.displayName(kind)));
                }
                var ext = extensionFromFilename(safeName);
                if (ext.isEmpty()) ext = canonicalExtensionForMime(sniffedMime);
                var onDiskName = uuid + (ext.isEmpty() ? "" : "." + ext);

                java.nio.file.Path target;
                try {
                    target = AgentService.acquireContained(stagingDir, onDiskName);
                } catch (SecurityException e) {
                    error(400, "Invalid filename: " + f.getName());
                    return;
                }
                Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

                var entry = new HashMap<String, Object>();
                entry.put("attachmentId", uuid);
                entry.put("originalFilename", safeName);
                entry.put("mimeType", sniffedMime);
                entry.put("sizeBytes", Files.size(target));
                entry.put("kind", kind);
                results.add(entry);
            }
        } catch (java.io.IOException e) {
            EventLogger.error("chat", "Chat upload failed for agent %s: %s"
                    .formatted(agent.name, e.getMessage()));
            error(500, "Upload failed: " + e.getMessage());
        }

        var resp = new HashMap<String, Object>();
        resp.put("files", results);
        renderJSON(gson.toJson(resp));
    }

    private static String extensionFromFilename(String safeName) {
        var dot = safeName.lastIndexOf('.');
        if (dot < 0 || dot == safeName.length() - 1) return "";
        return safeName.substring(dot + 1).toLowerCase();
    }

    /**
     * Candidate extensions probed against {@link play.libs.MimeTypes} to
     * find one whose forward lookup matches a sniffed MIME. The data lives
     * in Play's bundled {@code mime-types.properties} plus the
     * {@code mimetype.*} overrides declared in {@code conf/application.conf}
     * — adding a new format there makes it resolvable here without touching
     * Java. Only hit when the uploader's original filename carried no
     * extension at all, which is rare.
     */
    private static final String[] EXTENSION_CANDIDATES = {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "avif", "heic", "heif",
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "weba",
            "pdf", "txt", "md", "csv", "json", "html", "xml",
            "zip", "tar", "gz",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    };

    /**
     * Reverse lookup delegated to {@link services.MimeExtensions} (which in
     * turn calls {@link play.libs.MimeTypes}). Data lives in Play's bundled
     * properties plus {@code mimetype.*} overrides in
     * {@code conf/application.conf}; extending coverage for a new format is
     * a one-line config change.
     */
    private static String canonicalExtensionForMime(String mime) {
        return services.MimeExtensions.forMime(mime, EXTENSION_CANDIDATES);
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
        // Grab the Netty-set queue-accept stamp on the invocation thread so we can
        // forward it across the virtual-thread hop inside AgentRunner. The trace
        // itself is now constructed inside runStreaming — that's what lets every
        // channel (not just web) populate the performance histograms.
        var acceptedAtNs = LatencyTrace.acceptedAtNsFromCurrentRequest();
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());
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

        ScheduledFuture<?> heartbeatFuture = streamScheduler().scheduleAtFixedRate(() -> {
            if (cancelled.get() || streamDone.isDone()) return;
            try {
                res.writeChunk(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception _) {
                cancelled.set(true);
                streamDone.complete(null);
            }
        }, 30, 30, TimeUnit.SECONDS);

        // JCLAW-26: slash-command intercept. /new creates a fresh conversation
        // (init frame carries the new id so the frontend switches); /reset +
        // /help mutate/query the current conversation. Unknown /foo falls
        // through as normal text. Emits SSE frames directly; never calls
        // runStreaming, so the model isn't invoked at all.
        var slashCmd = slash.Commands.parse(messageText);
        if (slashCmd.isPresent()) {
            Conversation slashConv;
            if (slashCmd.get() == slash.Commands.Command.NEW) {
                slashConv = null;
            } else if (conversationId != null) {
                slashConv = ConversationService.findById(conversationId);
                if (slashConv == null) notFound();
            } else {
                slashConv = ConversationService.findOrCreate(agent, "web", username);
            }
            // JCLAW-111: args-aware execute so /model status etc. work via SSE.
            var slashResult = slash.Commands.execute(
                    slashCmd.get(), agent, "web", username, slashConv,
                    slash.Commands.extractArgs(messageText));
            if (slashResult.conversation() != null) {
                writeSse(res, cancelled, streamDone,
                        Map.of("type", "init", "conversationId", slashResult.conversation().id), false);
            }
            writeSse(res, cancelled, streamDone,
                    Map.of("type", "complete", "content", slashResult.responseText()), true);
            // Slash commands are synthetic turns — no LLM, no prologue → intentionally
            // not instrumented so they don't skew the Chat Performance histograms.
            streamDone.whenComplete((_, _) -> heartbeatFuture.cancel(false));
            await(streamDone, _ -> { });
            return;
        }

        // Switch SSE payload shape on the first token only (includes a timestamp
        // field the frontend uses for TTFT visualization). Subsequent tokens take
        // the hot path. This is purely a wire-format decision — trace-side
        // FIRST_TOKEN marking is handled inside AgentRunner now.
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
                content -> writeSse(res, cancelled, streamDone,
                        Map.of("type", "complete", "content", content), true),
                error -> {
                    writeSse(res, cancelled, streamDone,
                            Map.of("type", "error", "content", "An error occurred: " + error.getMessage()), true);
                    EventLogger.error("channel", agent.name, "web",
                            "SSE stream error: %s".formatted(error.getMessage()));
                }
        );
        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                cancelled, callbacks, acceptedAtNs, ctx.attachments());

        // Safety timeout: emit one error frame and complete normally if no
        // terminal frame arrives within 600s. Scheduled on the shared scheduler;
        // we hold its ScheduledFuture so we can cancel it (along with the heartbeat)
        // when the stream finishes. Using a manual watcher instead of
        // CompletableFuture.orTimeout avoids a race where the future completes
        // exceptionally before we write the timeout SSE frame.
        ScheduledFuture<?> timeoutFuture = streamScheduler().schedule(() -> {
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
