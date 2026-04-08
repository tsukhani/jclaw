package controllers;

import agents.AgentRunner;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.ConversationService;
import services.EventLogger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@With(AuthCheck.class)
public class ApiChatController extends Controller {

    private static final Gson gson = new Gson();

    /**
     * POST /api/chat/send — Send a message and get a synchronous response.
     */
    public static void send() {
        var body = readJsonBody();
        if (body == null || !body.has("message") || !body.has("agentId")) {
            badRequest();
        }

        var agentId = body.get("agentId").getAsLong();
        Agent agent = Agent.findById(agentId);
        if (agent == null) notFound();

        var messageText = body.get("message").getAsString();

        Conversation conversation;
        if (body.has("conversationId") && !body.get("conversationId").isJsonNull()) {
            conversation = ConversationService.findById(body.get("conversationId").getAsLong());
            if (conversation == null) notFound();
        } else {
            conversation = ConversationService.findOrCreate(agent, "web", session.get("username"));
        }

        var result = AgentRunner.run(agent, conversation, messageText);

        var resp = new HashMap<String, Object>();
        resp.put("conversationId", conversation.id);
        resp.put("response", result.response());
        resp.put("agentId", agent.id);
        resp.put("agentName", agent.name);
        renderJSON(gson.toJson(resp));
    }

    /**
     * POST /api/chat/stream — Send a message and stream the response as SSE.
     * Body: { agentId, conversationId (optional), message }
     */
    public static void streamChat() {
        var body = readJsonBody();
        if (body == null || !body.has("message") || !body.has("agentId")) {
            badRequest();
        }

        var agentId = body.get("agentId").getAsLong();
        Agent agent = Agent.findById(agentId);
        if (agent == null) notFound();

        var messageText = body.get("message").getAsString();

        Long conversationId = (body.has("conversationId") && !body.get("conversationId").isJsonNull())
                ? body.get("conversationId").getAsLong() : null;
        String username = session.get("username");

        var res = Http.Response.current();
        res.contentType = "text/event-stream";
        res.setHeader("Cache-Control", "no-cache");
        res.setHeader("Connection", "keep-alive");
        res.setHeader("X-Accel-Buffering", "no");

        var latch = new CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        // SSE heartbeat to prevent proxy/browser timeouts during long tool chains
        var heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().unstarted(r));
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (cancelled.get()) return;
            try {
                res.writeChunk(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                cancelled.set(true);
                latch.countDown();
            }
        }, 30, 30, TimeUnit.SECONDS);

        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                cancelled,
                // onInit — send conversation ID as first SSE event
                conversation -> {
                    try {
                        var initEvent = gson.toJson(Map.of("type", "init", "conversationId", conversation.id));
                        res.writeChunk("data: %s\n\n".formatted(initEvent).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        cancelled.set(true);
                        latch.countDown();
                    }
                },
                // onToken — include server timestamp on the first token (time-to-first-token)
                new Consumer<String>() {
                    private boolean first = true;
                    @Override public void accept(String token) {
                    try {
                        Map<String, Object> data = new java.util.HashMap<>(Map.of("type", "token", "content", token));
                        if (first) {
                            data.put("timestamp", java.time.Instant.now().toString());
                            first = false;
                        }
                        var event = gson.toJson(data);
                        res.writeChunk("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        cancelled.set(true);
                        latch.countDown();
                    }
                    }
                },
                // onComplete
                content -> {
                    try {
                        var event = gson.toJson(Map.of("type", "complete", "content", content));
                        res.writeChunk("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                    } finally {
                        latch.countDown();
                    }
                },
                // onError
                error -> {
                    try {
                        var event = gson.toJson(Map.of("type", "error",
                                "content", "An error occurred: " + error.getMessage()));
                        res.writeChunk("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                        EventLogger.error("channel", agent.name, "web",
                                "SSE stream error: %s".formatted(error.getMessage()));
                    } finally {
                        latch.countDown();
                    }
                }
        );

        try {
            if (!latch.await(180, TimeUnit.SECONDS)) {
                try {
                    var event = gson.toJson(Map.of("type", "error", "content", "Request timed out"));
                    res.writeChunk("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // Client already disconnected
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            heartbeatExecutor.shutdownNow();
        }
    }

    /**
     * GET /api/conversations — List conversations with optional filters.
     */
    public static void listConversations(String channel, Long agentId, Integer limit, Integer offset) {
        var query = new StringBuilder();
        var params = new java.util.ArrayList<>();
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

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        String jpql = query.isEmpty()
                ? "SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC"
                : "SELECT c FROM Conversation c JOIN FETCH c.agent WHERE " + query + " ORDER BY c.updatedAt DESC";
        var q = JPA.em().createQuery(jpql, Conversation.class);
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
        List<Conversation> convos = q.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

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
     * GET /api/conversations/{id}/messages — Get messages for a conversation.
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
     * GET /api/conversations/{id}/queue — Get queue status for a conversation.
     */
    public static void getQueueStatus(Long id) {
        var busy = services.ConversationQueue.isBusy(id);
        var queueSize = services.ConversationQueue.getQueueSize(id);
        renderJSON(gson.toJson(java.util.Map.of(
                "busy", busy,
                "queueSize", queueSize
        )));
    }

    /**
     * DELETE /api/conversations/{id} — Delete a conversation and all its messages.
     */
    public static void deleteConversation(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        // Delete messages first (no cascade configured)
        Message.delete("conversation = ?1", conversation);
        conversation.delete();

        renderJSON(gson.toJson(Map.of("status", "deleted")));
    }

    /**
     * DELETE /api/conversations — Bulk delete conversations by IDs.
     * Body: { "ids": [1, 2, 3] }
     */
    public static void deleteConversations() {
        var body = readJsonBody();
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
     * POST /api/conversations/{id}/generate-title — Generate a title for a conversation via LLM.
     */
    public static void generateTitle(Long id) {
        Conversation conversation = Conversation.findById(id);
        if (conversation == null) notFound();

        // Collect user and assistant messages for context
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

    private static com.google.gson.JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
