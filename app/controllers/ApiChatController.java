package controllers;

import agents.AgentRunner;
import com.google.gson.Gson;
import models.Agent;
import models.Conversation;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Chat dispatch endpoints: sync send, SSE streaming, and file upload.
 * Conversation management (list, get messages, delete, title gen) lives
 * in {@link ApiConversationsController}.
 */
@With(AuthCheck.class)
public class ApiChatController extends Controller {

    private static final Gson gson = new Gson();

    /**
     * POST /api/chat/send — Send a message and get a synchronous response.
     */
    public static void send() {
        var body = JsonBodyReader.readJsonBody();
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

    private static final int MAX_UPLOAD_FILES = 5;
    private static final long MAX_UPLOAD_FILE_BYTES = 10L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /**
     * POST /api/chat/upload — Multipart upload for chat attachments.
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
            if (f.length() > MAX_UPLOAD_FILE_BYTES) {
                error(400, "File too large: " + f.getName()
                        + " (max " + (MAX_UPLOAD_FILE_BYTES / (1024 * 1024)) + " MB)");
            }
        }

        var batchId = "uploads/" + buildBatchId();
        java.nio.file.Path batchDir;
        try {
            batchDir = AgentService.acquireWorkspacePath(agent.name, batchId);
        } catch (SecurityException e) {
            error(400, "Invalid upload target");
            return;
        }

        var results = new ArrayList<Map<String, Object>>();
        try {
            Files.createDirectories(batchDir);
            for (var f : files) {
                var safeName = sanitizeFilename(f.getName());
                if (safeName.isEmpty()) {
                    error(400, "Invalid filename: " + f.getName());
                }
                java.nio.file.Path target;
                try {
                    target = AgentService.acquireContained(batchDir, safeName);
                } catch (SecurityException e) {
                    error(400, "Invalid filename: " + f.getName());
                    return;
                }
                Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

                var entry = new HashMap<String, Object>();
                entry.put("path", batchId + "/" + safeName);
                entry.put("name", safeName);
                entry.put("size", Files.size(target));
                results.add(entry);
            }
        } catch (java.io.IOException e) {
            EventLogger.error("chat", "Chat upload failed for agent %s: %s"
                    .formatted(agent.name, e.getMessage()));
            error(500, "Upload failed: " + e.getMessage());
        }

        var resp = new HashMap<String, Object>();
        resp.put("batchId", batchId);
        resp.put("files", results);
        renderJSON(gson.toJson(resp));
    }

    private static String buildBatchId() {
        var ts = ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(ID_ALPHABET[RANDOM.nextInt(ID_ALPHABET.length)]);
        }
        return ts + "-" + suffix;
    }

    private static String sanitizeFilename(String name) {
        if (name == null) return "";
        var base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.trim();
        while (base.startsWith(".")) base = base.substring(1);
        var cleaned = base.replaceAll("[^A-Za-z0-9._\\- ]", "_");
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }

    /**
     * POST /api/chat/stream — Send a message and stream the response as SSE.
     */
    public static void streamChat() {
        var body = JsonBodyReader.readJsonBody();
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

        var firstToken = new java.util.concurrent.atomic.AtomicBoolean(true);
        var callbacks = new AgentRunner.StreamingCallbacks(
                conversation -> {
                    try {
                        var initData = new java.util.HashMap<>(Map.of("type", "init", "conversationId", conversation.id));
                        if (agent.thinkingMode != null && !agent.thinkingMode.isBlank()) {
                            initData.put("thinkingMode", agent.thinkingMode);
                        }
                        res.writeChunk("data: %s\n\n".formatted(gson.toJson(initData)).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        cancelled.set(true);
                        latch.countDown();
                    }
                },
                token -> {
                    try {
                        Map<String, Object> data = new java.util.HashMap<>(Map.of("type", "token", "content", token));
                        if (firstToken.compareAndSet(true, false)) {
                            data.put("timestamp", java.time.Instant.now().toString());
                        }
                        res.writeChunk("data: %s\n\n".formatted(gson.toJson(data)).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        cancelled.set(true);
                        latch.countDown();
                    }
                },
                reasoning -> {
                    try {
                        res.writeChunk("data: %s\n\n".formatted(gson.toJson(Map.of("type", "reasoning", "content", reasoning))).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        cancelled.set(true);
                        latch.countDown();
                    }
                },
                status -> {
                    try {
                        res.writeChunk("data: %s\n\n".formatted(gson.toJson(Map.of("type", "status", "content", status))).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        cancelled.set(true);
                        latch.countDown();
                    }
                },
                content -> {
                    try {
                        res.writeChunk("data: %s\n\n".formatted(gson.toJson(Map.of("type", "complete", "content", content))).getBytes(StandardCharsets.UTF_8));
                    } finally {
                        latch.countDown();
                    }
                },
                error -> {
                    try {
                        res.writeChunk("data: %s\n\n".formatted(gson.toJson(Map.of("type", "error",
                                "content", "An error occurred: " + error.getMessage()))).getBytes(StandardCharsets.UTF_8));
                        EventLogger.error("channel", agent.name, "web",
                                "SSE stream error: %s".formatted(error.getMessage()));
                    } finally {
                        latch.countDown();
                    }
                }
        );
        AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
                cancelled, callbacks);

        try {
            if (!latch.await(600, TimeUnit.SECONDS)) {
                cancelled.set(true);
                try {
                    res.writeChunk("data: %s\n\n".formatted(gson.toJson(Map.of("type", "error", "content", "Request timed out"))).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) { /* Client already disconnected */ }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            heartbeatExecutor.shutdownNow();
        }
    }
}
