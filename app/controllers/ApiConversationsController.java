package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Conversation;
import models.Message;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.ConversationService;
import utils.JpqlFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * CRUD and query endpoints for conversations. Extracted from ApiChatController
 * (which retains sync chat dispatch, SSE streaming, and file upload) to keep
 * each controller focused on a single responsibility.
 */
@With(AuthCheck.class)
public class ApiConversationsController extends Controller {

    private static final Gson gson = INSTANCE;

    // OpenAI-shaped tool-call element key (the wrapping "function" object).
    private static final String KEY_FUNCTION = "function";

    public record ConversationView(Long id, Long agentId, String agentName, String channelType,
                                   String peerId, String createdAt, String updatedAt,
                                   long messageCount, String preview,
                                   String modelProviderOverride, String modelIdOverride,
                                   Long parentConversationId,
                                   long compactionCount) {}

    /**
     * Documents the {@code GET /messages} response shape. The actual emission
     * uses a HashMap because several fields are conditionally absent (only
     * populated when the underlying Message column is non-null); converting
     * to a record would change the wire format from "field absent" to
     * "field: null" for those fields, which the chat-history hydrator on
     * the frontend may treat differently.
     *
     * @param id                   server message id
     * @param role                 message role ({@code user}, {@code assistant},
     *                             {@code tool}, {@code system})
     * @param content              message body text
     * @param toolCalls            assistant {@code tool_calls} JSON array
     *                             (parsed); null for non-assistant rows or
     *                             when the row had no tool calls
     * @param toolResults          {@code tool_call_id} this tool-row answers
     *                             (tool-role rows only)
     * @param toolResultStructured optional structured payload for richer UI
     *                             rendering (search-result chips etc.)
     * @param reasoning            chain-of-thought text for thinking models
     * @param createdAt            ISO-8601 timestamp
     * @param usage                token-usage JSON for assistant rows
     * @param subagentRunId        when this row was produced by a subagent,
     *                             links back to the {@code SubagentRun} id
     * @param messageKind          discriminator for non-standard kinds
     *                             ({@code subagent_announce},
     *                             {@code subagent_send}, …)
     * @param metadata             kind-specific metadata object
     * @param attachments          file attachments on the row
     */
    public record MessageView(Long id, String role, String content,
                              JsonElement toolCalls, String toolResults,
                              JsonElement toolResultStructured,
                              String reasoning, String createdAt,
                              JsonElement usage,
                              Long subagentRunId,
                              String messageKind,
                              JsonElement metadata,
                              List<MessageAttachmentView> attachments) {}

    /**
     * Per-attachment metadata surfaced to the frontend in /messages.
     *
     * @param uuid             client-facing key for the
     *                         {@code GET /api/attachments/{uuid}} download
     *                         endpoint (JCLAW-279)
     * @param originalFilename filename the user uploaded
     * @param mimeType         content type
     * @param sizeBytes        attachment size in bytes
     * @param kind             discriminator for special-rendered kinds
     *                         ({@code image}, {@code audio}, generic file)
     */
    public record MessageAttachmentView(String uuid, String originalFilename,
                                        String mimeType, long sizeBytes, String kind) {}

    public record QueueStatusResponse(boolean busy, int queueSize) {}

    public record StatusResponse(String status) {}

    public record DeletedCountResponse(int deleted) {}

    public record DeleteByIdsRequest(List<Long> ids) {}

    public record DeleteFilter(String channel, Long agentId, String name, String peer) {}

    public record DeleteByFilterRequest(DeleteFilter filter) {}

    public record ModelOverrideRequest(String modelProvider, String modelId) {}

    public record ModelOverrideResponse(String modelProvider, String modelId) {}

    public record ErrorResponse(String error) {}

    /**
     * GET /api/conversations — List conversations with optional filters.
     *
     * <p>The {@code name} filter does a case-insensitive substring match on
     * the conversation's preview text directly at the DB level. The previous
     * implementation pulled every LIKE-matching row into Hibernate's identity
     * map and then refined with a Java word-boundary regex — at 50k+ rows
     * with a common search term that materialized the whole match set on
     * every keystroke. Substring is the standard chat-search semantic
     * (Slack, Discord, etc); the upside of word-boundary refinement was
     * not worth the unbounded fetch cost.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ConversationView.class))))
    public static void listConversations(String channel, Long agentId, String name, String peer,
                                          String q, Integer limit, Integer offset) {
        boolean hasNameFilter = name != null && !name.isBlank();

        var filter = new JpqlFilter()
                .eq("channelType", channel)
                .eq("agent.id", agentId)
                .like("LOWER(preview)", hasNameFilter ? "%" + name.toLowerCase() + "%" : null)
                .like("LOWER(peerId)", peer != null && !peer.isBlank() ? "%" + peer.toLowerCase() + "%" : null);

        // JCLAW-304: when q is non-blank, resolve it against the
        // CONVERSATION_MESSAGE Lucene scope, derive the distinct set of
        // conversation ids whose messages matched, and add it as an
        // additional `c.id IN (:fts)` predicate intersected with the
        // existing equality filters. A blank q is a no-op so legacy
        // callers without the parameter see identical behavior.
        //
        // Cap the FTS search at 500 matches — operators rarely scroll
        // past the first page of hits, and the IN-list grows linearly
        // with this cap, so an unbounded fetch would balloon the
        // generated SQL for popular keywords.
        var ftsConvIds = ftsConversationIds(q);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        // Subagent child conversations (parentConversation != null) are
        // scoped to their parent's spawn tree and surface on the dedicated
        // /subagents admin page, not in the user-facing conversation list
        // or the chat sidebar's recent feed. Filtering at the API rather
        // than the frontend keeps both consumers consistent and preserves
        // accurate pagination counts. Mirrors the JCLAW-274 follow-up that
        // hid subagents from /api/agents (commit 26e2acd).
        var dynamicWhere = filter.toWhereClause();
        var fullWhere = dynamicWhere.isEmpty()
                ? "c.parentConversation IS NULL"
                : "c.parentConversation IS NULL AND " + dynamicWhere;
        if (ftsConvIds != null) {
            // Empty result set from FTS short-circuits to "no rows" —
            // the JPQL `IN (:fts)` with an empty collection would throw,
            // so we substitute an unsatisfiable predicate that returns
            // zero rows cleanly. Pagination headers still emit total=0.
            if (ftsConvIds.isEmpty()) {
                setPaginationHeaders(0);
                renderJSON("[]");
            }
            fullWhere = fullWhere + " AND c.id IN (:fts)";
        }
        String jpql = "SELECT c FROM Conversation c JOIN FETCH c.agent WHERE "
                + fullWhere + " ORDER BY c.updatedAt DESC";
        var jpaQ = JPA.em().createQuery(jpql, Conversation.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            jpaQ.setParameter(i + 1, params.get(i));
        }
        if (ftsConvIds != null) jpaQ.setParameter("fts", ftsConvIds);

        String countJpql = "SELECT COUNT(c) FROM Conversation c WHERE " + fullWhere;
        var countQ = JPA.em().createQuery(countJpql, Long.class);
        for (int i = 0; i < params.size(); i++) {
            countQ.setParameter(i + 1, params.get(i));
        }
        if (ftsConvIds != null) countQ.setParameter("fts", ftsConvIds);
        long total = countQ.getSingleResult();
        List<Conversation> convos = jpaQ.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

        setPaginationHeaders(total);

        var result = convos.stream().map(ApiConversationsController::conversationToMap).toList();

        renderJSON(gson.toJson(result));
    }

    /**
     * JCLAW-304: resolve a {@code q} keyword to the distinct set of
     * conversation ids whose messages match. Returns {@code null} when
     * {@code q} is null/blank (caller treats null as "no FTS filter").
     * Returns an empty list when the FTS index returned no matches
     * (caller short-circuits to a zero-row response). Returns a
     * non-empty list otherwise.
     *
     * <p>The intermediate hop through {@code Message.conversation.id} is
     * a single JPQL distinct-projection — cheaper than fetching the
     * full Message rows just to read their FK.
     */
    private static List<Long> ftsConversationIds(String q) {
        if (q == null || q.isBlank()) return null;
        try {
            var messageIds = services.search.MessageSearch.searchIds(
                    services.search.LuceneIndexer.Scope.CONVERSATION_MESSAGE, q, 500);
            if (messageIds.isEmpty()) return List.of();
            @SuppressWarnings("unchecked")
            var convIds = (List<Long>) JPA.em()
                    .createQuery("SELECT DISTINCT m.conversation.id FROM Message m WHERE m.id IN :ids")
                    .setParameter("ids", messageIds)
                    .getResultList();
            return convIds.isEmpty() ? List.of() : convIds;
        } catch (java.io.IOException e) {
            // FTS backend unreachable — surface as "no FTS filter" rather
            // than fail the whole list. The operator sees the equality
            // filters' result set and the absence of FTS-narrowed hits;
            // less surprising than a 500 on a search bar.
            services.EventLogger.warn("search", null, null,
                    "FTS lookup failed for conversations q='%s': %s"
                            .formatted(q, e.getMessage()));
            return null;
        }
    }

    /**
     * GET /api/conversations/{id} — JCLAW-171: return a single conversation row
     * in the same JSON shape as one element of {@link #listConversations}.
     * Replaces the broken pattern of asking the list endpoint for {@code ?id=N}
     * (which the list endpoint silently ignores, returning the most-recently-
     * updated row regardless of the requested id).
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConversationView.class)))
    public static void getConversation(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        renderJSON(gson.toJson(conversationToMap(conversation)));
    }

    /**
     * GET /api/conversations/{id}/messages
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = MessageView.class))))
    public static void getMessages(Long id, Integer limit, Integer offset) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 200;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        long total = Message.count("conversation = ?1", conversation);
        var query = Message.find("conversation = ?1 ORDER BY createdAt ASC", conversation);
        List<Message> messages = query.from(effectiveOffset).fetch(effectiveLimit);

        setPaginationHeaders(total);

        var result = messages.stream().map(ApiConversationsController::messageToMap).toList();

        renderJSON(gson.toJson(result));
    }

    private static HashMap<String, Object> messageToMap(Message m) {
        var map = new HashMap<String, Object>();
        map.put("id", m.id);
        map.put("role", m.role);
        map.put("content", m.content);
        // JCLAW-170: enrich each persisted tool call with the registry's
        // current {@code icon} hint so the chat UI can render historical
        // tool-call rows without maintaining a client-side name→icon
        // mapping. Parsed to a JsonArray so the enriched payload lands
        // as a real array in the response (not a stringified nested JSON).
        map.put("toolCalls", enrichToolCallsWithIcons(m.toolCalls));
        map.put("toolResults", m.toolResults);
        if (m.toolResultStructured != null) {
            map.put("toolResultStructured", com.google.gson.JsonParser.parseString(m.toolResultStructured));
        }
        // Include reasoning text so the collapsible thinking bubble
        // re-renders identically after a conversation reload. Null for
        // assistant turns without thinking and for user/tool rows.
        if (m.reasoning != null) map.put("reasoning", m.reasoning);
        map.put("createdAt", m.createdAt.toString());
        if (m.usageJson != null) {
            map.put("usage", com.google.gson.JsonParser.parseString(m.usageJson));
        }
        // JCLAW-267: marker for inline-mode subagent runs. The chat UI
        // folds consecutive messages sharing this id into a single
        // collapsible nested-turn block. Null for every non-inline-
        // subagent row (the dominant case) and omitted from the wire
        // shape so the frontend's `m.subagentRunId` test stays falsy.
        if (m.subagentRunId != null) {
            map.put("subagentRunId", m.subagentRunId);
        }
        // JCLAW-270: surface async-spawn announce metadata. The chat UI
        // switches to the structured-card render path whenever
        // {@code messageKind} is set; {@code metadata} carries the
        // kind-specific JSON payload (e.g. {runId, label, status,
        // reply, childConversationId} for "subagent_announce"). Both
        // fields are omitted entirely when null so the dominant
        // non-announce path stays small on the wire.
        if (m.messageKind != null) {
            map.put("messageKind", m.messageKind);
            if (m.metadata != null) {
                map.put("metadata", com.google.gson.JsonParser.parseString(m.metadata));
            }
        }
        // JCLAW-291: model-output truncation flag. Surfaced only when true
        // so the dominant non-truncated row stays small on the wire.
        if (m.truncated) {
            map.put("truncated", Boolean.TRUE);
        }
        // JCLAW-279: surface attachment metadata so the chat UI can render
        // download chips on conversation reload. Empty list rather than
        // absent so the frontend can rely on the field always being present.
        if (m.attachments != null && !m.attachments.isEmpty()) {
            map.put("attachments", attachmentsToList(m.attachments));
        }
        return map;
    }

    private static List<HashMap<String, Object>> attachmentsToList(
            java.util.List<models.MessageAttachment> attachments) {
        return attachments.stream().map(a -> {
            var av = new HashMap<String, Object>();
            av.put("uuid", a.uuid);
            av.put("originalFilename", a.originalFilename);
            av.put("mimeType", a.mimeType);
            av.put("sizeBytes", a.sizeBytes);
            av.put("kind", a.kind);
            return av;
        }).toList();
    }

    /**
     * GET /api/conversations/{id}/queue
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = QueueStatusResponse.class)))
    public static void getQueueStatus(Long id) {
        var busy = services.ConversationQueue.isBusy(id);
        var queueSize = services.ConversationQueue.getQueueSize(id);
        renderJSON(gson.toJson(new QueueStatusResponse(busy, queueSize)));
    }

    /**
     * DELETE /api/conversations/{id}/messages/{mid} — Remove a single message
     * from a conversation. Returns 404 when either the conversation or message
     * is missing, and 400 when the message belongs to a different conversation
     * (prevents a spoofed path from deleting a foreign message even if the
     * mid were somehow known).
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    public static void deleteMessage(Long id, Long mid) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        Message message = Message.findById(mid);
        if (message == null) notFound();
        if (message.conversation == null || !message.conversation.id.equals(id)) {
            badRequest();
        }
        services.Tx.run((Runnable) message::delete);
        renderJSON(gson.toJson(new StatusResponse("deleted")));
    }

    /**
     * DELETE /api/conversations/{id}
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    public static void deleteConversation(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        ConversationService.deleteByIds(List.of(id));
        renderJSON(gson.toJson(new StatusResponse("deleted")));
    }

    /**
     * DELETE /api/conversations — Bulk delete.
     *
     * <p>Two body shapes:
     * <ul>
     *   <li>{@code {"ids": [1, 2, 3]}} — delete the listed ids.</li>
     *   <li>{@code {"filter": {"channel": "...", "agentId": ..., "name": "...", "peer": "..."}}}
     *       — delete every row matching the filter, using the same predicates
     *       as the listing endpoint. Each filter field is optional; an empty
     *       filter object matches every conversation, mirroring the no-filter
     *       semantic of GET /api/conversations.</li>
     * </ul>
     *
     * <p>Rejects with 400 when neither shape is present — guards against an
     * accidental empty-body DELETE wiping the entire table without an
     * explicit "filter" intent from the caller.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DeletedCountResponse.class)))
    public static void deleteConversations() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        if (body.has("ids")) {
            var ids = body.getAsJsonArray("ids");
            var idList = new ArrayList<Long>();
            for (var elem : ids) idList.add(elem.getAsLong());
            if (idList.isEmpty()) {
                renderJSON(gson.toJson(new DeletedCountResponse(0)));
                return;
            }
            int deleted = ConversationService.deleteByIds(idList);
            renderJSON(gson.toJson(new DeletedCountResponse(deleted)));
            return;
        }

        if (body.has("filter")) {
            var f = body.getAsJsonObject("filter");
            String channel = stringField(f, "channel");
            Long agentId = longField(f, "agentId");
            String name = stringField(f, "name");
            String peer = stringField(f, "peer");
            int deleted = ConversationService.deleteByFilter(channel, agentId, name, peer);
            renderJSON(gson.toJson(new DeletedCountResponse(deleted)));
            return;
        }

        badRequest();
    }

    private static String stringField(com.google.gson.JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        var s = obj.get(key).getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Long longField(com.google.gson.JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsLong();
    }

    /**
     * GET /api/conversations/channels — Distinct channel types in use.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(type = "string"))))
    public static void listConversationChannels() {
        List<String> channels = JPA.em()
                .createQuery("SELECT DISTINCT c.channelType FROM Conversation c ORDER BY c.channelType", String.class)
                .getResultList();
        renderJSON(gson.toJson(channels));
    }

    /**
     * PUT /api/conversations/{id}/model-override (JCLAW-108)
     *
     * <p>Write a conversation-scoped model override. Body:
     * {@code {"modelProvider": "openrouter", "modelId": "google-flash"}}.
     * Both fields must be present and non-blank. The provider/model pair is
     * validated against {@link llm.ProviderRegistry} before the write lands —
     * invalid combinations return 400 with an explanatory message.
     *
     * <p>Counterpart to the chat-page model dropdown's onModelChange (which
     * now writes a conversation override instead of mutating the Agent row)
     * and to the {@code /model NAME} slash command (which shares validation
     * logic via {@link slash.Commands#performModelSwitch}).
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ModelOverrideRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ModelOverrideResponse.class)))
    public static void setModelOverride(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("modelProvider") || !body.has("modelId")) badRequest();
        var newProvider = body.get("modelProvider").getAsString();
        var newModelId = body.get("modelId").getAsString();
        if (newProvider == null || newProvider.isBlank()
                || newModelId == null || newModelId.isBlank()) badRequest();

        // Validate against ProviderRegistry — same checks as /model NAME.
        var provider = llm.ProviderRegistry.get(newProvider);
        if (provider == null) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse(
                    "Provider '" + newProvider + "' is not configured.")));
            return;
        }
        var modelExists = provider.config().models().stream()
                .anyMatch(m -> newModelId.equals(m.id()));
        if (!modelExists) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse(
                    "Provider '" + newProvider + "' has no model with id '" + newModelId + "'.")));
            return;
        }

        ConversationService.setModelOverride(conversation, newProvider, newModelId);
        renderJSON(gson.toJson(new ModelOverrideResponse(newProvider, newModelId)));
    }

    /**
     * DELETE /api/conversations/{id}/model-override (JCLAW-108)
     *
     * <p>Clear the conversation-scoped override, reverting to the agent's
     * default. Idempotent — returns 200 whether or not an override was set.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    public static void clearModelOverride(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        ConversationService.clearModelOverride(conversation);
        renderJSON(gson.toJson(new StatusResponse("cleared")));
    }

    // --- Helpers ---

    private static void setPaginationHeaders(long total) {
        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
    }

    /**
     * JCLAW-171: shared row→map serialization used by both the list endpoint
     * and the new single-conversation endpoint, so the wire shape stays
     * identical between them. Adding a field here surfaces it in both
     * places automatically — the alternative (two parallel inline blocks)
     * was the kind of subtle drift this codebase has accumulated bug fixes
     * for elsewhere.
     */
    private static Map<String, Object> conversationToMap(Conversation c) {
        var map = new HashMap<String, Object>();
        map.put("id", c.id);
        map.put("agentId", c.agent.id);
        map.put("agentName", c.agent.name);
        map.put("channelType", c.channelType);
        map.put("peerId", c.peerId);
        map.put("createdAt", c.createdAt.toString());
        map.put("updatedAt", c.updatedAt.toString());
        map.put("messageCount", c.messageCount);
        map.put("preview", c.preview != null ? c.preview : "");
        // JCLAW-108: expose override fields so the chat UI's model
        // dropdown can reflect the effective model for the open
        // conversation, and so the cost aggregator's per-turn attribution
        // knows whether subsequent turns inherit from the agent or from
        // a persisted override.
        map.put("modelProviderOverride", c.modelProviderOverride);
        map.put("modelIdOverride", c.modelIdOverride);
        // JCLAW-267: surface the parent-conversation FK so the sidebar can
        // render a "child of parent X" badge for session-mode subagent
        // conversations (they live as their own rows alongside top-level
        // chats; the badge tells the operator at a glance which ones are
        // delegated runs vs. user-initiated). Null for top-level convos.
        if (c.parentConversation != null) {
            map.put("parentConversationId", c.parentConversation.id);
        }
        // JCLAW: count of SessionCompaction rows for this conversation,
        // surfaced to the chat-header context meter so the operator can
        // see at a glance whether the displayed "current context" reading
        // is a fresh prompt or has been reset by compaction (and how many
        // times). Cheap COUNT on an indexed column; no need to denormalize.
        map.put("compactionCount", models.SessionCompaction.count("conversation = ?1", c));
        return map;
    }

    /**
     * JCLAW-170: stamp the current registry's {@link agents.ToolRegistry#iconFor}
     * hint onto each persisted tool-call entry on the way out of /messages, and
     * normalize the shape to a JSON array.
     *
     * <p>The persisted column holds a single ToolCall object per assistant row
     * ({@code gson.toJson(tc)} in {@code AgentRunner.executeToolsParallel})
     * rather than a proper array — each tool invocation gets its own row so
     * the persisted shape is {@code {id, type, function: {name, arguments}}}
     * for one call or, in theory, an array for callers that batch. This
     * helper accepts either shape, adds an {@code icon} sibling to each
     * call object, and always returns an array so the frontend's hydrator
     * can iterate uniformly. Returns {@code null} on null/blank input or
     * malformed JSON so the UI's guard on missing tool-call columns still
     * works.
     */
    private static com.google.gson.JsonArray enrichToolCallsWithIcons(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) return null;
        try {
            var parsed = com.google.gson.JsonParser.parseString(toolCallsJson);
            com.google.gson.JsonArray arr;
            if (parsed.isJsonArray()) {
                arr = parsed.getAsJsonArray();
            }
            else if (parsed.isJsonObject()) {
                arr = new com.google.gson.JsonArray();
                arr.add(parsed.getAsJsonObject());
            }
            else {
                return null;
            }
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                var obj = el.getAsJsonObject();
                String name = null;
                if (obj.has(KEY_FUNCTION) && obj.get(KEY_FUNCTION).isJsonObject()) {
                    var fn = obj.getAsJsonObject(KEY_FUNCTION);
                    if (fn.has("name")) name = fn.get("name").getAsString();
                }
                obj.addProperty("icon", agents.ToolRegistry.iconFor(name));
            }
            return arr;
        } catch (Exception _) {
            // Malformed persisted JSON — drop to null rather than crash the /messages endpoint.
            return null;
        }
    }
}
