package controllers;

import agents.AgentRunner;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
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

        response.contentType = "text/event-stream";
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        var latch = new CountDownLatch(1);

        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                // onInit — send conversation ID as first SSE event
                conversation -> {
                    var initEvent = gson.toJson(Map.of("type", "init", "conversationId", conversation.id));
                    response.writeChunk("data: %s\n\n".formatted(initEvent));
                },
                // onToken
                token -> {
                    var event = gson.toJson(Map.of("type", "token", "content", token));
                    response.writeChunk("data: %s\n\n".formatted(event));
                },
                // onComplete
                content -> {
                    var event = gson.toJson(Map.of("type", "complete", "content", content));
                    response.writeChunk("data: %s\n\n".formatted(event));
                    latch.countDown();
                },
                // onError
                error -> {
                    var event = gson.toJson(Map.of("type", "error",
                            "content", "An error occurred: " + error.getMessage()));
                    response.writeChunk("data: %s\n\n".formatted(event));
                    EventLogger.error("channel", agent.name, "web",
                            "SSE stream error: %s".formatted(error.getMessage()));
                    latch.countDown();
                }
        );

        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
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

        var orderBy = query.isEmpty() ? "ORDER BY updatedAt DESC" : query + " ORDER BY updatedAt DESC";
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        List<Conversation> convos = Conversation.find(orderBy.toString(), params.toArray())
                .from(effectiveOffset).fetch(effectiveLimit);

        var result = convos.stream().map(c -> {
            var map = new HashMap<String, Object>();
            map.put("id", c.id);
            map.put("agentId", c.agent.id);
            map.put("agentName", c.agent.name);
            map.put("channelType", c.channelType);
            map.put("peerId", c.peerId);
            map.put("createdAt", c.createdAt.toString());
            map.put("updatedAt", c.updatedAt.toString());
            var msgCount = Message.count("conversation = ?1", c);
            map.put("messageCount", msgCount);
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

    private static com.google.gson.JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
