package controllers;

import com.google.gson.Gson;
import models.Conversation;
import models.Message;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.ConversationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD and query endpoints for conversations. Extracted from ApiChatController
 * (which retains sync chat dispatch, SSE streaming, and file upload) to keep
 * each controller focused on a single responsibility.
 */
@With(AuthCheck.class)
public class ApiConversationsController extends Controller {

    private static final Gson gson = new Gson();

    /**
     * GET /api/conversations — List conversations with optional filters.
     */
    public static void listConversations(String channel, Long agentId, String name, String peer, Integer limit, Integer offset) {
        var query = new StringBuilder();
        var params = new ArrayList<>();
        int idx = 1;

        if (channel != null && !channel.isBlank()) {
            query.append("channelType = ?%d".formatted(idx++));
            params.add(channel);
        }
        if (agentId != null) {
            if (!query.isEmpty()) query.append(" AND ");
            query.append("agent.id = ?%d".formatted(idx++));
            params.add(agentId);
        }
        boolean hasNameFilter = name != null && !name.isBlank();
        if (hasNameFilter) {
            if (!query.isEmpty()) query.append(" AND ");
            query.append("LOWER(preview) LIKE ?%d".formatted(idx++));
            params.add("%" + name.toLowerCase() + "%");
        }
        if (peer != null && !peer.isBlank()) {
            if (!query.isEmpty()) query.append(" AND ");
            query.append("LOWER(peerId) LIKE ?%d".formatted(idx++));
            params.add("%" + peer.toLowerCase() + "%");
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        String jpql = query.isEmpty()
                ? "SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC"
                : "SELECT c FROM Conversation c JOIN FETCH c.agent WHERE " + query + " ORDER BY c.updatedAt DESC";
        var q = JPA.em().createQuery(jpql, Conversation.class);
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }

        List<Conversation> convos;
        long total;

        if (hasNameFilter) {
            List<Conversation> candidates = q.getResultList();
            var pattern = java.util.regex.Pattern.compile(
                    "\\b" + java.util.regex.Pattern.quote(name.trim()) + "\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
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
            String countJpql = query.isEmpty()
                    ? "SELECT COUNT(c) FROM Conversation c"
                    : "SELECT COUNT(c) FROM Conversation c WHERE " + query;
            var countQ = JPA.em().createQuery(countJpql, Long.class);
            for (int i = 0; i < params.size(); i++) {
                countQ.setParameter(i + 1, params.get(i));
            }
            total = countQ.getSingleResult();
            convos = q.setFirstResult(effectiveOffset)
                    .setMaxResults(effectiveLimit).getResultList();
        }

        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");

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
    public static void getMessages(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        List<Message> messages = Message.find("conversation = ?1 ORDER BY createdAt ASC", conversation).fetch();

        var result = messages.stream().map(m -> {
            var map = new HashMap<String, Object>();
            map.put("id", m.id);
            map.put("role", m.role);
            map.put("content", m.content);
            map.put("toolCalls", m.toolCalls);
            map.put("toolResults", m.toolResults);
            map.put("createdAt", m.createdAt.toString());
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
        Message.delete("conversation = ?1", conversation);
        conversation.delete();
        renderJSON(gson.toJson(Map.of("status", "deleted")));
    }

    /**
     * DELETE /api/conversations — Bulk delete by IDs.
     */
    public static void deleteConversations() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("ids")) badRequest();

        var ids = body.getAsJsonArray("ids");
        int deleted = 0;
        for (var elem : ids) {
            var convoId = elem.getAsLong();
            Conversation convo = Conversation.findById(convoId);
            if (convo != null) {
                Message.delete("conversation = ?1", convo);
                convo.delete();
                deleted++;
            }
        }
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

        List<Message> msgs = Message.find("conversation = ?1 ORDER BY createdAt ASC", conversation).fetch(10);
        var userParts = new StringBuilder();
        var assistantParts = new StringBuilder();
        for (var m : msgs) {
            if ("user".equals(m.role) && m.content != null) {
                if (!userParts.isEmpty()) userParts.append("\n");
                userParts.append(m.content);
            } else if ("assistant".equals(m.role) && m.content != null) {
                if (!assistantParts.isEmpty()) assistantParts.append("\n");
                assistantParts.append(m.content);
            }
        }

        if (userParts.isEmpty()) {
            renderJSON(gson.toJson(Map.of("title", conversation.preview != null ? conversation.preview : "")));
            return;
        }

        ConversationService.generateTitleAsync(id,
                userParts.substring(0, Math.min(userParts.length(), 500)),
                assistantParts.substring(0, Math.min(assistantParts.length(), 500)));

        renderJSON(gson.toJson(Map.of("status", "generating")));
    }
}
