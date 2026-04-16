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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static utils.GsonHolder.INSTANCE;

/**
 * CRUD and query endpoints for conversations. Extracted from ApiChatController
 * (which retains sync chat dispatch, SSE streaming, and file upload) to keep
 * each controller focused on a single responsibility.
 */
@With(AuthCheck.class)
public class ApiConversationsController extends Controller {

    private static final Gson gson = INSTANCE;

    /** Small LRU cache of compiled word-boundary patterns keyed by the search string. */
    private static final int PATTERN_CACHE_MAX = 64;
    private static final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private static Pattern wordBoundaryPattern(String name) {
        var key = name.strip().toLowerCase();
        return patternCache.computeIfAbsent(key, k -> {
            if (patternCache.size() > PATTERN_CACHE_MAX) patternCache.clear();
            return Pattern.compile("\\b" + Pattern.quote(name.strip()) + "\\b", Pattern.CASE_INSENSITIVE);
        });
    }

    /**
     * GET /api/conversations — List conversations with optional filters.
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

        List<Conversation> convos;
        long total;

        if (hasNameFilter) {
            List<Conversation> candidates = q.getResultList();
            var pattern = wordBoundaryPattern(name);
            List<Conversation> refined = new ArrayList<>();
            for (var c : candidates) {
                if (c.preview != null && pattern.matcher(c.preview).find()) {
                    refined.add(c);
                }
            }
            total = refined.size();
            int from = Math.min(effectiveOffset, refined.size());
            int to = Math.min(from + effectiveLimit, refined.size());
            convos = refined.subList(from, to);
        } else {
            String countJpql = where.isEmpty()
                    ? "SELECT COUNT(c) FROM Conversation c"
                    : "SELECT COUNT(c) FROM Conversation c WHERE " + where;
            var countQ = JPA.em().createQuery(countJpql, Long.class);
            for (int i = 0; i < params.size(); i++) {
                countQ.setParameter(i + 1, params.get(i));
            }
            total = countQ.getSingleResult();
            convos = q.setFirstResult(effectiveOffset)
                    .setMaxResults(effectiveLimit).getResultList();
        }

        setPaginationHeaders(total);

        var result = convos.stream().map(c -> {
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
            return map;
        }).toList();

        renderJSON(gson.toJson(result));
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
            map.put("toolCalls", m.toolCalls);
            map.put("toolResults", m.toolResults);
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
     * POST /api/conversations/{id}/generate-title
     */
    public static void generateTitle(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        if (!ConversationService.requestTitleGeneration(conversation)) {
            renderJSON(gson.toJson(Map.of("title", conversation.preview != null ? conversation.preview : "")));
            return;
        }
        renderJSON(gson.toJson(Map.of("status", "generating")));
    }

    // --- Helpers ---

    private static void setPaginationHeaders(long total) {
        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
    }
}
