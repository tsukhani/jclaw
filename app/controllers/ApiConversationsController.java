package controllers;

import com.google.gson.Gson;
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
    public static void listConversations(String channel, Long agentId, String name, String peer, Integer limit, Integer offset) {
        boolean hasNameFilter = name != null && !name.isBlank();

        var filter = new JpqlFilter()
                .eq("channelType", channel)
                .eq("agent.id", agentId)
                .like("LOWER(preview)", hasNameFilter ? "%" + name.toLowerCase() + "%" : null)
                .like("LOWER(peerId)", peer != null && !peer.isBlank() ? "%" + peer.toLowerCase() + "%" : null);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var where = filter.toWhereClause();
        String jpql = where.isEmpty()
                ? "SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC"
                : "SELECT c FROM Conversation c JOIN FETCH c.agent WHERE " + where + " ORDER BY c.updatedAt DESC";
        var q = JPA.em().createQuery(jpql, Conversation.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }

        String countJpql = where.isEmpty()
                ? "SELECT COUNT(c) FROM Conversation c"
                : "SELECT COUNT(c) FROM Conversation c WHERE " + where;
        var countQ = JPA.em().createQuery(countJpql, Long.class);
        for (int i = 0; i < params.size(); i++) {
            countQ.setParameter(i + 1, params.get(i));
        }
        long total = countQ.getSingleResult();
        List<Conversation> convos = q.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

        setPaginationHeaders(total);

        var result = convos.stream().map(ApiConversationsController::conversationToMap).toList();

        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/conversations/{id} — JCLAW-171: return a single conversation row
     * in the same JSON shape as one element of {@link #listConversations}.
     * Replaces the broken pattern of asking the list endpoint for {@code ?id=N}
     * (which the list endpoint silently ignores, returning the most-recently-
     * updated row regardless of the requested id).
     */
    public static void getConversation(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        renderJSON(gson.toJson(conversationToMap(conversation)));
    }

    /**
     * GET /api/conversations/{id}/messages
     */
    public static void getMessages(Long id, Integer limit, Integer offset) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 200;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        long total = Message.count("conversation = ?1", conversation);
        var query = Message.find("conversation = ?1 ORDER BY createdAt ASC", conversation);
        List<Message> messages = query.from(effectiveOffset).fetch(effectiveLimit);

        setPaginationHeaders(total);

        var jsonParser = new com.google.gson.JsonParser();
        var result = messages.stream().map(m -> {
            var map = new HashMap<String, Object>();
            map.put("id", m.id);
            map.put("role", m.role);
            map.put("content", m.content);
            // JCLAW-170: enrich each persisted tool call with the registry's
            // current {@code icon} hint so the chat UI can render historical
            // tool-call rows without maintaining a client-side name→icon
            // mapping. Parsed to a JsonArray so the enriched payload lands
            // as a real array in the response (not a stringified nested JSON).
            map.put("toolCalls", enrichToolCallsWithIcons(m.toolCalls, jsonParser));
            map.put("toolResults", m.toolResults);
            if (m.toolResultStructured != null) {
                map.put("toolResultStructured", jsonParser.parse(m.toolResultStructured));
            }
            // Include reasoning text so the collapsible thinking bubble
            // re-renders identically after a conversation reload. Null for
            // assistant turns without thinking and for user/tool rows.
            if (m.reasoning != null) map.put("reasoning", m.reasoning);
            map.put("createdAt", m.createdAt.toString());
            if (m.usageJson != null) {
                map.put("usage", jsonParser.parse(m.usageJson));
            }
            return map;
        }).toList();

        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/conversations/{id}/queue
     */
    public static void getQueueStatus(Long id) {
        var busy = services.ConversationQueue.isBusy(id);
        var queueSize = services.ConversationQueue.getQueueSize(id);
        renderJSON(gson.toJson(Map.of("busy", busy, "queueSize", queueSize)));
    }

    /**
     * DELETE /api/conversations/{id}/messages/{mid} — Remove a single message
     * from a conversation. Returns 404 when either the conversation or message
     * is missing, and 400 when the message belongs to a different conversation
     * (prevents a spoofed path from deleting a foreign message even if the
     * mid were somehow known).
     */
    public static void deleteMessage(Long id, Long mid) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        Message message = Message.findById(mid);
        if (message == null) notFound();
        if (message.conversation == null || !message.conversation.id.equals(id)) {
            badRequest();
        }
        services.Tx.run(() -> { message.delete(); });
        renderJSON(gson.toJson(Map.of("status", "deleted")));
    }

    /**
     * DELETE /api/conversations/{id}
     */
    public static void deleteConversation(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        ConversationService.deleteByIds(List.of(id));
        renderJSON(gson.toJson(Map.of("status", "deleted")));
    }

    /**
     * DELETE /api/conversations — Bulk delete by IDs.
     */
    public static void deleteConversations() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("ids")) badRequest();

        var ids = body.getAsJsonArray("ids");
        var idList = new ArrayList<Long>();
        for (var elem : ids) idList.add(elem.getAsLong());
        if (idList.isEmpty()) {
            renderJSON(gson.toJson(Map.of("deleted", 0)));
            return;
        }

        int deleted = ConversationService.deleteByIds(idList);
        renderJSON(gson.toJson(Map.of("deleted", deleted)));
    }

    /**
     * GET /api/conversations/channels — Distinct channel types in use.
     */
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
            renderJSON(gson.toJson(Map.of("error",
                    "Provider '" + newProvider + "' is not configured.")));
            return;
        }
        var modelExists = provider.config().models().stream()
                .anyMatch(m -> newModelId.equals(m.id()));
        if (!modelExists) {
            response.status = 400;
            renderJSON(gson.toJson(Map.of("error",
                    "Provider '" + newProvider + "' has no model with id '" + newModelId + "'.")));
            return;
        }

        ConversationService.setModelOverride(conversation, newProvider, newModelId);
        renderJSON(gson.toJson(Map.of(
                "modelProvider", newProvider,
                "modelId", newModelId)));
    }

    /**
     * DELETE /api/conversations/{id}/model-override (JCLAW-108)
     *
     * <p>Clear the conversation-scoped override, reverting to the agent's
     * default. Idempotent — returns 200 whether or not an override was set.
     */
    public static void clearModelOverride(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();
        ConversationService.clearModelOverride(conversation);
        renderJSON(gson.toJson(Map.of("status", "cleared")));
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
    private static com.google.gson.JsonArray enrichToolCallsWithIcons(String toolCallsJson,
                                                                       com.google.gson.JsonParser parser) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) return null;
        try {
            var parsed = parser.parse(toolCallsJson);
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
                if (obj.has("function") && obj.get("function").isJsonObject()) {
                    var fn = obj.getAsJsonObject("function");
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
