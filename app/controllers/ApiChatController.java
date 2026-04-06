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

@With(AuthCheck.class)
public class ApiChatController extends Controller {

    private static final Gson gson = new Gson();

    /**
     * POST /api/chat/send — Send a message and get a response.
     * Body: { agentId, conversationId (optional), message }
     */
    public static void send() {
        var body = readJsonBody();
        if (body == null || !body.has("message") || !body.has("agentId")) {
            badRequest();
        }

        var agentId = body.get("agentId").getAsLong();
        Agent agent = Agent.findById(agentId);
        if (agent == null) {
            notFound();
        }

        var messageText = body.get("message").getAsString();

        Conversation conversation;
        if (body.has("conversationId") && !body.get("conversationId").isJsonNull()) {
            conversation = ConversationService.findById(body.get("conversationId").getAsLong());
            if (conversation == null) notFound();
        } else {
            conversation = ConversationService.findOrCreate(agent, "web", session.get("username"));
        }

        var result = AgentRunner.run(agent, conversation, messageText);

        var response = new HashMap<String, Object>();
        response.put("conversationId", conversation.id);
        response.put("response", result.response());
        response.put("agentId", agent.id);
        response.put("agentName", agent.name);
        renderJSON(gson.toJson(response));
    }

    /**
     * GET /api/chat/stream/{conversationId} — SSE streaming endpoint.
     * Streams LLM response tokens as Server-Sent Events.
     */
    public static void stream(Long conversationId) {
        Conversation conversation = Conversation.findById(conversationId);
        if (conversation == null) notFound();

        // Get the latest user message to process
        var messages = ConversationService.loadRecentMessages(conversation);
        if (messages.isEmpty()) {
            notFound();
        }
        var lastMsg = messages.getLast();
        if (!"user".equals(lastMsg.role)) {
            // No pending user message to respond to
            response.contentType = "text/event-stream";
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            renderText("data: {\"type\":\"complete\",\"content\":\"\"}\n\n");
            return;
        }

        response.contentType = "text/event-stream";
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no"); // Disable nginx buffering

        var agent = conversation.agent;
        var out = response.out;

        // Stream using AgentRunner
        AgentRunner.runStreaming(agent, conversation, lastMsg.content,
                // onToken
                token -> {
                    try {
                        var event = gson.toJson(Map.of("type", "token", "content", token));
                        out.write("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception _) {
                        // Client disconnected
                    }
                },
                // onComplete
                content -> {
                    try {
                        var event = gson.toJson(Map.of("type", "complete", "content", content));
                        out.write("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception _) {}
                },
                // onError
                error -> {
                    try {
                        var event = gson.toJson(Map.of("type", "error",
                                "content", "An error occurred: " + error.getMessage()));
                        out.write("data: %s\n\n".formatted(event).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception _) {}
                    EventLogger.error("channel", agent.name, "web",
                            "SSE stream error: %s".formatted(error.getMessage()));
                }
        );

        // Keep connection open until streaming completes
        // The virtual thread in AgentRunner handles the lifecycle
        try {
            Thread.sleep(120_000); // Max 2 min SSE connection
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
            // Message count via query
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
