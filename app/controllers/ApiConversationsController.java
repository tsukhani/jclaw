package controllers;

import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import llm.ProviderRegistry;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.SessionCompaction;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.ApiResponses;
import utils.JpqlFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static utils.GsonHolder.GSON;

/**
 * CRUD and query endpoints for conversations. Extracted from ApiChatController
 * (which retains sync chat dispatch, SSE streaming, and file upload) to keep
 * each controller focused on a single responsibility.
 */
@With(AuthCheck.class)
public class ApiConversationsController extends Controller {

    private static final Gson gson = GSON;

    // OpenAI-shaped tool-call element key (the wrapping "function" object).
    private static final String KEY_FUNCTION = "function";
    // Conversation field/JSON keys reused across the filter, sort whitelist, and view mappers.
    private static final String CHANNEL_TYPE = "channelType";
    private static final String CREATED_AT = "createdAt";
    // Conversation.preview is @Column(length = 100), so a longer name cannot be stored.
    private static final int MAX_NAME_LENGTH = 100;

    public record ConversationView(Long id, Long agentId, String agentName, String channelType,
                                   String peerId, String createdAt, String updatedAt,
                                   long messageCount, String preview,
                                   boolean starred, boolean pinned,
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
     * @param truncated            {@code true} when the model output was
     *                             truncated; emitted only when true (JCLAW-291)
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
                              Boolean truncated,
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
     * @param generated        {@code true} when the attachment was produced by a
     *                         tool (e.g. image-gen) rather than user-uploaded;
     *                         the chat UI badges these (JCLAW-227)
     * @param deleted          {@code true} once removed from the workspace; the
     *                         chip shows a "deleted" marker (JCLAW-209)
     * @param generationMetadata optional generation parameters JSON, present only
     *                         for tool-generated attachments
     * @param generationJobId  optional id of the generation job the chat polls for
     *                         status; present only while a generation is in flight
     *                         (JCLAW-234)
     */
    public record MessageAttachmentView(String uuid, String originalFilename,
                                        String mimeType, long sizeBytes, String kind,
                                        boolean generated, boolean deleted,
                                        String generationMetadata, Long generationJobId) {}

    public record QueueStatusResponse(boolean busy, int queueSize) {}

    public record StatusResponse(String status) {}

    public record DeletedCountResponse(int deleted) {}

    public record DeleteByIdsRequest(List<Long> ids) {}

    public record DeleteFilter(String channel, Long agentId, String name, String peer,
                              Boolean starred, String q) {}

    public record DeleteByFilterRequest(DeleteFilter filter) {}

    public record RenameRequest(String name) {}

    public record NameResponse(String name) {}

    public record ModelOverrideRequest(String modelProvider, String modelId) {}

    public record ModelOverrideResponse(String modelProvider, String modelId) {}

    /**
     * GET /api/conversations — List conversations with optional filters.
     *
     * <p>The {@code name} filter does a case-insensitive substring match on
     * the conversation's preview text directly at the DB level. The previous
     * implementation pulled every LIKE-matching row into Hibernate's identity
     * map and then refined with a Java word-boundary regex — at 50k+ rows
     * with a common search term that materialized the whole match set on
     * every keystroke. Substring is the standard chat-search semantic
     * (Slack, Discord, etc.); the upside of word-boundary refinement was
     * not worth the unbounded fetch cost.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ConversationView.class))))
    @Operation(summary = "List conversations with optional channel, agent, name, peer, starred, pinned, and full-text (q) filters, paginated")
    public static void listConversations(String channel, Long agentId, String name, String peer,
                                          String q, Boolean starred, Boolean pinned,
                                          String sort, String dir, Integer limit, Integer offset) {
        boolean hasNameFilter = name != null && !name.isBlank();

        // starred and pinned are tri-state: absent means "no constraint", which
        // is what every caller other than the /conversations page sends — the
        // chat sidebar and the command palette must keep seeing pinned rows.
        // That page asks for pinned=false in the paginated list and pinned=true
        // for the section above it, so no row can appear in both.
        var filter = new JpqlFilter()
                .eq(CHANNEL_TYPE, channel)
                .eq("agent.id", agentId)
                .like("LOWER(preview)", hasNameFilter ? "%" + name.toLowerCase() + "%" : null)
                .like("LOWER(peerId)", peer != null && !peer.isBlank() ? "%" + peer.toLowerCase() + "%" : null)
                .eq("starred", starred)
                .eq("pinned", pinned);

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

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, PagedJpqlQuery.MAX_LIMIT) : 20;
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
        // JCLAW-722: WHERE -> bind -> COUNT -> paginate assembled once, so the
        // COUNT and the SELECT can't drift apart. The COUNT drops the JOIN FETCH
        // (fetch joins are meaningless on a COUNT) but shares the WHERE + params.
        var page = PagedJpqlQuery.of(Conversation.class, "Conversation c", "c")
                .joinFetch("JOIN FETCH c.agent")
                .where(fullWhere)
                .positionalParams(filter.paramList())
                .namedParam("fts", ftsConvIds)
                .orderBy(orderByClause(sort, dir))
                .page(effectiveOffset, effectiveLimit)
                .execute();
        List<Conversation> convos = page.rows();

        setPaginationHeaders(page.total());

        // Bulk-fetch SessionCompaction counts for the page in one GROUP BY pass
        // so conversationToMap doesn't fire a per-row COUNT (N+1, up to 100/call).
        // Mirrors the lastFiredAt bulk pattern in ApiTasksController.list;
        // conversations with no compactions are absent from the map (default 0).
        var convIds = convos.stream().map(c -> c.id).filter(Objects::nonNull).toList();
        Map<Long, Long> compactionCounts = Map.of();
        if (!convIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            var rows = (List<Object[]>) JPA.em().createQuery(
                    "SELECT sc.conversation.id, COUNT(sc) FROM SessionCompaction sc "
                            + "WHERE sc.conversation.id IN :ids GROUP BY sc.conversation.id")
                    .setParameter("ids", convIds)
                    .getResultList();
            var m = HashMap.<Long, Long>newHashMap(rows.size());
            for (var row : rows) {
                m.put((Long) row[0], (Long) row[1]);
            }
            compactionCounts = m;
        }
        final var counts = compactionCounts;
        var result = convos.stream()
                .map(c -> conversationToMap(c, counts.getOrDefault(c.id, 0L)))
                .toList();

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
    @SuppressWarnings("java:S1168") // null vs empty-list is a deliberate tri-state: null = "no q filter"; empty = "matched nothing, render zero rows"; non-empty = "narrow" (see listConversations)
    private static @Nullable List<Long> ftsConversationIds(String q) {
        if (q == null || q.isBlank()) return null;
        try {
            var convIds = searchConversationIds(q);
            return convIds.isEmpty() ? List.of() : convIds;
        } catch (IOException e) {
            // FTS backend unreachable — surface as "no FTS filter" rather
            // than fail the whole list. The operator sees the equality
            // filters' result set and the absence of FTS-narrowed hits;
            // less surprising than a 500 on a search bar.
            EventLogger.warn("search", null, null,
                    "FTS lookup failed for conversations q='%s': %s"
                            .formatted(q, e.getMessage()));
            return null;
        }
    }

    /**
     * Resolve {@code q} to the conversation ids whose messages match, letting an
     * unreachable index surface as {@link IOException}.
     *
     * <p>{@link #ftsConversationIds} is the degrade-open wrapper around this, and
     * is right for the listing endpoint: a wider result set is harmless to look
     * at. Callers that act on the scope rather than display it — the bulk delete —
     * call this directly, because "no FTS constraint" would widen what they
     * destroy.
     */
    private static List<Long> searchConversationIds(String q) throws IOException {
        // A term matching more than 500 messages truncates silently, so a common
        // word under-reports conversations rather than erroring (JCLAW-1064).
        var messageIds = MessageSearch.searchIds(
                LuceneIndexer.Scope.CONVERSATION_MESSAGE, q, 500);
        if (messageIds.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        var convIds = (List<Long>) JPA.em()
                .createQuery("SELECT DISTINCT m.conversation.id FROM Message m WHERE m.id IN :ids")
                .setParameter("ids", messageIds)
                .getResultList();
        return convIds;
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
    @Operation(summary = "Get a single conversation by id, in the same shape as one list row")
    public static void getConversation(Long id) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        renderJSON(gson.toJson(conversationToMap(conversation,
                SessionCompaction.count("conversation = ?1", conversation))));
    }

    /**
     * GET /api/conversations/{id}/messages
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = MessageView.class))))
    @Operation(summary = "List a conversation's messages in ascending order, paginated")
    public static void getMessages(Long id, Integer limit, Integer offset) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 200;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        long total = Message.count("conversation = ?1", conversation);
        var query = Message.find("conversation = ?1 ORDER BY createdAt ASC", conversation);
        List<Message> messages = query.from(effectiveOffset).fetch(effectiveLimit);

        setPaginationHeaders(total);

        // JCLAW-806: bulk-fetch this page's attachments in one IN-clause query and
        // group by message id, instead of touching m.attachments lazily per row
        // (a PersistentBag init per message = N+1, up to 500/page). A LEFT JOIN
        // FETCH on the to-many collection can't be combined with from()/fetch()
        // pagination (Hibernate HHH000104 in-memory pagination), so the separate
        // bulk-fetch-then-group is the safe shape. Mirrors the compactionCounts
        // bulk-group in listConversations.
        var attachmentsByMessage = attachmentsForMessages(messages);
        var result = messages.stream()
                .map(m -> messageToMap(m, attachmentsByMessage.getOrDefault(m.id, List.of())))
                .toList();

        renderJSON(gson.toJson(result));
    }

    /**
     * JCLAW-806: one IN-clause query for every {@link MessageAttachment} on the
     * given page of messages, grouped into {@code messageId -> attachments}
     * (id-ascending, matching the entity's {@code @OrderBy}). Messages with no
     * attachments are simply absent from the map. Returns an empty map for an
     * empty page.
     */
    private static Map<Long, List<MessageAttachment>> attachmentsForMessages(List<Message> messages) {
        var msgIds = messages.stream().map(m -> m.id).filter(Objects::nonNull).toList();
        if (msgIds.isEmpty()) return Map.of();
        List<MessageAttachment> rows = JPA.em().createQuery(
                "SELECT a FROM MessageAttachment a WHERE a.message.id IN :ids ORDER BY a.id ASC",
                MessageAttachment.class)
                .setParameter("ids", msgIds)
                .getResultList();
        var grouped = new HashMap<Long, List<MessageAttachment>>();
        for (var a : rows) {
            grouped.computeIfAbsent(a.message.id, _ -> new ArrayList<>()).add(a);
        }
        return grouped;
    }

    private static HashMap<String, Object> messageToMap(Message m, List<MessageAttachment> attachments) {
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
            map.put("toolResultStructured", JsonParser.parseString(m.toolResultStructured));
        }
        // Include reasoning text so the collapsible thinking bubble
        // re-renders identically after a conversation reload. Null for
        // assistant turns without thinking and for user/tool rows.
        if (m.reasoning != null) map.put("reasoning", m.reasoning);
        map.put(CREATED_AT, m.createdAt.toString());
        if (m.usageJson != null) {
            map.put("usage", JsonParser.parseString(m.usageJson));
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
                map.put("metadata", JsonParser.parseString(m.metadata));
            }
        }
        // JCLAW-291: model-output truncation flag. Surfaced only when true
        // so the dominant non-truncated row stays small on the wire.
        if (m.truncated) {
            map.put("truncated", Boolean.TRUE);
        }
        // JCLAW-279: surface attachment metadata so the chat UI can render
        // download chips on conversation reload. JCLAW-806: sourced from the
        // page-level bulk fetch (see attachmentsForMessages) rather than the
        // lazy m.attachments bag, so this stays N+1-free.
        if (!attachments.isEmpty()) {
            map.put("attachments", attachmentsToList(attachments));
        }
        return map;
    }

    private static List<HashMap<String, Object>> attachmentsToList(
            List<MessageAttachment> attachments) {
        return attachments.stream().map(a -> {
            var av = new HashMap<String, Object>();
            av.put("uuid", a.uuid);
            av.put("originalFilename", a.originalFilename);
            av.put("mimeType", a.mimeType);
            av.put("sizeBytes", a.sizeBytes);
            av.put("kind", a.kind);
            av.put("generated", a.generated); // JCLAW-227: chat UI badges tool-generated images
            av.put("deleted", a.deleted); // JCLAW-209: chip shows a "deleted from workspace" marker
            if (a.generationMetadata != null) av.put("generationMetadata", a.generationMetadata);
            if (a.generationJobId != null) av.put("generationJobId", a.generationJobId); // JCLAW-234: chat polls this job's status
            return av;
        }).toList();
    }

    /**
     * GET /api/conversations/{id}/queue
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = QueueStatusResponse.class)))
    @Operation(summary = "Get the busy flag and queued-message count for a conversation")
    public static void getQueueStatus(Long id) {
        var busy = ConversationQueue.isBusy(id);
        var queueSize = ConversationQueue.getQueueSize(id);
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
    @ChatHidden("destructive history deletion")
    public static void deleteMessage(Long id, Long mid) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        Message message = ConversationService.findMessageById(mid);
        if (message == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        if (message.conversation == null || !message.conversation.id.equals(id)) {
            badRequest();
        }
        ConversationService.deleteMessage(mid);
        renderJSON(gson.toJson(new StatusResponse("deleted")));
    }

    /**
     * DELETE /api/conversations/{id}
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @ChatHidden("destructive history deletion")
    public static void deleteConversation(Long id) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        ConversationService.deleteByIds(List.of(id));
        renderJSON(gson.toJson(new StatusResponse("deleted")));
    }

    /**
     * DELETE /api/conversations — Bulk delete.
     *
     * <p>Two body shapes:
     * <ul>
     *   <li>{@code {"ids": [1, 2, 3]}} — delete the listed ids.</li>
     *   <li>{@code {"filter": {"channel": "...", "agentId": ..., "name": "...",
     *       "peer": "...", "starred": true, "q": "..."}}} — delete every row
     *       matching the filter, using the same predicates as the listing
     *       endpoint, {@code q} included: the page quotes a filtered count in
     *       its confirm dialog, so every key it can filter by has to narrow the
     *       delete too. Each field is optional; an empty filter object matches
     *       every conversation the list would show, mirroring the no-filter
     *       semantic of GET /api/conversations. Pinned conversations are never
     *       in scope (see {@link ConversationService#deleteByFilter}).</li>
     * </ul>
     *
     * <p>Rejects with 400 when neither shape is present — guards against an
     * accidental empty-body DELETE wiping the entire table without an
     * explicit "filter" intent from the caller.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DeletedCountResponse.class)))
    @ChatHidden("destructive bulk history deletion -- wipes conversations")
    public static void deleteConversations() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }

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
            Boolean starred = booleanField(f, "starred");
            String q = stringField(f, "q");
            List<Long> ftsIds = null;
            if (q != null) {
                try {
                    ftsIds = searchConversationIds(q);
                } catch (IOException e) {
                    // Fail closed where the listing endpoint degrades open: treating
                    // an unreachable index as "no keyword constraint" would delete
                    // every row the other filters match, rather than the handful the
                    // operator counted before confirming.
                    ApiResponses.errorAndLog(e, 503, ApiResponses.SEARCH_FAILED,
                            "Search is unavailable, so the scope of this delete cannot be "
                                    + "resolved. Retry, or drop the q: filter.");
                    return;
                }
            }
            int deleted = ConversationService.deleteByFilter(channel, agentId, name, peer,
                    starred, ftsIds);
            renderJSON(gson.toJson(new DeletedCountResponse(deleted)));
            return;
        }

        badRequest();
    }

    private static @Nullable String stringField(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        var s = obj.get(key).getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static @Nullable Boolean booleanField(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsBoolean();
    }

    private static @Nullable Long longField(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsLong();
    }

    /**
     * GET /api/conversations/channels — Distinct channel types in use.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(type = "string"))))
    @Operation(summary = "List the distinct channel types currently in use across conversations")
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
    @Operation(summary = "Set a conversation-scoped model provider/model override, validated against the provider registry")
    public static void setModelOverride(Long id) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("modelProvider") || !body.has("modelId")) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var newProvider = body.get("modelProvider").getAsString();
        var newModelId = body.get("modelId").getAsString();
        if (newProvider == null || newProvider.isBlank()
                || newModelId == null || newModelId.isBlank()) badRequest();

        // Validate against ProviderRegistry — same checks as /model NAME.
        var provider = ProviderRegistry.get(newProvider);
        if (provider == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Provider '" + newProvider + "' is not configured.");
            return;
        }
        var modelExists = provider.config().models().stream()
                .anyMatch(m -> newModelId.equals(m.id()));
        if (!modelExists) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Provider '" + newProvider + "' has no model with id '" + newModelId + "'.");
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
    @Operation(summary = "Clear a conversation's model override, reverting to the agent default (idempotent)")
    public static void clearModelOverride(Long id) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        ConversationService.clearModelOverride(conversation);
        renderJSON(gson.toJson(new StatusResponse("cleared")));
    }

    /**
     * PUT /api/conversations/{id}/name
     *
     * <p>Body: {@code {"name": "Invoice thread"}}. Writes {@link models.Conversation#preview}
     * itself, so the new name reaches the chat header, the command palette and the
     * detail page as well as the list — every surface already reads that field.
     *
     * <p>A blank name or one over the column's 100-character cap is a 400 rather
     * than a truncation, so what is stored is never quietly different from what
     * the operator typed.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = RenameRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = NameResponse.class)))
    @Operation(summary = "Rename a conversation; the name is non-blank and at most 100 characters")
    public static void renameConversation(Long id) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        var body = JsonBodyReader.readJsonBody();
        var name = body == null ? null : stringField(body, "name");
        if (name == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "A non-blank 'name' is required.");
            return;
        }
        var trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Name must be at most " + MAX_NAME_LENGTH + " characters.");
            return;
        }

        ConversationService.rename(conversation, trimmed);
        renderJSON(gson.toJson(new NameResponse(trimmed)));
    }

    /**
     * PUT /api/conversations/{id}/star — mark the conversation a favorite.
     * Idempotent; the list's {@code starred=true} filter reads the same column.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @Operation(summary = "Star a conversation (idempotent)")
    public static void starConversation(Long id) {
        ConversationService.setStarred(requireConversation(id), true);
        renderJSON(gson.toJson(new StatusResponse("starred")));
    }

    /** DELETE /api/conversations/{id}/star — clear the favorite marker. Idempotent. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @Operation(summary = "Unstar a conversation (idempotent)")
    public static void unstarConversation(Long id) {
        ConversationService.setStarred(requireConversation(id), false);
        renderJSON(gson.toJson(new StatusResponse("unstarred")));
    }

    /**
     * PUT /api/conversations/{id}/pin — move the conversation into the pinned
     * section above the list. Idempotent, but 409s once
     * {@link ConversationService#MAX_PINNED} are already pinned: the alternative
     * (evicting the oldest pin) would silently undo an operator's choice.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @ApiResponse(responseCode = "409", description = "The pinned-conversation cap is already reached")
    @Operation(summary = "Pin a conversation, up to a cap of 10 (idempotent below the cap)")
    public static void pinConversation(Long id) {
        if (!ConversationService.pin(requireConversation(id))) {
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "At most " + ConversationService.MAX_PINNED
                            + " conversations can be pinned. Unpin one first.");
            return;
        }
        renderJSON(gson.toJson(new StatusResponse("pinned")));
    }

    /** DELETE /api/conversations/{id}/pin — return the conversation to the list. Idempotent. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @Operation(summary = "Unpin a conversation (idempotent)")
    public static void unpinConversation(Long id) {
        ConversationService.unpin(requireConversation(id));
        renderJSON(gson.toJson(new StatusResponse("unpinned")));
    }

    // --- Helpers ---

    /** Load the addressed conversation or end the request with a 404. */
    private static Conversation requireConversation(Long id) {
        Conversation conversation = ConversationService.findById(id);
        if (conversation == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        return conversation;
    }

    private static void setPaginationHeaders(long total) {
        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
    }

    /**
     * Map the DataTable sort column + direction to an ORDER BY. Columns are a
     * closed whitelist keyed by the frontend column ids; direction resolves to
     * the ASC/DESC literal, so the concatenated JPQL carries no user input. An
     * absent/unknown column falls back to the recency default (newest first).
     * A stable id tiebreak keeps paging deterministic across equal sort keys.
     */
    private static String orderByClause(String sort, String dir) {
        String col = switch (sort == null ? "" : sort) {
            case "preview" -> "c.preview";
            case CHANNEL_TYPE -> "c.channelType";
            case "agentName" -> "c.agent.name";
            case "peerId" -> "c.peerId";
            case "messageCount" -> "c.messageCount";
            case CREATED_AT -> "c.createdAt";
            case "updatedAt" -> "c.updatedAt";
            default -> null;
        };
        if (col == null) return "ORDER BY c.updatedAt DESC";
        String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
        return "ORDER BY " + col + " " + direction + ", c.id ASC";
    }

    /**
     * JCLAW-171: shared row→map serialization used by both the list endpoint
     * and the new single-conversation endpoint, so the wire shape stays
     * identical between them. Adding a field here surfaces it in both
     * places automatically — the alternative (two parallel inline blocks)
     * was the kind of subtle drift this codebase has accumulated bug fixes
     * for elsewhere.
     */
    private static Map<String, Object> conversationToMap(Conversation c, long compactionCount) {
        var map = new HashMap<String, Object>();
        map.put("id", c.id);
        map.put("agentId", c.agent.id);
        map.put("agentName", c.agent.name);
        map.put(CHANNEL_TYPE, c.channelType);
        map.put("peerId", c.peerId);
        map.put(CREATED_AT, c.createdAt.toString());
        map.put("updatedAt", c.updatedAt.toString());
        map.put("messageCount", c.messageCount);
        map.put("preview", c.preview != null ? c.preview : "");
        map.put("starred", c.starred);
        map.put("pinned", c.pinned);
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
        map.put("compactionCount", compactionCount);
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
    private static @Nullable JsonArray enrichToolCallsWithIcons(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) return null;
        try {
            var parsed = JsonParser.parseString(toolCallsJson);
            JsonArray arr;
            if (parsed.isJsonArray()) {
                arr = parsed.getAsJsonArray();
            }
            else if (parsed.isJsonObject()) {
                arr = new JsonArray();
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
                obj.addProperty("icon", ToolRegistry.iconFor(name));
            }
            return arr;
        } catch (Exception _) {
            // Malformed persisted JSON — drop to null rather than crash the /messages endpoint.
            return null;
        }
    }
}
