package controllers;

import agents.AgentRunner;
import agents.TurnBroadcast;
import agents.TurnCancellation;
import agents.TurnFrames;
import channels.QuotedReply;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import llm.LlmResilience;
import llm.ProviderRegistry;
import llm.routing.ModelRouter;
import models.Agent;
import models.Conversation;
import models.MessageAttachment;
import org.jspecify.annotations.Nullable;
import play.data.Upload;
import play.db.jpa.NoTransaction;
import play.mvc.Controller;
import play.mvc.SseStream;
import play.mvc.With;
import services.AgentService;
import services.AttachmentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import services.UploadStaging;
import slash.Commands;
import tools.SubagentSpawnTool;
import utils.ApiResponses;
import utils.InactivityTimer;
import utils.LatencyTrace;
import utils.TokenCoalescer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Chat dispatch endpoints: sync send, SSE streaming, turn start, and file upload.
 * Conversation management (list, get messages, delete, title gen) lives
 * in {@link ApiConversationsController}.
 */
@With(AuthCheck.class)
public class ApiChatController extends Controller {

    private static final Gson gson = GSON;

    private static final Duration CHAT_STREAM_FLOOR = Duration.ofMinutes(10);
    private static final Duration CHAT_STREAM_MARGIN = Duration.ofMinutes(2);

    /** A token stream re-arms the inactivity timer at most this often. */
    private static final Duration CHAT_STREAM_REARM_MIN = Duration.ofSeconds(5);

    /**
     * How long one chat stream may go without a frame before it is closed. Derived from the
     * first-chunk budget rather than fixed (JCLAW-1192): the sweep abandons a silent stream
     * just past that budget, and a ceiling that fell first cut the browser off with "client
     * disconnect" a few seconds before the abandonment it was about to receive. Never under
     * ten minutes. An inactivity budget, not a cap on the turn (JCLAW-1204): a 41-round tool
     * loop that keeps emitting frames is never cut by it.
     */
    public static Duration chatStreamTimeout() {
        var derived = LlmResilience.firstChunkBudget().plus(CHAT_STREAM_MARGIN);
        return derived.compareTo(CHAT_STREAM_FLOOR) > 0 ? derived : CHAT_STREAM_FLOOR;
    }

    // JSON body keys (request input + per-attachment metadata) and SSE/response payload keys.
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_STATUS = "status";
    private static final String KEY_QUOTE = "quote";
    private static final String KEY_ATTACHMENTS = "attachments";
    private static final String KEY_ATTACHMENT_ID = "attachmentId";
    private static final String KEY_ORIGINAL_FILENAME = "originalFilename";
    private static final String KEY_MIME_TYPE = "mimeType";
    private static final String KEY_SIZE_BYTES = "sizeBytes";

    /**
     * Pre-built SSE-frame fragments for the per-chunk callbacks fired by
     * the streaming pipeline. The token + reasoning frames fire 50-200
     * times per second per active stream; at modest concurrency the prior
     * {@code sse.send(Map.of(...))} path was the dominant allocator on
     * the hot path (one HashMap per chunk, plus Gson's StringBuilder /
     * JsonWriter / intermediate strings, plus a fresh byte[] for the
     * formatted frame). These bytes and {@link TurnFrames.Chunk}'s openings
     * are immutable shared prefixes; the only per-frame allocation is the
     * JSON-escaped content string and the final concatenated byte array.
     *
     * <p>Newlines in the token content are NOT a framing hazard:
     * {@code gson.toJson(String)} escapes {@code \n} to {@code \\n}, so
     * the resulting JSON is a single line and the only literal newlines
     * in the frame are the spec-mandated {@code \n\n} terminator.
     */
    private static final byte[] SSE_DATA = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SSE_FRAME_END = "\n\n".getBytes(StandardCharsets.UTF_8);

    private static void sendChunkFrame(SseStream sse, TurnFrames.Chunk kind, String content) {
        sse.sendRaw(TurnFrames.chunk(SSE_DATA, kind, content, SSE_FRAME_END));
    }

    /** Validated prologue shared by send(), streamChat() and startTurn(). */
    private record ChatContext(Agent agent, String message, @Nullable Long conversationId, String username,
                                List<AttachmentService.Input> attachments, @Nullable PendingOverrides overrides) {}

    /**
     * Model and thinking picks made on a fresh web chat before its first message (JCLAW-1196).
     * Applied as conversation overrides on the conversation that message creates; ignored
     * when the request names an existing conversation, whose overrides are set directly.
     */
    private record PendingOverrides(@Nullable String modelProvider, @Nullable String modelId,
                                    @Nullable String thinkingMode) {}

    /**
     * Parse and validate the common fields from a chat request body.
     * Calls {@code badRequest()} / {@code notFound()} (which throw) on invalid input,
     * so the return value is always non-null when control returns to the caller.
     *
     * <p>JCLAW-25: also parses the optional {@code attachments} array — each
     * entry is the per-file metadata the frontend roundtripped from a prior
     * {@code /api/chat/upload} response.
     *
     * <p>JCLAW-215: images are universally accepted now (mirroring the JCLAW-165
     * audio change). A vision-capable model receives the image as a native
     * {@code image_url} part; a non-vision model receives a generated caption as
     * a text part (via the captioning pipeline + capability routing in
     * {@code AgentRunner} / {@code VisionAudioAssembler.userMessageFor}). No
     * model-side gate; the rest of the pipeline handles the downgrade.
     */
    @SuppressWarnings("java:S2259")
    private static ChatContext resolveChatContext(@Nullable JsonObject body) {
        if (body == null || !body.has("message") || !body.has(KEY_AGENT_ID)) {
            badRequest();
            throw ApiResponses.unreachable();
        }

        var agentId = body.get(KEY_AGENT_ID).getAsLong();
        // JCLAW-199: both chat routes are @NoTransaction so Play does not wrap the
        // request in a JPA tx; explicit short Tx.run for the lookup. Agent has
        // no lazy fields used downstream, so reading String columns on the
        // detached entity after the tx closes is safe.
        Agent agent = Tx.run(() -> AgentService.findById(agentId));
        if (agent == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        var messageText = QuotedReply.fold(body.get("message").getAsString(), parseQuote(body));
        Long conversationId = (body.has(KEY_CONVERSATION_ID) && !body.get(KEY_CONVERSATION_ID).isJsonNull())
                ? body.get(KEY_CONVERSATION_ID).getAsLong() : null;

        var attachments = parseAttachments(body);
        var overrides = conversationId == null ? parseOverrides(body) : null;

        return new ChatContext(agent, messageText, conversationId, session.get("username"), attachments, overrides);
    }

    /** JCLAW-1299: the quoted block for a reply, or null when the body quotes nothing. */
    private static @Nullable String parseQuote(JsonObject body) {
        if (!body.has(KEY_QUOTE) || body.get(KEY_QUOTE).isJsonNull()) return null;
        String block = null;
        if (body.get(KEY_QUOTE).isJsonObject()) {
            var quote = body.getAsJsonObject(KEY_QUOTE);
            var text = optionalString(quote, "text");
            if (text != null) block = QuotedReply.webBlock(optionalString(quote, "kind"), text);
        }
        if (block == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "'quote' needs a non-blank 'text' and a 'kind' of assistant, user, delivered or reminder");
            throw ApiResponses.unreachable();
        }
        return block;
    }

    private static @Nullable String optionalString(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) return null;
        var v = body.get(key).getAsString();
        return v == null || v.isBlank() ? null : v;
    }

    private static @Nullable PendingOverrides parseOverrides(JsonObject body) {
        var provider = optionalString(body, "modelProvider");
        var modelId = optionalString(body, "modelId");
        var thinking = optionalString(body, "thinkingMode");
        if (provider == null && modelId == null && thinking == null) return null;
        if ((provider == null) != (modelId == null)) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "'modelProvider' and 'modelId' go together: send both or neither");
            throw ApiResponses.unreachable();
        }
        return new PendingOverrides(provider, modelId, thinking);
    }

    /**
     * Create the conversation a fresh web chat's first message starts, carrying the picks the
     * operator made before sending as conversation overrides. Validated first so a bad pick is
     * a 400 with nothing created; the agent row is never touched.
     */
    private static Conversation createWithOverrides(Agent agent, String username, PendingOverrides o) {
        rejectInvalidOverrides(agent, o);
        return Tx.run(() -> {
            var conversation = ConversationService.create(agent, "web", username);
            if (o.modelProvider() != null) {
                ConversationService.setModelOverride(conversation, o.modelProvider(), o.modelId());
            }
            if (o.thinkingMode() != null) {
                ConversationService.setThinkingOverride(conversation, o.thinkingMode());
            }
            return conversation;
        });
    }

    /** Renders a 400 and throws for a pick the provider or model cannot honor; returns for a good one. */
    private static void rejectInvalidOverrides(Agent agent, PendingOverrides o) {
        if (o.modelProvider() != null) {
            if (!ModelRouter.PROVIDER.equals(o.modelProvider()) && ProviderRegistry.get(o.modelProvider()) == null) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                        "Provider '" + o.modelProvider() + "' is not configured.");
                throw ApiResponses.unreachable();
            }
            if (ModelRouter.findModel(o.modelProvider(), o.modelId()).isEmpty()) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                        "Provider '" + o.modelProvider() + "' has no model with id '" + o.modelId() + "'.");
                throw ApiResponses.unreachable();
            }
        }
        if (o.thinkingMode() != null) {
            var effProvider = o.modelProvider() != null ? o.modelProvider() : agent.modelProvider;
            var effModel = o.modelId() != null ? o.modelId() : agent.modelId;
            var rejection = ConversationService.thinkingOverrideRejection(effProvider, effModel, o.thinkingMode());
            if (rejection != null) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, rejection);
                throw ApiResponses.unreachable();
            }
        }
    }

    private static List<AttachmentService.Input> parseAttachments(JsonObject body) {
        if (body == null || !body.has(KEY_ATTACHMENTS) || body.get(KEY_ATTACHMENTS).isJsonNull()) {
            return List.of();
        }
        var arr = body.getAsJsonArray(KEY_ATTACHMENTS);
        var out = new ArrayList<AttachmentService.Input>(arr.size());
        for (var el : arr) {
            out.add(parseAttachment(el.getAsJsonObject()));
        }
        return out;
    }

    @SuppressWarnings("java:S2259")
    private static AttachmentService.Input parseAttachment(JsonObject o) {
        var id = o.has(KEY_ATTACHMENT_ID) ? o.get(KEY_ATTACHMENT_ID).getAsString() : null;
        if (id == null || id.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "attachment missing attachmentId");
            throw ApiResponses.unreachable();
        }
        var originalFilename = o.has(KEY_ORIGINAL_FILENAME) ? o.get(KEY_ORIGINAL_FILENAME).getAsString() : null;
        var mimeType = o.has(KEY_MIME_TYPE) ? o.get(KEY_MIME_TYPE).getAsString() : null;
        var sizeBytes = o.has(KEY_SIZE_BYTES) ? o.get(KEY_SIZE_BYTES).getAsLong() : 0L;
        var kind = o.has("kind") ? o.get("kind").getAsString() : MessageAttachment.KIND_FILE;
        return new AttachmentService.Input(id, originalFilename, mimeType, sizeBytes, kind);
    }

    /**
     * POST /api/chat/send — Send a message and get a synchronous response.
     *
     * <p>{@code @NoTransaction}, as {@link #streamChat}: the turn's rows commit as it writes them, so a
     * subagent it starts on another thread can read its own run (JF-163).
     */
    @SuppressWarnings("java:S2259")
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "an agent calling chat drives another turn -- unbounded recursion")
    public static void send() {
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());

        var slashResult = runSlashCommand(ctx.agent(), ctx.message(), ctx.conversationId(), ctx.username());
        if (slashResult != null) {
            var slashResp = new HashMap<String, Object>();
            slashResp.put(KEY_CONVERSATION_ID,
                    slashResult.conversation() != null ? slashResult.conversation().id : null);
            slashResp.put("response", slashResult.responseText());
            slashResp.put(KEY_AGENT_ID, ctx.agent().id);
            slashResp.put("agentName", ctx.agent().name);
            renderJSON(gson.toJson(slashResp));
        }

        Conversation conversation;
        final Long requestedConversationId = ctx.conversationId();
        if (requestedConversationId != null) {
            conversation = Tx.run(() -> ConversationService.findById(requestedConversationId));
            if (conversation == null) {
                notFound();
                throw ApiResponses.unreachable();
            }
        } else if (ctx.overrides() != null) {
            conversation = createWithOverrides(ctx.agent(), ctx.username(), ctx.overrides());
        } else {
            conversation = Tx.run(() -> ConversationService.findOrCreate(ctx.agent(), "web", ctx.username()));
        }

        var result = AgentRunner.run(ctx.agent(), conversation, ctx.message(), ctx.attachments());

        var resp = new HashMap<String, Object>();
        resp.put(KEY_CONVERSATION_ID, conversation.id);
        TurnReply.put(resp, result);
        resp.put(KEY_AGENT_ID, ctx.agent().id);
        resp.put("agentName", ctx.agent().name);
        renderJSON(gson.toJson(resp));
    }

    /**
     * POST /api/chat/upload — Multipart upload for chat attachments. JCLAW-25:
     * files stage under {@code workspace/{agent.name}/attachments/staging/{uuid}.{ext}}
     * and the response returns a per-file {@code attachmentId} the client
     * roundtrips on the matching send. Finalization (move to the
     * conversation-keyed directory and {@code chat_message_attachment} row
     * insertion) happens in {@link AgentRunner} when the send lands.
     */
    @SuppressWarnings("java:S2259")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "stages arbitrary bytes into an agent workspace as a chat attachment")
    public static void uploadChatFiles(Long agentId, Upload[] files) {
        if (agentId == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        Agent agent = AgentService.findById(agentId);
        if (agent == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        // JCLAW-765: chat and the App->Agent invoke endpoint share ONE staging path
        // (utils/services.UploadStaging) so the same size/type limits + containment
        // apply to both — no second upload surface to drift into a bypass.
        var results = new ArrayList<Map<String, Object>>();
        for (var in : UploadStaging.stage(agent, files)) {
            var entry = new HashMap<String, Object>();
            entry.put(KEY_ATTACHMENT_ID, in.attachmentId());
            entry.put(KEY_ORIGINAL_FILENAME, in.originalFilename());
            entry.put(KEY_MIME_TYPE, in.mimeType());
            entry.put(KEY_SIZE_BYTES, in.sizeBytes());
            entry.put("kind", in.kind());
            results.add(entry);
        }

        var resp = new HashMap<String, Object>();
        resp.put("files", results);
        renderJSON(gson.toJson(resp));
    }

    /**
     * POST /api/chat/stream — Send a message and stream the response as SSE.
     * SSE plumbing — chunked headers, framing, heartbeats, and disconnect
     * detection — lives in the play1 fork's {@link SseStream} (PF-16). The
     * {@code cancelled} flag is bridged from {@code sse.onClose} so SSE
     * disconnect and {@code POST /api/conversations/{id}/stop} (which flips the same
     * flag via {@link services.ConversationQueue#stop}) reach AgentRunner through one signal.
     * The turn is tied to this request; {@link #startTurn} starts one that is not (JF-202).
     * This route stays as it is while the chat page and the load-test harness drive it.
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
    @SuppressWarnings("java:S2259")
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY, reason = "the same recursion as send, over SSE")
    public static void streamChat() {
        // Grab the Netty-set queue-accept stamp on the invocation thread so we can
        // forward it across the virtual-thread hop inside AgentRunner. The trace
        // itself is now constructed inside runStreaming — that's what lets every
        // channel (not just web) populate the performance histograms.
        var acceptedAtNs = LatencyTrace.acceptedAtNsFromCurrentRequest();
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());
        var agent = ctx.agent();
        var messageText = ctx.message();
        var username = ctx.username();
        var conversationId = conversationFor(ctx);

        // Heartbeat well inside the fork's 30 s QUIC idle window, so an HTTP/3 connection
        // survives a silent tool call (JCLAW-1204).
        SseStream sse = openSSE().heartbeat(Duration.ofSeconds(15));
        var close = new StreamCloseState(new AtomicBoolean(false), new AtomicBoolean(false));
        var idle = InactivityTimer.start(chatStreamTimeout(), CHAT_STREAM_REARM_MIN, () -> {
            close.idleTimedOut().set(true);
            EventLogger.warn("llm", agent.name, "web",
                    "Chat stream saw no frame for %s — closing it and cancelling the turn".formatted(chatStreamTimeout()));
            sse.close();
        });

        // Bridge SSE close (heartbeat-write fail, explicit close, or the inactivity
        // timeout) into the AgentRunner cancellation flag. POST /api/conversations/{id}/stop
        // also flips this flag via ConversationQueue.stop, so AgentRunner sees one unified
        // signal regardless of source; the log line here is what tells the sources apart.
        var cancelled = new AtomicBoolean(false);
        sse.onClose(() -> {
            idle.cancel();
            TurnCancellation.cancel(cancelled);
            if (!close.finished().get() && !close.idleTimedOut().get()) {
                EventLogger.info("llm", agent.name, "web",
                        "Chat stream closed by the client before the turn finished — cancelling the turn");
            }
        });

        close.finished().set(true); // a slash command answers and closes inside the call below
        if (handleStreamingSlashCommand(sse, agent, messageText, conversationId, username)) {
            return;
        }
        close.finished().set(false);

        var callbacks = buildStreamingCallbacks(sse, agent, idle, close);
        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                cancelled, callbacks, acceptedAtNs, ctx.attachments());

        // Suspend this invocation until the SSE stream closes (terminal frame,
        // disconnect, or the inactivity timeout). The Play worker thread returns to
        // the pool immediately; all real work happens on the agent's virtual
        // thread via sse.send() / sse.close() calls.
        await(sse.completion());
    }

    /**
     * POST /api/chat/turn — start a turn and answer once it has started or queued, before the model is called
     * (JF-202). The body is {@link #streamChat}'s. The turn's frames go only to its conversation's stream
     * ({@code GET /api/conversations/{id}/stream}), and it runs until it ends or an operator stops it
     * ({@code POST /api/conversations/{id}/stop}): no request holds it, so a closed tab does not cancel it and no idle
     * timer cuts it. A queued send publishes nothing until it drains. A slash command answers its reply here, with the
     * conversation it ran in, as the chat stream's {@code init} and {@code complete} carry them.
     *
     * <p>{@code @NoTransaction}, as {@link #streamChat}: the turn's own thread reads the conversation this request may
     * create, so nothing may wait on a request transaction's commit.
     */
    @SuppressWarnings("java:S2259")
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY, reason = "the same recursion as send, started without a stream")
    public static void startTurn() {
        var acceptedAtNs = LatencyTrace.acceptedAtNsFromCurrentRequest();
        var ctx = resolveChatContext(JsonBodyReader.readJsonBody());
        var conversationId = conversationFor(ctx);

        var slashResult = runSlashCommand(ctx.agent(), ctx.message(), conversationId, ctx.username());
        if (slashResult != null) {
            var answer = new HashMap<String, Object>();
            answer.put(KEY_STATUS, "command");
            answer.put(KEY_CONVERSATION_ID, slashResult.conversation() != null ? slashResult.conversation().id : null);
            answer.put("content", slashResult.responseText());
            renderJSON(gson.toJson(answer));
        }

        var start = await(AgentRunner.startStreaming(ctx.agent(), conversationId, ctx.username(), ctx.message(),
                startedTurnCallbacks(ctx.agent()), acceptedAtNs, ctx.attachments()));
        var answer = new HashMap<String, Object>();
        answer.put(KEY_CONVERSATION_ID, start.conversationId());
        switch (start.outcome()) {
            case STARTED -> answer.put(KEY_STATUS, "started");
            case QUEUED -> {
                answer.put(KEY_STATUS, "queued");
                answer.put("position", start.position());
            }
            case NOT_FOUND -> {
                notFound();
                throw ApiResponses.unreachable();
            }
            case FAILED -> {
                ApiResponses.error(500, ApiResponses.INTERNAL_ERROR, "The turn could not start.");
                throw ApiResponses.unreachable();
            }
        }
        renderJSON(gson.toJson(answer));
    }

    /**
     * A started turn's own callbacks. Its frames reach its watchers through the runner, so they send nothing; a
     * failure is logged as the chat stream logs one.
     */
    private static AgentRunner.StreamingCallbacks startedTurnCallbacks(Agent agent) {
        return new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { },
                error -> EventLogger.error("channel", agent.name, "web",
                        "Chat turn error: %s".formatted(error.getMessage())),
                () -> { });
    }

    /**
     * The conversation a chat request runs in: the one it names, else none yet (the runner creates one), else, for a
     * fresh chat with model or thinking picks, one created now carrying them, so a bad pick is an ordinary 400.
     */
    private static @Nullable Long conversationFor(ChatContext ctx) {
        var overrides = ctx.overrides();
        return overrides != null && ctx.conversationId() == null
                ? createWithOverrides(ctx.agent(), ctx.username(), overrides).id
                : ctx.conversationId();
    }

    /**
     * JCLAW-26: run {@code messageText} as a slash command, or return null when it is none, so it runs as normal
     * text. /new creates a fresh conversation; /reset and /help mutate or query the current one. The model is never
     * called.
     */
    private static Commands.@Nullable Result runSlashCommand(Agent agent, String messageText,
                                                             @Nullable Long conversationId, String username) {
        var slashCmd = Commands.parse(messageText);
        if (slashCmd.isEmpty()) return null;
        var slashConv = resolveSlashConversation(slashCmd.get(), agent, conversationId, username);
        // JCLAW-111: the typed arguments go through the args-aware execute overload, so /model status, /model reset
        // and /model NAME don't fall through to the no-args summary branch on web, as JCLAW-109 fixed for Telegram.
        // JCLAW-1228: web chat sits behind AuthCheck, so the sender is the operator.
        return Commands.execute(slashCmd.get(), agent, "web", username, slashConv,
                Commands.extractArgs(messageText), true);
    }

    /**
     * JCLAW-26: slash-command intercept on the chat stream: the init frame carries the conversation the command ran
     * in (a fresh one for /new, so the frontend switches), and the complete frame its reply. Never calls
     * runStreaming, so the model isn't invoked at all.
     *
     * @return true when the command was handled (caller must return); false
     *         when the message is not a slash command and normal streaming
     *         should proceed.
     */
    private static boolean handleStreamingSlashCommand(SseStream sse, Agent agent, String messageText,
                                                       @Nullable Long conversationId, String username) {
        var slashResult = runSlashCommand(agent, messageText, conversationId, username);
        if (slashResult == null) return false;
        if (slashResult.conversation() != null) {
            sse.send(TurnFrames.init(slashResult.conversation().id));
        }
        sse.send(TurnFrames.complete(slashResult.responseText()));
        sse.close();
        // Slash commands are synthetic turns — no LLM, no prologue → intentionally
        // not instrumented so they don't skew the Chat Performance histograms.
        await(sse.completion());
        return true;
    }

    @SuppressWarnings("java:S2259")
    private static @Nullable Conversation resolveSlashConversation(Commands.Command cmd, Agent agent,
                                                          @Nullable Long conversationId, String username) {
        if (cmd == Commands.Command.NEW) return null;
        if (conversationId != null) {
            // JCLAW-199: both chat routes are @NoTransaction; explicit Tx.run for
            // the lookup since ConversationService.findById assumes its
            // caller owns the tx.
            final Long capturedConvId = conversationId;
            var conv = Tx.run(() -> ConversationService.findById(capturedConvId));
            if (conv == null) {
                notFound();
                throw ApiResponses.unreachable();
            }
            return conv;
        }
        return Tx.run(() -> ConversationService.findOrCreate(agent, "web", username));
    }

    /** Why a stream closed: the turn finished normally, or the inactivity timer closed it. Anything else is the client. */
    private record StreamCloseState(AtomicBoolean finished, AtomicBoolean idleTimedOut) {}

    private static AgentRunner.StreamingCallbacks buildStreamingCallbacks(SseStream sse, Agent agent,
                                                                          InactivityTimer idle, StreamCloseState close) {
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
        final int coalesceChars = Math.max(0, ConfigService.getInt(TokenCoalescer.CONFIG_KEY, 0));
        var tokenCoalescer = new TokenCoalescer(coalesceChars,
                s -> sendChunkFrame(sse, TurnFrames.Chunk.TOKEN, s));
        var reasoningCoalescer = new TokenCoalescer(coalesceChars,
                s -> sendChunkFrame(sse, TurnFrames.Chunk.REASONING, s));

        // JCLAW-661: bridge this SSE to any acp coding run spawned during the turn.
        // Register the callbacks under the resolved conversation id (known only once
        // onInit fires) so SubagentSpawnTool can promote them onto the run's id, and
        // unregister on completion/error. convIdRef also gates the unregister; cbRef
        // threads the record's self-reference into its own onInit handler. What reaches
        // the tab through that registration bypasses the turn's own callbacks, so the
        // registered set also publishes it to the turn's watchers (JF-200).
        var convIdRef = new AtomicReference<Long>();
        var cbRef = new AtomicReference<AgentRunner.StreamingCallbacks>();
        var bridgeRef = new AtomicReference<AgentRunner.StreamingCallbacks>();
        var callbacks = new AgentRunner.StreamingCallbacks(
                conversation -> {
                    idle.touch();
                    sse.send(TurnFrames.init(agent, conversation));
                    convIdRef.set(conversation.id);
                    var bridge = TurnBroadcast.alsoPublishing(conversation.id, Objects.requireNonNull(cbRef.get()));
                    bridgeRef.set(bridge);
                    SubagentSpawnTool.registerChatCallbacks(conversation.id, bridge);
                },
                token -> {
                    idle.touch();
                    if (firstToken.compareAndSet(true, false)) {
                        // First-token path keeps the timestamp field for TTFT
                        // visualization. Fires once per turn, so the extra
                        // HashMap allocation isn't worth the pre-built-frame
                        // dance the steady-state path uses.
                        sse.send(TurnFrames.firstToken(token));
                    } else {
                        tokenCoalescer.accept(token);
                    }
                },
                reasoning -> {
                    idle.touch();
                    reasoningCoalescer.accept(reasoning);
                },
                status -> {
                    idle.touch();
                    sse.send(TurnFrames.status(status));
                },
                ev -> {
                    idle.touch();
                    sse.send(TurnFrames.toolCall(ev));
                },
                content -> {
                    // JCLAW-200: drain coalescer buffers before the terminal
                    // frame so any tail tokens reach the client. No-op when
                    // coalescing is disabled (buffers stay empty).
                    tokenCoalescer.drain();
                    reasoningCoalescer.drain();
                    SubagentSpawnTool.unregisterChatCallbacks(convIdRef.get(), bridgeRef.get());
                    close.finished().set(true);
                    sse.send(TurnFrames.complete(content));
                    sse.close();
                },
                error -> {
                    tokenCoalescer.drain();
                    reasoningCoalescer.drain();
                    SubagentSpawnTool.unregisterChatCallbacks(convIdRef.get(), bridgeRef.get());
                    close.finished().set(true);
                    // JCLAW-1133: the raw throwable text goes only to the EventLogger line below.
                    sse.send(webErrorFrame(error));
                    sse.close();
                    EventLogger.error("channel", agent.name, "web",
                            "SSE stream error: %s".formatted(error.getMessage()));
                },
                // onCancel: web cancellation is signaled via SSE close, not via
                // ConversationQueue.cancellationFlag, so the runner-level onCancel
                // hook has no transport frame to send.
                // It still fires on the SSE-close path (where onComplete/onError do
                // not), so JCLAW-661 uses it to drop the coding-run bridge for a
                // tab that closed mid-turn.
                () -> SubagentSpawnTool.unregisterChatCallbacks(convIdRef.get(), bridgeRef.get())
        );
        cbRef.set(callbacks);
        return callbacks;
    }

    /**
     * The SSE frame a web reader sees when a turn fails (JCLAW-1133): {@link TurnFrames#error}.
     * Extracted so the choice is testable without driving a real LLM stream, which this
     * controller's tests deliberately avoid.
     */
    public static Map<String, Object> webErrorFrame(Throwable error) {
        return TurnFrames.error(error);
    }
}
