package controllers;

import agents.AgentRunner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import models.Agent;
import models.Conversation;
import org.apache.tika.Tika;
import play.db.jpa.NoTransaction;
import play.mvc.Controller;
import play.mvc.SseStream;
import play.mvc.With;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import utils.LatencyTrace;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * Pre-built SSE-frame fragments for the per-chunk callbacks fired by
     * the streaming pipeline. The token + reasoning frames fire 50-200
     * times per second per active stream; at modest concurrency the prior
     * {@code sse.send(Map.of(...))} path was the dominant allocator on
     * the hot path (one HashMap per chunk, plus Gson's StringBuilder /
     * JsonWriter / intermediate strings, plus a fresh byte[] for the
     * formatted frame). The bytes here are immutable shared prefixes;
     * the only per-frame allocation is the JSON-escaped content string
     * and the final concatenated byte array.
     *
     * <p>Newlines in the token content are NOT a framing hazard:
     * {@code gson.toJson(String)} escapes {@code \n} to {@code \\n}, so
     * the resulting JSON is a single line and the only literal newlines
     * in the frame are the spec-mandated {@code \n\n} terminator.
     */
    private static final byte[] SSE_TOKEN_PREFIX =
            "data: {\"type\":\"token\",\"content\":".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] SSE_REASONING_PREFIX =
            "data: {\"type\":\"reasoning\",\"content\":".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] SSE_FRAME_SUFFIX =
            "}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static void sendChunkFrame(play.mvc.SseStream sse, byte[] prefix, String content) {
        var contentBytes = gson.toJson(content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var frame = new byte[prefix.length + contentBytes.length + SSE_FRAME_SUFFIX.length];
        System.arraycopy(prefix, 0, frame, 0, prefix.length);
        System.arraycopy(contentBytes, 0, frame, prefix.length, contentBytes.length);
        System.arraycopy(SSE_FRAME_SUFFIX, 0, frame, prefix.length + contentBytes.length, SSE_FRAME_SUFFIX.length);
        sse.sendRaw(frame);
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
        // JCLAW-199: streamChat is @NoTransaction so Play does not wrap the
        // request in a JPA tx; explicit short Tx.run for the lookup. Agent has
        // no lazy fields used downstream, so reading String columns on the
        // detached entity after the tx closes is safe.
        Agent agent = services.Tx.run(() -> Agent.findById(agentId));
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
     * SSE plumbing — chunked headers, framing, heartbeats, and disconnect
     * detection — lives in the play1 fork's {@link SseStream} (PF-16). The
     * {@code cancelled} flag is bridged from {@code sse.onClose} so SSE
     * disconnect and {@code /stop} (which flips the same flag via
     * {@link services.ConversationQueue}) reach AgentRunner through one signal.
     *
     * <p>JCLAW-199: {@code @NoTransaction} opts out of Play 1.x's per-request
     * JPA transaction wrapper. Without this, the framework's TransactionalFilter
     * holds a HikariCP connection for the entire SSE duration (typically 2–30 s
     * for a real LLM), capping concurrent chats at the pool size (38% errors at
     * c=50 against the default 30-connection pool, profiled 2026-05-03). Every
     * DB touch on the chat path already goes through {@link services.Tx#run},
     * which opens its own short transaction when none is in scope, so the outer
     * Play tx was pure overhead. {@link AuthCheck} reads {@link services.ConfigService},
     * which also wraps its DB hit in {@code Tx.run}, so the auth interceptor
     * still works without an outer tx.
     */
    @NoTransaction
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

        SseStream sse = openSSE()
                .heartbeat(Duration.ofSeconds(30))
                .timeout(Duration.ofMinutes(10));

        // Bridge SSE close (heartbeat-write fail, explicit close, or 10-min
        // timeout) into the AgentRunner cancellation flag. /stop also flips
        // this flag via ConversationQueue, so AgentRunner sees one unified
        // signal regardless of source.
        var cancelled = new AtomicBoolean(false);
        sse.onClose(() -> cancelled.set(true));

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
                // JCLAW-199: streamChat is @NoTransaction; explicit Tx.run for
                // the lookup since ConversationService.findById assumes its
                // caller owns the tx.
                final Long capturedConvId = conversationId;
                slashConv = services.Tx.run(() -> ConversationService.findById(capturedConvId));
                if (slashConv == null) notFound();
            } else {
                slashConv = services.Tx.run(() -> ConversationService.findOrCreate(agent, "web", username));
            }
            // JCLAW-111: args-aware execute so /model status etc. work via SSE.
            var slashResult = slash.Commands.execute(
                    slashCmd.get(), agent, "web", username, slashConv,
                    slash.Commands.extractArgs(messageText));
            if (slashResult.conversation() != null) {
                sse.send(Map.of("type", "init", "conversationId", slashResult.conversation().id));
            }
            sse.send(Map.of("type", "complete", "content", slashResult.responseText()));
            sse.close();
            // Slash commands are synthetic turns — no LLM, no prologue → intentionally
            // not instrumented so they don't skew the Chat Performance histograms.
            await(sse.completion());
            return;
        }

        // Switch SSE payload shape on the first token only (includes a timestamp
        // field the frontend uses for TTFT visualization). Subsequent tokens take
        // a leaner shape. This is purely a wire-format decision — trace-side
        // FIRST_TOKEN marking is handled inside AgentRunner now.
        var firstToken = new AtomicBoolean(true);

        // JCLAW-200: optional steady-state token coalescer. Each SSE write
        // triggers a Netty resumeChunkedTransfer + flush (~25 ms/chunk on
        // loopback), so chat.stream.token_coalesce_chars > 0 buffers tokens
        // and emits one frame per ~N chars of accumulated content. Default
        // 0 = current per-token behavior. First token always emits
        // immediately; only steady-state tokens batch.
        final int coalesceChars = Math.max(0, services.ConfigService.getInt("chat.stream.token_coalesce_chars", 0));
        var tokenCoalescer = new utils.TokenCoalescer(coalesceChars,
                s -> sendChunkFrame(sse, SSE_TOKEN_PREFIX, s));
        var reasoningCoalescer = new utils.TokenCoalescer(coalesceChars,
                s -> sendChunkFrame(sse, SSE_REASONING_PREFIX, s));
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
                    sse.send(initData);
                },
                token -> {
                    if (firstToken.compareAndSet(true, false)) {
                        // First-token path keeps the timestamp field for TTFT
                        // visualization. Fires once per turn, so the extra
                        // HashMap allocation isn't worth the pre-built-frame
                        // dance the steady-state path uses.
                        sse.send(Map.of("type", "token", "content", token,
                                "timestamp", java.time.Instant.now().toString()));
                    } else {
                        tokenCoalescer.accept(token);
                    }
                },
                reasoning -> reasoningCoalescer.accept(reasoning),
                status -> sse.send(Map.of("type", "status", "content", status)),
                // JCLAW-170: tool-call frame. structuredJson rides as a raw
                // JsonElement (not a nested string) so the frontend can parse
                // it as an object tree — search-style tools embed a
                // {provider, results:[...]} payload that the UI turns into
                // clickable result chips.
                ev -> {
                    var payload = new java.util.LinkedHashMap<String, Object>();
                    payload.put("type", "tool_call");
                    payload.put("id", ev.id());
                    payload.put("name", ev.name());
                    payload.put("icon", ev.icon());
                    payload.put("arguments", ev.arguments());
                    payload.put("resultText", ev.resultText() == null ? "" : ev.resultText());
                    if (ev.resultStructuredJson() != null) {
                        payload.put("resultStructured",
                                com.google.gson.JsonParser.parseString(ev.resultStructuredJson()));
                    }
                    sse.send(payload);
                },
                content -> {
                    // JCLAW-200: drain coalescer buffers before the terminal
                    // frame so any tail tokens reach the client. No-op when
                    // coalescing is disabled (buffers stay empty).
                    tokenCoalescer.drain();
                    reasoningCoalescer.drain();
                    sse.send(Map.of("type", "complete", "content", content));
                    sse.close();
                },
                error -> {
                    tokenCoalescer.drain();
                    reasoningCoalescer.drain();
                    sse.send(Map.of("type", "error", "content", "An error occurred: " + error.getMessage()));
                    sse.close();
                    EventLogger.error("channel", agent.name, "web",
                            "SSE stream error: %s".formatted(error.getMessage()));
                },
                // onCancel: web cancellation is signalled via SSE close, not via
                // ConversationQueue.cancellationFlag (the only flag /stop flips),
                // so the runner-level onCancel hook has no transport-side state
                // to quiesce here. Kept as an explicit no-op so the Telegram path
                // (typing-heartbeat cleanup) can rely on the callback firing.
                () -> {}
        );
        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                cancelled, callbacks, acceptedAtNs, ctx.attachments());

        // Suspend this invocation until the SSE stream closes (terminal frame,
        // disconnect, or 10-min timeout). The Play worker thread returns to
        // the pool immediately; all real work happens on the agent's virtual
        // thread via sse.send() / sse.close() calls.
        await(sse.completion());
    }
}
